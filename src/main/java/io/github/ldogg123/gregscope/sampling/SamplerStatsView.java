package io.github.ldogg123.gregscope.sampling;

/**
 * An immutable copy of the design-v0.2 section 7.6 global counters, carried by a {@link TelemetryFrame}. [pure]
 *
 * <p>
 * Every field is a primitive copied when the frame is built, so a reader on another thread (OpenComputers, the v0.4
 * exporter) sees one consistent set of numbers and never reads {@link SamplerStats} while the server thread writes it.
 * The class is not named in design-v0.2 section 2, but section 7.7 puts a {@code SamplerStatsView} in the frame.
 */
public final class SamplerStatsView {

    private final long ticksTotal;
    private final long cyclesTotal;
    private final long samplesTotal;
    private final long sampleNanosTotal;
    private final long budgetExceededTicksTotal;
    private final long samplingSkippedTotal;
    private final long probeErrorsTotal;
    private final long duplicatesRekeyedTotal;
    private final long quotaRefusedTotal;
    private final long ioQueuedTotal;
    private final long ioDroppedTotal;
    private final long ioErrorsTotal;
    private final long clockSkewRefusedTotal;
    private final long framesPublishedTotal;
    private final long lastCycleNanos;
    private final long lastFrameBuildNanos;
    private final long cycleMicrosP50;
    private final long cycleMicrosP99;
    private final long cycleMicrosMax;
    private final long windowTicks;

    /**
     * Copies {@code stats}; {@code duplicatesRekeyedTotal} and {@code quotaRefusedTotal} come from the registry core,
     * which owns them.
     */
    public SamplerStatsView(SamplerStats stats, long duplicatesRekeyedTotal, long quotaRefusedTotal) {
        this.ticksTotal = stats.ticksTotal();
        this.cyclesTotal = stats.cyclesTotal();
        this.samplesTotal = stats.samplesTotal();
        this.sampleNanosTotal = stats.sampleNanosTotal();
        this.budgetExceededTicksTotal = stats.budgetExceededTicksTotal();
        this.samplingSkippedTotal = stats.samplingSkippedTotal();
        this.probeErrorsTotal = stats.probeErrorsTotal();
        this.duplicatesRekeyedTotal = duplicatesRekeyedTotal;
        this.quotaRefusedTotal = quotaRefusedTotal;
        this.ioQueuedTotal = stats.ioQueuedTotal();
        this.ioDroppedTotal = stats.ioDroppedTotal();
        this.ioErrorsTotal = stats.ioErrorsTotal();
        this.clockSkewRefusedTotal = stats.clockSkewRefusedTotal();
        this.framesPublishedTotal = stats.framesPublishedTotal();
        this.lastCycleNanos = stats.lastCycleNanos();
        this.lastFrameBuildNanos = stats.lastFrameBuildNanos();
        this.cycleMicrosP50 = stats.cycleMicrosQuantile(0.50);
        this.cycleMicrosP99 = stats.cycleMicrosQuantile(0.99);
        this.cycleMicrosMax = stats.cycleMicrosMax();
        this.windowTicks = stats.windowTicks();
    }

    public long ticksTotal() {
        return ticksTotal;
    }

    public long cyclesTotal() {
        return cyclesTotal;
    }

    public long samplesTotal() {
        return samplesTotal;
    }

    public long sampleNanosTotal() {
        return sampleNanosTotal;
    }

    public long budgetExceededTicksTotal() {
        return budgetExceededTicksTotal;
    }

    public long samplingSkippedTotal() {
        return samplingSkippedTotal;
    }

    public long probeErrorsTotal() {
        return probeErrorsTotal;
    }

    public long duplicatesRekeyedTotal() {
        return duplicatesRekeyedTotal;
    }

    public long quotaRefusedTotal() {
        return quotaRefusedTotal;
    }

    public long ioQueuedTotal() {
        return ioQueuedTotal;
    }

    public long ioDroppedTotal() {
        return ioDroppedTotal;
    }

    public long ioErrorsTotal() {
        return ioErrorsTotal;
    }

    public long clockSkewRefusedTotal() {
        return clockSkewRefusedTotal;
    }

    public long framesPublishedTotal() {
        return framesPublishedTotal;
    }

    public long lastCycleNanos() {
        return lastCycleNanos;
    }

    public long lastFrameBuildNanos() {
        return lastFrameBuildNanos;
    }

    /** Median microseconds per working tick over the last completed 60-second window. */
    public long cycleMicrosP50() {
        return cycleMicrosP50;
    }

    public long cycleMicrosP99() {
        return cycleMicrosP99;
    }

    public long cycleMicrosMax() {
        return cycleMicrosMax;
    }

    /** How many ticks the reported window holds; 0 before the first window completed. */
    public long windowTicks() {
        return windowTicks;
    }

    @Override
    public String toString() {
        return "SamplerStatsView{ticks=" + ticksTotal
            + ", cycles="
            + cyclesTotal
            + ", samples="
            + samplesTotal
            + ", budgetExceeded="
            + budgetExceededTicksTotal
            + ", skipped="
            + samplingSkippedTotal
            + ", probeErrors="
            + probeErrorsTotal
            + ", us p50/p99/max="
            + cycleMicrosP50
            + "/"
            + cycleMicrosP99
            + "/"
            + cycleMicrosMax
            + "}";
    }
}
