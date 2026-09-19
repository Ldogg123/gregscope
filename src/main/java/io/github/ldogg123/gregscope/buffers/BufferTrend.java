package io.github.ldogg123.gregscope.buffers;

import io.github.ldogg123.gregscope.history.SecondRing;

/**
 * Which way a machine's input buffers are going, over the last few minutes. [pure]
 *
 * <p>
 * This is the half that makes buffer telemetry an alert rather than a readout. A machine sitting at 12% is not
 * news; a machine at 12% and <em>falling</em> is about to stop, and one at 12% and steady is simply running lean on
 * purpose. The distinction is what a player would otherwise have to watch the GUI to notice.
 *
 * <p>
 * <b>Deliberately not a rate.</b> The direction is read from saturation, which is a level, so this reports which
 * way it moved and by how much — never litres per second. Design-v0.3-buffers section 1 has the reasoning, and
 * flow-meters.md has the record of why the throughput version was abandoned.
 */
public final class BufferTrend {

    /**
     * Compared endpoints must differ by more than this fraction before it counts as movement, not noise.
     *
     * <p>
     * The comparison is made at the ring's own resolution rather than on the raw doubles. The ring stores
     * saturation as permyriad, so a 0.50-to-0.52 ramp decodes to a delta of 0.020000000000000018 and a naive
     * {@code >} would call exactly-the-deadband a rise. Rounding both sides to permyriad first makes the boundary
     * mean what it says.
     */
    public static final double DEADBAND = 0.02D;

    /** The ring's resolution: saturation is stored x10000, so nothing finer than this is real. */
    private static final double PERMYRIAD = 10_000.0D;

    /** Fewer measurable samples than this in the window and the answer is {@link Direction#UNKNOWN}. */
    public static final int MIN_SAMPLES = 5;

    public enum Direction {
        /** Filling up. */
        RISING,
        /** Draining: on a machine that is running, this is the one worth acting on. */
        FALLING,
        /** Moving less than the deadband. */
        STEADY,
        /** Not enough measurable samples, or nothing here reports a capacity at all. */
        UNKNOWN
    }

    private final Direction direction;
    private final double from;
    private final double to;
    private final int samples;

    private BufferTrend(Direction direction, double from, double to, int samples) {
        this.direction = direction;
        this.from = from;
        this.to = to;
        this.samples = samples;
    }

    private static final BufferTrend NONE = new BufferTrend(Direction.UNKNOWN, Double.NaN, Double.NaN, 0);

    public Direction direction() {
        return direction;
    }

    /** Saturation at the start of the window, or {@code NaN} when unknown. */
    public double from() {
        return from;
    }

    /** Saturation at the end of the window, or {@code NaN} when unknown. */
    public double to() {
        return to;
    }

    /** How much it moved, positive for rising; {@code NaN} when unknown. */
    public double delta() {
        return to - from;
    }

    /** Measurable samples the answer is based on. */
    public int samples() {
        return samples;
    }

    /**
     * Reads the trend over the last {@code windowSeconds} of {@code ring}.
     *
     * <p>
     * Only samples that measured something count: a gap second, or a second where no buffer reported a capacity,
     * is skipped rather than treated as zero — otherwise an unloaded chunk would look like a machine draining to
     * empty, which is exactly the false alarm this is meant to avoid.
     *
     * @param nowEpochSec   the present, so a stale ring does not report a trend from hours ago
     * @param windowSeconds how far back to look
     */
    public static BufferTrend of(SecondRing ring, long nowEpochSec, int windowSeconds) {
        if (ring == null || windowSeconds <= 0) {
            return NONE;
        }
        long oldest = nowEpochSec - windowSeconds;
        double first = Double.NaN;
        double last = Double.NaN;
        int measured = 0;
        for (int i = 0; i < ring.size(); i++) {
            if (ring.epochSec(i) < oldest || ring.epochSec(i) > nowEpochSec) {
                continue;
            }
            double saturation = ring.inputSaturation(i);
            if (Double.isNaN(saturation)) {
                continue;
            }
            if (measured == 0) {
                first = saturation;
            }
            last = saturation;
            measured++;
        }
        if (measured < MIN_SAMPLES) {
            return new BufferTrend(Direction.UNKNOWN, first, last, measured);
        }
        double moved = last - first;
        long movedPermyriad = Math.round(moved * PERMYRIAD);
        long deadbandPermyriad = Math.round(DEADBAND * PERMYRIAD);
        Direction direction;
        if (movedPermyriad > deadbandPermyriad) {
            direction = Direction.RISING;
        } else if (movedPermyriad < -deadbandPermyriad) {
            direction = Direction.FALLING;
        } else {
            direction = Direction.STEADY;
        }
        return new BufferTrend(direction, first, last, measured);
    }

    @Override
    public String toString() {
        if (direction == Direction.UNKNOWN) {
            return "UNKNOWN(" + samples + " samples)";
        }
        return direction + "("
            + Math.round(from * 100)
            + "% -> "
            + Math.round(to * 100)
            + "%, "
            + samples
            + " samples)";
    }
}
