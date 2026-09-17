package io.github.ldogg123.gregscope.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.model.StateCodes;

/** {@link LogHistogram}, {@link SensorCounters}, {@link Clock} and {@link FakeClock}. */
class SamplingPrimitivesTest {

    @Test
    void histogramBucketsAreMonotonicAndBounded() {
        assertEquals(0, LogHistogram.bucket(0));
        assertEquals(15, LogHistogram.bucket(15));
        assertEquals(16, LogHistogram.bucket(16));
        assertEquals(17, LogHistogram.bucket(17));
        assertEquals(31, LogHistogram.bucket(31));
        assertEquals(32, LogHistogram.bucket(32));
        assertEquals(32, LogHistogram.bucket(33));
        assertEquals(959, LogHistogram.bucket(Long.MAX_VALUE));
        assertTrue(LogHistogram.bucket(Long.MAX_VALUE) < LogHistogram.BUCKETS);
        assertEquals(Long.MAX_VALUE, LogHistogram.upperBound(959));
        long previous = -1;
        for (int b = 0; b < 960; b++) {
            long upper = LogHistogram.upperBound(b);
            assertTrue(upper > previous, "bucket " + b);
            assertEquals(b, LogHistogram.bucket(upper), "upper bound of " + b);
            assertEquals(b, LogHistogram.bucket(previous + 1), "lower bound of " + b);
            previous = upper;
        }
    }

    @Test
    void histogramQuantilesWithinOneSixteenth() {
        LogHistogram h = new LogHistogram();
        assertEquals(0, h.quantile(0.5));
        assertEquals(0, h.max());
        Random random = new Random(42);
        long[] values = new long[10_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = (long) Math.exp(random.nextDouble() * 12); // 1 µs .. ~160 ms
            h.record(values[i]);
        }
        java.util.Arrays.sort(values);
        assertEquals(10_000, h.count());
        assertEquals(values[values.length - 1], h.max());
        for (double q : new double[] { 0.5, 0.9, 0.99, 1.0 }) {
            long exact = values[(int) Math.ceil(q * values.length) - 1];
            long reported = h.quantile(q);
            assertTrue(reported >= exact, "q=" + q + " reported " + reported + " < exact " + exact);
            assertTrue(reported <= exact + exact / 16 + 1, "q=" + q + " reported " + reported + " vs exact " + exact);
        }
        assertEquals(h.max(), h.quantile(1.0));
        assertThrows(IllegalArgumentException.class, () -> h.quantile(0));
        assertThrows(IllegalArgumentException.class, () -> h.quantile(1.01));

        LogHistogram last = new LogHistogram();
        last.copyFrom(h);
        h.clear();
        assertEquals(0, h.count());
        assertEquals(10_000, last.count());
        h.record(-5);
        assertEquals(0, h.max());
        assertEquals(1, h.count());
    }

    @Test
    void countersAccumulate() {
        SensorCounters c = new SensorCounters();
        c.onSample(StateCodes.RUNNING, true, 1920, 20);
        c.onSample(StateCodes.RUNNING, true, -500, 20);
        c.onSample(StateCodes.IDLE, false, 999, 20);
        c.onSample(StateCodes.IDLE, true, 0, 20);
        c.onGap(GapReason.CHUNK_UNLOADED, 1);
        c.onGap(GapReason.CHUNK_UNLOADED, 5);
        c.onGap(GapReason.SAMPLING_SKIPPED, 0);
        c.onProbeError();
        c.onRecipesCounterReset();
        assertEquals(4, c.samplesTotal());
        assertEquals(2, c.stateSamplesTotal(StateCodes.RUNNING));
        assertEquals(2, c.stateSamplesTotal(StateCodes.IDLE));
        assertEquals(38_400, c.euConsumedSampledTotal());
        assertEquals(10_000, c.euGeneratedSampledTotal());
        assertEquals(6, c.gapSecondsTotal(GapReason.CHUNK_UNLOADED));
        assertEquals(0, c.gapSecondsTotal(GapReason.SAMPLING_SKIPPED));
        assertEquals(1, c.probeErrorsTotal());
        assertEquals(1, c.recipesCounterResetsTotal());
        assertEquals(StateCodes.COUNT, c.stateSamplesTotal().length);
        assertEquals(GapReason.STORED_COUNT, c.gapSecondsTotal().length);
        c.stateSamplesTotal()[StateCodes.RUNNING] = 99;
        assertEquals(2, c.stateSamplesTotal(StateCodes.RUNNING), "getters return copies");
        assertThrows(IllegalArgumentException.class, () -> c.onGap(GapReason.SERVER_OFFLINE, 1));
        assertThrows(IllegalArgumentException.class, () -> c.gapSecondsTotal(GapReason.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> c.onSample(10, false, 0, 20));
    }

    @Test
    void countersSaturate() {
        SensorCounters c = new SensorCounters();
        c.onSample(StateCodes.RUNNING, true, Long.MAX_VALUE, 100);
        c.onSample(StateCodes.RUNNING, true, Long.MAX_VALUE, 100);
        c.onSample(StateCodes.RUNNING, true, Long.MIN_VALUE, 100);
        assertEquals(Long.MAX_VALUE, c.euConsumedSampledTotal());
        assertEquals(Long.MAX_VALUE, c.euGeneratedSampledTotal());
        c.onGap(GapReason.PROBE_ERROR, Long.MAX_VALUE);
        c.onGap(GapReason.PROBE_ERROR, 10);
        assertEquals(Long.MAX_VALUE, c.gapSecondsTotal(GapReason.PROBE_ERROR));
    }

    @Test
    void clocks() {
        FakeClock clock = FakeClock.atEpochSec(1_759_107_659L);
        assertEquals(1_759_107_659_000L, clock.epochMillis());
        assertEquals(1_759_107_659L, clock.epochSec());
        assertEquals(29_318_460L, clock.epochMinute());
        clock.advanceSeconds(1);
        assertEquals(29_318_461L, clock.epochMinute());
        clock.advanceMillis(-1);
        assertEquals(1_759_107_659L, clock.epochSec());
        clock.setEpochMillis(-1);
        assertEquals(-1, clock.epochSec(), "floor, not truncation");
        long before = System.currentTimeMillis();
        long now = Clock.SYSTEM.epochMillis();
        assertTrue(now >= before && now <= System.currentTimeMillis());
    }
}
