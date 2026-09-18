package io.github.ldogg123.gregscope.hub;

import java.util.Arrays;

import io.github.ldogg123.gregscope.history.Summaries;
import io.github.ldogg123.gregscope.model.StateCodes;

/**
 * One window summary inside a {@link HubDetail}: the "Last 5 min" and "Last 24 h" lines of design-v0.2 section 9.2.
 * Immutable. [pure]
 *
 * <p>
 * It is the wire form of {@link Summaries.Summary} and keeps its numbers rather than its percentages, so the section
 * 7.5 reader contract holds on the client too: {@link #stateFraction(int)} divides by observed samples and
 * {@link #coverage()} divides by the window's expected samples. Nothing is interpolated, and a window with no
 * observed sample has no fractions at all ({@code NaN}), which is what makes "uptime with no coverage" showable as
 * "-" instead of a misleading 0%.
 */
public final class HubWindow {

    /** A window nothing was recorded in. */
    public static final HubWindow EMPTY = new HubWindow(
        new long[StateCodes.COUNT],
        0L,
        0L,
        0,
        0L,
        HubCodecs.NONE,
        HubCodecs.NONE,
        HubCodecs.NONE,
        -1L,
        false,
        0,
        0);

    private final long[] stateSamples;
    private final long samples;
    private final long expectedSamples;
    private final int observedMinutes;
    private final long euSamples;
    private final long euPerTickAvg;
    private final long euPerTickMin;
    private final long euPerTickMax;
    private final long recipesCompleted;
    private final boolean recipesCounterReset;
    private final int maintenanceMax;
    private final int gapMask;

    public HubWindow(long[] stateSamples, long samples, long expectedSamples, int observedMinutes, long euSamples,
        long euPerTickAvg, long euPerTickMin, long euPerTickMax, long recipesCompleted, boolean recipesCounterReset,
        int maintenanceMax, int gapMask) {
        if (stateSamples == null || stateSamples.length != StateCodes.COUNT) {
            throw new IllegalArgumentException("stateSamples must have " + StateCodes.COUNT + " columns");
        }
        this.stateSamples = stateSamples.clone();
        this.samples = samples;
        this.expectedSamples = expectedSamples;
        this.observedMinutes = observedMinutes;
        this.euSamples = euSamples;
        this.euPerTickAvg = euPerTickAvg;
        this.euPerTickMin = euPerTickMin;
        this.euPerTickMax = euPerTickMax;
        this.recipesCompleted = recipesCompleted;
        this.recipesCounterReset = recipesCounterReset;
        this.maintenanceMax = maintenanceMax;
        this.gapMask = gapMask;
    }

    /** The wire form of a {@link Summaries.Summary}. */
    public static HubWindow of(Summaries.Summary summary) {
        long[] states = new long[StateCodes.COUNT];
        for (int i = 0; i < states.length; i++) {
            states[i] = summary.stateSamples(i);
        }
        return new HubWindow(
            states,
            summary.samples(),
            summary.expectedSamples(),
            summary.observedMinutes(),
            summary.euSamples(),
            summary.euPerTickAvg(),
            summary.euPerTickMin(),
            summary.euPerTickMax(),
            summary.recipesCompleted(),
            summary.recipesCounterReset(),
            summary.maintenanceMax(),
            summary.gapMask());
    }

    public long samples() {
        return samples;
    }

    public long expectedSamples() {
        return expectedSamples;
    }

    public boolean hasSamples() {
        return samples > 0;
    }

    /** Minutes with at least one sample. */
    public int observedMinutes() {
        return observedMinutes;
    }

    public long stateSamples(int stateCode) {
        return stateSamples[stateCode];
    }

    /** {@code stateSamples / samples}; {@code NaN} when nothing was observed, exactly as the summary has it. */
    public double stateFraction(int stateCode) {
        return samples == 0 ? Double.NaN : stateSamples[stateCode] / (double) samples;
    }

    /** {@code samples / expected}, capped at 1; 0 for an empty window. */
    public double coverage() {
        return expectedSamples == 0 ? 0.0 : Math.min(1.0, samples / (double) expectedSamples);
    }

    public long euSamples() {
        return euSamples;
    }

    /** Weighted by each minute's EU samples; {@link HubCodecs#NONE} when no minute had one. */
    public long euPerTickAvg() {
        return euPerTickAvg;
    }

    public long euPerTickMin() {
        return euPerTickMin;
    }

    public long euPerTickMax() {
        return euPerTickMax;
    }

    /** Recipes finished in the window, or -1 when no minute counted any (not a multiblock). */
    public long recipesCompleted() {
        return recipesCompleted;
    }

    public boolean recipesCounterReset() {
        return recipesCounterReset;
    }

    public int maintenanceMax() {
        return maintenanceMax;
    }

    /** OR of the stored gap masks of the window's minutes. */
    public int gapMask() {
        return gapMask;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HubWindow)) {
            return false;
        }
        HubWindow w = (HubWindow) o;
        return samples == w.samples && expectedSamples == w.expectedSamples
            && observedMinutes == w.observedMinutes
            && euSamples == w.euSamples
            && euPerTickAvg == w.euPerTickAvg
            && euPerTickMin == w.euPerTickMin
            && euPerTickMax == w.euPerTickMax
            && recipesCompleted == w.recipesCompleted
            && recipesCounterReset == w.recipesCounterReset
            && maintenanceMax == w.maintenanceMax
            && gapMask == w.gapMask
            && Arrays.equals(stateSamples, w.stateSamples);
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(stateSamples);
        h = h * 31 + (int) (samples ^ (samples >>> 32));
        h = h * 31 + (int) (expectedSamples ^ (expectedSamples >>> 32));
        h = h * 31 + observedMinutes;
        h = h * 31 + (int) (euSamples ^ (euSamples >>> 32));
        h = h * 31 + (int) (euPerTickAvg ^ (euPerTickAvg >>> 32));
        h = h * 31 + (int) (euPerTickMin ^ (euPerTickMin >>> 32));
        h = h * 31 + (int) (euPerTickMax ^ (euPerTickMax >>> 32));
        h = h * 31 + (int) (recipesCompleted ^ (recipesCompleted >>> 32));
        h = h * 31 + (recipesCounterReset ? 1 : 0);
        h = h * 31 + maintenanceMax;
        return h * 31 + gapMask;
    }

    @Override
    public String toString() {
        return "HubWindow{" + samples + "/" + expectedSamples + " samples, " + observedMinutes + " minutes}";
    }
}
