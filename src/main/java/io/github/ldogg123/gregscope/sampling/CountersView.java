package io.github.ldogg123.gregscope.sampling;

import io.github.ldogg123.gregscope.history.GapReason;

/**
 * The per-sensor counters a {@link SensorView} carries, as an interface (design-v0.3 section 5.1 A6). [pure]
 *
 * <p>
 * Only what every sensor kind has is here: how many samples were observed and how many gap seconds were attributed to
 * each stored reason. A machine sensor adds its state, EU and recipe counters in {@link MachineCountersView}; the v0.3
 * flow meters will add their own implementation for kinds 1 and 2 without any reader having to special-case a
 * {@code null}. Immutable: every implementation copies primitives when the frame is built.
 */
public interface CountersView {

    /** The {@code SensorKind} these counters belong to; decides which implementation this is. */
    int kind();

    /** Observed samples (not gap seconds) since this process started. */
    long samplesTotal();

    /** Gap seconds attributed to one stored reason. @throws IllegalArgumentException for a derived reason */
    long gapSecondsTotal(GapReason reason);

    /** A copy indexed by {@link GapReason#bit()}, stored reasons only. */
    long[] gapSecondsTotal();
}
