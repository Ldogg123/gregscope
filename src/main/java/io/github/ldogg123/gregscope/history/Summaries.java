package io.github.ldogg123.gregscope.history;

import java.math.BigInteger;

import io.github.ldogg123.gregscope.model.StateCodes;

/**
 * Window summaries over minute history, such as the Hub's "last 5 min" and "last 24 h" lines and {@code /gregscope
 * info} (design-v0.2 §9.2, §11). [pure]
 *
 * <p>
 * Follows the §7.5 reader contract: only observed samples count; nothing is interpolated or zero-filled. State
 * fractions are {@code stateSamples / samples}. Coverage is {@code samples / expected} (§7.5 rule 1), where
 * {@code expected} adds up each stored slot's own {@code expectedSamples} (the {@code 1200 / intervalTicks} that was
 * in effect when that minute was recorded, §7.4) and, for each missing minute, the caller's current
 * {@code expectedSamplesPerMinute}. {@code intervalTicks} is a restart-only setting and history outlives restarts, so
 * one window can hold minutes recorded at different intervals; missing minutes lower coverage without changing the
 * state fractions.
 */
public final class Summaries {

    private Summaries() {}

    /**
     * Summarizes the minutes {@code [fromMinute, toMinute)}.
     *
     * @param expectedSamplesPerMinute {@code 1200 / intervalTicks} of the current configuration; used for minutes
     *                                 without a stored slot, and for a stored slot whose {@code expectedSamples} is 0
     */
    public static Summary minutes(MinuteSource source, int fromMinute, int toMinute, int expectedSamplesPerMinute) {
        if (toMinute < fromMinute || expectedSamplesPerMinute <= 0) {
            throw new IllegalArgumentException(
                "window " + fromMinute + ".." + toMinute + ", expected " + expectedSamplesPerMinute);
        }
        long[] states = new long[StateCodes.COUNT];
        long samples = 0;
        long euSamples = 0;
        BigInteger euSum = BigInteger.ZERO;
        long euMin = MinuteSlot.NONE;
        long euMax = MinuteSlot.NONE;
        long recipes = -1;
        int maintenanceMax = 0;
        int gapMask = 0;
        int observedMinutes = 0;
        boolean recipesReset = false;
        long expected = 0;
        for (long m = fromMinute; m < toMinute; m++) {
            MinuteSlot slot = m == 0 ? null : source.slot((int) m);
            if (slot == null) {
                expected += expectedSamplesPerMinute;
                continue;
            }
            expected += slot.expectedSamples() > 0 ? slot.expectedSamples() : expectedSamplesPerMinute;
            gapMask |= slot.gapMask();
            recipesReset |= slot.hasFlag(MinuteSlot.FLAG_RECIPES_COUNTER_RESET);
            if (slot.recipesCompletedDelta() >= 0) {
                recipes = Math.max(recipes, 0) + slot.recipesCompletedDelta();
            }
            if (slot.samples() == 0) {
                continue;
            }
            observedMinutes++;
            samples += slot.samples();
            for (int i = 0; i < StateCodes.COUNT; i++) {
                states[i] += slot.stateSamples(i);
            }
            maintenanceMax = Math.max(maintenanceMax, slot.maintenanceMax());
            if (slot.euSamples() > 0) {
                euSum = euSum.add(
                    BigInteger.valueOf(slot.euPerTickAvg())
                        .multiply(BigInteger.valueOf(slot.euSamples())));
                euMin = euSamples == 0 ? slot.euPerTickMin() : Math.min(euMin, slot.euPerTickMin());
                euMax = euSamples == 0 ? slot.euPerTickMax() : Math.max(euMax, slot.euPerTickMax());
                euSamples += slot.euSamples();
            }
        }
        long euAvg = euSamples == 0 ? MinuteSlot.NONE : LongMath.roundedDivide(euSum, euSamples);
        return new Summary(
            states,
            samples,
            expected,
            observedMinutes,
            euSamples,
            euAvg,
            euMin,
            euMax,
            recipes,
            recipesReset,
            maintenanceMax,
            gapMask);
    }

    /** An immutable window summary. */
    public static final class Summary {

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

        Summary(long[] stateSamples, long samples, long expectedSamples, int observedMinutes, long euSamples,
            long euPerTickAvg, long euPerTickMin, long euPerTickMax, long recipesCompleted, boolean recipesCounterReset,
            int maintenanceMax, int gapMask) {
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

        /** {@code stateSamples / samples}; {@code NaN} when nothing was observed (there is no fraction to show). */
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

        /** Weighted by each minute's {@code euSamples}; {@link MinuteSlot#NONE} without EU samples. */
        public long euPerTickAvg() {
            return euPerTickAvg;
        }

        public long euPerTickMin() {
            return euPerTickMin;
        }

        public long euPerTickMax() {
            return euPerTickMax;
        }

        /** Sum of the minutes' recipe deltas, or -1 if no minute had one (not a multiblock). */
        public long recipesCompleted() {
            return recipesCompleted;
        }

        public boolean recipesCounterReset() {
            return recipesCounterReset;
        }

        public int maintenanceMax() {
            return maintenanceMax;
        }

        /** OR of the stored gap masks of the window's slots. */
        public int gapMask() {
            return gapMask;
        }
    }
}
