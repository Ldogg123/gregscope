package io.github.ldogg123.gregscope.sampling;

import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.model.StateCodes;

/**
 * Per-sensor counters (design-v0.2 §7.6). Monotonic within a process and saturating at {@code Long.MAX_VALUE}; they
 * reset on restart, which Prometheus-style consumers handle. Written on the server thread only; frames copy them.
 * [pure]
 */
public final class SensorCounters {

    private long samplesTotal;
    private final long[] stateSamplesTotal = new long[StateCodes.COUNT];
    private final long[] gapSecondsTotal = new long[GapReason.STORED_COUNT];
    private long probeErrorsTotal;
    private long recipesCounterResetsTotal;
    private long euConsumedSampledTotal;
    private long euGeneratedSampledTotal;

    private static long add(long total, long amount) {
        long sum = total + amount;
        return sum < total ? Long.MAX_VALUE : sum;
    }

    /**
     * Counts one observed sample. EU is an estimate, {@code |euPerTick| x intervalTicks}: added to consumed for a
     * positive value and to generated for a negative one (schema v1: positive is consumption).
     */
    public void onSample(int stateCode, boolean hasEu, long euPerTick, int intervalTicks) {
        if (!StateCodes.isKnown(stateCode)) {
            throw new IllegalArgumentException("stateCode " + stateCode);
        }
        if (intervalTicks <= 0) {
            throw new IllegalArgumentException("intervalTicks " + intervalTicks);
        }
        samplesTotal = add(samplesTotal, 1);
        stateSamplesTotal[stateCode] = add(stateSamplesTotal[stateCode], 1);
        if (hasEu && euPerTick != 0) {
            long magnitude = euPerTick == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(euPerTick);
            long eu = magnitude > Long.MAX_VALUE / intervalTicks ? Long.MAX_VALUE : magnitude * intervalTicks;
            if (euPerTick > 0) {
                euConsumedSampledTotal = add(euConsumedSampledTotal, eu);
            } else {
                euGeneratedSampledTotal = add(euGeneratedSampledTotal, eu);
            }
        }
    }

    /** Counts {@code seconds} of gap with a stored reason. */
    public void onGap(GapReason reason, long seconds) {
        if (reason == null || !reason.isStored()) {
            throw new IllegalArgumentException("not a stored gap reason: " + reason);
        }
        if (seconds > 0) {
            gapSecondsTotal[reason.bit()] = add(gapSecondsTotal[reason.bit()], seconds);
        }
    }

    public void onProbeError() {
        probeErrorsTotal = add(probeErrorsTotal, 1);
    }

    public void onRecipesCounterReset() {
        recipesCounterResetsTotal = add(recipesCounterResetsTotal, 1);
    }

    public long samplesTotal() {
        return samplesTotal;
    }

    public long stateSamplesTotal(int stateCode) {
        return stateSamplesTotal[stateCode];
    }

    /** A copy indexed by {@link StateCodes} code. */
    public long[] stateSamplesTotal() {
        return stateSamplesTotal.clone();
    }

    public long gapSecondsTotal(GapReason reason) {
        if (!reason.isStored()) {
            throw new IllegalArgumentException("not a stored gap reason: " + reason);
        }
        return gapSecondsTotal[reason.bit()];
    }

    /** A copy indexed by {@link GapReason#bit()} (stored reasons only). */
    public long[] gapSecondsTotal() {
        return gapSecondsTotal.clone();
    }

    public long probeErrorsTotal() {
        return probeErrorsTotal;
    }

    public long recipesCounterResetsTotal() {
        return recipesCounterResetsTotal;
    }

    public long euConsumedSampledTotal() {
        return euConsumedSampledTotal;
    }

    public long euGeneratedSampledTotal() {
        return euGeneratedSampledTotal;
    }
}
