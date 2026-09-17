package io.github.ldogg123.gregscope.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GS108-T5: the global counters of design-v0.2 section 7.6 that nothing else pins - the saturating add every counter
 * shares, and {@code clockSkewRefusedTotal}, which is fed with the per-fold deltas
 * {@link SampleFolder#clockSkewRefusedDelta()} reports rather than summed over the live accumulators. [pure]
 */
class SamplerStatsTest {

    private SamplerStats stats;

    @BeforeEach
    void setUp() {
        stats = new SamplerStats();
    }

    @Test
    void clockSkewRefusalsAccumulateAndSurviveAPurgedSensor() {
        assertEquals(0L, stats.clockSkewRefusedTotal());

        // Sensor A refuses five seconds, then goes away with its accumulator.
        stats.onClockSkewRefused(5L);
        assertEquals(5L, stats.clockSkewRefusedTotal());

        // Sensor B refuses two more. A sum over the live accumulators would report 2 and lose A's five.
        stats.onClockSkewRefused(2L);
        assertEquals(7L, stats.clockSkewRefusedTotal());
    }

    @Test
    void aFoldWithNoRefusalMovesNothing() {
        stats.onClockSkewRefused(3L);
        stats.onClockSkewRefused(0L);
        stats.onClockSkewRefused(-1L);
        assertEquals(3L, stats.clockSkewRefusedTotal(), "only a positive delta counts");
    }

    @Test
    void countersSaturateInsteadOfOverflowing() {
        stats.onClockSkewRefused(Long.MAX_VALUE);
        stats.onClockSkewRefused(1L);
        assertEquals(Long.MAX_VALUE, stats.clockSkewRefusedTotal());

        stats.onSamplingSkipped(Long.MAX_VALUE);
        stats.onSamplingSkipped(10L);
        assertEquals(Long.MAX_VALUE, stats.samplingSkippedTotal());
    }

    @Test
    void theMicrosecondWindowReportsOnlyCompletedWindows() {
        stats.recordCycleNanos(4_000L);
        assertEquals(0L, stats.windowTicks(), "an open window is never reported");
        assertEquals(0L, stats.cycleMicrosMax());

        stats.rollWindow();
        assertEquals(1L, stats.windowTicks());
        assertEquals(4L, stats.cycleMicrosMax(), "nanoseconds are rounded down to whole microseconds");

        stats.rollWindow();
        assertEquals(0L, stats.windowTicks(), "the next window starts empty");
    }
}
