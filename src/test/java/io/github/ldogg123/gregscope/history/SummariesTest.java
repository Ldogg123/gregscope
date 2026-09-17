package io.github.ldogg123.gregscope.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.model.StateCodes;

class SummariesTest {

    private static final int M = 29_318_460;

    private static MinuteSlot.Builder minute(int m, int running, int idle) {
        return MinuteSlot.builder(m)
            .samples(running + idle)
            .expectedSamples(60)
            .lastStateCode(running > 0 ? StateCodes.RUNNING : StateCodes.IDLE)
            .stateSamples(StateCodes.RUNNING, running)
            .stateSamples(StateCodes.IDLE, idle);
    }

    @Test
    void fractionsUseObservedSamplesAndCoverageUsesTheWholeWindow() {
        MinuteRing ring = new MinuteRing();
        ring.put(minute(M, 45, 15).build());
        ring.put(
            minute(M + 1, 30, 0).gapMask(GapReason.CHUNK_UNLOADED.mask())
                .flags(MinuteSlot.FLAG_PARTIAL_MINUTE)
                .build());
        // M+2..M+4 missing
        Summaries.Summary s = Summaries.minutes(ring, M, M + 5, 60);
        assertEquals(90, s.samples());
        assertEquals(300, s.expectedSamples());
        assertEquals(2, s.observedMinutes());
        assertEquals(75 / 90.0, s.stateFraction(StateCodes.RUNNING), 1e-12);
        assertEquals(15 / 90.0, s.stateFraction(StateCodes.IDLE), 1e-12);
        assertEquals(0.0, s.stateFraction(StateCodes.SHUTDOWN));
        assertEquals(0.3, s.coverage(), 1e-12);
        assertEquals(GapReason.CHUNK_UNLOADED.mask(), s.gapMask());
        assertEquals(-1, s.recipesCompleted());
    }

    /**
     * §7.5 rule 1 uses each slot's stored {@code expectedSamples}: a window can span a restart that changed
     * {@code intervalTicks}. The current setting only fills in missing minutes (and a stored 0).
     */
    @Test
    void coverageUsesEachSlotsStoredExpectedSamples() {
        MinuteRing ring = new MinuteRing();
        // Recorded at intervalTicks=20 (60/min), fully observed.
        ring.put(minute(M, 60, 0).build());
        ring.put(minute(M + 1, 50, 10).build());
        // Recorded after a restart at intervalTicks=100 (12/min), fully observed.
        ring.put(
            minute(M + 2, 12, 0).expectedSamples(12)
                .build());
        ring.put(
            minute(M + 3, 0, 12).expectedSamples(12)
                .build());
        // M+4 missing: counted with the current setting (12).
        Summaries.Summary s = Summaries.minutes(ring, M, M + 5, 12);
        assertEquals(144, s.samples());
        assertEquals(60 + 60 + 12 + 12 + 12, s.expectedSamples());
        assertEquals(144 / 156.0, s.coverage(), 1e-12);

        // The reverse change, both halves fully observed and nothing missing: full coverage, not 60 %.
        MinuteRing reverse = new MinuteRing();
        reverse.put(
            minute(M, 12, 0).expectedSamples(12)
                .build());
        reverse.put(minute(M + 1, 60, 0).build());
        Summaries.Summary full = Summaries.minutes(reverse, M, M + 2, 60);
        assertEquals(72, full.expectedSamples());
        assertEquals(1.0, full.coverage(), 1e-12);

        // A stored slot without expectedSamples falls back to the current setting.
        MinuteRing unset = new MinuteRing();
        unset.put(
            minute(M, 30, 0).expectedSamples(0)
                .build());
        assertEquals(
            60,
            Summaries.minutes(unset, M, M + 1, 60)
                .expectedSamples());
    }

    @Test
    void emptyWindowHasNoFractions() {
        Summaries.Summary s = Summaries.minutes(new MinuteRing(), M, M + 5, 60);
        assertFalse(s.hasSamples());
        assertTrue(Double.isNaN(s.stateFraction(StateCodes.RUNNING)));
        assertEquals(0.0, s.coverage());
        assertEquals(MinuteSlot.NONE, s.euPerTickAvg());
        assertEquals(
            0.0,
            Summaries.minutes(new MinuteRing(), M, M, 60)
                .coverage());
        assertThrows(IllegalArgumentException.class, () -> Summaries.minutes(new MinuteRing(), M, M - 1, 60));
    }

    @Test
    void euWeightedByEuSamplesAndRecipesSummed() {
        MinuteRing ring = new MinuteRing();
        ring.put(
            minute(M, 60, 0).euSamples(60)
                .euPerTickAvg(100)
                .euPerTickMin(50)
                .euPerTickMax(150)
                .recipesCompletedDelta(4)
                .maintenanceMax(1)
                .build());
        ring.put(
            minute(M + 1, 20, 0).euSamples(20)
                .euPerTickAvg(-500)
                .euPerTickMin(-600)
                .euPerTickMax(-400)
                .recipesCompletedDelta(0)
                .flags(MinuteSlot.FLAG_RECIPES_COUNTER_RESET)
                .build());
        ring.put(minute(M + 2, 0, 10).build()); // no EU samples: not averaged as 0
        Summaries.Summary s = Summaries.minutes(ring, M, M + 3, 60);
        assertEquals(80, s.euSamples());
        assertEquals(-50, s.euPerTickAvg()); // (6000 - 10000) / 80
        assertEquals(-600, s.euPerTickMin());
        assertEquals(150, s.euPerTickMax());
        assertEquals(4, s.recipesCompleted());
        assertTrue(s.recipesCounterReset());
        assertEquals(1, s.maintenanceMax());
    }

    @Test
    void gapOnlySlotsContributeReasonsButNoSamples() {
        MinuteRing ring = new MinuteRing();
        ring.put(
            MinuteSlot.builder(M)
                .expectedSamples(60)
                .gapMask(GapReason.TARGET_MISSING.mask())
                .build());
        Summaries.Summary s = Summaries.minutes(ring, M, M + 1, 60);
        assertEquals(0, s.observedMinutes());
        assertEquals(GapReason.TARGET_MISSING.mask(), s.gapMask());
        assertFalse(s.hasSamples());
    }
}
