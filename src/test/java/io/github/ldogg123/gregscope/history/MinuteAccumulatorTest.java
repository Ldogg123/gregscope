package io.github.ldogg123.gregscope.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.model.StateCodes;

class MinuteAccumulatorTest {

    /** Start of a minute, in epoch seconds. */
    private static final int M = 29_318_460;
    private static final long S = M * 60L;
    private static final long NOT_MULTI = -1;

    private static MinuteAccumulator acc() {
        return new MinuteAccumulator(20, 1);
    }

    private static MinuteSlot sample(MinuteAccumulator a, long sec, int state) {
        return a.sample(sec, state, 0, false, 0, false, 0, NOT_MULTI);
    }

    private static MinuteSlot eu(MinuteAccumulator a, long sec, long euPerTick) {
        return a.sample(sec, StateCodes.RUNNING, 0, true, euPerTick, false, 0, NOT_MULTI);
    }

    @Test
    void expectedSamplesFromInterval() {
        assertEquals(60, MinuteSlot.expectedSamples(20));
        assertEquals(30, MinuteSlot.expectedSamples(40));
        assertEquals(20, MinuteSlot.expectedSamples(60));
        assertEquals(12, MinuteSlot.expectedSamples(100));
        assertThrows(IllegalArgumentException.class, () -> MinuteSlot.expectedSamples(0));
        assertThrows(IllegalArgumentException.class, () -> MinuteSlot.expectedSamples(7));
        assertThrows(IllegalArgumentException.class, () -> MinuteSlot.expectedSamples(1));
        MinuteAccumulator slow = new MinuteAccumulator(100, 1);
        sample(slow, S, StateCodes.RUNNING);
        assertEquals(
            12,
            slow.closePartial(null)
                .expectedSamples());
        assertThrows(IllegalArgumentException.class, () -> new MinuteAccumulator(7, 1));
    }

    @Test
    void fullMinuteClosesWhenTheNextMinuteArrives() {
        MinuteAccumulator a = acc();
        for (int i = 0; i < 60; i++) {
            assertNull(sample(a, S + i, i < 45 ? StateCodes.RUNNING : StateCodes.IDLE));
        }
        assertTrue(a.isOpen());
        assertEquals(M, a.openEpochMinute());
        MinuteSlot slot = sample(a, S + 60, StateCodes.RUNNING);
        assertNotNull(slot);
        assertEquals(M, slot.epochMinute());
        assertEquals(60, slot.samples());
        assertEquals(60, slot.expectedSamples());
        assertEquals(45, slot.stateSamples(StateCodes.RUNNING));
        assertEquals(15, slot.stateSamples(StateCodes.IDLE));
        assertEquals(StateCodes.IDLE, slot.lastStateCode());
        assertEquals(0, slot.gapMask());
        assertFalse(slot.hasFlag(MinuteSlot.FLAG_PARTIAL_MINUTE));
        assertEquals(1.0, slot.coverage());
        assertEquals(MinuteSlot.NONE, slot.euPerTickAvg());
        assertEquals(MinuteSlot.NONE, slot.euPerTickMin());
        assertEquals(MinuteSlot.NONE, slot.euPerTickMax());
        assertEquals(MinuteSlot.NONE, slot.energyStoredLast());
        assertEquals(MinuteSlot.RECIPES_NONE, slot.recipesCompletedDelta());
        assertEquals(M + 1, a.openEpochMinute());
        assertEquals(M, a.newestWrittenEpochMinute());
    }

    @Test
    void stateSamplesSaturateAt255() {
        MinuteAccumulator a = acc();
        for (int i = 0; i < 300; i++) {
            sample(a, S + (i % 60), i < 280 ? StateCodes.RUNNING : StateCodes.WAITING);
        }
        MinuteSlot slot = a.closeBefore(M + 1);
        assertEquals(255, slot.samples());
        assertEquals(255, slot.stateSamples(StateCodes.RUNNING));
        assertEquals(20, slot.stateSamples(StateCodes.WAITING));
        assertEquals(1.0, slot.coverage(), "coverage is capped at 1");
    }

    @Test
    void minAvgMaxWithNegativeEu() {
        MinuteAccumulator a = acc();
        eu(a, S, -2000);
        eu(a, S + 1, 1000);
        eu(a, S + 2, -1);
        MinuteSlot slot = a.closeBefore(M + 1);
        assertEquals(3, slot.euSamples());
        assertEquals(-2000, slot.euPerTickMin());
        assertEquals(1000, slot.euPerTickMax());
        // (-2000 + 1000 - 1) / 3 = -333.67, rounded half away from zero
        assertEquals(-334, slot.euPerTickAvg());
    }

    @Test
    void avgRoundsHalfAwayFromZero() {
        MinuteAccumulator a = acc();
        eu(a, S, 1);
        eu(a, S + 1, 2);
        assertEquals(
            2,
            a.closeBefore(M + 1)
                .euPerTickAvg());
        eu(a, S + 60, -1);
        eu(a, S + 61, -2);
        assertEquals(
            -2,
            a.closeBefore(M + 2)
                .euPerTickAvg());
    }

    @Test
    void absentEuIsNotAveragedAsZero() {
        MinuteAccumulator a = acc();
        eu(a, S, 900);
        sample(a, S + 1, StateCodes.IDLE); // no EU value
        eu(a, S + 2, 1100);
        MinuteSlot slot = a.closeBefore(M + 1);
        assertEquals(3, slot.samples());
        assertEquals(2, slot.euSamples());
        assertEquals(1000, slot.euPerTickAvg());
        assertEquals(900, slot.euPerTickMin());
        assertEquals(1100, slot.euPerTickMax());
    }

    @Test
    void hugeEuDoesNotOverflowTheAverage() {
        MinuteAccumulator a = acc();
        eu(a, S, Long.MAX_VALUE);
        eu(a, S + 1, Long.MAX_VALUE);
        eu(a, S + 2, Long.MAX_VALUE - 2);
        MinuteSlot slot = a.closeBefore(M + 1);
        assertEquals(Long.MAX_VALUE - 1, slot.euPerTickAvg());

        eu(a, S + 60, Long.MIN_VALUE);
        MinuteSlot neg = a.closeBefore(M + 2);
        assertEquals(Long.MIN_VALUE + 1, neg.euPerTickAvg(), "MIN_VALUE is the 'none' sentinel and is never stored");
        assertEquals(Long.MIN_VALUE + 1, neg.euPerTickMin());
    }

    @Test
    void energyStoredLastIsTheLastPresentValue() {
        MinuteAccumulator a = acc();
        a.sample(S, StateCodes.RUNNING, 0, false, 0, true, 500, NOT_MULTI);
        a.sample(S + 1, StateCodes.RUNNING, 0, false, 0, true, 700, NOT_MULTI);
        a.sample(S + 2, StateCodes.RUNNING, 0, false, 0, false, 0, NOT_MULTI);
        assertEquals(
            700,
            a.closeBefore(M + 1)
                .energyStoredLast());
    }

    @Test
    void maintenanceMaxSaturates() {
        MinuteAccumulator a = acc();
        a.sample(S, StateCodes.RUNNING, 3, false, 0, false, 0, NOT_MULTI);
        a.sample(S + 1, StateCodes.RUNNING, 1, false, 0, false, 0, NOT_MULTI);
        assertEquals(
            3,
            a.closeBefore(M + 1)
                .maintenanceMax());
        a.sample(S + 60, StateCodes.RUNNING, 1000, false, 0, false, 0, NOT_MULTI);
        assertEquals(
            255,
            a.closeBefore(M + 2)
                .maintenanceMax());
    }

    @Test
    void recipesDeltaAcrossMinutesAndResetFlag() {
        MinuteAccumulator a = acc();
        // First sample only sets the baseline.
        a.sample(S, StateCodes.RUNNING, 0, false, 0, false, 0, 100);
        a.sample(S + 1, StateCodes.RUNNING, 0, false, 0, false, 0, 104);
        assertFalse(a.lastSampleResetRecipes());
        MinuteSlot first = a.sample(S + 60, StateCodes.RUNNING, 0, false, 0, false, 0, 110);
        assertEquals(4, first.recipesCompletedDelta());
        assertFalse(first.hasFlag(MinuteSlot.FLAG_RECIPES_COUNTER_RESET));

        // The step into the second minute (104 -> 110) belongs to the second minute; then GT's counter decreases.
        a.sample(S + 61, StateCodes.RUNNING, 0, false, 0, false, 0, 3);
        assertTrue(a.lastSampleResetRecipes());
        a.sample(S + 62, StateCodes.RUNNING, 0, false, 0, false, 0, 5);
        assertFalse(a.lastSampleResetRecipes());
        MinuteSlot second = a.closeBefore(M + 2);
        assertEquals(6 + 0 + 2, second.recipesCompletedDelta());
        assertTrue(second.hasFlag(MinuteSlot.FLAG_RECIPES_COUNTER_RESET));

        // A multiblock minute with no progress has delta 0, not -1.
        a.sample(S + 120, StateCodes.IDLE, 0, false, 0, false, 0, 5);
        assertEquals(
            0,
            a.closeBefore(M + 3)
                .recipesCompletedDelta());
    }

    @Test
    void serverTicksCountedWhileOpenAndSaturate() {
        MinuteAccumulator a = acc();
        a.addServerTicks(20); // nothing open: ignored
        sample(a, S, StateCodes.RUNNING);
        a.addServerTicks(20);
        a.addServerTicks(1160);
        assertEquals(
            1180,
            a.closeBefore(M + 1)
                .serverTicks());
        sample(a, S + 60, StateCodes.RUNNING);
        for (int i = 0; i < 70; i++) {
            a.addServerTicks(1000);
        }
        assertEquals(
            65535,
            a.closeBefore(M + 2)
                .serverTicks());
    }

    @Test
    void partialMinuteWithReason() {
        MinuteAccumulator a = acc();
        for (int i = 0; i < 20; i++) {
            sample(a, S + i, StateCodes.RUNNING);
        }
        MinuteSlot slot = a.closePartial(GapReason.CHUNK_UNLOADED);
        assertEquals(20, slot.samples());
        assertTrue(slot.hasFlag(MinuteSlot.FLAG_PARTIAL_MINUTE));
        assertEquals(GapReason.CHUNK_UNLOADED.mask(), slot.gapMask());
        assertFalse(a.isOpen());
        assertNull(a.closePartial(GapReason.SENSOR_REMOVED), "nothing open");
        assertThrows(IllegalArgumentException.class, () -> a.closePartial(GapReason.UNKNOWN));
        assertEquals(1.0 / 3, slot.coverage(), 1e-9);
    }

    @Test
    void gapsSetMaskOnlyAndCanOpenAMinute() {
        MinuteAccumulator a = acc();
        assertNull(a.gap(S + 5, GapReason.TARGET_MISSING));
        a.gap(S + 6, GapReason.SAMPLING_SKIPPED);
        a.gap(S + 7, GapReason.TARGET_MISSING);
        MinuteSlot slot = a.closeBefore(M + 1);
        assertEquals(0, slot.samples());
        assertEquals(GapReason.TARGET_MISSING.mask() | GapReason.SAMPLING_SKIPPED.mask(), slot.gapMask());
        assertTrue(slot.isGap());
        assertThrows(IllegalArgumentException.class, () -> a.gap(S, GapReason.SERVER_OFFLINE));
    }

    @Test
    void serverStartMinuteFlag() {
        MinuteAccumulator a = new MinuteAccumulator(20, M);
        sample(a, S + 30, StateCodes.STARTING);
        MinuteSlot first = sample(a, S + 60, StateCodes.RUNNING);
        assertTrue(first.hasFlag(MinuteSlot.FLAG_SERVER_START_MINUTE));
        assertFalse(
            a.closeBefore(M + 2)
                .hasFlag(MinuteSlot.FLAG_SERVER_START_MINUTE));
    }

    @Test
    void olderMinuteFoldsIntoTheOpenMinute() {
        MinuteAccumulator a = acc();
        sample(a, S + 65, StateCodes.RUNNING); // opens M+1
        assertNull(sample(a, S + 10, StateCodes.IDLE), "clock went back into M: folded, nothing closed");
        MinuteSlot slot = a.closeBefore(M + 2);
        assertEquals(M + 1, slot.epochMinute());
        assertEquals(2, slot.samples());
        assertEquals(0, a.clockSkewRefused());
    }

    @Test
    void minuteOlderThanNewestWrittenIsRefused() {
        MinuteAccumulator a = acc();
        sample(a, S + 120, StateCodes.RUNNING);
        a.closePartial(GapReason.CHUNK_UNLOADED); // M+2 written
        assertNull(sample(a, S + 10, StateCodes.RUNNING));
        assertFalse(a.isOpen());
        assertEquals(1, a.clockSkewRefused());
        a.gap(S + 11, GapReason.TARGET_MISSING);
        assertEquals(2, a.clockSkewRefused());

        // The same minute may reopen (merged by MinuteRing.put).
        sample(a, S + 130, StateCodes.RUNNING);
        assertTrue(a.isOpen());
        assertEquals(M + 2, a.openEpochMinute());

        MinuteAccumulator loaded = acc();
        loaded.noteWritten(M + 5);
        loaded.noteWritten(M + 3); // never lowers
        assertEquals(M + 5, loaded.newestWrittenEpochMinute());
        assertNull(sample(loaded, S + 4 * 60, StateCodes.RUNNING));
        assertEquals(1, loaded.clockSkewRefused());
    }

    @Test
    void closeBeforeOnlyClosesOlderMinutes() {
        MinuteAccumulator a = acc();
        assertNull(a.closeBefore(M + 1));
        sample(a, S, StateCodes.RUNNING);
        assertNull(a.closeBefore(M));
        assertNotNull(a.closeBefore(M + 1));
        assertNull(a.closeBefore(M + 1));
    }

    @Test
    void mergeRuleForAReopenedMinute() {
        MinuteSlot older = MinuteSlot.builder(M)
            .samples(200)
            .expectedSamples(60)
            .gapMask(GapReason.CHUNK_UNLOADED.mask())
            .lastStateCode(StateCodes.RUNNING)
            .stateSamples(StateCodes.RUNNING, 200)
            .maintenanceMax(2)
            .flags(MinuteSlot.FLAG_PARTIAL_MINUTE)
            .serverTicks(60000)
            .euSamples(2)
            .euPerTickAvg(100)
            .euPerTickMin(90)
            .euPerTickMax(110)
            .energyStoredLast(5000)
            .recipesCompletedDelta(3)
            .build();
        MinuteSlot newer = MinuteSlot.builder(M)
            .samples(100)
            .expectedSamples(60)
            .gapMask(GapReason.PROBE_ERROR.mask())
            .lastStateCode(StateCodes.IDLE)
            .stateSamples(StateCodes.RUNNING, 90)
            .stateSamples(StateCodes.IDLE, 10)
            .maintenanceMax(1)
            .flags(MinuteSlot.FLAG_RECIPES_COUNTER_RESET)
            .serverTicks(6000)
            .euSamples(1)
            .euPerTickAvg(-200)
            .euPerTickMin(-200)
            .euPerTickMax(-200)
            .energyStoredLast(4000)
            .recipesCompletedDelta(4)
            .build();
        MinuteSlot merged = MinuteSlot.merge(older, newer);
        assertEquals(255, merged.samples(), "saturating");
        assertEquals(255, merged.stateSamples(StateCodes.RUNNING));
        assertEquals(10, merged.stateSamples(StateCodes.IDLE));
        assertEquals(GapReason.CHUNK_UNLOADED.mask() | GapReason.PROBE_ERROR.mask(), merged.gapMask());
        assertEquals(MinuteSlot.FLAG_PARTIAL_MINUTE | MinuteSlot.FLAG_RECIPES_COUNTER_RESET, merged.flags());
        assertEquals(65535, merged.serverTicks());
        assertEquals(3, merged.euSamples());
        assertEquals(0, merged.euPerTickAvg(), "(100*2 + -200*1) / 3");
        assertEquals(-200, merged.euPerTickMin());
        assertEquals(110, merged.euPerTickMax());
        assertEquals(StateCodes.IDLE, merged.lastStateCode(), "last from the newer slot");
        assertEquals(4000, merged.energyStoredLast(), "energy from the newer slot");
        assertEquals(7, merged.recipesCompletedDelta());
        assertEquals(2, merged.maintenanceMax());
        assertEquals(60, merged.expectedSamples());

        // Newer piece without samples, EU or energy keeps the older values; -1 delta keeps the other side.
        MinuteSlot gapOnly = MinuteSlot.builder(M)
            .expectedSamples(60)
            .gapMask(GapReason.SENSOR_REMOVED.mask())
            .build();
        MinuteSlot kept = MinuteSlot.merge(older, gapOnly);
        assertEquals(StateCodes.RUNNING, kept.lastStateCode());
        assertEquals(5000, kept.energyStoredLast());
        assertEquals(100, kept.euPerTickAvg());
        assertEquals(90, kept.euPerTickMin());
        assertEquals(3, kept.recipesCompletedDelta());
        assertEquals(
            MinuteSlot.RECIPES_NONE,
            MinuteSlot.merge(gapOnly, gapOnly)
                .recipesCompletedDelta());

        assertThrows(
            IllegalArgumentException.class,
            () -> MinuteSlot.merge(
                older,
                MinuteSlot.builder(M + 1)
                    .build()));
    }

    @Test
    void accumulatorPiecesMergeInTheRing() {
        MinuteRing ring = new MinuteRing();
        MinuteAccumulator a = acc();
        for (int i = 0; i < 20; i++) {
            eu(a, S + i, 100);
        }
        ring.put(a.closePartial(GapReason.CHUNK_UNLOADED));
        for (int i = 40; i < 60; i++) {
            eu(a, S + i, 400);
        }
        MinuteSlot stored = ring.put(a.closeBefore(M + 1));
        assertEquals(40, stored.samples());
        assertEquals(250, stored.euPerTickAvg());
        assertEquals(GapReason.CHUNK_UNLOADED.mask(), stored.gapMask());
        assertTrue(stored.hasFlag(MinuteSlot.FLAG_PARTIAL_MINUTE));
        assertEquals(stored, ring.slot(M));
    }

    @Test
    void rejectsUnknownStateCodes() {
        MinuteAccumulator a = acc();
        assertThrows(IllegalArgumentException.class, () -> sample(a, S, 10));
        assertThrows(IllegalArgumentException.class, () -> sample(a, S, -1));
    }
}
