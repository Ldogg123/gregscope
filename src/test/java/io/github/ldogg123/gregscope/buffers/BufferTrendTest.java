package io.github.ldogg123.gregscope.buffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.SecondRing;
import io.github.ldogg123.gregscope.model.StateCodes;

/** GS-304: the direction a machine's inputs are going, and the cases where the honest answer is "I don't know". */
class BufferTrendTest {

    private static final int START = 1_759_107_600;

    /** Fills a ring with {@code count} seconds whose saturation walks from {@code from} to {@code to}. */
    private static SecondRing ramp(double from, double to, int count) {
        SecondRing ring = new SecondRing();
        for (int i = 0; i < count; i++) {
            double at = count == 1 ? from : from + (to - from) * i / (double) (count - 1);
            ring.appendSample(START + i, StateCodes.RUNNING, SecondRing.FLAG_ACTIVE, 0, 0.0, 0L, 0L, at);
        }
        return ring;
    }

    @Test
    void aDrainingMachineReadsAsFalling() {
        BufferTrend trend = BufferTrend.of(ramp(0.9D, 0.1D, 60), START + 60, 300);
        assertEquals(BufferTrend.Direction.FALLING, trend.direction());
        assertEquals(0.9D, trend.from(), 1e-3);
        assertEquals(0.1D, trend.to(), 1e-3);
        assertTrue(trend.delta() < 0, "a falling trend has a negative delta");
        assertEquals(60, trend.samples());
    }

    @Test
    void aFillingMachineReadsAsRising() {
        assertEquals(
            BufferTrend.Direction.RISING,
            BufferTrend.of(ramp(0.1D, 0.9D, 60), START + 60, 300)
                .direction());
    }

    /** Small wobble is not a trend: a machine consuming and being refilled sits still on average. */
    @Test
    void movementInsideTheDeadbandIsSteady() {
        BufferTrend trend = BufferTrend.of(ramp(0.50D, 0.51D, 60), START + 60, 300);
        assertEquals(BufferTrend.Direction.STEADY, trend.direction());
    }

    @Test
    void theDeadbandEdgeIsNotMovement() {
        assertEquals(
            BufferTrend.Direction.STEADY,
            BufferTrend.of(ramp(0.50D, 0.50D + BufferTrend.DEADBAND, 60), START + 60, 300)
                .direction(),
            "exactly the deadband is still steady; it has to be exceeded");
    }

    @Test
    void tooFewSamplesIsUnknownRatherThanAGuess() {
        BufferTrend trend = BufferTrend.of(ramp(0.9D, 0.1D, BufferTrend.MIN_SAMPLES - 1), START + 10, 300);
        assertEquals(BufferTrend.Direction.UNKNOWN, trend.direction());
        assertEquals(BufferTrend.MIN_SAMPLES - 1, trend.samples());
    }

    /**
     * The false alarm this exists to avoid: an unloaded chunk records gap seconds, and if those counted as zero the
     * machine would look like it drained to empty the moment nobody was watching.
     */
    @Test
    void gapSecondsAreSkippedNotCountedAsEmpty() {
        SecondRing ring = new SecondRing();
        for (int i = 0; i < 30; i++) {
            ring.appendSample(START + i, StateCodes.RUNNING, SecondRing.FLAG_ACTIVE, 0, 0.0, 0L, 0L, 0.80D);
        }
        for (int i = 30; i < 60; i++) {
            ring.appendGap(START + i, GapReason.CHUNK_UNLOADED);
        }
        BufferTrend trend = BufferTrend.of(ring, START + 60, 300);

        assertEquals(BufferTrend.Direction.STEADY, trend.direction(), "the gap must not read as a drain to zero");
        assertEquals(0.80D, trend.to(), 1e-9, "the last MEASURED second is what counts");
        assertEquals(30, trend.samples(), "only the measured seconds count");
    }

    /** A machine whose inputs report no capacity at all (all ME) has no trend to report. */
    @Test
    void unmeasurableSaturationIsUnknown() {
        SecondRing ring = new SecondRing();
        for (int i = 0; i < 60; i++) {
            ring.appendSample(START + i, StateCodes.RUNNING, SecondRing.FLAG_ACTIVE, 0, 0.0, 0L, 0L, Double.NaN);
        }
        BufferTrend trend = BufferTrend.of(ring, START + 60, 300);
        assertEquals(BufferTrend.Direction.UNKNOWN, trend.direction());
        assertEquals(0, trend.samples());
    }

    /** A ring full of hours-old data must not be reported as the current trend. */
    @Test
    void samplesOutsideTheWindowAreIgnored() {
        BufferTrend trend = BufferTrend.of(ramp(0.9D, 0.1D, 60), START + 10_000, 300);
        assertEquals(BufferTrend.Direction.UNKNOWN, trend.direction(), "all of it is older than the window");
        assertEquals(0, trend.samples());
    }

    @Test
    void anEmptyOrAbsentRingIsUnknown() {
        assertEquals(
            BufferTrend.Direction.UNKNOWN,
            BufferTrend.of(null, START, 300)
                .direction());
        assertEquals(
            BufferTrend.Direction.UNKNOWN,
            BufferTrend.of(new SecondRing(), START, 300)
                .direction());
        assertEquals(
            BufferTrend.Direction.UNKNOWN,
            BufferTrend.of(ramp(0.9D, 0.1D, 60), START + 60, 0)
                .direction(),
            "a zero-length window asks nothing");
    }
}
