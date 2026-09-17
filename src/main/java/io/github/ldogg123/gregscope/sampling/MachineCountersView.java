package io.github.ldogg123.gregscope.sampling;

import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * An immutable copy of one machine sensor's {@link SensorCounters} (design-v0.2 section 7.6), carried by a
 * {@link SensorView}. [pure]
 *
 * <p>
 * The arrays are copied on construction and again on every getter, so nothing a reader does can reach the live
 * counters the server thread writes.
 */
public final class MachineCountersView implements CountersView {

    private final long samplesTotal;
    private final long[] stateSamplesTotal;
    private final long[] gapSecondsTotal;
    private final long probeErrorsTotal;
    private final long recipesCounterResetsTotal;
    private final long euConsumedSampledTotal;
    private final long euGeneratedSampledTotal;

    public MachineCountersView(SensorCounters counters) {
        this.samplesTotal = counters.samplesTotal();
        this.stateSamplesTotal = counters.stateSamplesTotal();
        this.gapSecondsTotal = counters.gapSecondsTotal();
        this.probeErrorsTotal = counters.probeErrorsTotal();
        this.recipesCounterResetsTotal = counters.recipesCounterResetsTotal();
        this.euConsumedSampledTotal = counters.euConsumedSampledTotal();
        this.euGeneratedSampledTotal = counters.euGeneratedSampledTotal();
    }

    @Override
    public int kind() {
        return SensorKind.MACHINE;
    }

    @Override
    public long samplesTotal() {
        return samplesTotal;
    }

    @Override
    public long gapSecondsTotal(GapReason reason) {
        if (!reason.isStored()) {
            throw new IllegalArgumentException("not a stored gap reason: " + reason);
        }
        return gapSecondsTotal[reason.bit()];
    }

    @Override
    public long[] gapSecondsTotal() {
        return gapSecondsTotal.clone();
    }

    /** Samples observed in one {@link StateCodes} state. */
    public long stateSamplesTotal(int stateCode) {
        if (!StateCodes.isKnown(stateCode)) {
            throw new IllegalArgumentException("stateCode " + stateCode);
        }
        return stateSamplesTotal[stateCode];
    }

    /** A copy indexed by {@link StateCodes} code. */
    public long[] stateSamplesTotal() {
        return stateSamplesTotal.clone();
    }

    public long probeErrorsTotal() {
        return probeErrorsTotal;
    }

    public long recipesCounterResetsTotal() {
        return recipesCounterResetsTotal;
    }

    /** Estimate: the sum of {@code euPerTick x intervalTicks} over samples with a positive EU/t. */
    public long euConsumedSampledTotal() {
        return euConsumedSampledTotal;
    }

    /** Estimate: the sum of {@code |euPerTick| x intervalTicks} over samples with a negative EU/t. */
    public long euGeneratedSampledTotal() {
        return euGeneratedSampledTotal;
    }

    @Override
    public String toString() {
        return "MachineCountersView{samples=" + samplesTotal + ", probeErrors=" + probeErrorsTotal + "}";
    }
}
