package io.github.ldogg123.gregscope.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.MinuteAccumulator;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.history.SecondRing;
import io.github.ldogg123.gregscope.model.MachineKind;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.MachineState;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistryCore;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-108 (design-v0.2 section 6.2): what one sample, and one gap second, do to a sensor's second ring, open minute,
 * counters and cached machine metadata. Pure: the entries come from a real {@code SensorRegistryCore} driven by a fake
 * clock, and the snapshots are built by hand, so no Minecraft class is touched.
 */
class SampleFolderTest {

    private static final long T0 = 1_700_000_040L;
    private static final int INTERVAL = 20;

    private SampleFolder folder;
    private SensorRegistryCore core;
    private SensorEntry entry;

    /** A resolver with no teams at all: the caps never merge two owners. */
    private static final class NoTeams implements TeamResolver<Object> {

        @Override
        public Object teamOf(UUID player) {
            return null;
        }

        @Override
        public boolean isMember(Object team, UUID player) {
            return false;
        }

        @Override
        public boolean isOfficerOrOwner(Object team, UUID player) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        folder = new SampleFolder();
        core = new SensorRegistryCore(() -> Settings.DEFAULTS, () -> T0 * 1000L, new NoTeams());
        SensorIdentity identity = new SensorIdentity(UUID.randomUUID(), "", null, null, T0);
        core.heartbeat(identity, SensorKind.MACHINE, 0, 1, 64, 2, 1, 100L);
        entry = core.entry(identity.id());
        assertNotNull(entry, "the test entry did not register");
    }

    private static MachineSnapshot.Builder basic() {
        return MachineSnapshot.builder()
            .kind(MachineKind.SINGLEBLOCK)
            .state(MachineState.RUNNING)
            .statusId("running")
            .statusText("Running")
            .name("Test Furnace")
            .metaName("Electric Furnace LV")
            .metaId(3)
            .machineClass("MTEBasicMachine")
            .dimension(0)
            .x(1)
            .y(64)
            .z(2)
            .active(true)
            .allowedToWork(true)
            .hasThingsToDo(true)
            .wasShutdown(false)
            .progressTicks(32)
            .maxProgressTicks(128)
            .progress(0.25)
            .warnings(Collections.<String>emptyList());
    }

    // --- one observed sample ---

    @Test
    void aSampleReachesTheSecondRingTheMinuteAndTheCounters() {
        MachineSnapshot snapshot = basic().euPerTick(64L)
            .energyStored(2048L)
            .build();

        folder.fold(entry, snapshot, T0, INTERVAL, INTERVAL);

        SecondRing seconds = entry.seconds();
        assertEquals(1, seconds.size());
        assertEquals((int) T0, seconds.epochSec(0));
        assertEquals(StateCodes.RUNNING, seconds.stateCode(0));
        assertTrue(seconds.isValid(0), "an observed sample must be valid");
        assertNull(seconds.gapReason(0), "an observed sample has no gap reason");
        assertTrue(seconds.hasFlag(0, SecondRing.FLAG_ACTIVE), "active");
        assertTrue(seconds.hasFlag(0, SecondRing.FLAG_ALLOWED_TO_WORK), "allowedToWork");
        assertFalse(seconds.hasFlag(0, SecondRing.FLAG_WAS_SHUTDOWN), "wasShutdown");
        assertFalse(seconds.hasFlag(0, SecondRing.FLAG_FORMED_KNOWN), "a basic machine has no formed key");
        assertTrue(seconds.hasFlag(0, SecondRing.FLAG_HAS_EU), "hasEu");
        assertTrue(seconds.hasFlag(0, SecondRing.FLAG_HAS_ENERGY), "hasEnergy");
        assertEquals(64L, seconds.euPerTick(0));
        assertEquals(2048L, seconds.energyStored(0));
        assertEquals(2500, seconds.progress(0), "progress x10000");

        assertEquals(
            1L,
            entry.counters()
                .samplesTotal());
        assertEquals(
            1L,
            entry.counters()
                .stateSamplesTotal(StateCodes.RUNNING));
        // 64 EU/t over 20 ticks is the section 7.6 estimate.
        assertEquals(
            64L * INTERVAL,
            entry.counters()
                .euConsumedSampledTotal());
        assertEquals(
            0L,
            entry.counters()
                .euGeneratedSampledTotal());

        assertTrue(
            entry.accumulator()
                .isOpen(),
            "the minute must be open");
        assertNull(folder.closedMinute(), "no minute rolls over on the first sample");
        assertEquals(SensorEntry.NO_GAP, entry.lastGapReason());
        assertEquals(T0, entry.lastSampleEpochSec());
        assertEquals(T0, entry.lastSeenEpochSec());
        assertEquals(snapshot, entry.lastSnapshot());
    }

    @Test
    void theFirstSampleSetsTheMachineMetadataAndTheSecondDoesNot() {
        folder.fold(entry, basic().build(), T0, INTERVAL, 0);
        assertTrue(folder.metadataChanged(), "the first sample must make the registry dirty");
        assertEquals(3, entry.metaId());
        assertEquals("Electric Furnace LV", entry.metaName());
        assertEquals("Test Furnace", entry.machineName());
        assertEquals("running", entry.lastStatusId());

        folder.fold(entry, basic().build(), T0 + 1, INTERVAL, INTERVAL);
        assertFalse(folder.metadataChanged(), "unchanged metadata must not make the registry dirty");

        folder.fold(
            entry,
            basic().state(MachineState.IDLE)
                .statusId("idle")
                .build(),
            T0 + 2,
            INTERVAL,
            INTERVAL);
        assertTrue(folder.metadataChanged(), "a new status id changes the metadata");
        assertEquals("idle", entry.lastStatusId());
    }

    @Test
    void absentEuAndEnergyAreNotRecordedAsZero() {
        folder.fold(entry, basic().build(), T0, INTERVAL, 0);

        assertFalse(
            entry.seconds()
                .hasFlag(0, SecondRing.FLAG_HAS_EU),
            "no EU key means no EU flag");
        assertFalse(
            entry.seconds()
                .hasFlag(0, SecondRing.FLAG_HAS_ENERGY),
            "no energy key means no energy flag");
        assertEquals(
            0L,
            entry.counters()
                .euConsumedSampledTotal(),
            "an absent EU/t is not counted as zero consumption");

        MinuteSlot closed = closeMinute();
        assertEquals(0, closed.euSamples(), "a minute without EU samples");
        assertEquals(MinuteSlot.NONE, closed.euPerTickAvg());
    }

    @Test
    void aGeneratorCountsAsGeneratedEu() {
        folder.fold(
            entry,
            basic().euPerTick(-512L)
                .build(),
            T0,
            INTERVAL,
            0);

        assertEquals(
            0L,
            entry.counters()
                .euConsumedSampledTotal());
        assertEquals(
            512L * INTERVAL,
            entry.counters()
                .euGeneratedSampledTotal());
        MinuteSlot closed = closeMinute();
        assertEquals(-512L, closed.euPerTickMin());
        assertEquals(-512L, closed.euPerTickMax());
    }

    @Test
    void aMultiblockRecordsFormedMaintenanceAndRecipes() {
        MachineSnapshot.Builder multi = basic().kind(MachineKind.MULTIBLOCK)
            .formed(true)
            .maintenanceIssues(2)
            .recipesCompleted(100L);

        folder.fold(entry, multi.build(), T0, INTERVAL, 0);
        assertTrue(
            entry.seconds()
                .hasFlag(0, SecondRing.FLAG_FORMED_KNOWN),
            "formedKnown");
        assertTrue(
            entry.seconds()
                .hasFlag(0, SecondRing.FLAG_FORMED),
            "formed");
        assertEquals(
            2,
            entry.seconds()
                .maintenanceIssues(0));

        folder.fold(
            entry,
            multi.recipesCompleted(103L)
                .build(),
            T0 + 1,
            INTERVAL,
            INTERVAL);
        MinuteSlot closed = closeMinute();
        assertEquals(3, closed.recipesCompletedDelta(), "the first sample only sets the baseline");
        assertEquals(2, closed.maintenanceMax());
    }

    @Test
    void aRecipeCounterResetIsFlaggedAndCounted() {
        MachineSnapshot.Builder multi = basic().kind(MachineKind.MULTIBLOCK)
            .formed(true)
            .recipesCompleted(100L);
        folder.fold(entry, multi.build(), T0, INTERVAL, 0);
        folder.fold(
            entry,
            multi.recipesCompleted(4L)
                .build(),
            T0 + 1,
            INTERVAL,
            INTERVAL);

        assertEquals(
            1L,
            entry.counters()
                .recipesCounterResetsTotal(),
            "GT's counter going backwards is a reset");
        assertTrue(closeMinute().hasFlag(MinuteSlot.FLAG_RECIPES_COUNTER_RESET));
    }

    @Test
    void aSampleInTheNextMinuteClosesTheOpenOne() {
        folder.fold(entry, basic().build(), T0, INTERVAL, 0);
        assertNull(folder.closedMinute());

        folder.fold(entry, basic().build(), T0 + 60, INTERVAL, INTERVAL);

        MinuteSlot closed = folder.closedMinute();
        assertNotNull(closed, "the minute must close when the next one starts");
        assertEquals((int) (T0 / 60), closed.epochMinute());
        assertEquals(1, closed.samples());
        assertEquals(60, closed.expectedSamples(), "1200 / 20");
        assertEquals(0, closed.gapMask(), "an observed minute has no gap reasons");
        assertFalse(closed.hasFlag(MinuteSlot.FLAG_PARTIAL_MINUTE));
    }

    @Test
    void serverTicksAreAddedToTheOpenMinute() {
        folder.fold(entry, basic().build(), T0, INTERVAL, 0);
        folder.fold(entry, basic().build(), T0 + 1, INTERVAL, INTERVAL);
        folder.fold(entry, basic().build(), T0 + 2, INTERVAL, INTERVAL);

        // The delta is added before the sample is folded, so the ticks since the previous sample belong to the
        // minute that was open: 0 + 20 + 20 for the three samples, plus the 20 of the sample that closes the minute.
        assertEquals(3 * INTERVAL, closeMinute().serverTicks(), "server ticks that elapsed while the minute was open");
    }

    // --- gap seconds ---

    @Test
    void aSkipRecordsAGapSecondAndTheMinuteMask() {
        folder.fold(entry, basic().build(), T0, INTERVAL, 0);
        folder.gap(entry, GapReason.SAMPLING_SKIPPED, T0 + 1);

        SecondRing seconds = entry.seconds();
        assertEquals(2, seconds.size());
        assertFalse(seconds.isValid(1), "a gap second is not an observation");
        assertEquals(StateCodes.UNAVAILABLE, seconds.stateCode(1));
        assertEquals(GapReason.SAMPLING_SKIPPED, seconds.gapReason(1));
        assertEquals(
            1L,
            entry.counters()
                .gapSecondsTotal(GapReason.SAMPLING_SKIPPED));
        assertEquals(
            1L,
            entry.counters()
                .samplesTotal(),
            "a gap is not an observed sample");
        assertEquals(GapReason.SAMPLING_SKIPPED.bit(), entry.lastGapReason());

        MinuteSlot closed = closeMinute();
        assertEquals(GapReason.SAMPLING_SKIPPED.mask(), closed.gapMask());
        assertEquals(1, closed.samples(), "the observed sample still counts");
    }

    @Test
    void aGapLeavesTheLastSnapshotAndMetadataAlone() {
        MachineSnapshot snapshot = basic().build();
        folder.fold(entry, snapshot, T0, INTERVAL, 0);

        folder.gap(entry, GapReason.PROBE_ERROR, T0 + 1);

        assertEquals(snapshot, entry.lastSnapshot(), "a reader still sees the last known machine");
        assertEquals("running", entry.lastStatusId());
        assertEquals(T0, entry.lastSampleEpochSec(), "a gap is not a sample");
        assertFalse(folder.metadataChanged());
    }

    @Test
    void aDerivedGapReasonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> folder.gap(entry, GapReason.SERVER_OFFLINE, T0));
        assertThrows(IllegalArgumentException.class, () -> folder.gap(entry, GapReason.UNKNOWN, T0));
        assertThrows(IllegalArgumentException.class, () -> folder.gap(entry, null, T0));
    }

    /**
     * GS108-T5: a sample whose minute is older than the newest written one is refused (design-v0.2 section 6.2 clock
     * skew), and the fold reports that as a delta, so the section 7.6 counter survives the entry being freed.
     */
    @Test
    void aSampleRefusedForClockSkewIsReportedAsADelta() {
        entry.accumulator()
            .noteWritten(MinuteAccumulator.epochMinute(T0));

        folder.fold(entry, basic().build(), T0 - 120, INTERVAL, 0);

        assertEquals(1L, folder.clockSkewRefusedDelta(), "the refusal must be reported once");
        assertEquals(
            1L,
            entry.accumulator()
                .clockSkewRefused());
        assertNull(folder.closedMinute(), "a refused sample closes nothing");

        folder.fold(entry, basic().build(), T0, INTERVAL, 0);
        assertEquals(0L, folder.clockSkewRefusedDelta(), "an accepted sample reports no refusal");
        assertEquals(
            1L,
            entry.accumulator()
                .clockSkewRefused(),
            "the accumulator's own total does not move either");
    }

    @Test
    void aGapRefusedForClockSkewIsReportedAsADelta() {
        entry.accumulator()
            .noteWritten(MinuteAccumulator.epochMinute(T0));

        folder.gap(entry, GapReason.SAMPLING_SKIPPED, T0 - 120);

        assertEquals(1L, folder.clockSkewRefusedDelta());

        folder.gap(entry, GapReason.SAMPLING_SKIPPED, T0);
        assertEquals(0L, folder.clockSkewRefusedDelta());
    }

    /** Closes the open minute by folding a sample a minute later, and returns the closed slot. */
    private MinuteSlot closeMinute() {
        folder.fold(entry, basic().build(), T0 + 60, INTERVAL, INTERVAL);
        MinuteSlot closed = folder.closedMinute();
        assertNotNull(closed, "the minute did not close");
        return closed;
    }
}
