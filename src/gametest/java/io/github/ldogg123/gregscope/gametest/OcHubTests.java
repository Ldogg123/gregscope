package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.AfterBatch;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.Tags;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.hub.TelemetryHubs;
import io.github.ldogg123.gregscope.hub.TileTelemetryHub;
import io.github.ldogg123.gregscope.integration.opencomputers.HubEnvironment;
import io.github.ldogg123.gregscope.integration.opencomputers.HubScope;
import io.github.ldogg123.gregscope.integration.opencomputers.LuaTables;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.model.StatusIds;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.FakeClock;
import io.github.ldogg123.gregscope.sampling.SensorView;
import io.github.ldogg123.gregscope.sensor.Labels;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import li.cil.oc.api.Network;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;

/**
 * GS-116 (design-v0.2 sections 10.3, 10.4, 5 and 14): the {@code gregscope_hub} component, driven through
 * OpenComputers' own {@code Component.invoke} exactly as a Lua program would call it, on a real Adapter placed against
 * a real Telemetry Hub.
 *
 * <p>
 * <b>Scope is the Hub's.</b> Every list and every lookup goes through {@code AccessPolicy.inHubScope}, which has no
 * viewer at all: a computer wired to a Hub sees what that Hub sees. {@link #listScopedByHubOwner} builds a real
 * GTNHLib team to prove it, and {@link #outOfScopeIdIsNotFound} proves the other half of section 5 - an id outside the
 * scope is answered exactly like an id that does not exist.
 *
 * <p>
 * <b>Why the fixtures are built inside the sequence.</b> Tests of one batch run in parallel, so no test here may empty
 * the shared registry: every fixture owner is derived from the test's own name instead, which is what makes the counts
 * exact. The sensors are then placed in the step that runs once the Adapter has joined, so that step is one
 * synchronous block in which no cover heartbeats and no sampler tick lands - otherwise a sensor driven UNLOADED would
 * be LIVE again a tick later, and a sensor that must have no snapshot would have one.
 *
 * <p>
 * A Horizon-QA warp never advances server time (erratum E2), so the history test drives time with a {@link FakeClock}
 * inside that same block: no server tick passes there, so the real sampler never sees it.
 *
 * <p>
 * <b>Batch name.</b> {@code gregscope.surface.ochub} sorts after every existing GregScope batch, so no older test's
 * cell moves (the GS-109/GS-110 rule). Every test here is one Adapter join plus a synchronous body (observed 1 to 2
 * ticks), so the batch asks for a much smaller timeout than Horizon-QA's default of 100.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope", "gtnhlib" })
public class OcHubTests {

    private static final String BATCH = "gregscope.surface.ochub";

    private static final TestPos HUB = at(1, 0, 1);
    private static final TestPos ADAPTER = at(2, 0, 1);
    private static final TestPos MACHINE = at(1, 0, 3);
    private static final TestPos SECOND = at(2, 0, 3);
    private static final TestPos THIRD = at(3, 0, 3);
    private static final ForgeDirection COVERED = ForgeDirection.UP;

    /** Upper bound for OpenComputers' scheduled network join of an Adapter (observed within 1-2 ticks). */
    private static final int OC_JOIN_TIMEOUT_TICKS = 20;
    /** The join budget plus room for the sequence steps around it; every body here is synchronous. */
    private static final int TIMEOUT_TICKS = 40;

    /**
     * 2033-05-18T03:34:00Z, on a minute boundary and after the real clock, exactly as {@code HistoryTests} picks it:
     * a run can never stop before it started.
     */
    private static final long BASE_EPOCH_SEC = 33_333_334L * 60L;
    private static final int SAMPLES_PER_MINUTE = 60;
    /** Two whole minutes plus the first sample of the third, which is what closes the second minute. */
    private static final int RECORDED_SECONDS = 2 * SAMPLES_PER_MINUTE + 1;

    private OcHubTests() {}

    @AfterBatch(BATCH)
    public static void cleanUpAfterOcHub() {
        SensorCleanup.detachAllAndPurge();
    }

    // --- the component itself (section 10.3) ---

    /**
     * Section 14 {@code componentPresent}: an Adapter against a Telemetry Hub carries a component named
     * {@code gregscope_hub} with exactly the four callbacks section 10.3 lists, and {@code getInfo} answers with the
     * pinned versions, the real ring capacities and the Hub's own owner.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void componentPresent(GameTestHelper helper) {
        UUID hubOwner = owner("present");
        State st = hubWithAdapter(helper, hubOwner, "ocHubPresent");

        whenComponentReady(helper, st, () -> {
            helper.assertEquals(HubEnvironment.COMPONENT_NAME, st.component.name(), "component name");
            helper.assertEquals("gregscope_hub", HubEnvironment.COMPONENT_NAME, "the frozen component name");
            Collection<String> methods = st.component.methods();
            helper.assertEquals(
                new TreeSet<>(Arrays.asList("getInfo", "listSensors", "getLatest", "getSensorHistory")),
                new TreeSet<>(methods),
                "the Hub component's callbacks");

            Map<String, Object> info = table(helper, OcComponents.invoke(helper, st.component, "getInfo"), "getInfo");
            Snapshots.log("ochub#1 getInfo", info);
            helper.assertEquals(2L, longOf(helper, info, "apiVersion"), "apiVersion");
            helper.assertEquals(1L, longOf(helper, info, "schemaVersion"), "schemaVersion");
            helper.assertEquals(1L, longOf(helper, info, "historyVersion"), "historyVersion");
            helper.assertEquals(1L, longOf(helper, info, "sensorRecordVersion"), "sensorRecordVersion");
            helper.assertEquals(Tags.VERSION, info.get("gregscopeVersion"), "gregscopeVersion");
            helper.assertEquals(hubOwner.toString(), info.get("owner"), "owner");
            helper.assertEquals("ocHubPresent", info.get("ownerName"), "ownerName");
            helper.assertEquals(300L, longOf(helper, info, "secondsCapacity"), "secondsCapacity");
            helper.assertEquals(1440L, longOf(helper, info, "minutesCapacity"), "minutesCapacity");
            helper.assertEquals(
                (long) GregScope.settings()
                    .intervalTicks(),
                longOf(helper, info, "intervalTicks"),
                "intervalTicks");
            helper.assertEquals(
                (long) GregScope.settings()
                    .maxSensors(),
                longOf(helper, info, "maxSensors"),
                "maxSensors");
            helper.assertEquals(
                GregScope.frame()
                    .sequence(),
                longOf(helper, info, "frameSequence"),
                "frameSequence");
            helper.assertTrue(longOf(helper, info, "frameAgeSeconds") >= 0L, "frameAgeSeconds in " + info);
            for (Map.Entry<String, Object> e : info.entrySet()) {
                helper.assertNotNull(e.getValue(), "nil value for key " + e.getKey());
            }
        });
    }

    /**
     * The GS-116 deviation from section 10.3, pinned so it cannot drift back silently: none of the four callbacks is
     * {@code direct}. The scope filter needs GTNHLib's team tables and the Hub's tile entity, which are server-thread
     * state; the reasoning is in {@code HubEnvironment}'s javadoc and in the implementation notes.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void callbacksAreServerThreadOnly(GameTestHelper helper) {
        State st = hubWithAdapter(helper, owner("direct"), "ocHubDirect");

        whenComponentReady(helper, st, () -> {
            for (String method : st.component.methods()) {
                Callback annotation = st.component.annotation(method);
                helper.assertNotNull(annotation, "no @Callback annotation for " + method);
                helper.assertFalse(
                    annotation.direct(),
                    method + " is a direct callback: it would read GTNHLib teams from a computer thread");
            }
        });
    }

    // --- scope (section 5) ---

    /**
     * Section 14 {@code listScopedByHubOwner}: {@code listSensors} shows the Hub owner's sensors and the Hub owner's
     * team's, and nothing else, in sensor UUID order. {@code total} counts the scope, not the registry.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void listScopedByHubOwner(GameTestHelper helper) {
        UUID hubOwner = owner("scopeOwner");
        UUID mate = owner("scopeMate");
        UUID outsider = owner("scopeOutsider");
        State st = hubWithAdapter(helper, hubOwner, "ocHubScope");

        whenComponentReady(helper, st, () -> {
            // The team lives only as long as this one synchronous block, so nothing of it outlives the test.
            try (TestTeams teams = new TestTeams()) {
                Team team = teams.create("gsOcHubTeam", hubOwner);
                team.addMember(mate);

                UUID own = liveSensor(helper, MACHINE, "0e11", hubOwner, "ocHubScope");
                UUID mates = liveSensor(helper, SECOND, "0e12", mate, "ocHubScopeMate");
                UUID theirs = liveSensor(helper, THIRD, "0e13", outsider, "ocHubScopeOther");
                publish(helper);

                Map<String, Object> page = table(
                    helper,
                    OcComponents.invoke(helper, st.component, "listSensors"),
                    "listSensors");
                Snapshots.log("ochub#2 listSensors", page);
                helper.assertEquals(2L, longOf(helper, page, "total"), "total counts the Hub's scope");
                helper.assertEquals(0L, longOf(helper, page, "offset"), "the default offset");
                List<UUID> shown = idsOf(helper, page);
                helper.assertTrue(shown.contains(own), "the Hub owner's own sensor is missing: " + shown);
                helper.assertTrue(shown.contains(mates), "the team mate's sensor is missing: " + shown);
                helper.assertFalse(shown.contains(theirs), "an outsider's sensor is in the Hub: " + shown);
                List<UUID> sorted = new ArrayList<>(shown);
                Collections.sort(sorted);
                helper.assertEquals(sorted, shown, "listSensors is not in sensor UUID order");

                // The whole record of section 10.1 comes back, not just an id.
                Map<String, Object> record = OcComponents.asMap(
                    helper,
                    OcComponents.asList(helper, page.get("sensors"), "sensors")
                        .get(0));
                helper.assertEquals(
                    (long) LuaTables.SENSOR_RECORD_VERSION,
                    OcComponents.asLong(helper, record.get("sensorRecordVersion"), "sensorRecordVersion"));
                helper.assertEquals("live", record.get("availability"), "availability of a LIVE sensor");
                helper.assertTrue(record.containsKey("displayName"), "displayName missing from " + record);

                // getInfo counts the same scope.
                Map<String, Object> info = table(
                    helper,
                    OcComponents.invoke(helper, st.component, "getInfo"),
                    "getInfo");
                helper.assertEquals(2L, longOf(helper, info, "visible"), "visible");
                helper.assertEquals(2L, longOf(helper, info, "live"), "live");
            }
        });
    }

    /**
     * Section 5 and section 14: an id that is real but outside this Hub's scope is answered exactly like an id that
     * does not exist, on both callbacks that take one. A prefix that matches two sensors is {@code "ambiguous id"},
     * and one shorter than eight characters is not an id at all.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void outOfScopeIdIsNotFound(GameTestHelper helper) {
        UUID hubOwner = owner("notFoundOwner");
        UUID outsider = owner("notFoundOutsider");
        State st = hubWithAdapter(helper, hubOwner, "ocHubNotFound");

        whenComponentReady(helper, st, () -> {
            // Two sensors of one owner share the whole first UUID group, so an eight-character prefix is ambiguous.
            UUID mine = liveSensor(helper, MACHINE, "0e21", hubOwner, "ocHubNotFound");
            UUID sibling = liveSensor(helper, SECOND, "0e21", hubOwner, "ocHubNotFound");
            UUID theirs = liveSensor(helper, THIRD, "0e23", outsider, "ocHubNotFoundOther");
            publish(helper);
            String shared = mine.toString()
                .substring(0, 8);
            helper.assertTrue(
                sibling.toString()
                    .startsWith(shared) && !mine.equals(sibling),
                "the two fixture ids no longer share their first eight characters: " + mine + " and " + sibling);

            assertSoftError(
                helper,
                "getLatest of an out-of-scope sensor",
                OcComponents.invoke(helper, st.component, "getLatest", theirs.toString()),
                LuaTables.ERROR_SENSOR_NOT_FOUND);
            assertSoftError(
                helper,
                "getSensorHistory of an out-of-scope sensor",
                OcComponents.invoke(helper, st.component, "getSensorHistory", theirs.toString(), "minute"),
                LuaTables.ERROR_SENSOR_NOT_FOUND);
            assertSoftError(
                helper,
                "getLatest of an unknown id",
                OcComponents.invoke(helper, st.component, "getLatest", "ffffffff-0000-4000-8000-000000000000"),
                LuaTables.ERROR_SENSOR_NOT_FOUND);

            // Section 10.3 writes the argument as idOrPrefix>=8: four characters are not an id at all.
            assertSoftError(
                helper,
                "getLatest of a prefix shorter than eight characters",
                OcComponents.invoke(helper, st.component, "getLatest", shared.substring(0, 4)),
                LuaTables.ERROR_SENSOR_NOT_FOUND);
            helper.assertEquals(8L, (long) HubScope.MIN_ID_PREFIX, "the pinned minimum id prefix");

            // Eight characters that match two sensors in scope: "ambiguous id", not one of them at random.
            assertSoftError(
                helper,
                "getLatest of an ambiguous prefix",
                OcComponents.invoke(helper, st.component, "getLatest", shared),
                LuaTables.ERROR_AMBIGUOUS_ID);
            assertSoftError(
                helper,
                "getSensorHistory of an ambiguous prefix",
                OcComponents.invoke(helper, st.component, "getSensorHistory", shared, "minute"),
                LuaTables.ERROR_AMBIGUOUS_ID);

            // A full id in scope still resolves, so the checks above are not refusing everything.
            Map<String, Object> ok = table(
                helper,
                OcComponents.invoke(helper, st.component, "getLatest", mine.toString()),
                "getLatest of a sensor in scope");
            helper.assertEquals(mine.toString(), ok.get("id"), "getLatest answered about another sensor");
        });
    }

    /** Section 5: an unowned Hub (placed by a FakePlayer) has an empty scope and reveals nothing. */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void unownedHubShowsNothing(GameTestHelper helper) {
        UUID sensorOwner = owner("unownedOwner");
        State st = hubWithAdapter(helper, null, null);

        whenComponentReady(helper, st, () -> {
            UUID id = liveSensor(helper, MACHINE, "0e31", sensorOwner, "ocHubUnowned");
            publish(helper);

            Map<String, Object> info = table(helper, OcComponents.invoke(helper, st.component, "getInfo"), "getInfo");
            helper.assertFalse(info.containsKey("owner"), "an unowned Hub reported an owner: " + info);
            helper.assertEquals(0L, longOf(helper, info, "visible"), "visible");
            helper.assertEquals(0L, longOf(helper, info, "live"), "live");

            Map<String, Object> page = table(
                helper,
                OcComponents.invoke(helper, st.component, "listSensors"),
                "listSensors");
            helper.assertEquals(0L, longOf(helper, page, "total"), "total");
            helper.assertEquals(
                0,
                OcComponents.asList(helper, page.get("sensors"), "sensors")
                    .size(),
                "an unowned Hub listed a sensor");
            assertSoftError(
                helper,
                "getLatest on an unowned Hub",
                OcComponents.invoke(helper, st.component, "getLatest", id.toString()),
                LuaTables.ERROR_SENSOR_NOT_FOUND);
        });
    }

    // --- getLatest (section 10.3) ---

    /**
     * Section 14 {@code latestMatchesFrame}: the second return value is the exact schema v1 map the sampler stored,
     * key for key, and the record beside it is about the same sensor.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void latestMatchesFrame(GameTestHelper helper) {
        UUID hubOwner = owner("latest");
        State st = hubWithAdapter(helper, hubOwner, "ocHubLatest");

        whenComponentReady(helper, st, () -> {
            UUID id = liveSensor(helper, MACHINE, "0e41", hubOwner, "ocHubLatest");
            // Two intervals: a sensor added mid-interval can be served after the frame of that interval was already
            // published, so one interval does not guarantee the frame carries its snapshot yet.
            publish(helper);
            publish(helper);
            SensorEntry entry = entry(helper, id);
            SensorView view = GregScope.frame()
                .sensor(id);
            helper.assertNotNull(view, "the fixture sensor is not in the published frame");
            MachineSnapshot stored = view.lastSnapshot();
            helper.assertNotNull(stored, "the fixture sensor was never sampled");
            // The frame is what getLatest answers from, and it can be one sampling interval behind the registry
            // (section 10.3), so the snapshot compared here is the frame's, not whatever the entry holds right now.
            MachineSnapshot entryBefore = entry.lastSnapshot();
            long sampleBefore = entry.lastSampleEpochSec();

            Object[] result = OcComponents.invoke(helper, st.component, "getLatest", id.toString());
            helper.assertTrue(
                result != null && result.length == 2,
                "getLatest returns the record and the snapshot: " + (result == null ? null : Arrays.asList(result)));
            Map<String, Object> record = OcComponents.asMap(helper, result[0]);
            helper.assertEquals(id.toString(), record.get("id"), "record id");
            helper.assertEquals(Labels.shortId(id), record.get("shortId"), "record shortId");
            Snapshots.log("ochub#4 getLatest record", record);

            Map<String, Object> viaOc = OcComponents.asMap(helper, result[1]);
            Map<String, Object> expected = stored.toMap();
            helper.assertEquals(expected.keySet(), viaOc.keySet(), "getLatest snapshot key set");
            for (Map.Entry<String, Object> e : expected.entrySet()) {
                Object want = e.getValue();
                Object got = viaOc.get(e.getKey());
                if (want instanceof List) {
                    helper.assertIterableEquals(
                        (List<?>) want,
                        OcComponents.asList(helper, got, e.getKey()),
                        "getLatest snapshot " + e.getKey());
                } else {
                    helper.assertEquals(want, got, "getLatest snapshot " + e.getKey());
                }
            }

            // Read-only (section 1.4): the callback took no sample and changed no state.
            helper.assertEquals(SensorState.LIVE, entry.state(), "getLatest changed the lifecycle state");
            helper.assertEquals(sampleBefore, entry.lastSampleEpochSec(), "getLatest took a sample");
            helper.assertSame(entryBefore, entry.lastSnapshot(), "getLatest replaced the stored snapshot");
        });
    }

    /**
     * Section 14 {@code unloadedSensorRecordNoSnapshot} and section 10.1: a sensor that is not LIVE and was never
     * sampled reports its real availability and the v1-reserved {@code unavailable} / {@code machine_unavailable},
     * with no {@code lastSample} and no {@code ageSeconds} at all, and {@code getLatest} answers the record with no
     * snapshot beside it.
     *
     * <p>
     * The UNLOADED state is driven through {@code SensorRegistryCore.unloaded}, the very transition the chunk-unload
     * handler calls (design-v0.2 section 4.3), inside the same tick as the assertions - the cover's next heartbeat
     * would make it LIVE again. Unloading a real chunk is covered by
     * {@code CommandTests.labelRefusedWhenUnloadedAndChunkStaysUnloaded}; here it would take the Hub and the Adapter
     * down with the sensor, because they share the cell.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void unloadedSensorRecordNoSnapshot(GameTestHelper helper) {
        UUID hubOwner = owner("unloaded");
        State st = hubWithAdapter(helper, hubOwner, "ocHubUnloaded");

        whenComponentReady(helper, st, () -> {
            UUID id = liveSensor(helper, MACHINE, "0e51", hubOwner, "ocHubUnloaded");
            SensorEntry entry = entry(helper, id);
            TestPos abs = helper.absolute(MACHINE);
            helper.assertTrue(
                registry(helper).core()
                    .unloaded(
                        id,
                        helper.getWorld().provider.dimensionId,
                        abs.x(),
                        abs.y(),
                        abs.z(),
                        COVERED.ordinal(),
                        GapReason.CHUNK_UNLOADED),
                "the entry did not become UNLOADED");
            helper.assertEquals(SensorState.UNLOADED, entry.state(), "state before the frame");
            publish(helper);
            helper.assertNull(entry.lastSnapshot(), "an UNLOADED sensor was sampled");

            Map<String, Object> page = table(
                helper,
                OcComponents.invoke(helper, st.component, "listSensors"),
                "listSensors");
            helper.assertEquals(1L, longOf(helper, page, "total"), "an UNLOADED sensor stays in the Hub's scope");
            Map<String, Object> record = OcComponents.asMap(
                helper,
                OcComponents.asList(helper, page.get("sensors"), "sensors")
                    .get(0));
            Snapshots.log("ochub#5 unloaded record", record);
            helper.assertEquals("unloaded", record.get("availability"), "availability");
            helper.assertEquals(StateCodes.id(StateCodes.UNAVAILABLE), record.get("state"), "the v1-reserved state");
            helper.assertEquals(StatusIds.MACHINE_UNAVAILABLE, record.get("statusId"), "the v1-reserved statusId");
            helper.assertFalse(record.containsKey("lastSample"), "lastSample on a never-sampled sensor: " + record);
            helper.assertFalse(record.containsKey("ageSeconds"), "ageSeconds without a sample: " + record);

            Object[] result = OcComponents.invoke(helper, st.component, "getLatest", id.toString());
            helper.assertTrue(
                result != null && result.length == 2,
                "getLatest result count: " + (result == null ? null : Arrays.asList(result)));
            helper.assertEquals(
                id.toString(),
                OcComponents.asMap(helper, result[0])
                    .get("id"),
                "getLatest record id");
            helper.assertNull(result[1], "getLatest returned a snapshot for a sensor that has none");

            // getInfo counts it as visible but not live.
            Map<String, Object> info = table(helper, OcComponents.invoke(helper, st.component, "getInfo"), "getInfo");
            helper.assertEquals(1L, longOf(helper, info, "visible"), "visible");
            helper.assertEquals(0L, longOf(helper, info, "live"), "live");
        });
    }

    // --- listSensors paging (section 10.3) ---

    /**
     * Section 10.3 and section 14: the limit is clamped, never refused, and an offset past the end is an empty page.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void listLimitAndOffsetClamped(GameTestHelper helper) {
        UUID hubOwner = owner("paging");
        State st = hubWithAdapter(helper, hubOwner, "ocHubPaging");

        whenComponentReady(helper, st, () -> {
            liveSensor(helper, MACHINE, "0e61", hubOwner, "ocHubPaging");
            liveSensor(helper, SECOND, "0e62", hubOwner, "ocHubPaging");
            liveSensor(helper, THIRD, "0e63", hubOwner, "ocHubPaging");
            publish(helper);

            List<UUID> all = idsOf(helper, page(helper, st.component, 0, 64));
            helper.assertEquals(3, all.size(), "the three fixture sensors are in scope: " + all);

            // limit 0 and a negative limit both become 1 (HubScope.MIN_LIST_LIMIT).
            helper.assertEquals(
                all.subList(0, 1),
                idsOf(helper, page(helper, st.component, 0, 0)),
                "limit 0 is clamped to one row");
            helper.assertEquals(
                all.subList(0, 1),
                idsOf(helper, page(helper, st.component, 0, -5)),
                "a negative limit is clamped to one row");
            // A limit past the cap is clamped to 64, which is more than this scope holds.
            helper.assertEquals(
                all,
                idsOf(helper, page(helper, st.component, 0, 1000)),
                "an oversize limit is clamped, not refused");
            helper.assertEquals(64L, (long) HubScope.MAX_LIST_LIMIT, "the pinned listSensors cap of section 10.3");

            // Paging meets exactly, and the reported offset is the clamped one.
            Map<String, Object> second = page(helper, st.component, 1, 2);
            helper.assertEquals(1L, longOf(helper, second, "offset"), "offset");
            helper.assertEquals(3L, longOf(helper, second, "total"), "total is the whole scope, not the page");
            helper.assertEquals(all.subList(1, 3), idsOf(helper, second), "the second page");

            Map<String, Object> negative = page(helper, st.component, -7, 64);
            helper.assertEquals(0L, longOf(helper, negative, "offset"), "a negative offset starts at 0");
            helper.assertEquals(all, idsOf(helper, negative), "a negative offset is the first page");

            Map<String, Object> beyond = page(helper, st.component, 99, 64);
            helper.assertEquals(99L, longOf(helper, beyond, "offset"), "the offset is echoed");
            helper.assertEquals(3L, longOf(helper, beyond, "total"), "total");
            helper.assertEquals(0, idsOf(helper, beyond).size(), "an offset past the end is an empty page");
        });
    }

    // --- history (section 10.4) ---

    /**
     * Section 14 {@code minuteHistoryPaging}: minute rows come back oldest first over the aligned window of section
     * 10.4, and passing the returned {@code from} back as {@code before} reaches the minute before with no overlap.
     * The bad-resolution soft error is checked here too, because it is answered before the id is even looked at.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void minuteHistoryPaging(GameTestHelper helper) {
        UUID hubOwner = owner("history");
        State st = hubWithAdapter(helper, hubOwner, "ocHubHistory");

        whenComponentReady(helper, st, () -> {
            UUID id = liveSensor(helper, MACHINE, "0e71", hubOwner, "ocHubHistory");
            publish(helper);

            assertSoftError(
                helper,
                "getSensorHistory with an unknown resolution",
                OcComponents.invoke(helper, st.component, "getSensorHistory", id.toString(), "hour"),
                LuaTables.ERROR_BAD_RESOLUTION);
            assertSoftError(
                helper,
                "getSensorHistory with no arguments",
                OcComponents.invoke(helper, st.component, "getSensorHistory"),
                LuaTables.ERROR_BAD_RESOLUTION);

            // Still one synchronous block: no server tick passes, so the real sampler never sees the fake clock.
            FakeClock clock = FakeClock.atEpochSec(BASE_EPOCH_SEC);
            helper.assertTrue(GregScopeTestHooks.setClock(clock), "GregScope test hooks are disabled");
            try {
                settle(helper);
                int firstMinute = (int) (BASE_EPOCH_SEC / 60L);
                for (int i = 0; i < RECORDED_SECONDS; i++) {
                    helper.assertTrue(GregScopeTestHooks.sampleNow(id), "the sample did not resolve its target");
                    clock.advanceSeconds(1L);
                }
                settle(helper);
                SensorEntry entry = entry(helper, id);
                helper.assertTrue(entry.historyLoaded(), "minute history was never marked loaded");
                long now = clock.epochSec();

                Map<String, Object> page = table(
                    helper,
                    OcComponents.invoke(helper, st.component, "getSensorHistory", id.toString(), "minute", 1),
                    "minute history");
                Snapshots.log("ochub#7 minute history", page);
                helper.assertEquals(
                    (long) LuaTables.HISTORY_VERSION,
                    OcComponents.asLong(helper, page.get("historyVersion"), "historyVersion"));
                helper.assertEquals(id.toString(), page.get("id"), "history id");
                helper.assertEquals("minute", page.get("resolution"), "resolution");
                helper.assertEquals(60L, OcComponents.asLong(helper, page.get("step"), "step"));
                long to = OcComponents.asLong(helper, page.get("to"), "to");
                long from = OcComponents.asLong(helper, page.get("from"), "from");
                helper.assertEquals(now / 60L * 60L, to, "the window ends at the start of the open minute");
                helper.assertEquals(to - 60L, from, "one minute of window");

                List<Object> rows = OcComponents.asList(helper, page.get("rows"), "rows");
                helper.assertEquals(1, rows.size(), "the second recorded minute is one row");
                Map<String, Object> row = OcComponents.asMap(helper, rows.get(0));
                helper.assertEquals((firstMinute + 1) * 60L, OcComponents.asLong(helper, row.get("t"), "t"));
                helper.assertEquals(
                    (long) SAMPLES_PER_MINUTE,
                    OcComponents.asLong(helper, row.get("samples"), "samples"),
                    "a whole minute of one sample per second");
                helper.assertTrue(row.containsKey("lastState"), "a minute row without lastState: " + row);
                helper.assertTrue(row.containsKey("stateSeconds"), "a minute row without stateSeconds: " + row);

                // Paging: the returned `from` is the next `before`, and the pages meet exactly.
                Map<String, Object> older = table(
                    helper,
                    OcComponents.invoke(helper, st.component, "getSensorHistory", id.toString(), "minute", 1, from),
                    "older minute page");
                helper.assertEquals(from, OcComponents.asLong(helper, older.get("to"), "to"), "the pages meet");
                List<Object> olderRows = OcComponents.asList(helper, older.get("rows"), "rows");
                helper.assertEquals(1, olderRows.size(), "the first recorded minute is one row");
                helper.assertEquals(
                    firstMinute * 60L,
                    OcComponents.asLong(
                        helper,
                        OcComponents.asMap(helper, olderRows.get(0))
                            .get("t"),
                        "t"),
                    "paging back did not reach the older minute");

                // Second resolution answers over the same rings, oldest first.
                Map<String, Object> seconds = table(
                    helper,
                    OcComponents.invoke(helper, st.component, "getSensorHistory", id.toString(), "second", 10),
                    "second history");
                helper.assertEquals("second", seconds.get("resolution"), "resolution");
                helper.assertEquals(
                    10,
                    OcComponents.asList(helper, seconds.get("rows"), "rows")
                        .size(),
                    "ten observed seconds");

                // Read-only: reading history neither samples nor rewrites the ring.
                long newest = entry.minutes()
                    .newestEpochMinute();
                OcComponents.invoke(helper, st.component, "getSensorHistory", id.toString(), "minute", 240);
                helper.assertEquals(
                    newest,
                    entry.minutes()
                        .newestEpochMinute(),
                    "reading history changed the minute ring");
                helper.assertEquals(now - 1L, entry.lastSampleEpochSec(), "reading history took a sample");
            } finally {
                GregScopeTestHooks.setClock(null);
            }
        });
    }

    // --- fixtures ---

    /** Mutable state a Horizon-QA sequence carries between its steps. */
    private static final class State {

        Environment adapter;
        Component component;
    }

    /**
     * A Telemetry Hub owned by {@code hubOwner} (null for an unowned one, as a FakePlayer placement leaves it) with an
     * OpenComputers Adapter beside it. The registry is deliberately left alone: tests of this batch run in parallel,
     * so each one keeps to its own owner instead of emptying what another test is using.
     */
    private static State hubWithAdapter(GameTestHelper helper, UUID hubOwner, String hubOwnerName) {
        TileTelemetryHub hub = placeHub(helper, hubOwner, hubOwnerName);
        helper.assertEquals(hubOwner, hub.owner(), "the Hub did not take the intended owner");
        TileEntity adapter = OcComponents.placeBlock(helper, ADAPTER, "adapter");
        Network.joinOrCreateNetwork(adapter);
        State st = new State();
        st.adapter = (Environment) adapter;
        return st;
    }

    /** Waits for the Adapter's {@code gregscope_hub} component, then runs {@code body} in one synchronous step. */
    private static void whenComponentReady(GameTestHelper helper, State st, Runnable body) {
        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the Hub",
                OC_JOIN_TIMEOUT_TICKS,
                () -> st.component = hubComponent(helper, st))
            .thenExecute(body)
            .thenSucceed();
    }

    /** The {@code gregscope_hub} component of the placed Adapter; fails while the Adapter has not joined yet. */
    private static Component hubComponent(GameTestHelper helper, State st) {
        Component found = OcComponents.findAdapterComponentWith(helper, st.adapter, "listSensors");
        helper.assertNotNull(found, "the Adapter exposes no component with listSensors");
        return found;
    }

    private static TileTelemetryHub placeHub(GameTestHelper helper, UUID hubOwner, String hubOwnerName) {
        ItemStack stack = TelemetryHubs.hubStack();
        TestPos abs = helper.absolute(HUB);
        ItemBlock item = helper.assertInstanceOf(ItemBlock.class, stack.getItem(), "the Hub item is not an ItemBlock");
        EntityPlayer placer = hubOwner == null ? helper.spawnFakePlayer("gregscope-ochub-robot")
            : CommandSenders.realPlayer(helper, hubOwnerName, hubOwner, -1);
        helper.assertTrue(
            item.placeBlockAt(stack, placer, helper.getWorld(), abs.x(), abs.y(), abs.z(), 1, 0.5F, 0.5F, 0.5F, 0),
            "the Telemetry Hub was not placed at " + HUB);
        return helper.assertInstanceOf(
            TileTelemetryHub.class,
            helper.assertTileEntityPresent(HUB),
            "placed tile entity at " + HUB);
    }

    /** An owner UUID of one test only, so the parallel tests of this batch cannot see each other's sensors. */
    private static UUID owner(String tag) {
        return UUID.nameUUIDFromBytes(("gregscope-ochub:" + tag).getBytes(StandardCharsets.UTF_8));
    }

    /** A LIVE sensor with a chosen UUID and owner, exactly as the Hub GUI tests build one (section 3.4). */
    private static UUID liveSensor(GameTestHelper helper, TestPos pos, String idPrefix, UUID sensorOwner,
        String ownerName) {
        UUID id = UUID.fromString(idPrefix + "0116-0000-4000-8000-" + String.format("%012x", pos.x() * 100 + pos.z()));
        SensorIdentity identity = new SensorIdentity(id, "", sensorOwner, ownerName, 1_600_000_000L);
        IGregTechTileEntity holder = SensorFixtures.placeMachineWithSensorNbt(helper, pos, COVERED, identity);
        MachineSensorCover attached = helper
            .assertInstanceOf(MachineSensorCover.class, holder.getCoverAtSide(COVERED), "sensor cover at " + pos);
        helper.assertTrue(GregScopeTestHooks.heartbeatNow(attached), "GregScope test hooks are disabled");
        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.LIVE, entry.state(), "the fixture sensor did not register");
        helper.assertEquals(sensorOwner, entry.owner(), "owner");
        return id;
    }

    /** Publishes a fresh {@code TelemetryFrame}, which is what the Hub component reads (errata E2: a warp does not). */
    private static void publish(GameTestHelper helper) {
        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");
    }

    /** Lets an async history load land and its answer be written, the way the sampler's per-interval drain does. */
    private static void settle(GameTestHelper helper) {
        helper.assertTrue(GregScopeTestHooks.flushIo(), "the I/O queue did not drain");
        helper.assertTrue(GregScopeTestHooks.applyHistoryLoadsNow() >= 0, "GregScope test hooks are disabled");
        helper.assertTrue(GregScopeTestHooks.flushIo(), "the I/O queue did not drain");
    }

    private static SensorRegistry registry(GameTestHelper helper) {
        SensorRegistry registry = GregScope.registry();
        helper.assertNotNull(registry, "GregScope has no registry; is the server running?");
        return registry;
    }

    private static SensorEntry entry(GameTestHelper helper, UUID id) {
        SensorEntry entry = registry(helper).core()
            .entry(id);
        helper.assertNotNull(entry, "no registry entry for sensor " + id);
        return entry;
    }

    private static Map<String, Object> page(GameTestHelper helper, Component component, int offset, int limit) {
        return table(
            helper,
            OcComponents.invoke(helper, component, "listSensors", offset, limit),
            "listSensors(" + offset + ", " + limit + ")");
    }

    /** The sensor ids of a {@code listSensors} page, in the order the page lists them. */
    private static List<UUID> idsOf(GameTestHelper helper, Map<String, Object> page) {
        List<UUID> out = new ArrayList<>();
        for (Object raw : OcComponents.asList(helper, page.get("sensors"), "sensors")) {
            Object id = OcComponents.asMap(helper, raw)
                .get("id");
            out.add(UUID.fromString(String.valueOf(id)));
        }
        return out;
    }

    /** The single table a successful callback returns; fails on a soft error. */
    private static Map<String, Object> table(GameTestHelper helper, Object[] result, String what) {
        helper.assertTrue(
            result != null && result.length >= 1 && result[0] != null,
            what + " did not return a table: " + (result == null ? null : Arrays.asList(result)));
        return OcComponents.asMap(helper, result[0]);
    }

    private static long longOf(GameTestHelper helper, Map<String, Object> table, String key) {
        helper.assertTrue(table.containsKey(key), key + " missing from " + table);
        return OcComponents.asLong(helper, table.get(key), key);
    }

    private static void assertSoftError(GameTestHelper helper, String what, Object[] result, String message) {
        helper.assertTrue(
            result != null && result.length == 2,
            what + ": soft error result count, got " + (result == null ? null : Arrays.asList(result)));
        helper.assertNull(result[0], what + ": soft error first value");
        helper.assertEquals(message, result[1], what + ": soft error message");
    }
}
