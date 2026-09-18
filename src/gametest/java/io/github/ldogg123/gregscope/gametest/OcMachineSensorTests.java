package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.AfterBatch;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.integration.opencomputers.LuaTables;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.FakeClock;
import io.github.ldogg123.gregscope.sensor.Labels;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import li.cil.oc.api.Network;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;

/**
 * GS-115 (design-v0.2 sections 10.1, 10.2, 10.4 and 14): the two callbacks the merged machine component gains, driven
 * through OpenComputers' own {@code Component.invoke} exactly as a Lua program would call them.
 *
 * <p>
 * Every assertion is about the shipped environment reached through a really placed Adapter, so the v0.1 promises are
 * checked as facts and not as intentions: {@code getSnapshot} still returns the same single schema v1 map, and the
 * merged component keeps its address across a real chunk unload and reload now that two callbacks were added to the
 * environment (erratum E3).
 *
 * <p>
 * A Horizon-QA warp never advances server time (erratum E2), so the history test drives time with a {@link FakeClock}
 * and {@code sampleNow}, and installs that clock only inside one synchronous block: no server tick passes there, so
 * the real sampler never sees it.
 *
 * <p>
 * <b>Batch names.</b> Both batches sort after every existing GregScope batch, so no older test's cell moves, and the
 * chunk-reload test has a batch of its own because it takes its cell down (the reason the GS-109/GS-110 notes give).
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope" })
public class OcMachineSensorTests {

    private static final String BATCH = "gregscope.surface.ocsensor";
    /** Takes the whole cell down and brings it back, so it runs alone. */
    private static final String RELOAD_BATCH = "gregscope.surface.ocsensor.reload";

    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos ADAPTER = at(2, 0, 1);
    private static final ForgeDirection COVERED = ForgeDirection.UP;

    /** Upper bound for OpenComputers' scheduled network join of an Adapter (observed within 1-2 ticks). */
    private static final int OC_JOIN_TIMEOUT_TICKS = 20;
    /**
     * Every test here is one Adapter join plus a synchronous body (observed: 0 to 1 ticks), so the Horizon-QA default
     * of 100 would charge the worst-case suite budget more than twice what these tests can use. This is the join
     * budget plus room for the sequence steps around it.
     */
    private static final int TIMEOUT_TICKS = 40;
    /** The same, plus the unload tick and the reloaded Adapter's own join (observed: 3 ticks). */
    private static final int RELOAD_TIMEOUT_TICKS = 60;

    /**
     * 2033-05-18T03:34:00Z, on a minute boundary and after the real clock, exactly as {@code HistoryTests} picks it:
     * a run can never stop before it started.
     */
    private static final long BASE_EPOCH_SEC = 33_333_334L * 60L;
    private static final int SAMPLES_PER_MINUTE = 60;
    /** Two whole minutes plus the first sample of the third, which is what closes the second minute. */
    private static final int RECORDED_SECONDS = 2 * SAMPLES_PER_MINUTE + 1;

    private OcMachineSensorTests() {}

    @AfterBatch(BATCH)
    public static void cleanUpAfterOcSensors() {
        SensorCleanup.detachAllAndPurge();
    }

    @AfterBatch(RELOAD_BATCH)
    public static void cleanUpAfterOcSensorReload() {
        SensorCleanup.detachAllAndPurge();
    }

    /**
     * Design-v0.2 section 10: the v0.1 surface is untouched. The merged component keeps {@code getSnapshot} and OC's
     * own {@code getStoredEU}, gains exactly the two section 10.2 callbacks, and {@code getSnapshot} still answers
     * with the one schema v1 map the probe produces for the same machine.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void v01SnapshotOutputIsUnchanged(GameTestHelper helper) {
        TileEntity adapter = machineWithSensorAndAdapter(helper, "00b1", "ocSnapshotOwner");
        State st = new State();

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the machine",
                OC_JOIN_TIMEOUT_TICKS,
                () -> { st.component = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter); })
            .thenExecute(() -> {
                Collection<String> methods = st.component.methods();
                helper.assertTrue(methods.contains("getSnapshot"), "methods lack getSnapshot: " + methods);
                helper.assertTrue(methods.contains("getStoredEU"), "methods lack getStoredEU: " + methods);
                helper.assertTrue(methods.contains("getSensor"), "methods lack getSensor: " + methods);
                helper.assertTrue(methods.contains("getSensorHistory"), "methods lack getSensorHistory: " + methods);

                Object[] result = OcComponents.invoke(helper, st.component, "getSnapshot");
                helper.assertEquals(1L, result.length, "getSnapshot still returns exactly one value");
                Map<String, Object> viaOc = OcComponents.asMap(helper, result[0]);
                Map<String, Object> viaProbe = Snapshots.probe(helper, MACHINE, "ocsensor#1 probe");
                helper.assertEquals(viaProbe.keySet(), viaOc.keySet(), "getSnapshot key set changed in v0.2");
                for (Map.Entry<String, Object> e : viaProbe.entrySet()) {
                    Object expected = e.getValue();
                    Object actual = viaOc.get(e.getKey());
                    if (expected instanceof List) {
                        // OpenComputers turns a java.util.List result into an Object[] on the way out.
                        helper.assertIterableEquals(
                            (List<?>) expected,
                            OcComponents.asList(helper, actual, e.getKey()),
                            "getSnapshot " + e.getKey());
                    } else {
                        helper.assertEquals(expected, actual, "getSnapshot " + e.getKey());
                    }
                }
            })
            .thenSucceed();
    }

    /**
     * Design-v0.2 sections 10.1 and 14 {@code getSensorMatchesCoverUuid}: the record is about the cover that really
     * sits on this machine, and reading it changes nothing.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void getSensorMatchesCoverUuid(GameTestHelper helper) {
        UUID owner = UUID.fromString("00c0ffee-0000-4000-8000-000000000115");
        TileEntity adapter = machineWithSensorAndAdapter(helper, "00b2", owner, "ocSensorOwner");
        UUID id = sensorCover(helper).identity()
            .id();
        State st = new State();

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the machine",
                OC_JOIN_TIMEOUT_TICKS,
                () -> { st.component = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter); })
            .thenExecute(() -> {
                SensorEntry entry = entry(helper, id);
                long sampleBefore = entry.lastSampleEpochSec();
                int sizeBefore = registry(helper).core()
                    .size();

                Map<String, Object> record = record(helper, st.component);
                Snapshots.log("ocsensor#2 getSensor", record);
                helper.assertEquals(
                    (long) LuaTables.SENSOR_RECORD_VERSION,
                    OcComponents.asLong(helper, record.get("sensorRecordVersion"), "sensorRecordVersion"));
                helper.assertEquals(id.toString(), record.get("id"), "the record is about another sensor");
                helper.assertEquals(Labels.shortId(id), record.get("shortId"), "shortId");
                helper.assertEquals("live", record.get("availability"), "availability of a LIVE sensor");
                helper.assertEquals("machine", record.get("kind"), "kind");
                helper.assertEquals(
                    COVERED.name()
                        .toLowerCase(Locale.ROOT),
                    record.get("side"),
                    "the side the cover really sits on");
                helper.assertEquals(owner.toString(), record.get("owner"), "owner");
                helper.assertEquals("ocSensorOwner", record.get("ownerName"), "ownerName");

                TestPos abs = helper.absolute(MACHINE);
                helper.assertEquals((long) abs.x(), OcComponents.asLong(helper, record.get("x"), "x"));
                helper.assertEquals((long) abs.y(), OcComponents.asLong(helper, record.get("y"), "y"));
                helper.assertEquals((long) abs.z(), OcComponents.asLong(helper, record.get("z"), "z"));
                helper.assertEquals(
                    (long) helper.getWorld().provider.dimensionId,
                    OcComponents.asLong(helper, record.get("dimension"), "dimension"));
                helper.assertEquals((long) entry.metaId(), OcComponents.asLong(helper, record.get("metaId"), "metaId"));
                helper.assertTrue(record.containsKey("lastSeen"), "lastSeen missing from " + record);
                helper.assertTrue(record.containsKey("displayName"), "displayName missing from " + record);
                for (Map.Entry<String, Object> e : record.entrySet()) {
                    helper.assertNotNull(e.getValue(), "nil value for key " + e.getKey());
                }

                // Read-only (design-v0.2 section 1.4): the callback touched neither the entry nor the registry.
                helper.assertEquals(SensorState.LIVE, entry.state(), "getSensor changed the lifecycle state");
                helper.assertEquals(sampleBefore, entry.lastSampleEpochSec(), "getSensor took a sample");
                helper.assertEquals(
                    sizeBefore,
                    registry(helper).core()
                        .size(),
                    "getSensor changed the registry size");
            })
            .thenSucceed();
    }

    /** Design-v0.2 section 14 {@code noSensorNil}: a machine with no cover answers the soft error, not an exception. */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void noSensorNil(GameTestHelper helper) {
        GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_E_Furnace.get(1L));
        TileEntity adapter = OcComponents.placeBlock(helper, ADAPTER, "adapter");
        Network.joinOrCreateNetwork(adapter);
        State st = new State();

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the machine",
                OC_JOIN_TIMEOUT_TICKS,
                () -> { st.component = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter); })
            .thenExecute(() -> {
                helper.assertFalse(
                    machine(helper).getCoverAtSide(COVERED) instanceof MachineSensorCover,
                    "this machine must carry no sensor");
                assertSoftError(
                    helper,
                    "getSensor",
                    OcComponents.invoke(helper, st.component, "getSensor"),
                    "no sensor");
                assertSoftError(
                    helper,
                    "getSensorHistory minute",
                    OcComponents.invoke(helper, st.component, "getSensorHistory", "minute"),
                    "no sensor");
                assertSoftError(
                    helper,
                    "getSensorHistory second",
                    OcComponents.invoke(helper, st.component, "getSensorHistory", "second"),
                    "no sensor");
            })
            .thenSucceed();
    }

    /**
     * Design-v0.2 section 10.4: an unknown resolution, and no resolution at all, are the soft error
     * {@code nil, "bad resolution"} - answered before the sensor is even looked up.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void badResolutionSoftErrors(GameTestHelper helper) {
        TileEntity adapter = machineWithSensorAndAdapter(helper, "00b3", "ocResolutionOwner");
        State st = new State();

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the machine",
                OC_JOIN_TIMEOUT_TICKS,
                () -> { st.component = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter); })
            .thenExecute(() -> {
                assertSoftError(
                    helper,
                    "getSensorHistory hour",
                    OcComponents.invoke(helper, st.component, "getSensorHistory", "hour"),
                    "bad resolution");
                assertSoftError(
                    helper,
                    "getSensorHistory with no argument",
                    OcComponents.invoke(helper, st.component, "getSensorHistory"),
                    "bad resolution");
            })
            .thenSucceed();
    }

    /**
     * Design-v0.2 section 14 {@code historyRowsOldestFirst}: both resolutions return rows oldest first over the
     * window section 10.4 describes, and paging back with the returned {@code from} reaches the minute before.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void historyRowsOldestFirst(GameTestHelper helper) {
        TileEntity adapter = machineWithSensorAndAdapter(helper, "00b4", "ocHistoryOwner");
        UUID id = sensorCover(helper).identity()
            .id();
        State st = new State();

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the machine",
                OC_JOIN_TIMEOUT_TICKS,
                () -> { st.component = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter); })
            .thenExecute(() -> {
                // One synchronous block: no server tick passes, so the real sampler never sees the fake clock.
                FakeClock clock = FakeClock.atEpochSec(BASE_EPOCH_SEC);
                helper.assertTrue(GregScopeTestHooks.setClock(clock), "GregScope test hooks are disabled");
                try {
                    heartbeat(helper);
                    settle(helper);
                    SensorEntry entry = entry(helper, id);
                    helper.assertEquals(SensorState.LIVE, entry.state(), "the fixture sensor is not LIVE");
                    int firstMinute = (int) (BASE_EPOCH_SEC / 60L);
                    for (int i = 0; i < RECORDED_SECONDS; i++) {
                        helper.assertTrue(GregScopeTestHooks.sampleNow(id), "the sample did not resolve its target");
                        clock.advanceSeconds(1L);
                    }
                    settle(helper);
                    helper.assertTrue(entry.historyLoaded(), "minute history was never marked loaded");
                    long now = clock.epochSec();

                    assertSecondHistory(helper, st.component, id, now);
                    assertMinuteHistory(helper, st.component, id, firstMinute, now);

                    // Read-only: reading history neither samples nor rewrites the ring.
                    long newest = entry.minutes()
                        .newestEpochMinute();
                    OcComponents.invoke(helper, st.component, "getSensorHistory", "minute", 240);
                    OcComponents.invoke(helper, st.component, "getSensorHistory", "second", 300);
                    helper.assertEquals(
                        newest,
                        entry.minutes()
                            .newestEpochMinute(),
                        "reading history changed the minute ring");
                    helper.assertEquals(now - 1L, entry.lastSampleEpochSec(), "reading history took a sample");
                } finally {
                    GregScopeTestHooks.setClock(null);
                }
            })
            .thenSucceed();
    }

    private static void assertSecondHistory(GameTestHelper helper, Component component, UUID id, long now) {
        Map<String, Object> table = table(
            helper,
            OcComponents.invoke(helper, component, "getSensorHistory", "second", 10),
            "second history");
        Snapshots.log("ocsensor#5 second history", table);
        helper.assertEquals(
            (long) LuaTables.HISTORY_VERSION,
            OcComponents.asLong(helper, table.get("historyVersion"), "historyVersion"));
        helper.assertEquals(id.toString(), table.get("id"), "history id");
        helper.assertEquals("second", table.get("resolution"), "resolution");
        helper.assertEquals(1L, OcComponents.asLong(helper, table.get("step"), "step"));
        helper.assertEquals(now - 10L, OcComponents.asLong(helper, table.get("from"), "from"));
        helper.assertEquals(now, OcComponents.asLong(helper, table.get("to"), "to"));

        List<Object> rows = OcComponents.asList(helper, table.get("rows"), "rows");
        helper.assertEquals(10L, (long) rows.size(), "ten observed seconds: " + rows.size());
        long previous = Long.MIN_VALUE;
        for (Object raw : rows) {
            Map<String, Object> row = OcComponents.asMap(helper, raw);
            long t = OcComponents.asLong(helper, row.get("t"), "t");
            helper.assertTrue(t > previous, "second rows are not oldest first: " + t + " after " + previous);
            previous = t;
            helper.assertTrue(row.containsKey("state"), "a second row without a state: " + row);
            helper.assertTrue(row.containsKey("progress"), "a second row without progress: " + row);
            helper.assertTrue(row.containsKey("maintenanceIssues"), "a second row without maintenance: " + row);
        }
        helper.assertEquals(
            0L,
            (long) OcComponents.asList(helper, table.get("gaps"), "gaps")
                .size(),
            "a fully sampled window must carry no gap");
    }

    private static void assertMinuteHistory(GameTestHelper helper, Component component, UUID id, int firstMinute,
        long now) {
        Map<String, Object> page = table(
            helper,
            OcComponents.invoke(helper, component, "getSensorHistory", "minute", 1),
            "minute history");
        Snapshots.log("ocsensor#5 minute history", page);
        helper.assertEquals("minute", page.get("resolution"), "resolution");
        helper.assertEquals(60L, OcComponents.asLong(helper, page.get("step"), "step"));
        long to = OcComponents.asLong(helper, page.get("to"), "to");
        long from = OcComponents.asLong(helper, page.get("from"), "from");
        helper.assertEquals(now / 60L * 60L, to, "the window ends at the start of the open minute");
        helper.assertEquals(to - 60L, from, "one minute of window");

        List<Object> rows = OcComponents.asList(helper, page.get("rows"), "rows");
        helper.assertEquals(1L, (long) rows.size(), "the second recorded minute is one row");
        Map<String, Object> row = OcComponents.asMap(helper, rows.get(0));
        helper.assertEquals((firstMinute + 1) * 60L, OcComponents.asLong(helper, row.get("t"), "t"));
        helper.assertEquals(
            (long) SAMPLES_PER_MINUTE,
            OcComponents.asLong(helper, row.get("samples"), "samples"),
            "a whole minute of one sample per second");
        helper.assertEquals(1.0, ((Number) row.get("coverage")).doubleValue(), 1.0e-9, "coverage of a whole minute");
        helper.assertTrue(row.containsKey("lastState"), "a minute row without lastState: " + row);
        Map<String, Object> states = OcComponents.asMap(helper, row.get("stateSeconds"));
        long stateSum = 0L;
        for (Map.Entry<String, Object> e : states.entrySet()) {
            helper.assertNotNull(StateCodes.state(codeOf(helper, e.getKey())), "unknown state id " + e.getKey());
            stateSum += OcComponents.asLong(helper, e.getValue(), e.getKey());
        }
        helper.assertEquals((long) SAMPLES_PER_MINUTE, stateSum, "the per-state counts must add up: " + states);

        // Paging: the returned `from` is the next `before`, and it reaches the minute before with no overlap.
        Map<String, Object> older = table(
            helper,
            OcComponents.invoke(helper, component, "getSensorHistory", "minute", 1, from),
            "older minute page");
        helper.assertEquals(from, OcComponents.asLong(helper, older.get("to"), "to"), "the pages do not meet");
        List<Object> olderRows = OcComponents.asList(helper, older.get("rows"), "rows");
        helper.assertEquals(1L, (long) olderRows.size(), "the first recorded minute is one row");
        helper.assertEquals(
            firstMinute * 60L,
            OcComponents.asLong(
                helper,
                OcComponents.asMap(helper, olderRows.get(0))
                    .get("t"),
                "t"),
            "paging back did not reach the older minute");
    }

    /**
     * Design-v0.2 section 14 {@code addressStableAcrossChunkReload} and erratum E3: adding callbacks to the
     * environment must not change the address OpenComputers restores for the merged component.
     */
    @GameTest(batch = RELOAD_BATCH, timeoutTicks = RELOAD_TIMEOUT_TICKS)
    public static void addressStableAcrossChunkReload(GameTestHelper helper) {
        TileEntity oldAdapter = machineWithSensorAndAdapter(helper, "00b5", "ocAddressOwner");
        ChunkReload chunks = ChunkReload.of(helper, MACHINE, ADAPTER);
        State st = new State();

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the machine",
                OC_JOIN_TIMEOUT_TICKS,
                () -> { st.component = OcComponents.adapterSnapshotComponent(helper, (Environment) oldAdapter); })
            .thenExecute("unload", () -> {
                st.address = st.component.address();
                st.name = st.component.name();
                helper.assertTrue(
                    st.component.methods()
                        .contains("getSensor"),
                    "the component under test has no getSensor");
                chunks.unload();
            })
            .thenIdle(1)
            .thenExecute("reload", () -> {
                helper.assertFalse(chunks.anyLoaded(), "the cell did not unload");
                chunks.reload();
            })
            .thenWaitUntil("reloaded Adapter exposes the machine", OC_JOIN_TIMEOUT_TICKS, () -> {
                TileEntity adapter = helper.assertTileEntityPresent(ADAPTER);
                helper.assertNotSame(oldAdapter, adapter, "Adapter tile entity object after reload");
                st.reloaded = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter);
            })
            .thenExecute(() -> {
                helper.assertEquals(st.address, st.reloaded.address(), "merged component address after reload");
                helper.assertEquals(st.name, st.reloaded.name(), "merged component name after reload");
                Collection<String> methods = st.reloaded.methods();
                helper.assertTrue(methods.contains("getSnapshot"), "methods lack getSnapshot: " + methods);
                helper.assertTrue(methods.contains("getSensor"), "methods lack getSensor: " + methods);
                helper.assertTrue(methods.contains("getSensorHistory"), "methods lack getSensorHistory: " + methods);
                Snapshots.log("ocsensor#6 address across reload", st.address);
            })
            .thenSucceed();
    }

    /**
     * The side names section 10.1 spells ({@code side="east"}) are pinned in a [pure] class, which may not name
     * {@code ForgeDirection}. This is the check that keeps the two from drifting.
     */
    @GameTest(batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void sideNamesMatchForgeDirection(GameTestHelper helper) {
        for (int side = 0; side < 6; side++) {
            ForgeDirection direction = ForgeDirection.getOrientation(side);
            helper.assertEquals(
                direction.name()
                    .toLowerCase(Locale.ROOT),
                LuaTables.sideName(side),
                "pinned side name " + side);
        }
        helper.assertEquals(
            ForgeDirection.UNKNOWN,
            ForgeDirection.getOrientation(6),
            "ForgeDirection ordinal 6 is no longer UNKNOWN");
        helper.assertEquals("unknown", LuaTables.sideName(6), "ForgeDirection.UNKNOWN");
        helper.succeed();
    }

    // --- fixtures ---

    /** Mutable state a Horizon-QA sequence carries between its steps. */
    private static final class State {

        Component component;
        Component reloaded;
        String address;
        String name;
    }

    private static TileEntity machineWithSensorAndAdapter(GameTestHelper helper, String idPrefix, String ownerName) {
        return machineWithSensorAndAdapter(
            helper,
            idPrefix,
            UUID.fromString("00c0ffee-0000-4000-8000-0000000001" + idPrefix.substring(2)),
            ownerName);
    }

    /**
     * A GT machine carrying a sensor cover with a chosen identity and owner, an Adapter next to it, and the sensor
     * registered LIVE by one heartbeat. The identity is placed through the machine's item NBT because an in-game
     * attach by a {@code FakePlayer} produces an unowned sensor (design-v0.2 section 3.4).
     */
    private static TileEntity machineWithSensorAndAdapter(GameTestHelper helper, String idPrefix, UUID owner,
        String ownerName) {
        UUID id = UUID.fromString(idPrefix + "0115-0000-4000-8000-000000000115");
        SensorIdentity identity = new SensorIdentity(id, "", owner, ownerName, 1_600_000_000L);
        IGregTechTileEntity holder = SensorFixtures.placeMachineWithSensorNbt(helper, MACHINE, COVERED, identity);
        MachineSensorCover cover = helper
            .assertInstanceOf(MachineSensorCover.class, holder.getCoverAtSide(COVERED), "sensor cover on " + COVERED);
        helper.assertEquals(
            id,
            cover.identity()
                .id(),
            "the placed cover carries another identity");
        heartbeat(helper);
        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.LIVE, entry.state(), "the fixture sensor did not register");
        TileEntity adapter = OcComponents.placeBlock(helper, ADAPTER, "adapter");
        Network.joinOrCreateNetwork(adapter);
        return adapter;
    }

    private static void heartbeat(GameTestHelper helper) {
        helper.assertTrue(GregScopeTestHooks.heartbeatNow(sensorCover(helper)), "GregScope test hooks are disabled");
    }

    /** Lets an async history load land and its answer be written, the way the sampler's per-interval drain does. */
    private static void settle(GameTestHelper helper) {
        helper.assertTrue(GregScopeTestHooks.flushIo(), "the I/O queue did not drain");
        helper.assertTrue(GregScopeTestHooks.applyHistoryLoadsNow() >= 0, "GregScope test hooks are disabled");
        helper.assertTrue(GregScopeTestHooks.flushIo(), "the I/O queue did not drain");
    }

    private static IGregTechTileEntity machine(GameTestHelper helper) {
        return helper
            .assertInstanceOf(IGregTechTileEntity.class, helper.assertTileEntityPresent(MACHINE), "GT machine");
    }

    private static MachineSensorCover sensorCover(GameTestHelper helper) {
        return helper.assertInstanceOf(
            MachineSensorCover.class,
            machine(helper).getCoverAtSide(COVERED),
            "sensor cover on " + COVERED);
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

    private static Map<String, Object> record(GameTestHelper helper, Component component) {
        return table(helper, OcComponents.invoke(helper, component, "getSensor"), "getSensor");
    }

    /** The single table a successful callback returns; fails on a soft error. */
    private static Map<String, Object> table(GameTestHelper helper, Object[] result, String what) {
        helper.assertTrue(
            result != null && result.length == 1 && result[0] != null,
            what + " did not return a table: " + (result == null ? null : Arrays.asList(result)));
        return OcComponents.asMap(helper, result[0]);
    }

    private static void assertSoftError(GameTestHelper helper, String what, Object[] result, String message) {
        helper.assertTrue(
            result != null && result.length == 2,
            what + ": soft error result count, got " + (result == null ? null : Arrays.asList(result)));
        helper.assertNull(result[0], what + ": soft error first value");
        helper.assertEquals(message, result[1], what + ": soft error message");
    }

    /** The pinned state code of a state id, for checking that a {@code stateSeconds} key is a real state. */
    private static int codeOf(GameTestHelper helper, String stateId) {
        for (int code = 0; code < StateCodes.COUNT; code++) {
            if (stateId.equals(StateCodes.id(code))) {
                return code;
            }
        }
        helper.fail("unknown state id " + stateId);
        return -1;
    }
}
