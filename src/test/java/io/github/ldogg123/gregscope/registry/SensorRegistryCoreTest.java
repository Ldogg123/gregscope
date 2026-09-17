package io.github.ldogg123.gregscope.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.sampling.Clock;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-107 (design-v0.2 section 4, ticket section 14): one test per row of the section 4.3 transition table, plus the
 * caps of section 4.2, the OVER_CAP retry window, tombstone expiry and eviction, and the design-v0.3 section 5.1 A1
 * rules for the {@code (position, side)} reverse index.
 *
 * <p>
 * The clock is fake, so expiry is exercised by moving time rather than waiting, and the UUID supplier is a queue, so a
 * duplicate re-key produces a UUID the test named.
 */
class SensorRegistryCoreTest {

    private static final long T0 = 1_700_000_000L;
    private static final int DIM = 0;
    private static final int SIDE_NORTH = 2;
    private static final int SIDE_SOUTH = 3;

    private FakeClock clock;
    private Settings[] settings;
    private FakeTeams teams;
    private Deque<UUID> nextIds;
    private RecordingEvents events;
    private CountingMap<UUID, SensorEntry> byId;
    private CountingMap<PosKey, UUID> byPosition;
    private SensorRegistryCore core;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(T0);
        settings = new Settings[] { Settings.DEFAULTS };
        teams = new FakeTeams();
        nextIds = new ArrayDeque<>();
        events = new RecordingEvents();
        byId = new CountingMap<>();
        byPosition = new CountingMap<>();
        core = new SensorRegistryCore(
            supplier(),
            clock,
            teams,
            this::nextId,
            SensorKind::isSupported,
            byId,
            byPosition);
        core.setEvents(events);
    }

    private Supplier<Settings> supplier() {
        return () -> settings[0];
    }

    private UUID nextId() {
        UUID queued = nextIds.poll();
        return queued != null ? queued : UUID.randomUUID();
    }

    private void withSettings(Settings replacement) {
        settings[0] = replacement;
    }

    // --- unknown -> LIVE, and the caps ---

    @Test
    void unknownHeartbeatRegistersLiveAndAllocatesRings() {
        SensorIdentity sensor = identity();
        assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(sensor, 1, 2, 3, SIDE_NORTH, 100L));

        SensorEntry entry = core.entry(sensor.id());
        assertNotNull(entry, "no entry after the first heartbeat");
        assertEquals(SensorState.LIVE, entry.state());
        assertEquals(SensorKind.MACHINE, entry.kind());
        assertEquals(RemovalCause.NONE, entry.removalCause());
        assertEquals(T0, entry.stateSinceEpochSec());
        assertEquals(100L, entry.lastHeartbeatTick());
        assertNotNull(entry.seconds(), "second ring");
        assertNotNull(entry.minutes(), "minute ring");
        assertNotNull(entry.accumulator(), "minute accumulator");
        assertNotNull(entry.counters(), "counters");
        assertEquals(sensor.id(), core.idAt(DIM, 1, 2, 3, SIDE_NORTH), "reverse index");
        assertEquals(1, core.countedSensors());
        assertTrue(core.dirty(), "registering a sensor must make the registry dirty");
        assertEquals(1, events.live.size(), "sensorLive events");
        assertSame(entry, events.live.get(0));
    }

    @Test
    void globalCapRefusesTheSeventeenthSensorAndCounts() {
        withSettings(
            Settings.builder()
                .maxSensors(16)
                .build());
        List<SensorIdentity> sensors = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            SensorIdentity sensor = identity();
            sensors.add(sensor);
            assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(sensor, i, 0, 0, SIDE_NORTH, 100L), "#" + i);
        }
        SensorIdentity refused = identity();
        assertEquals(SensorRegistryCore.Heartbeat.OVER_CAP, beat(refused, 99, 0, 0, SIDE_NORTH, 100L));
        assertNull(core.entry(refused.id()), "a refused sensor must get no entry");
        assertEquals(16, core.countedSensors(), "the cap must never be exceeded");
        assertEquals(1L, core.quotaRefusedTotal());
        assertEquals(sensors.size(), events.live.size());
    }

    @Test
    void overCapIsRetriedOnlyEvery1200Ticks() {
        withSettings(
            Settings.builder()
                .maxSensors(16)
                .build());
        for (int i = 0; i < 16; i++) {
            beat(identity(), i, 0, 0, SIDE_NORTH, 100L);
        }
        SensorIdentity refused = identity();
        assertEquals(SensorRegistryCore.Heartbeat.OVER_CAP, beat(refused, 99, 0, 0, SIDE_NORTH, 100L));
        assertEquals(1L, core.quotaRefusedTotal());

        // Room appears, but the retry window has not passed: still refused, and the caps were not even consulted.
        core.purge(
            core.entries()
                .iterator()
                .next()
                .id());
        assertEquals(SensorRegistryCore.Heartbeat.OVER_CAP, beat(refused, 99, 0, 0, SIDE_NORTH, 100L + 1199L));
        assertEquals(1L, core.quotaRefusedTotal(), "a throttled retry must not count as a refusal");
        assertEquals(15, core.countedSensors());

        assertEquals(
            SensorRegistryCore.Heartbeat.REGISTERED,
            beat(refused, 99, 0, 0, SIDE_NORTH, 100L + SensorRegistryCore.OVER_CAP_RETRY_TICKS));
        assertEquals(16, core.countedSensors());
    }

    @Test
    void perTeamCapCountsTeammatesAndSurvivesAMerge() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        FakeTeams.Team red = teams.team();
        red.member(alice);
        withSettings(
            Settings.builder()
                .maxSensorsPerTeam(2)
                .build());

        assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(ownedBy(alice), 0, 0, 0, SIDE_NORTH, 1L));
        assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(ownedBy(alice), 1, 0, 0, SIDE_NORTH, 1L));
        // Alice is full; Bob is in no team, so he has his own quota.
        assertEquals(SensorRegistryCore.Heartbeat.OVER_CAP, beat(ownedBy(alice), 2, 0, 0, SIDE_NORTH, 1L));
        assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(ownedBy(bob), 3, 0, 0, SIDE_NORTH, 1L));

        // A merge: Bob joins Alice's team. His sensor is kept, but the team is over its cap, so Carol's is refused
        // once she joins too.
        red.member(bob);
        red.member(carol);
        assertEquals(3, core.countedSensors(), "existing sensors are never removed by a merge");
        assertEquals(SensorRegistryCore.Heartbeat.OVER_CAP, beat(ownedBy(carol), 4, 0, 0, SIDE_NORTH, 1L));
        assertEquals(3, core.countedSensors());
    }

    @Test
    void unownedSensorsShareOnePseudoOwner() {
        withSettings(
            Settings.builder()
                .maxSensorsPerTeam(1)
                .build());
        assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(ownedBy(null), 0, 0, 0, SIDE_NORTH, 1L));
        assertEquals(SensorRegistryCore.Heartbeat.OVER_CAP, beat(ownedBy(null), 1, 0, 0, SIDE_NORTH, 1L));
        // An owned sensor is not counted against the unowned pseudo-owner.
        assertEquals(
            SensorRegistryCore.Heartbeat.REGISTERED,
            beat(ownedBy(UUID.randomUUID()), 2, 0, 0, SIDE_NORTH, 1L));
    }

    @Test
    void perTeamCapZeroMeansUnlimited() {
        withSettings(
            Settings.builder()
                .maxSensorsPerTeam(0)
                .build());
        UUID alice = UUID.randomUUID();
        for (int i = 0; i < 70; i++) {
            assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(ownedBy(alice), i, 0, 0, SIDE_NORTH, 1L));
        }
        assertEquals(70, core.countedSensors());
    }

    // --- LIVE fast path ---

    @Test
    void liveHeartbeatAtTheSamePlaceIsOneLookupAndNothingElse() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        SensorEntry entry = core.entry(sensor.id());
        long stateSince = entry.stateSinceEpochSec();

        byId.reads = 0;
        byPosition.reads = 0;
        byPosition.writes = 0;
        clock.reads = 0;
        teams.calls = 0;
        events.clear();

        assertEquals(SensorRegistryCore.Heartbeat.LIVE, beat(sensor, 1, 2, 3, SIDE_NORTH, 140L));

        assertEquals(1, byId.reads, "the fast path must do exactly one id lookup");
        assertEquals(0, byPosition.reads, "the fast path must not touch the reverse index");
        assertEquals(0, byPosition.writes, "the fast path must not write the reverse index");
        assertEquals(0, clock.reads, "the fast path must not read the clock");
        assertEquals(0, teams.calls, "the fast path must not ask about teams");
        assertTrue(events.isEmpty(), "the fast path must raise no events: " + events);
        assertEquals(140L, entry.lastHeartbeatTick());
        assertEquals(stateSince, entry.stateSinceEpochSec(), "the fast path must not restate the entry");
        assertSame(entry, core.entry(sensor.id()), "the fast path must not replace the entry");
    }

    // --- duplicates (design-v0.2 section 4.3, design-v0.3 A1) ---

    @Test
    void duplicateOfALiveSensorRekeysTheNewcomer() {
        SensorIdentity sensor = labelled("labelled");
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        UUID fresh = UUID.randomUUID();
        nextIds.add(fresh);

        assertEquals(SensorRegistryCore.Heartbeat.REKEYED, beat(sensor, 9, 2, 3, SIDE_NORTH, 100L));

        assertEquals(
            fresh,
            core.rekeyedIdentity()
                .id());
        assertEquals(
            "labelled",
            core.rekeyedIdentity()
                .label(),
            "a re-key keeps everything but the UUID");
        assertEquals(1L, core.duplicatesRekeyedTotal());
        assertEquals(
            SensorState.LIVE,
            core.entry(sensor.id())
                .state(),
            "the original must keep its history");
        assertEquals(
            1,
            core.entry(sensor.id())
                .x(),
            "the original must not move");
        assertEquals(
            SensorState.LIVE,
            core.entry(fresh)
                .state());
        assertEquals(fresh, core.idAt(DIM, 9, 2, 3, SIDE_NORTH));
        assertEquals(2, core.countedSensors());
    }

    @Test
    void duplicateOfAnUnloadedSensorRekeysTheNewcomer() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        core.unloaded(sensor.id(), DIM, 1, 2, 3, SIDE_NORTH, GapReason.CHUNK_UNLOADED);
        assertEquals(
            SensorState.UNLOADED,
            core.entry(sensor.id())
                .state());
        UUID fresh = UUID.randomUUID();
        nextIds.add(fresh);

        assertEquals(SensorRegistryCore.Heartbeat.REKEYED, beat(sensor, 9, 2, 3, SIDE_NORTH, 100L));
        assertEquals(
            SensorState.UNLOADED,
            core.entry(sensor.id())
                .state(),
            "the original must stay unloaded");
        assertEquals(
            SensorState.LIVE,
            core.entry(fresh)
                .state());
        assertEquals(1L, core.duplicatesRekeyedTotal());
    }

    /** Design-v0.3 section 5.1 A1: a known UUID heartbeating with another kind is a duplicate. */
    @Test
    void kindMismatchRekeys() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        UUID fresh = UUID.randomUUID();
        nextIds.add(fresh);

        // A kind this build does not register is refused before anything else, so the mismatch is tried with a kind
        // that is registered but different from the entry's; the entry is forced to kind 1 for the check.
        SensorEntry entry = core.entry(sensor.id());
        assertEquals(SensorKind.MACHINE, entry.kind());
        assertEquals(
            SensorRegistryCore.Heartbeat.UNSUPPORTED_KIND,
            core.heartbeat(sensor, SensorKind.ITEM_FLOW, DIM, 1, 2, 3, SIDE_NORTH, 100L),
            "a v0.2 build registers no flow meters");

        SensorRegistryCore flowAware = flowAwareCore();
        SensorIdentity meter = identity();
        assertEquals(
            SensorRegistryCore.Heartbeat.REGISTERED,
            flowAware.heartbeat(meter, SensorKind.MACHINE, DIM, 1, 2, 3, SIDE_NORTH, 100L));
        assertEquals(
            SensorRegistryCore.Heartbeat.REKEYED,
            flowAware.heartbeat(meter, SensorKind.ITEM_FLOW, DIM, 1, 2, 3, SIDE_SOUTH, 100L),
            "the same UUID with another kind must be re-keyed");
        assertEquals(1L, flowAware.duplicatesRekeyedTotal());
        assertEquals(
            SensorKind.MACHINE,
            flowAware.entry(meter.id())
                .kind(),
            "the original keeps its kind");
        assertEquals(
            SensorKind.ITEM_FLOW,
            flowAware.entry(
                flowAware.rekeyedIdentity()
                    .id())
                .kind());
    }

    @Test
    void unsupportedKindIsNeverRegisteredOrCounted() {
        SensorIdentity meter = identity();
        assertEquals(
            SensorRegistryCore.Heartbeat.UNSUPPORTED_KIND,
            core.heartbeat(meter, SensorKind.FLUID_FLOW, DIM, 1, 2, 3, SIDE_NORTH, 100L));
        assertNull(core.entry(meter.id()));
        assertEquals(0, core.countedSensors());
        assertEquals(0, core.size());
    }

    // --- replacement (design-v0.3 A1) ---

    @Test
    void anotherUuidAtTheSamePositionAndSideReplacesTheOriginal() {
        SensorIdentity first = identity();
        SensorIdentity second = identity();
        beat(first, 1, 2, 3, SIDE_NORTH, 100L);
        beat(second, 1, 2, 3, SIDE_NORTH, 100L);

        SensorEntry replaced = core.entry(first.id());
        assertEquals(SensorState.REMOVED, replaced.state());
        assertEquals(RemovalCause.REPLACED, replaced.removalCause());
        assertNull(replaced.seconds(), "a tombstone keeps no rings");
        assertNull(replaced.minutes(), "a tombstone keeps no rings");
        assertEquals(second.id(), core.idAt(DIM, 1, 2, 3, SIDE_NORTH));
        assertEquals(1, core.countedSensors());
    }

    /** Design-v0.3 section 5.1 A1: REPLACED fires only for the same (pos, side)... */
    @Test
    void sameBlockDifferentSideNotReplacedForDifferentKinds() {
        SensorRegistryCore flowAware = flowAwareCore();
        SensorIdentity machine = identity();
        SensorIdentity meter = identity();
        assertEquals(
            SensorRegistryCore.Heartbeat.REGISTERED,
            flowAware.heartbeat(machine, SensorKind.MACHINE, DIM, 1, 2, 3, SIDE_NORTH, 100L));
        assertEquals(
            SensorRegistryCore.Heartbeat.REGISTERED,
            flowAware.heartbeat(meter, SensorKind.ITEM_FLOW, DIM, 1, 2, 3, SIDE_SOUTH, 100L));

        assertEquals(
            SensorState.LIVE,
            flowAware.entry(machine.id())
                .state(),
            "a meter on another face must not replace the machine sensor");
        assertEquals(
            SensorState.LIVE,
            flowAware.entry(meter.id())
                .state());
        assertEquals(2, flowAware.countedSensors());
    }

    /** ...and for kind 0 only, the slow path also scans all six sides of the block. */
    @Test
    void anotherMachineSensorOnAnotherFaceOfTheSameBlockIsReplaced() {
        SensorIdentity first = identity();
        SensorIdentity second = identity();
        beat(first, 1, 2, 3, SIDE_NORTH, 100L);
        beat(second, 1, 2, 3, SIDE_SOUTH, 100L);

        assertEquals(
            SensorState.REMOVED,
            core.entry(first.id())
                .state(),
            "only one Machine Sensor may exist per machine");
        assertEquals(
            RemovalCause.REPLACED,
            core.entry(first.id())
                .removalCause());
        assertNull(core.idAt(DIM, 1, 2, 3, SIDE_NORTH));
        assertEquals(second.id(), core.idAt(DIM, 1, 2, 3, SIDE_SOUTH));
    }

    // --- unload, resume, move ---

    @Test
    void unloadClosesTheOpenMinuteKeepsTheMinuteRingAndFreesTheSecondRing() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        SensorEntry entry = core.entry(sensor.id());
        entry.accumulator()
            .gap(T0, GapReason.SAMPLING_SKIPPED);
        entry.setBucket(4);

        assertTrue(core.unloaded(sensor.id(), DIM, 1, 2, 3, SIDE_NORTH, GapReason.CHUNK_UNLOADED));

        assertEquals(SensorState.UNLOADED, entry.state());
        assertNull(entry.seconds(), "the second ring is dropped while unloaded");
        assertNotNull(entry.minutes(), "the minute ring survives an unload");
        assertEquals(-1, entry.bucket(), "an unloaded sensor is not sampled");
        assertEquals(1, events.minutes.size(), "the open minute must be closed and queued");
        MinuteSlot closed = events.minutes.get(0);
        assertTrue((closed.gapMask() & GapReason.CHUNK_UNLOADED.mask()) != 0, "gap reason: " + closed);
        assertTrue(closed.hasFlag(MinuteSlot.FLAG_PARTIAL_MINUTE), "partial minute flag");
        assertEquals(1, events.inactive.size());
        assertEquals(1, core.countedSensors(), "an unloaded sensor still counts towards the cap");
    }

    @Test
    void unloadIsIgnoredForAnotherPosition() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        assertFalse(core.unloaded(sensor.id(), DIM, 9, 2, 3, SIDE_NORTH, GapReason.CHUNK_UNLOADED));
        assertEquals(
            SensorState.LIVE,
            core.entry(sensor.id())
                .state());
    }

    @Test
    void unloadedHeartbeatAtTheSamePositionBecomesLiveAgain() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        core.unloaded(sensor.id(), DIM, 1, 2, 3, SIDE_NORTH, GapReason.CHUNK_UNLOADED);
        SensorEntry entry = core.entry(sensor.id());
        clock.advanceSeconds(120);
        events.clear();

        assertEquals(SensorRegistryCore.Heartbeat.RESUMED, beat(sensor, 1, 2, 3, SIDE_NORTH, 200L));
        assertEquals(SensorState.LIVE, entry.state());
        assertNotNull(entry.seconds(), "a live sensor gets a second ring again");
        assertEquals(T0 + 120L, entry.stateSinceEpochSec());
        assertEquals(1, events.live.size());
    }

    @Test
    void movesFromEveryTombstoneResumeWithTheSameHistory() {
        for (RemovalCause cause : new RemovalCause[] { RemovalCause.DETACHED, RemovalCause.IN_ITEM,
            RemovalCause.REPLACED }) {
            setUp();
            SensorIdentity sensor = identity();
            beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
            core.removed(sensor.id(), DIM, 1, 2, 3, SIDE_NORTH, cause);
            SensorEntry entry = core.entry(sensor.id());
            assertTrue(
                entry.state()
                    .isTombstone(),
                cause + ": not a tombstone");
            assertEquals(0, core.countedSensors(), cause + ": a tombstone must not count");

            assertEquals(SensorRegistryCore.Heartbeat.RESUMED, beat(sensor, 7, 8, 9, SIDE_SOUTH, 200L), "" + cause);
            assertSame(entry, core.entry(sensor.id()), cause + ": the entry must be the same object");
            assertEquals(SensorState.LIVE, entry.state());
            assertEquals(RemovalCause.NONE, entry.removalCause());
            assertEquals(7, entry.x(), "" + cause);
            assertEquals(SIDE_SOUTH, entry.side(), "" + cause);
            assertEquals(sensor.id(), core.idAt(DIM, 7, 8, 9, SIDE_SOUTH), "" + cause);
            assertNull(core.idAt(DIM, 1, 2, 3, SIDE_NORTH), cause + ": stale reverse-index entry");
        }
    }

    @Test
    void missingTombstoneAlsoResumes() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        for (int i = 0; i < SensorEntry.MAX_STRIKES; i++) {
            core.strike(sensor.id());
        }
        assertEquals(
            SensorState.MISSING,
            core.entry(sensor.id())
                .state());
        assertEquals(SensorRegistryCore.Heartbeat.RESUMED, beat(sensor, 1, 2, 3, SIDE_NORTH, 200L));
        assertEquals(
            SensorState.LIVE,
            core.entry(sensor.id())
                .state());
    }

    @Test
    void aResumingTombstoneIsRefusedWhenTheCapIsFull() {
        withSettings(
            Settings.builder()
                .maxSensors(16)
                .build());
        SensorIdentity sensor = identity();
        beat(sensor, 0, 0, 0, SIDE_NORTH, 100L);
        core.removed(sensor.id(), DIM, 0, 0, 0, SIDE_NORTH, RemovalCause.DETACHED);
        for (int i = 1; i <= 16; i++) {
            assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(identity(), i, 0, 0, SIDE_NORTH, 100L));
        }
        assertEquals(SensorRegistryCore.Heartbeat.OVER_CAP, beat(sensor, 0, 0, 0, SIDE_NORTH, 100L));
        assertEquals(
            SensorState.REMOVED,
            core.entry(sensor.id())
                .state());
        assertEquals(16, core.countedSensors());
    }

    // --- strikes ---

    @Test
    void threeStrikesInARowBecomeMissing() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        SensorEntry entry = core.entry(sensor.id());

        assertFalse(core.strike(sensor.id()), "one strike must not be enough");
        assertEquals(1, entry.strikes());
        assertEquals(SensorState.LIVE, entry.state());
        assertFalse(core.strike(sensor.id()), "two strikes must not be enough");
        assertEquals(SensorState.LIVE, entry.state());
        assertTrue(core.strike(sensor.id()), "the third strike must make it MISSING");

        assertEquals(SensorState.MISSING, entry.state());
        assertEquals(RemovalCause.TARGET_MISSING, entry.removalCause());
        assertNull(entry.seconds());
        assertEquals(0, core.countedSensors());
        assertEquals(1, events.inactive.size());
    }

    @Test
    void aSuccessfulValidationResetsTheStrikes() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        core.strike(sensor.id());
        core.strike(sensor.id());
        core.validated(sensor.id());
        assertEquals(
            0,
            core.entry(sensor.id())
                .strikes());
        assertFalse(core.strike(sensor.id()), "the count must have restarted");
        assertEquals(
            SensorState.LIVE,
            core.entry(sensor.id())
                .state());
    }

    @Test
    void aStrikeRecordsATargetMissingSecondAndGap() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        SensorEntry entry = core.entry(sensor.id());
        core.strike(sensor.id());

        assertEquals(
            1,
            entry.seconds()
                .size());
        assertEquals(
            GapReason.TARGET_MISSING,
            entry.seconds()
                .gapReason(0));
        assertEquals(
            1L,
            entry.counters()
                .gapSecondsTotal(GapReason.TARGET_MISSING));
    }

    // --- removal ---

    @Test
    void detachAndDestroyProduceTheRightTombstones() {
        SensorIdentity detached = identity();
        beat(detached, 1, 2, 3, SIDE_NORTH, 100L);
        core.entry(detached.id())
            .accumulator()
            .gap(T0, GapReason.SAMPLING_SKIPPED);
        assertTrue(core.removed(detached.id(), DIM, 1, 2, 3, SIDE_NORTH, RemovalCause.DETACHED));
        SensorEntry entry = core.entry(detached.id());
        assertEquals(SensorState.REMOVED, entry.state());
        assertEquals(RemovalCause.DETACHED, entry.removalCause());
        assertNull(entry.minutes(), "a tombstone frees its RAM rings");
        assertEquals(1, events.minutes.size(), "the partial minute must be queued before the rings go");
        assertTrue(
            (events.minutes.get(0)
                .gapMask() & GapReason.SENSOR_REMOVED.mask()) != 0);

        SensorIdentity broken = identity();
        beat(broken, 4, 5, 6, SIDE_NORTH, 100L);
        assertTrue(core.removed(broken.id(), DIM, 4, 5, 6, SIDE_NORTH, RemovalCause.IN_ITEM));
        assertEquals(
            SensorState.IN_ITEM,
            core.entry(broken.id())
                .state());
        assertNull(core.idAt(DIM, 4, 5, 6, SIDE_NORTH), "a tombstone leaves the reverse index");
    }

    @Test
    void removalIsIgnoredForAnEntryThatIsNotThere() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        assertFalse(
            core.removed(sensor.id(), DIM, 9, 9, 9, SIDE_NORTH, RemovalCause.DETACHED),
            "a stale cover elsewhere must not remove the entry");
        assertEquals(
            SensorState.LIVE,
            core.entry(sensor.id())
                .state());
        assertFalse(core.removed(UUID.randomUUID(), DIM, 1, 2, 3, SIDE_NORTH, RemovalCause.DETACHED));
    }

    // --- expiry, eviction, purge ---

    @Test
    void tombstonesExpireAfterTheRetentionWindow() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        core.removed(sensor.id(), DIM, 1, 2, 3, SIDE_NORTH, RemovalCause.DETACHED);

        clock.advanceSeconds(24 * 3600L);
        assertEquals(0, core.housekeeping(), "exactly at the window the tombstone is kept");
        assertNotNull(core.entry(sensor.id()));

        clock.advanceSeconds(1);
        assertEquals(1, core.housekeeping());
        assertNull(core.entry(sensor.id()));
        assertEquals(1, events.expired.size());
        assertEquals(sensor.id(), events.expired.get(0));
    }

    @Test
    void unloadedSensorsExpireAfterTheStaleWindow() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        core.unloaded(sensor.id(), DIM, 1, 2, 3, SIDE_NORTH, GapReason.CHUNK_UNLOADED);

        clock.advanceSeconds(30 * 86_400L);
        assertEquals(0, core.housekeeping());
        clock.advanceSeconds(1);
        assertEquals(1, core.housekeeping());
        assertNull(core.entry(sensor.id()));
        assertNull(core.idAt(DIM, 1, 2, 3, SIDE_NORTH));
    }

    @Test
    void liveSensorsNeverExpire() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        clock.advanceSeconds(400 * 86_400L);
        assertEquals(0, core.housekeeping());
        assertEquals(
            SensorState.LIVE,
            core.entry(sensor.id())
                .state());
    }

    @Test
    void theOldestTombstoneIsEvictedFirstBeyondTheCap() {
        withSettings(
            Settings.builder()
                .maxSensors(16)
                .build());
        List<UUID> order = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            SensorIdentity sensor = identity();
            order.add(sensor.id());
            beat(sensor, i, 0, 0, SIDE_NORTH, 100L);
            core.removed(sensor.id(), DIM, i, 0, 0, SIDE_NORTH, RemovalCause.DETACHED);
            clock.advanceSeconds(1);
        }
        assertEquals(17, core.tombstones());

        assertEquals(1, core.housekeeping());
        assertEquals(16, core.tombstones());
        assertNull(core.entry(order.get(0)), "the oldest tombstone must go first");
        assertNotNull(core.entry(order.get(16)));
    }

    @Test
    void purgeExpiresImmediatelyAndTheSensorRegistersAgainWithEmptyHistory() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        SensorEntry first = core.entry(sensor.id());
        first.accumulator()
            .gap(T0, GapReason.SAMPLING_SKIPPED);

        assertTrue(core.purge(sensor.id()));
        assertNull(core.entry(sensor.id()));
        assertEquals(1, events.expired.size());
        assertEquals(1, events.inactive.size(), "a purged LIVE sensor leaves its sampler bucket");

        assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(sensor, 1, 2, 3, SIDE_NORTH, 200L));
        SensorEntry second = core.entry(sensor.id());
        assertNotSame(first, second, "a purged sensor must get a new entry object");
        assertEquals(sensor.id(), second.id(), "the UUID comes from the cover, so it is the same");
        assertFalse(
            second.accumulator()
                .isOpen(),
            "a purged sensor starts with empty history");
    }

    @Test
    void purgeAllEmptiesTheRegistryAndForgetsRefusals() {
        withSettings(
            Settings.builder()
                .maxSensors(16)
                .build());
        for (int i = 0; i < 16; i++) {
            beat(identity(), i, 0, 0, SIDE_NORTH, 100L);
        }
        SensorIdentity refused = identity();
        assertEquals(SensorRegistryCore.Heartbeat.OVER_CAP, beat(refused, 99, 0, 0, SIDE_NORTH, 100L));

        assertEquals(16, core.purgeAll());
        assertEquals(0, core.size());
        assertEquals(0, core.countedSensors());
        assertEquals(
            SensorRegistryCore.Heartbeat.REGISTERED,
            beat(refused, 99, 0, 0, SIDE_NORTH, 100L),
            "purgeAll must also clear the refusal window");
    }

    @Test
    void anEmptyRegistryRebuildsItselfFromHeartbeats() {
        assertEquals(0, core.size());
        assertEquals(0, core.housekeeping());
        List<SensorIdentity> sensors = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            SensorIdentity sensor = identity();
            sensors.add(sensor);
            assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(sensor, i, 0, 0, SIDE_NORTH, 100L));
        }
        Set<UUID> seen = new HashSet<>();
        for (SensorEntry entry : core.entries()) {
            seen.add(entry.id());
            assertEquals(SensorState.LIVE, entry.state());
        }
        assertEquals(5, seen.size());
        for (SensorIdentity sensor : sensors) {
            assertTrue(seen.contains(sensor.id()));
        }
    }

    @Test
    void theReverseIndexKeyPacksPositionAndSideLosslessly() {
        PosKey key = new PosKey(-1, -29_999_999, 200, 29_999_999, 5);
        assertEquals(-1, key.dim());
        assertEquals(-29_999_999, key.x());
        assertEquals(200, key.y());
        assertEquals(29_999_999, key.z());
        assertEquals(5, key.side());
        assertEquals(key.packed() >>> 3, key.packedPosition());
        assertEquals(key, new PosKey(-1, -29_999_999, 200, 29_999_999, 5));
        assertEquals(key.hashCode(), new PosKey(-1, -29_999_999, 200, 29_999_999, 5).hashCode());
        assertNotEquals(key, new PosKey(0, -29_999_999, 200, 29_999_999, 5));
        assertNotEquals(key, new PosKey(-1, -29_999_999, 200, 29_999_999, 4));
    }

    @Test
    void persistedStateAndCauseCodesArePinned() {
        assertEquals(-1, SensorState.LIVE.persistedCode(), "LIVE is never persisted");
        assertEquals(-1, SensorState.OVER_CAP.persistedCode(), "OVER_CAP is never persisted");
        assertEquals(0, SensorState.UNLOADED.persistedCode());
        assertEquals(1, SensorState.MISSING.persistedCode());
        assertEquals(2, SensorState.IN_ITEM.persistedCode());
        assertEquals(3, SensorState.REMOVED.persistedCode());
        assertSame(SensorState.UNLOADED, SensorState.fromPersistedCode(0));
        assertSame(SensorState.REMOVED, SensorState.fromPersistedCode(3));
        assertNull(SensorState.fromPersistedCode(-1));
        assertNull(SensorState.fromPersistedCode(9));
        assertEquals(0, RemovalCause.NONE.code());
        assertEquals(1, RemovalCause.DETACHED.code());
        assertEquals(2, RemovalCause.IN_ITEM.code());
        assertEquals(3, RemovalCause.REPLACED.code());
        assertEquals(4, RemovalCause.TARGET_MISSING.code());
        assertEquals(5, RemovalCause.PURGED.code());
        assertSame(RemovalCause.NONE, RemovalCause.fromCode(99));
    }

    // --- review follow-ups ---

    /**
     * GS-REV-2: a duplicate whose fresh identity a cap then refuses must not report a successful re-key. The NBT
     * rewrite still happens (the caller keeps the fresh identity, otherwise the collision would repeat for ever), but
     * no entry exists and the caller has to run its OVER_CAP branch.
     */
    @Test
    void aRekeyRefusedByTheCapReportsItAndCreatesNothing() {
        UUID owner = UUID.randomUUID();
        withSettings(
            Settings.builder()
                .maxSensorsPerTeam(1)
                .build());
        SensorIdentity sensor = ownedBy(owner);
        assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, beat(sensor, 1, 2, 3, SIDE_NORTH, 100L));
        UUID fresh = UUID.randomUUID();
        nextIds.add(fresh);

        SensorRegistryCore.Heartbeat result = beat(sensor, 9, 2, 3, SIDE_NORTH, 100L);

        assertEquals(SensorRegistryCore.Heartbeat.REKEYED_OVER_CAP, result);
        assertTrue(result.rekeyed(), "the cover must still store the fresh identity");
        assertEquals(
            fresh,
            core.rekeyedIdentity()
                .id());
        assertNull(core.entry(fresh), "a refused newcomer gets no entry");
        assertEquals(1L, core.duplicatesRekeyedTotal(), "the re-key itself happened");
        assertEquals(1L, core.quotaRefusedTotal());
        assertEquals(1, core.countedSensors(), "the cap must not be exceeded");
        assertEquals(
            SensorState.LIVE,
            core.entry(sensor.id())
                .state(),
            "the original keeps its history");
        assertEquals(sensor.id(), core.idAt(DIM, 1, 2, 3, SIDE_NORTH));
        assertNull(core.idAt(DIM, 9, 2, 3, SIDE_NORTH), "nothing is indexed for the refused sensor");
        assertEquals(SensorState.OVER_CAP.label(), result.availability());
    }

    /**
     * GS-107-05: the availability word follows from the heartbeat outcome alone, so the adapter needs no second
     * lookup of the entry the heartbeat just resolved.
     */
    @Test
    void everyHeartbeatOutcomeCarriesItsAvailabilityWord() {
        assertEquals(SensorState.LIVE.label(), SensorRegistryCore.Heartbeat.LIVE.availability());
        assertEquals(SensorState.LIVE.label(), SensorRegistryCore.Heartbeat.REGISTERED.availability());
        assertEquals(SensorState.LIVE.label(), SensorRegistryCore.Heartbeat.RESUMED.availability());
        assertEquals(SensorState.LIVE.label(), SensorRegistryCore.Heartbeat.REKEYED.availability());
        assertEquals(SensorState.OVER_CAP.label(), SensorRegistryCore.Heartbeat.OVER_CAP.availability());
        assertEquals(SensorState.OVER_CAP.label(), SensorRegistryCore.Heartbeat.REKEYED_OVER_CAP.availability());
        assertNull(SensorRegistryCore.Heartbeat.UNSUPPORTED_KIND.availability());
        for (SensorRegistryCore.Heartbeat outcome : SensorRegistryCore.Heartbeat.values()) {
            assertEquals(
                outcome == SensorRegistryCore.Heartbeat.REKEYED
                    || outcome == SensorRegistryCore.Heartbeat.REKEYED_OVER_CAP,
                outcome.rekeyed(),
                "rekeyed() for " + outcome);
        }
    }

    /**
     * GS-107-02: the third strike is an exit from LIVE like any other, so the open minute is closed and queued before
     * the rings are freed. Without it the machine's last minute - up to 59 s of folded history - is dropped.
     */
    @Test
    void theThirdStrikeQueuesThePartialMinuteBeforeFreeingTheRings() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        SensorEntry entry = core.entry(sensor.id());
        entry.accumulator()
            .sample(T0, 1, 0, true, 32L, true, 1000L, -1L);

        core.strike(sensor.id());
        core.strike(sensor.id());
        assertTrue(events.minutes.isEmpty(), "nothing is closed before the third strike");
        assertTrue(core.strike(sensor.id()), "the third strike must make it MISSING");

        assertEquals(SensorState.MISSING, entry.state());
        assertNull(entry.minutes(), "a tombstone frees its RAM rings");
        assertEquals(1, events.minutes.size(), "the partial minute must be queued before the rings go");
        MinuteSlot closed = events.minutes.get(0);
        assertTrue(closed.hasFlag(MinuteSlot.FLAG_PARTIAL_MINUTE), "partial minute flag: " + closed);
        assertTrue((closed.gapMask() & GapReason.TARGET_MISSING.mask()) != 0, "gap reason: " + closed);
        assertEquals(GapReason.TARGET_MISSING.bit(), entry.lastGapReason());
    }

    /**
     * GS-107-03: an unload is the registry seeing the sensor, so it refreshes {@code lastSeen}. Otherwise only a
     * successful sample does, and with {@code sampling.enabled=false} the stale window would be measured from
     * registration - a sensor that heartbeated for a month would expire on its first unload.
     */
    @Test
    void anUnloadRefreshesLastSeenSoTheStaleWindowStartsThere() {
        SensorIdentity sensor = identity();
        beat(sensor, 1, 2, 3, SIDE_NORTH, 100L);
        SensorEntry entry = core.entry(sensor.id());
        assertEquals(T0, entry.lastSeenEpochSec());

        // A month of heartbeats with nothing sampling: the fast path stores no clock time at all.
        clock.advanceSeconds(30 * 86_400L);
        beat(sensor, 1, 2, 3, SIDE_NORTH, 200L);
        assertEquals(T0, entry.lastSeenEpochSec(), "the fast path must stay clock-free");

        core.unloaded(sensor.id(), DIM, 1, 2, 3, SIDE_NORTH, GapReason.CHUNK_UNLOADED);

        assertEquals(T0 + 30 * 86_400L, entry.lastSeenEpochSec(), "the unload is the registry seeing the sensor");
        assertEquals(0, core.housekeeping(), "a sensor seen a moment ago must not be stale");
        assertNotNull(core.entry(sensor.id()));
        clock.advanceSeconds(30 * 86_400L + 1);
        assertEquals(1, core.housekeeping(), "the window is measured from the unload");
        assertNull(core.entry(sensor.id()));
    }

    // --- helpers ---

    private SensorRegistryCore.Heartbeat beat(SensorIdentity sensor, int x, int y, int z, int side, long tick) {
        return core.heartbeat(sensor, SensorKind.MACHINE, DIM, x, y, z, side, tick);
    }

    /** A core that registers kind 1 too, standing in for the v0.3 build the A1 rules are written for. */
    private SensorRegistryCore flowAwareCore() {
        IntPredicate machineOrItemFlow = kind -> kind == SensorKind.MACHINE || kind == SensorKind.ITEM_FLOW;
        SensorRegistryCore other = new SensorRegistryCore(
            supplier(),
            clock,
            teams,
            this::nextId,
            machineOrItemFlow,
            new HashMap<>(),
            new HashMap<>());
        other.setEvents(new RecordingEvents());
        return other;
    }

    private SensorIdentity identity() {
        return ownedBy(UUID.randomUUID());
    }

    private SensorIdentity ownedBy(UUID owner) {
        return new SensorIdentity(UUID.randomUUID(), "", owner, owner == null ? null : "owner", T0);
    }

    private SensorIdentity labelled(String label) {
        return new SensorIdentity(UUID.randomUUID(), label, UUID.randomUUID(), "owner", T0);
    }

    /** A clock a test can move, which also counts how often the core read it. */
    private static final class FakeClock implements Clock {

        private long epochMillis;
        int reads;

        FakeClock(long epochSec) {
            this.epochMillis = epochSec * 1000L;
        }

        void advanceSeconds(long seconds) {
            epochMillis += seconds * 1000L;
        }

        @Override
        public long epochMillis() {
            reads++;
            return epochMillis;
        }
    }

    /** In-memory teams, plus a call counter so the fast path can be shown not to ask. */
    private static final class FakeTeams implements TeamResolver<FakeTeams.Team> {

        static final class Team {

            final Set<UUID> members = new HashSet<>();

            Team member(UUID player) {
                members.add(player);
                return this;
            }
        }

        private final List<Team> teams = new ArrayList<>();
        int calls;

        Team team() {
            Team team = new Team();
            teams.add(team);
            return team;
        }

        @Override
        public Team teamOf(UUID player) {
            calls++;
            if (player == null) {
                return null;
            }
            for (Team team : teams) {
                if (team.members.contains(player)) {
                    return team;
                }
            }
            return null;
        }

        @Override
        public boolean isMember(Team team, UUID player) {
            calls++;
            return team != null && player != null && team.members.contains(player);
        }

        @Override
        public boolean isOfficerOrOwner(Team team, UUID player) {
            return isMember(team, player);
        }
    }

    /** A HashMap that counts reads and writes, for the fast-path assertions of design-v0.2 section 14. */
    private static final class CountingMap<K, V> extends HashMap<K, V> {

        private static final long serialVersionUID = 1L;

        int reads;
        int writes;

        @Override
        public V get(Object key) {
            reads++;
            return super.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            reads++;
            return super.containsKey(key);
        }

        @Override
        public V put(K key, V value) {
            writes++;
            return super.put(key, value);
        }

        @Override
        public V remove(Object key) {
            writes++;
            return super.remove(key);
        }
    }

    /** Records what the core handed to the sampler and the I/O thread. */
    private static final class RecordingEvents implements RegistryEvents {

        final List<SensorEntry> live = new ArrayList<>();
        final List<SensorEntry> inactive = new ArrayList<>();
        final List<MinuteSlot> minutes = new ArrayList<>();
        final List<UUID> expired = new ArrayList<>();

        void clear() {
            live.clear();
            inactive.clear();
            minutes.clear();
            expired.clear();
        }

        boolean isEmpty() {
            return live.isEmpty() && inactive.isEmpty() && minutes.isEmpty() && expired.isEmpty();
        }

        @Override
        public void sensorLive(SensorEntry entry) {
            live.add(entry);
        }

        @Override
        public void sensorInactive(SensorEntry entry) {
            inactive.add(entry);
        }

        @Override
        public void minuteClosed(SensorEntry entry, MinuteSlot slot) {
            minutes.add(slot);
        }

        @Override
        public void sensorExpired(UUID id, int kind) {
            expired.add(id);
        }

        @Override
        public String toString() {
            return "live " + live.size()
                + ", inactive "
                + inactive.size()
                + ", minutes "
                + minutes.size()
                + ", expired "
                + expired.size();
        }
    }
}
