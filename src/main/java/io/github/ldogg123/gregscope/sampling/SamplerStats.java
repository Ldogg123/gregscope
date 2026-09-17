package io.github.ldogg123.gregscope.sampling;

/**
 * The global sampler counters and gauges of design-v0.2 section 7.6. Mutable, written on the server thread only; a
 * {@link SamplerStatsView} copy is what leaves the server thread inside a {@link TelemetryFrame}. [pure]
 *
 * <p>
 * Counters are monotonic within a process and saturate at {@link Long#MAX_VALUE}; they reset on restart, which
 * Prometheus-style consumers handle. {@code duplicatesRekeyedTotal} and {@code quotaRefusedTotal} live in the registry
 * core and are copied in when a view is taken, so there is exactly one source for each number.
 *
 * <p>
 * <b>The microsecond window.</b> Design-v0.2 section 6.3 asks for p50/p99/max microseconds per tick over a 60-second
 * window. {@link #recordCycleNanos} feeds the open window; {@link #rollWindow()} is called once every
 * {@link #WINDOW_TICKS} ticks and makes the open window the reported one. Quantiles therefore describe the last
 * completed minute, never a half-filled one.
 */
public final class SamplerStats {

    /** 60 seconds at 20 TPS: the width of the reported microsecond window (design-v0.2 section 6.3). */
    public static final int WINDOW_TICKS = 1200;

    private long ticksTotal;
    private long cyclesTotal;
    private long samplesTotal;
    private long sampleNanosTotal;
    private long budgetExceededTicksTotal;
    private long samplingSkippedTotal;
    private long probeErrorsTotal;
    private long ioQueuedTotal;
    private long ioDroppedTotal;
    private long ioErrorsTotal;
    private long clockSkewRefusedTotal;
    private long framesPublishedTotal;

    private long lastCycleNanos;
    private long lastFrameBuildNanos;

    private final LogHistogram open = new LogHistogram();
    private final LogHistogram reported = new LogHistogram();

    private static long add(long total, long amount) {
        long sum = total + amount;
        return sum < total ? Long.MAX_VALUE : sum;
    }

    // --- counters ---

    /** Every server tick the handler saw, work or not. Not in section 7.6; it is the denominator of the rest. */
    public void onTick() {
        ticksTotal = add(ticksTotal, 1);
    }

    /**
     * One tick on which the handler did per-sensor work (a sampling cycle): it served carry-over, a bucket, or both.
     * A tick with nothing due never reaches this, which is what "no work without live sensors" means.
     */
    public void onCycle(long cycleNanos) {
        cyclesTotal = add(cyclesTotal, 1);
        lastCycleNanos = cycleNanos;
        recordCycleNanos(cycleNanos);
    }

    /** Feeds the open microsecond window; nanoseconds are rounded down to whole microseconds. */
    public void recordCycleNanos(long cycleNanos) {
        open.record(cycleNanos / 1000L);
    }

    public void onSample(long sampleNanos) {
        samplesTotal = add(samplesTotal, 1);
        sampleNanosTotal = add(sampleNanosTotal, Math.max(0L, sampleNanos));
    }

    public void onBudgetExceeded() {
        budgetExceededTicksTotal = add(budgetExceededTicksTotal, 1);
    }

    public void onSamplingSkipped(long count) {
        if (count > 0) {
            samplingSkippedTotal = add(samplingSkippedTotal, count);
        }
    }

    public void onProbeError() {
        probeErrorsTotal = add(probeErrorsTotal, 1);
    }

    public void onIoQueued() {
        ioQueuedTotal = add(ioQueuedTotal, 1);
    }

    public void onIoDropped() {
        ioDroppedTotal = add(ioDroppedTotal, 1);
    }

    public void onIoError() {
        ioErrorsTotal = add(ioErrorsTotal, 1);
    }

    /**
     * Adds the clock-skew refusals one fold observed (design-v0.2 section 6.2). Deltas, not a sum over the live
     * accumulators: an entry that is purged, expires or becomes a tombstone has its accumulator freed, so a sum would
     * fall back and the counter would stop being monotonic.
     */
    public void onClockSkewRefused(long count) {
        if (count > 0) {
            clockSkewRefusedTotal = add(clockSkewRefusedTotal, count);
        }
    }

    public void onFramePublished(long buildNanos) {
        framesPublishedTotal = add(framesPublishedTotal, 1);
        lastFrameBuildNanos = buildNanos;
    }

    /** Makes the open microsecond window the reported one and starts a new open window. */
    public void rollWindow() {
        reported.copyFrom(open);
        open.clear();
    }

    // --- getters ---

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

    /** Microseconds per tick at quantile {@code q} over the last completed window. */
    public long cycleMicrosQuantile(double q) {
        return reported.quantile(q);
    }

    /** The largest microseconds-per-tick value of the last completed window. */
    public long cycleMicrosMax() {
        return reported.max();
    }

    /** How many ticks the last completed window holds; 0 before the first {@link #rollWindow()}. */
    public long windowTicks() {
        return reported.count();
    }
}
