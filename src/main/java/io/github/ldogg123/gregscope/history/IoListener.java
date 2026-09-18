package io.github.ldogg123.gregscope.history;

/**
 * What the I/O thread reports to the rest of the mod (design-v0.2 §7.6 {@code ioQueuedTotal}, {@code ioDroppedTotal},
 * {@code ioErrorsTotal}). [pure]
 *
 * <p>
 * A seam rather than a direct call into {@code SamplerStats} and the mod logger, for two reasons: {@code HistoryIo}
 * stays unit-testable in a plain JVM, and the counters keep exactly one owner (the sampler's stats, written on the
 * server thread) instead of being incremented from two threads. The implementation GS-110 installs hands the numbers
 * to {@code SamplerStats}.
 *
 * <p>
 * {@link #onQueued()} and {@link #onDropped()} are called on the thread that enqueues (the server thread);
 * {@link #onError} is called on the I/O thread.
 */
public interface IoListener {

    /** Does nothing; the default until something is installed. */
    IoListener NONE = new IoListener() {

        @Override
        public void onQueued() {}

        @Override
        public void onDropped() {}

        @Override
        public void onDropWarning(long droppedTotal, int queueCapacity) {}

        @Override
        public void onError(String what, Throwable error) {}
    };

    /** A task was accepted by the queue. */
    void onQueued();

    /** The queue was full, so the task was dropped (design-v0.2 §8.4). */
    void onDropped();

    /**
     * Time to tell the operator about drops: design-v0.2 §8.4 wants a WARN at most once per 10 minutes, and
     * {@code HistoryIo} owns that window so the rate limit is testable.
     *
     * @param droppedTotal  drops since the I/O thread was created
     * @param queueCapacity {@code history.ioQueueCapacity}
     */
    void onDropWarning(long droppedTotal, int queueCapacity);

    /**
     * A task threw. The I/O thread logs and carries on; one bad file must not stop the queue.
     *
     * @param what a short description of the task, for the log line
     */
    void onError(String what, Throwable error);
}
