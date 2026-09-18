package io.github.ldogg123.gregscope.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.access.AccessPolicy;
import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.access.Viewer;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapRanges;
import io.github.ldogg123.gregscope.history.MinuteRing;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.history.MinuteSource;
import io.github.ldogg123.gregscope.model.MachineKind;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.MachineState;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.RemovalCause;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.LimitsView;
import io.github.ldogg123.gregscope.sampling.SamplerStats;
import io.github.ldogg123.gregscope.sampling.SamplerStatsView;
import io.github.ldogg123.gregscope.sampling.SensorView;
import io.github.ldogg123.gregscope.sampling.TelemetryFrame;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-113 (design-v0.2 section 9.2, section 9.3, section 7.5): the pure Hub view model. Scope, sort, the Problems
 * filter, paging, selection by UUID, the two window summaries, the hourly strip and the rebuild throttle, with no
 * world anywhere near it.
 *
 * <p>
 * Everything the model reads is an argument, so every rule of section 9.2 is a plain-JVM assertion here: the frame is
 * built by hand, the team system is a map, and the minute history is a {@link MinuteRing} the test fills.
 */
class HubViewModelTest {

    /** A minute boundary, so {@code nowEpochSec / 60} is exact. */
    private static final long T0 = 1_700_000_040L;
    private static final int NOW_MINUTE = (int) (T0 / 60L);
    private static final int EXPECTED_PER_MINUTE = MinuteSlot.expectedSamples(20);

    private static final UUID ALICE = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    private static final UUID CAROL = UUID.fromString("c0000000-0000-4000-8000-000000000003");

    private final Teams teams = new Teams();
    private final AccessPolicy policy = new AccessPolicy(Settings.DEFAULTS);
    private final Viewer alice = Viewer.player(ALICE, level -> false);

    // --- fixtures ---

    /** A {@link TeamResolver} over a map; the hub package cannot see the access package's own fake. */
    private static final class Teams implements TeamResolver<String> {

        private final Map<UUID, String> byPlayer = new HashMap<>();
        private final Map<String, Set<UUID>> members = new HashMap<>();
        private final Set<UUID> officers = new HashSet<>();

        Teams team(String name, UUID... players) {
            members.put(name, new HashSet<>(Arrays.asList(players)));
            for (UUID player : players) {
                byPlayer.put(player, name);
            }
            return this;
        }

        @Override
        public String teamOf(UUID player) {
            return player == null ? null : byPlayer.get(player);
        }

        @Override
        public boolean isMember(String team, UUID player) {
            return members.get(team)
                .contains(player);
        }

        @Override
        public boolean isOfficerOrOwner(String team, UUID player) {
            return officers.contains(player);
        }
    }

    /** Builds one {@link SensorView} the way the sampler would. */
    private static final class Sensor {

        private final SensorEntry entry;

        Sensor(String idPrefix, UUID owner, String label, SensorState state) {
            UUID id = UUID.fromString(idPrefix + "-0000-4000-8000-000000000000");
            SensorIdentity identity = new SensorIdentity(
                id,
                label,
                owner,
                owner == null ? null : "Owner",
                T0 - 86_400L);
            this.entry = new SensorEntry(identity, SensorKind.MACHINE, 0, 120, 64, -30, 4, state, T0 - 600L);
            entry.setMachineMetadata(1001, "machine.ebf", "Electric Blast Furnace", "running");
            entry.setLastSeenEpochSec(T0 - 5L);
            entry.setLastSampleEpochSec(T0 - 1L);
            if (state != SensorState.LIVE) {
                entry.restoreState(state, RemovalCause.NONE, T0 - 720L, T0 - 720L);
            }
        }

        Sensor snapshot(MachineState state, long euPerTick, String... warnings) {
            entry.setLastSnapshot(snapshotOf(state, euPerTick, Arrays.asList(warnings)));
            return this;
        }

        Sensor machine(String machineName) {
            entry.setMachineMetadata(1001, "machine.ebf", machineName, "running");
            return this;
        }

        Sensor seen(long lastSeen, long lastSample) {
            entry.setLastSeenEpochSec(lastSeen);
            entry.setLastSampleEpochSec(lastSample);
            return this;
        }

        SensorView view() {
            return new SensorView(entry);
        }

        UUID id() {
            return entry.id();
        }
    }

    private static MachineSnapshot snapshotOf(MachineState state, long euPerTick, List<String> warnings) {
        return MachineSnapshot.builder()
            .kind(MachineKind.MULTIBLOCK)
            .state(state)
            .statusId(state.id())
            .statusText("Running perfectly")
            .name("Electric Blast Furnace")
            .metaName("machine.ebf")
            .metaId(1001)
            .machineClass("MTEBlastFurnace")
            .dimension(0)
            .x(120)
            .y(64)
            .z(-30)
            .active(true)
            .allowedToWork(true)
            .hasThingsToDo(true)
            .wasShutdown(false)
            .progressTicks(42)
            .maxProgressTicks(100)
            .progress(0.42)
            .warnings(warnings)
            .euPerTick(euPerTick)
            .energyStored(1_200_000L)
            .energyCapacity(1_600_000L)
            .maintenanceIssues(0)
            .build();
    }

    private static TelemetryFrame frame(long sequence, SamplerStats stats, Sensor... sensors) {
        List<SensorView> views = new ArrayList<>();
        for (Sensor sensor : sensors) {
            views.add(sensor.view());
        }
        return new TelemetryFrame(
            sequence,
            1_000L,
            T0 * 1000L,
            20,
            views,
            new SamplerStatsView(stats, 0L, 0L),
            new LimitsView(Settings.DEFAULTS));
    }

    private static TelemetryFrame frame(long sequence, Sensor... sensors) {
        return frame(sequence, new SamplerStats(), sensors);
    }

    /** A history host with one sensor's ring. */
    private static final class OneRing implements HubViewModel.History {

        private final UUID id;
        private final MinuteRing ring;
        private boolean loaded = true;

        OneRing(UUID id, MinuteRing ring) {
            this.id = id;
            this.ring = ring;
        }

        @Override
        public MinuteSource minutes(UUID sensor) {
            return id.equals(sensor) ? ring : null;
        }

        @Override
        public boolean loaded(UUID sensor) {
            return loaded && id.equals(sensor);
        }

        @Override
        public GapRanges.Context gapContext(UUID sensor, long nowEpochSec) {
            return GapRanges.Context.of(Collections.<GapRanges.Run>emptyList());
        }
    }

    private static MinuteSlot minute(int epochMinute, int running, int idle) {
        return MinuteSlot.builder(epochMinute)
            .samples(running + idle)
            .expectedSamples(EXPECTED_PER_MINUTE)
            .lastStateCode(running > 0 ? StateCodes.RUNNING : StateCodes.IDLE)
            .stateSamples(StateCodes.RUNNING, running)
            .stateSamples(StateCodes.IDLE, idle)
            .build();
    }

    private HubViewModel model() {
        return new HubViewModel(ALICE, "Alice", false);
    }

    private boolean rebuild(HubViewModel model, TelemetryFrame frame) {
        return model.rebuild(frame, policy, alice, teams, HubViewModel.History.NONE, T0);
    }

    private boolean rebuild(HubViewModel model, TelemetryFrame frame, HubViewModel.History history) {
        return model.rebuild(frame, policy, alice, teams, history, T0);
    }

    // --- scope (section 5) ---

    @Test
    void theScopeIsTheHubOwnersTeamAndNotTheViewers() {
        teams.team("red", ALICE, BOB);
        Sensor mine = new Sensor("00000001", ALICE, "mine", SensorState.LIVE);
        Sensor mate = new Sensor("00000002", BOB, "team mate", SensorState.LIVE);
        Sensor stranger = new Sensor("00000003", CAROL, "stranger", SensorState.LIVE);
        HubViewModel model = model();

        rebuild(model, frame(1L, mine, mate, stranger));

        assertEquals(
            2,
            model.header()
                .total());
        assertEquals(
            new HashSet<>(Arrays.asList(mine.id(), mate.id())),
            new HashSet<>(
                Arrays.asList(
                    model.rows()
                        .get(0)
                        .id(),
                    model.rows()
                        .get(1)
                        .id())));
    }

    @Test
    void anOperatorViewingSomeoneElsesHubGainsNoExtraVisibility() {
        Sensor stranger = new Sensor("00000003", CAROL, "stranger", SensorState.LIVE);
        HubViewModel model = model();

        model.rebuild(frame(1L, stranger), policy, Viewer.CONSOLE, teams, HubViewModel.History.NONE, T0);

        assertEquals(
            0,
            model.header()
                .total());
        assertTrue(
            model.rows()
                .get(0)
                .isEmpty());
    }

    @Test
    void anUnownedSensorIsNeverInAnyHubScope() {
        Sensor unowned = new Sensor("00000004", null, "unowned", SensorState.LIVE);
        HubViewModel model = model();

        rebuild(model, frame(1L, unowned));

        assertEquals(
            0,
            model.header()
                .total());
    }

    @Test
    void anUnownedHubShowsNothingAtAll() {
        Sensor mine = new Sensor("00000001", ALICE, "mine", SensorState.LIVE);
        HubViewModel model = new HubViewModel(null, null, true);

        rebuild(model, frame(1L, mine));

        assertEquals(
            0,
            model.header()
                .total());
        assertTrue(
            model.header()
                .unsupported());
        assertNull(
            model.header()
                .owner());
    }

    // --- sort (section 9.2) ---

    @Test
    void rowsAreSortedBySeverityThenNameThenId() {
        Sensor running = new Sensor("00000001", ALICE, "zzz running", SensorState.LIVE)
            .snapshot(MachineState.RUNNING, 1_920L);
        Sensor shutdown = new Sensor("00000002", ALICE, "shutdown", SensorState.LIVE)
            .snapshot(MachineState.SHUTDOWN, 0L);
        Sensor starved = new Sensor("00000003", ALICE, "starved", SensorState.LIVE)
            .snapshot(MachineState.POWER_STARVED, 0L);
        Sensor blocked = new Sensor("00000004", ALICE, "blocked", SensorState.LIVE)
            .snapshot(MachineState.OUTPUT_BLOCKED, 0L);
        Sensor waiting = new Sensor("00000005", ALICE, "waiting", SensorState.LIVE).snapshot(MachineState.WAITING, 0L);
        Sensor unformed = new Sensor("00000006", ALICE, "unformed", SensorState.LIVE)
            .snapshot(MachineState.UNFORMED, 0L);
        Sensor disabled = new Sensor("00000007", ALICE, "disabled", SensorState.LIVE)
            .snapshot(MachineState.DISABLED, 0L);
        Sensor missing = new Sensor("00000008", ALICE, "missing", SensorState.MISSING);
        HubViewModel model = model();

        rebuild(model, frame(1L, running, missing, disabled, unformed, waiting, blocked, starved, shutdown));

        assertEquals(
            Arrays.asList(
                shutdown.id(),
                starved.id(),
                blocked.id(),
                waiting.id(),
                unformed.id(),
                disabled.id(),
                missing.id(),
                running.id()),
            ids(model.rows()));
    }

    @Test
    void unloadedSortsAfterEveryTombstoneAndBeforeAnyHealthyMachine() {
        Sensor idle = new Sensor("00000001", ALICE, "idle", SensorState.LIVE).snapshot(MachineState.IDLE, 0L);
        Sensor unloaded = new Sensor("00000002", ALICE, "unloaded", SensorState.UNLOADED);
        Sensor removed = new Sensor("00000003", ALICE, "removed", SensorState.REMOVED);
        Sensor inItem = new Sensor("00000004", ALICE, "in item", SensorState.IN_ITEM);
        Sensor running = new Sensor("00000005", ALICE, "running", SensorState.LIVE).snapshot(MachineState.RUNNING, 10L);
        HubViewModel model = model();

        rebuild(model, frame(1L, idle, unloaded, removed, inItem, running));

        assertEquals(
            Arrays.asList(inItem.id(), removed.id(), unloaded.id(), running.id(), idle.id()),
            ids(model.rows()));
    }

    @Test
    void namesTieBreakIgnoringCaseAndIdsBreakThatTie() {
        Sensor upper = new Sensor("00000002", ALICE, "Bravo", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        Sensor lower = new Sensor("00000001", ALICE, "alpha", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        Sensor sameA = new Sensor("00000003", ALICE, "alpha", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        HubViewModel model = model();

        rebuild(model, frame(1L, upper, sameA, lower));

        assertEquals(Arrays.asList(lower.id(), sameA.id(), upper.id()), ids(model.rows()));
    }

    @Test
    void aLiveSensorThatCouldNotBeReadSortsLastAndIsNoProblem() {
        Sensor idle = new Sensor("00000001", ALICE, "idle", SensorState.LIVE).snapshot(MachineState.IDLE, 0L);
        Sensor unreadable = new Sensor("00000002", ALICE, "unreadable", SensorState.LIVE);
        HubViewModel model = model();

        rebuild(model, frame(1L, idle, unreadable));

        assertEquals(Arrays.asList(idle.id(), unreadable.id()), ids(model.rows()));
        assertFalse(
            model.rows()
                .get(1)
                .problem());
        assertEquals(
            StateCodes.UNAVAILABLE,
            model.rows()
                .get(1)
                .stateCode());
    }

    // --- paging (section 9.2) ---

    @Test
    void aShortPageIsPaddedToEightRows() {
        HubViewModel model = model();

        rebuild(model, frame(1L, new Sensor("00000001", ALICE, "one", SensorState.LIVE)));

        assertEquals(
            HubViewModel.ROWS_PER_PAGE,
            model.rows()
                .size());
        assertFalse(
            model.rows()
                .get(0)
                .isEmpty());
        for (int i = 1; i < HubViewModel.ROWS_PER_PAGE; i++) {
            assertSame(
                HubRow.EMPTY,
                model.rows()
                    .get(i));
        }
        assertEquals(
            1,
            model.header()
                .pages());
    }

    @Test
    void theRowListIsUnmodifiable() {
        HubViewModel model = model();
        rebuild(model, frame(1L));

        assertThrows(
            UnsupportedOperationException.class,
            () -> model.rows()
                .set(0, null));
    }

    @Test
    void thePageSetterClampsBelowZeroAndAboveTheLastPage() {
        HubViewModel model = model();
        rebuild(model, frame(1L, sensors(20)));
        assertEquals(
            3,
            model.header()
                .pages());

        model.setPage(-5);
        assertEquals(0, model.page());
        model.setPage(999);
        assertEquals(2, model.page());
    }

    @Test
    void thePageIsClampedAgainWhenTheListShrinksUnderIt() {
        HubViewModel model = model();
        rebuild(model, frame(1L, sensors(20)));
        model.setPage(2);
        assertEquals(2, model.page());

        rebuild(model, frame(2L, sensors(3)));

        assertEquals(0, model.page());
        assertEquals(
            0,
            model.header()
                .page());
        assertEquals(
            1,
            model.header()
                .pages());
    }

    @Test
    void anEmptyHubStillReadsPageOneOfOne() {
        HubViewModel model = model();

        rebuild(model, frame(1L));

        assertEquals(
            0,
            model.header()
                .page());
        assertEquals(
            1,
            model.header()
                .pages());
        assertEquals(
            0,
            model.header()
                .shown());
        assertEquals(1, HubViewModel.pageCount(0));
        assertEquals(1, HubViewModel.pageCount(8));
        assertEquals(2, HubViewModel.pageCount(9));
    }

    // --- the Problems filter (section 9.2) ---

    @Test
    void aFilterValueOutsideZeroAndOneBecomesAll() {
        HubViewModel model = model();

        model.setFilter(7);
        assertEquals(HubViewModel.FILTER_ALL, model.filter());
        model.setFilter(HubViewModel.FILTER_PROBLEMS);
        assertEquals(HubViewModel.FILTER_PROBLEMS, model.filter());
        model.setFilter(-1);
        assertEquals(HubViewModel.FILTER_ALL, model.filter());
        // design-v0.3 section 6.2 adds 2 and 3; a v0.2 server does not know them yet.
        model.setFilter(2);
        assertEquals(HubViewModel.FILTER_ALL, model.filter());
        model.setFilter(3);
        assertEquals(HubViewModel.FILTER_ALL, model.filter());
    }

    @Test
    void switchingTheFilterResetsThePage() {
        HubViewModel model = model();
        rebuild(model, frame(1L, sensors(20)));
        model.setPage(2);

        model.setFilter(HubViewModel.FILTER_PROBLEMS);

        assertEquals(0, model.page());
    }

    @Test
    void theProblemsFilterKeepsTheProblemStatesAndMissing() {
        Sensor running = new Sensor("00000001", ALICE, "running", SensorState.LIVE).snapshot(MachineState.RUNNING, 10L);
        Sensor idle = new Sensor("00000002", ALICE, "idle", SensorState.LIVE).snapshot(MachineState.IDLE, 0L);
        Sensor starting = new Sensor("00000003", ALICE, "starting", SensorState.LIVE)
            .snapshot(MachineState.STARTING, 0L);
        Sensor blocked = new Sensor("00000004", ALICE, "blocked", SensorState.LIVE)
            .snapshot(MachineState.OUTPUT_BLOCKED, 0L);
        Sensor missing = new Sensor("00000005", ALICE, "missing", SensorState.MISSING);
        Sensor unloaded = new Sensor("00000006", ALICE, "unloaded", SensorState.UNLOADED);
        HubViewModel model = model();
        model.setFilter(HubViewModel.FILTER_PROBLEMS);

        rebuild(model, frame(1L, running, idle, starting, blocked, missing, unloaded));

        assertEquals(Arrays.asList(blocked.id(), missing.id()), ids(model.rows()));
        assertEquals(
            6,
            model.header()
                .total());
        assertEquals(
            2,
            model.header()
                .shown());
        assertEquals(
            6,
            model.header()
                .live() + 2);
    }

    @Test
    void aWarningOnALiveSensorIsAProblem() {
        Sensor warned = new Sensor("00000001", ALICE, "warned", SensorState.LIVE)
            .snapshot(MachineState.RUNNING, 10L, "maintenance_needed");
        HubViewModel model = model();
        model.setFilter(HubViewModel.FILTER_PROBLEMS);

        rebuild(model, frame(1L, warned));

        assertEquals(
            1,
            model.header()
                .shown());
        assertTrue(
            model.rows()
                .get(0)
                .warning());
        assertTrue(
            model.rows()
                .get(0)
                .problem());
    }

    @Test
    void aStaleWarningOnAnUnloadedSensorIsNotAProblem() {
        Sensor unloaded = new Sensor("00000001", ALICE, "unloaded", SensorState.UNLOADED)
            .snapshot(MachineState.SHUTDOWN, 0L, "maintenance_needed");
        HubViewModel model = model();
        model.setFilter(HubViewModel.FILTER_PROBLEMS);

        rebuild(model, frame(1L, unloaded));

        assertEquals(
            0,
            model.header()
                .shown());
        assertTrue(
            model.rows()
                .get(0)
                .isEmpty());
    }

    @Test
    void aRowHidesStaleMachineDataForAnythingThatIsNotLive() {
        Sensor unloaded = new Sensor("00000001", ALICE, "unloaded", SensorState.UNLOADED)
            .snapshot(MachineState.RUNNING, 1_920L)
            .seen(T0 - 720L, T0 - 720L);
        HubViewModel model = model();

        rebuild(model, frame(1L, unloaded));

        HubRow row = model.rows()
            .get(0);
        assertEquals(HubCodecs.AVAILABILITY_UNLOADED, row.availability());
        assertEquals(StateCodes.UNAVAILABLE, row.stateCode());
        assertEquals(HubCodecs.NONE, row.euPerTick());
        assertFalse(row.warning());
        assertEquals(720, row.ageSeconds());
    }

    @Test
    void aLiveRowsAgeIsMeasuredFromItsLastSample() {
        Sensor live = new Sensor("00000001", ALICE, "live", SensorState.LIVE).snapshot(MachineState.RUNNING, 5L)
            .seen(T0 - 30L, T0 - 3L);
        HubViewModel model = model();

        rebuild(model, frame(1L, live));

        assertEquals(
            3,
            model.rows()
                .get(0)
                .ageSeconds());
        assertEquals(
            5L,
            model.rows()
                .get(0)
                .euPerTick());
    }

    // --- selection (section 9.3) ---

    @Test
    void selectingARowResolvesToItsUuid() {
        Sensor a = new Sensor("00000001", ALICE, "alpha", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        Sensor b = new Sensor("00000002", ALICE, "bravo", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        HubViewModel model = model();
        rebuild(model, frame(1L, a, b));

        model.selectRow(1);

        assertEquals(b.id(), model.selected());
        rebuild(model, frame(2L, a, b));
        assertEquals(
            b.id(),
            model.detail()
                .id());
    }

    @Test
    void aSelectionSurvivesResorting() {
        Sensor a = new Sensor("00000001", ALICE, "alpha", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        Sensor b = new Sensor("00000002", ALICE, "bravo", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        HubViewModel model = model();
        rebuild(model, frame(1L, a, b));
        model.selectRow(1);
        assertEquals(b.id(), model.selected());

        // b breaks down and sorts to the top; the selection is a UUID, not the row index.
        Sensor broken = new Sensor("00000002", ALICE, "bravo", SensorState.LIVE).snapshot(MachineState.SHUTDOWN, 0L);
        rebuild(model, frame(2L, a, broken));

        assertEquals(
            broken.id(),
            model.rows()
                .get(0)
                .id());
        assertEquals(b.id(), model.selected());
        assertEquals(
            StateCodes.SHUTDOWN,
            model.detail()
                .stateCode());
    }

    @Test
    void selectingOutsideThePageOrAnEmptyRowSelectsNothing() {
        Sensor a = new Sensor("00000001", ALICE, "alpha", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        HubViewModel model = model();
        rebuild(model, frame(1L, a));
        model.selectRow(0);
        assertEquals(a.id(), model.selected());

        model.selectRow(7);
        assertNull(model.selected());
        model.selectRow(0);
        model.selectRow(-1);
        assertNull(model.selected());
        model.selectRow(0);
        model.selectRow(99);
        assertNull(model.selected());
    }

    @Test
    void aSelectionThatLeavesTheScopeIsDropped() {
        Sensor a = new Sensor("00000001", ALICE, "alpha", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        HubViewModel model = model();
        rebuild(model, frame(1L, a));
        model.selectRow(0);

        rebuild(model, frame(2L));

        assertNull(model.selected());
        assertSame(HubDetail.NONE, model.detail());
    }

    @Test
    void aSelectionTheFilterHidesIsKept() {
        Sensor healthy = new Sensor("00000001", ALICE, "healthy", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        Sensor broken = new Sensor("00000002", ALICE, "broken", SensorState.LIVE).snapshot(MachineState.SHUTDOWN, 0L);
        HubViewModel model = model();
        rebuild(model, frame(1L, healthy, broken));
        model.select(healthy.id());

        model.setFilter(HubViewModel.FILTER_PROBLEMS);
        rebuild(model, frame(1L, healthy, broken));

        assertEquals(Collections.singletonList(broken.id()), ids(model.rows()));
        assertEquals(healthy.id(), model.selected());
        assertEquals(
            healthy.id(),
            model.detail()
                .id());
    }

    // --- the detail pane (section 9.2) ---

    @Test
    void theDetailCarriesTheSnapshotNumbersAndTheirAge() {
        Sensor live = new Sensor("00000001", ALICE, "EBF North", SensorState.LIVE)
            .snapshot(MachineState.RUNNING, 1_920L)
            .seen(T0 - 2L, T0 - 2L);
        HubViewModel model = model();
        model.select(live.id());

        rebuild(model, frame(1L, live));

        HubDetail detail = model.detail();
        assertEquals("EBF North", detail.displayName());
        assertEquals("Electric Blast Furnace", detail.machineName());
        assertEquals("machine.ebf", detail.metaName());
        assertEquals(1001, detail.metaId());
        assertEquals("running", detail.statusId());
        assertEquals("Running perfectly", detail.statusText());
        assertEquals(4_200, detail.progressPermyriad());
        assertEquals(0, detail.maintenanceIssues());
        assertEquals(1_920L, detail.euPerTick());
        assertEquals(1_200_000L, detail.energyStored());
        assertEquals(1_600_000L, detail.energyCapacity());
        assertEquals(120, detail.x());
        assertEquals(-30, detail.z());
        assertEquals(4, detail.side());
        assertEquals(2, detail.sampleAgeSeconds());
    }

    @Test
    void theDetailStillShowsTheLastKnownSnapshotOfAnUnloadedSensor() {
        Sensor unloaded = new Sensor("00000001", ALICE, "unloaded", SensorState.UNLOADED)
            .snapshot(MachineState.RUNNING, 1_920L)
            .seen(T0 - 720L, T0 - 720L);
        HubViewModel model = model();
        model.select(unloaded.id());

        rebuild(model, frame(1L, unloaded));

        assertEquals(
            StateCodes.RUNNING,
            model.detail()
                .stateCode());
        assertEquals(
            1_920L,
            model.detail()
                .euPerTick());
        assertEquals(
            720,
            model.detail()
                .sampleAgeSeconds());
        assertEquals(
            HubCodecs.AVAILABILITY_UNLOADED,
            model.detail()
                .availability());
        assertFalse(
            model.detail()
                .canEdit());
    }

    @Test
    void canEditNeedsRenameRightsAndALiveSensor() {
        Sensor mine = new Sensor("00000001", ALICE, "mine", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        Sensor unloaded = new Sensor("00000002", ALICE, "unloaded", SensorState.UNLOADED);
        Sensor mates = new Sensor("00000003", BOB, "mates", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        teams.team("red", ALICE, BOB);
        HubViewModel model = model();

        model.select(mine.id());
        rebuild(model, frame(1L, mine, unloaded, mates));
        assertTrue(
            model.detail()
                .canEdit());

        model.select(unloaded.id());
        rebuild(model, frame(2L, mine, unloaded, mates));
        assertFalse(
            model.detail()
                .canEdit());

        // Same team, default permissions: a team mate may rename.
        model.select(mates.id());
        rebuild(model, frame(3L, mine, unloaded, mates));
        assertTrue(
            model.detail()
                .canEdit());
    }

    @Test
    void aSensorWithoutARingHasNoHistoryAndEmptyWindows() {
        Sensor tombstone = new Sensor("00000001", ALICE, "gone", SensorState.REMOVED);
        HubViewModel model = model();
        model.select(tombstone.id());

        rebuild(model, frame(1L, tombstone));

        assertFalse(
            model.detail()
                .hasHistory());
        assertFalse(
            model.detail()
                .historyLoaded());
        assertSame(
            HubWindow.EMPTY,
            model.detail()
                .fiveMinutes());
        assertEquals(
            "????????????????????????",
            model.detail()
                .strip());
    }

    @Test
    void historyThatIsStillLoadingIsSurfaced() {
        Sensor live = new Sensor("00000001", ALICE, "live", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        MinuteRing ring = new MinuteRing();
        ring.put(minute(NOW_MINUTE - 1, 60, 0));
        OneRing history = new OneRing(live.id(), ring);
        history.loaded = false;
        HubViewModel model = model();
        model.select(live.id());

        rebuild(model, frame(1L, live), history);

        assertTrue(
            model.detail()
                .hasHistory());
        assertFalse(
            model.detail()
                .historyLoaded());
    }

    // --- summaries and the strip (section 7.5, section 9.2) ---

    @Test
    void theFiveMinuteAndDayWindowsFollowTheReaderContract() {
        Sensor live = new Sensor("00000001", ALICE, "live", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        MinuteRing ring = new MinuteRing();
        // Four of the last five minutes observed: 45 running and 15 idle each; the fifth is missing.
        for (int i = 1; i <= 4; i++) {
            ring.put(minute(NOW_MINUTE - i, 45, 15));
        }
        HubViewModel model = model();
        model.select(live.id());

        rebuild(model, frame(1L, live), new OneRing(live.id(), ring));

        HubWindow five = model.detail()
            .fiveMinutes();
        assertEquals(240L, five.samples());
        assertEquals(5L * EXPECTED_PER_MINUTE, five.expectedSamples());
        assertEquals(4, five.observedMinutes());
        assertEquals(0.75, five.stateFraction(StateCodes.RUNNING), 1e-12);
        assertEquals(0.8, five.coverage(), 1e-12);
        HubWindow day = model.detail()
            .day();
        assertEquals(240L, day.samples());
        assertEquals((long) HubViewModel.DAY_MINUTES * EXPECTED_PER_MINUTE, day.expectedSamples());
        assertEquals(0.75, day.stateFraction(StateCodes.RUNNING), 1e-12);
    }

    @Test
    void aWindowWithNoCoverageHasNoFractionRatherThanZeroPercent() {
        Sensor live = new Sensor("00000001", ALICE, "live", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        HubViewModel model = model();
        model.select(live.id());

        rebuild(model, frame(1L, live), new OneRing(live.id(), new MinuteRing()));

        HubWindow five = model.detail()
            .fiveMinutes();
        assertFalse(five.hasSamples());
        assertEquals(0.0, five.coverage());
        assertTrue(Double.isNaN(five.stateFraction(StateCodes.RUNNING)), "no observation is not zero uptime");
        assertEquals(5L * EXPECTED_PER_MINUTE, five.expectedSamples());
    }

    @Test
    void theHourlyStripMarksUnobservedHoursWithAQuestionMark() {
        Sensor live = new Sensor("00000001", ALICE, "live", SensorState.LIVE).snapshot(MachineState.RUNNING, 0L);
        MinuteRing ring = new MinuteRing();
        // The newest hour of the window is hour 23; fill hour 22 fully with running minutes.
        int hour22Start = NOW_MINUTE - HubViewModel.DAY_MINUTES + 22 * 60;
        for (int i = 0; i < 60; i++) {
            ring.put(minute(hour22Start + i, 60, 0));
        }
        HubViewModel model = model();
        model.select(live.id());

        rebuild(model, frame(1L, live), new OneRing(live.id(), ring));

        String strip = model.detail()
            .strip();
        assertEquals(HubDetail.HOURS, strip.length());
        assertEquals('#', strip.charAt(22));
        assertEquals('?', strip.charAt(21));
        assertEquals('?', strip.charAt(23));
        assertEquals(
            100,
            model.detail()
                .hourly()[22]);
        assertEquals(
            HubDetail.HOUR_GAP,
            model.detail()
                .hourly()[0]);
    }

    @Test
    void theHourlyStripScalesTheRunningFractionOfEachHour() {
        MinuteRing ring = new MinuteRing();
        int base = NOW_MINUTE - HubViewModel.DAY_MINUTES;
        fillHour(ring, base + 20 * 60, 60, 0);
        fillHour(ring, base + 21 * 60, 36, 24);
        fillHour(ring, base + 22 * 60, 18, 42);
        fillHour(ring, base + 23 * 60, 6, 54);

        byte[] hourly = HubViewModel.hourly(ring, NOW_MINUTE, EXPECTED_PER_MINUTE);

        assertEquals(100, hourly[20]);
        assertEquals(60, hourly[21]);
        assertEquals(30, hourly[22]);
        assertEquals(10, hourly[23]);
        assertEquals(
            "#=-.",
            new String(
                new char[] { HubDetail.symbol(hourly[20]), HubDetail.symbol(hourly[21]), HubDetail.symbol(hourly[22]),
                    HubDetail.symbol(hourly[23]) }));
    }

    @Test
    void theDayWindowAndTheStripCoverExactlyTheMinuteRing() {
        assertEquals(MinuteRing.SLOTS, HubViewModel.DAY_MINUTES);
        assertEquals(HubDetail.HOURS * 60, HubViewModel.DAY_MINUTES);
    }

    // --- the header ---

    @Test
    void theHeaderCountsScopeLiveAndShown() {
        Sensor live = new Sensor("00000001", ALICE, "live", SensorState.LIVE).snapshot(MachineState.SHUTDOWN, 0L);
        Sensor unloaded = new Sensor("00000002", ALICE, "unloaded", SensorState.UNLOADED);
        Sensor stranger = new Sensor("00000003", CAROL, "stranger", SensorState.LIVE);
        HubViewModel model = model();

        rebuild(model, frame(1L, live, unloaded, stranger));

        assertEquals(
            2,
            model.header()
                .total());
        assertEquals(
            1,
            model.header()
                .live());
        assertEquals(
            2,
            model.header()
                .shown());
        assertEquals(
            "Alice",
            model.header()
                .ownerName());
        assertEquals(
            ALICE,
            model.header()
                .owner());
        assertFalse(
            model.header()
                .unsupported());
        assertTrue(
            model.header()
                .samplingEnabled());
    }

    @Test
    void theHeaderCarriesTheSamplerFooterAndTheAbandonedCount() {
        SamplerStats stats = new SamplerStats();
        stats.onCycle(410_000L);
        stats.rollWindow();
        stats.onSamplingSkipped(7L);
        TelemetryFrame published = frame(1L, stats);
        HubViewModel model = model();
        model.setSensorsAbandoned(2);

        rebuild(model, published);

        assertEquals(
            published.stats()
                .cycleMicrosP99(),
            model.header()
                .cycleMicrosP99());
        assertTrue(
            model.header()
                .cycleMicrosP99() > 0L,
            "the footer must show the real p99");
        assertEquals(
            7L,
            model.header()
                .samplingSkippedTotal());
        assertEquals(
            2,
            model.header()
                .sensorsAbandoned());
    }

    // --- the rebuild throttle (section 9.3) ---

    @Test
    void nothingIsRebuiltWhenNeitherTheSequenceNorAnInputChanged() {
        HubViewModel model = model();
        TelemetryFrame frame = frame(4L, new Sensor("00000001", ALICE, "one", SensorState.LIVE));

        assertTrue(rebuild(model, frame));
        assertEquals(1, model.rebuilds());
        assertFalse(rebuild(model, frame));
        assertFalse(rebuild(model, frame));
        assertEquals(1, model.rebuilds());
    }

    @Test
    void aNewSequenceOrAChangedInputRebuilds() {
        Sensor one = new Sensor("00000001", ALICE, "one", SensorState.LIVE);
        Sensor two = new Sensor("00000002", ALICE, "two", SensorState.LIVE);
        HubViewModel model = model();
        rebuild(model, frame(1L, one, two));
        assertEquals(1, model.rebuilds());

        assertTrue(rebuild(model, frame(2L, one, two)));
        assertEquals(2, model.rebuilds());

        model.setFilter(HubViewModel.FILTER_PROBLEMS);
        assertTrue(rebuild(model, frame(2L, one, two)));
        assertEquals(3, model.rebuilds());

        model.select(one.id());
        assertTrue(rebuild(model, frame(2L, one, two)));
        assertEquals(4, model.rebuilds());

        model.setSensorsAbandoned(1);
        assertTrue(rebuild(model, frame(2L, one, two)));
        assertEquals(5, model.rebuilds());
    }

    @Test
    void settingAnInputToWhatItAlreadyIsIsNotAChange() {
        HubViewModel model = model();
        TelemetryFrame frame = frame(1L, sensors(20));
        rebuild(model, frame);
        model.setPage(1);
        rebuild(model, frame);
        int before = model.rebuilds();

        model.setPage(1);
        model.setFilter(HubViewModel.FILTER_ALL);
        model.select(null);
        model.setSensorsAbandoned(0);

        assertFalse(rebuild(model, frame));
        assertEquals(before, model.rebuilds());
    }

    @Test
    void gettersReturnTheSameReferenceUntilARebuildReplacesThem() {
        HubViewModel model = model();
        TelemetryFrame frame = frame(1L, new Sensor("00000001", ALICE, "one", SensorState.LIVE));
        rebuild(model, frame);
        HubHeader header = model.header();
        List<HubRow> rows = model.rows();
        HubDetail detail = model.detail();

        rebuild(model, frame);

        assertSame(header, model.header());
        assertSame(rows, model.rows());
        assertSame(detail, model.detail());

        rebuild(model, frame(2L, new Sensor("00000001", ALICE, "one", SensorState.LIVE)));
        assertNotSame(header, model.header());
        assertNotSame(rows, model.rows());
    }

    @Test
    void aRebuildNeedsEveryCollaborator() {
        HubViewModel model = model();
        TelemetryFrame empty = frame(1L);

        assertThrows(
            IllegalArgumentException.class,
            () -> model.rebuild(null, policy, alice, teams, HubViewModel.History.NONE, T0));
        assertThrows(
            IllegalArgumentException.class,
            () -> model.rebuild(empty, null, alice, teams, HubViewModel.History.NONE, T0));
        assertThrows(
            IllegalArgumentException.class,
            () -> model.rebuild(empty, policy, null, teams, HubViewModel.History.NONE, T0));
        assertThrows(
            IllegalArgumentException.class,
            () -> model.rebuild(empty, policy, alice, null, HubViewModel.History.NONE, T0));
        assertThrows(IllegalArgumentException.class, () -> model.rebuild(empty, policy, alice, teams, null, T0));
    }

    @Test
    void theEmptyFrameBuildsAnEmptyViewRatherThanThrowing() {
        HubViewModel model = model();

        assertTrue(model.rebuild(TelemetryFrame.EMPTY, policy, alice, teams, HubViewModel.History.NONE, T0));

        assertEquals(
            0,
            model.header()
                .total());
        assertEquals(
            1,
            model.header()
                .pages());
        assertTrue(
            model.header()
                .samplingEnabled());
        assertSame(HubDetail.NONE, model.detail());
    }

    @Test
    void aDisplayNameLongerThanTheCapIsTruncatedInTheRow() {
        // A label is already capped at 32 code points by Labels.sanitize, so only the fallback
        // "<machine name> #<shortId>" can reach the section 9.3 cap: 32 sanitized units plus ten.
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            name.append("ab");
        }
        Sensor wordy = new Sensor("00000001", ALICE, "", SensorState.LIVE).machine(name.toString());
        HubViewModel model = model();

        rebuild(model, frame(1L, wordy));

        String displayName = model.rows()
            .get(0)
            .displayName();
        assertEquals(HubCodecs.MAX_DISPLAY_NAME, displayName.length());
        assertTrue(displayName.startsWith("abab"), displayName);
    }

    @Test
    void aSensorWithoutALabelOrASnapshotFallsBackToItsMachineNameAndShortId() {
        Sensor bare = new Sensor("00000001", ALICE, "", SensorState.LIVE);
        HubViewModel model = model();

        rebuild(model, frame(1L, bare));

        assertEquals(
            "Electric Blast Furnace #00000001",
            model.rows()
                .get(0)
                .displayName());
    }

    // --- helpers ---

    private static void fillHour(MinuteRing ring, int firstMinute, int running, int idle) {
        for (int i = 0; i < 60; i++) {
            ring.put(minute(firstMinute + i, running, idle));
        }
    }

    private static Sensor[] sensors(int count) {
        Sensor[] out = new Sensor[count];
        for (int i = 0; i < count; i++) {
            out[i] = new Sensor(String.format("%08d", i + 1), ALICE, "sensor " + i, SensorState.LIVE)
                .snapshot(MachineState.RUNNING, 0L);
        }
        return out;
    }

    private static List<UUID> ids(List<HubRow> rows) {
        List<UUID> out = new ArrayList<>();
        for (HubRow row : rows) {
            if (!row.isEmpty()) {
                out.add(row.id());
            }
        }
        return out;
    }
}
