package io.github.ldogg123.gregscope.history;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The single daemon I/O thread of design-v0.2 §8.4. One of the two I/O adapters design-v0.2 §2 lists beside the pure
 * history model (so it is listed in {@code PureSourcesTest.IMPURE}), and the one shipped class allowed to
 * name {@code java.lang.Thread} (the documented exception to §1.4 "no threads"; {@code ShippedClassesTest} keeps the
 * lift to this class and checks it is not vacuous).
 *
 * <p>
 * <b>Why a thread at all.</b> The server thread must never touch the disk: a 64-byte slot write per sensor per minute
 * is small, but a stalled disk would otherwise stall the tick. The server thread only serializes bytes and hands them
 * over; {@code NioFileStore} asserts under {@code -ea} that nothing else does file I/O.
 *
 * <p>
 * <b>The queue is bounded</b> ({@code history.ioQueueCapacity}, 4,096 by default) and a task that does not fit is
 * <em>dropped</em>, counted, and reported at most once per 10 minutes. Blocking the server thread would be worse than
 * losing a minute of history: the RAM rings stay correct either way, and a dropped slot simply reads as a gap after a
 * restart.
 *
 * <p>
 * <b>Loads are asynchronous.</b> {@link #queueLoad} reads a history file on this thread and parks the result in a
 * {@link ConcurrentLinkedQueue} that the server thread drains with {@link #pollLoaded()}. A file that is not
 * {@link HistoryFileCodec.Status#OK} is renamed aside here, and the result says so, so the caller can start a new
 * file.
 *
 * <p>
 * <b>Stopping</b> queues a flush, then a poison pill, then joins for at most {@value #STOP_TIMEOUT_MILLIS} ms and
 * warns if that is not enough. The thread is a daemon, so even a hung write cannot keep the JVM - or the Horizon-QA
 * CI step - alive. A worker that outlived its {@link #stop()} is <em>not</em> simply forgotten: it still owns the
 * save root's fixed temporary file names, so the next server run calls {@link #terminate} on it before starting a
 * thread of its own over the same folder.
 *
 * <p>
 * <b>A task that throws still answers.</b> The I/O thread logs and carries on, but a failed load produces a
 * {@link LoadResult#failed()} result and a failed file write a {@link WriteFailure}, both drained on the server
 * thread. Without that the sender would wait for a result that never comes (a sensor stuck "loading history" for the
 * rest of the run) or go on writing slots into a file that was never created.
 */
public final class HistoryIo {

    public static final String THREAD_NAME = "GregScope-IO";
    /** Design-v0.2 §8.4: {@code awaitTermination(10 s)} at shutdown. */
    public static final long STOP_TIMEOUT_MILLIS = 10_000L;
    /** Design-v0.2 §8.4: a dropped task is reported at most this often. */
    public static final long DROP_WARN_INTERVAL_NANOS = 600L * 1_000_000_000L;
    /** How long an enqueue that must not be dropped (flush, poison) waits for room. */
    public static final long CONTROL_OFFER_MILLIS = 1_000L;

    /** True while the calling thread is the GregScope I/O thread; what {@code NioFileStore} asserts. */
    public static boolean onIoThread() {
        return THREAD_NAME.equals(
            Thread.currentThread()
                .getName());
    }

    /** What a {@link HistoryIo#queueLoad} produced; drained on the server thread with {@link #pollLoaded()}. */
    public static final class LoadResult {

        private final UUID id;
        private final byte[] file;
        private final HistoryFileCodec.Status status;
        private final boolean failed;

        LoadResult(UUID id, byte[] file, HistoryFileCodec.Status status) {
            this(id, file, status, false);
        }

        LoadResult(UUID id, byte[] file, HistoryFileCodec.Status status, boolean failed) {
            this.id = id;
            this.file = file;
            this.status = status;
            this.failed = failed;
        }

        public UUID id() {
            return id;
        }

        /**
         * {@link HistoryFileCodec.Status#OK} only if a whole, checked, matching file was read. Meaningless when
         * {@link #failed()}: nothing was learned about the file then.
         */
        public HistoryFileCodec.Status status() {
            return status;
        }

        /**
         * The read itself threw (the file could not be opened, or the rename that should have followed failed). The
         * file on disk is untouched and nothing is known about it, so this must <b>not</b> be treated as
         * {@link #absent()}: writing a fresh image over a file that may be whole would destroy history. The caller
         * asks again later.
         */
        public boolean failed() {
            return failed;
        }

        /** True when there was no file at all: nothing was renamed and nothing is wrong. */
        public boolean absent() {
            return !failed && file == null && status == HistoryFileCodec.Status.OK;
        }

        /** The whole {@value HistoryFileCodec#FILE_BYTES} B file, or null when it was absent or unusable. */
        public byte[] file() {
            return !failed && status == HistoryFileCodec.Status.OK ? file : null;
        }

        @Override
        public String toString() {
            return "LoadResult{" + id + " " + (failed ? "failed" : status + (absent() ? " absent" : "")) + "}";
        }
    }

    /**
     * A {@code CreateFile} or {@code WriteSlot} that threw on the I/O thread, drained on the server thread with
     * {@link #drainWriteFailures()}.
     *
     * <p>
     * Without it the sender would go on believing the file is there: a failed {@code CreateFile} leaves the sensor
     * marked present, and every later minute then queues a slot write into a file that does not exist - one
     * unthrottled error per sensor per minute, for ever, with no way back.
     */
    public static final class WriteFailure {

        private final UUID id;
        private final boolean create;

        WriteFailure(UUID id, boolean create) {
            this.id = id;
            this.create = create;
        }

        /** The sensor whose history file the task was about. */
        public UUID id() {
            return id;
        }

        /** True for a {@code CreateFile}, false for a {@code WriteSlot}. */
        public boolean create() {
            return create;
        }

        @Override
        public String toString() {
            return "WriteFailure{" + id + (create ? " create" : " slot") + "}";
        }
    }

    private enum Kind {
        CREATE,
        WRITE_SLOT,
        DELETE,
        WRITE_REGISTRY,
        QUARANTINE_REGISTRY,
        LOAD,
        FLUSH,
        POISON
    }

    /** One queued unit of work. Immutable; the byte arrays are handed over and never touched again by the sender. */
    private static final class Task {

        final Kind kind;
        final UUID id;
        final int index;
        final byte[] bytes;
        final String text;
        final CountDownLatch done;

        Task(Kind kind, UUID id, int index, byte[] bytes, CountDownLatch done) {
            this(kind, id, index, bytes, null, done);
        }

        Task(Kind kind, UUID id, int index, byte[] bytes, String text, CountDownLatch done) {
            this.kind = kind;
            this.id = id;
            this.index = index;
            this.bytes = bytes;
            this.text = text;
            this.done = done;
        }

        String describe() {
            return kind + (id == null ? "" : " " + id)
                + (kind == Kind.WRITE_SLOT ? " slot " + index : "")
                + (text == null ? "" : " " + text);
        }
    }

    private final FileStore store;
    private final int capacity;
    private final BlockingQueue<Task> queue;
    private final ConcurrentLinkedQueue<LoadResult> loaded = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<WriteFailure> writeFailures = new ConcurrentLinkedQueue<>();
    private final LongSupplier nanos;

    private volatile IoListener listener = IoListener.NONE;
    private volatile Thread thread;
    private volatile boolean stopping;
    private volatile long droppedTotal;
    private volatile long errorsTotal;
    private volatile long completedTotal;
    private long lastDropWarnNanos;
    private boolean dropWarnArmed = true;

    public HistoryIo(FileStore store, int queueCapacity) {
        this(store, queueCapacity, System::nanoTime);
    }

    /** @param nanos the clock the 10-minute drop-warning window uses; a test supplies its own */
    public HistoryIo(FileStore store, int queueCapacity, LongSupplier nanos) {
        if (store == null || nanos == null) {
            throw new IllegalArgumentException("store and nanos are required");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity " + queueCapacity);
        }
        this.store = store;
        this.capacity = queueCapacity;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.nanos = nanos;
    }

    public void setListener(IoListener replacement) {
        this.listener = replacement == null ? IoListener.NONE : replacement;
    }

    /** Starts the daemon thread. Calling it twice is a mistake, not a no-op. */
    public void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        Thread worker = new Thread(new Runner(), THREAD_NAME);
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    public boolean isRunning() {
        Thread worker = thread;
        return worker != null && worker.isAlive();
    }

    public boolean isDaemon() {
        Thread worker = thread;
        return worker != null && worker.isDaemon();
    }

    public int queueCapacity() {
        return capacity;
    }

    public int queued() {
        return queue.size();
    }

    public long droppedTotal() {
        return droppedTotal;
    }

    public long errorsTotal() {
        return errorsTotal;
    }

    /** Tasks the I/O thread finished, poison and flush included; a test can wait on it without sleeping blindly. */
    public long completedTotal() {
        return completedTotal;
    }

    // --- enqueue (server thread) ---

    /** Creates {@code history/<uuid>.gsh} from a whole file image (design-v0.2 §8.2). */
    public boolean queueCreate(UUID id, byte[] file) {
        return offer(new Task(Kind.CREATE, id, 0, file, null));
    }

    /** Writes one closed minute into an existing file. */
    public boolean queueSlot(UUID id, int index, byte[] slot) {
        return offer(new Task(Kind.WRITE_SLOT, id, index, slot, null));
    }

    public boolean queueDelete(UUID id) {
        return offer(new Task(Kind.DELETE, id, 0, null, null));
    }

    /** Replaces {@code registry.dat} with these gzipped NBT bytes (design-v0.2 §8.3). */
    public boolean queueRegistry(byte[] bytes) {
        return offer(new Task(Kind.WRITE_REGISTRY, null, 0, bytes, null));
    }

    /**
     * Renames {@code registry.dat} aside (design-v0.2 §8.3 {@code v > 1}). Renaming is file I/O like any other, so
     * GS-110 queues it here rather than doing it on the server thread, which {@code NioFileStore} would refuse under
     * {@code -ea}. §8.4's task list does not name it; see the GS-110 implementation notes.
     */
    public boolean queueQuarantineRegistry(String suffix) {
        return offer(new Task(Kind.QUARANTINE_REGISTRY, null, 0, null, suffix, null));
    }

    /** Reads a history file; the outcome shows up in {@link #pollLoaded()} (design-v0.2 §8.4). */
    public boolean queueLoad(UUID id) {
        return offer(new Task(Kind.LOAD, id, 0, null, null));
    }

    /** The next finished load, or null. Drained on the server thread. */
    public LoadResult pollLoaded() {
        return loaded.poll();
    }

    /** Every finished load, oldest first; the list is fresh and may be empty. */
    public List<LoadResult> drainLoaded() {
        List<LoadResult> out = new ArrayList<>();
        for (LoadResult result = loaded.poll(); result != null; result = loaded.poll()) {
            out.add(result);
        }
        return out;
    }

    /**
     * Every file write that threw since the last call, oldest first. Drained on the server thread, so the sender can
     * stop believing in a file that was never written.
     */
    public List<WriteFailure> drainWriteFailures() {
        List<WriteFailure> out = new ArrayList<>();
        for (WriteFailure failure = writeFailures.poll(); failure != null; failure = writeFailures.poll()) {
            out.add(failure);
        }
        return out;
    }

    /**
     * Waits until everything queued so far has been written (design-v0.2 §8.4 {@code Flush}).
     *
     * @return true if the queue drained within {@code timeoutMillis}
     */
    public boolean flush(long timeoutMillis) {
        Thread worker = thread;
        if (worker == null || !worker.isAlive()) {
            return true;
        }
        CountDownLatch done = new CountDownLatch(1);
        if (!offerControl(new Task(Kind.FLUSH, null, 0, null, done))) {
            return false;
        }
        try {
            return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return false;
        }
    }

    /**
     * Flushes, poisons the queue and joins for at most {@value #STOP_TIMEOUT_MILLIS} ms (design-v0.2 §8.4). After it
     * returns, nothing more is accepted.
     *
     * @return true if the thread finished within the timeout; false means a WARN is due and the daemon is abandoned
     */
    public boolean stop() {
        Thread worker = thread;
        if (worker == null) {
            stopping = true;
            return true;
        }
        long deadline = nanos.getAsLong() + STOP_TIMEOUT_MILLIS * 1_000_000L;
        boolean flushed = flush(STOP_TIMEOUT_MILLIS);
        stopping = true;
        offerControl(new Task(Kind.POISON, null, 0, null, null));
        long remaining = (deadline - nanos.getAsLong()) / 1_000_000L;
        try {
            worker.join(Math.max(1L, remaining));
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return false;
        }
        return flushed && !worker.isAlive();
    }

    /**
     * Last resort after a {@link #stop()} that timed out: interrupt the worker and join it for at most
     * {@code timeoutMillis}. A task blocked in {@code java.nio} file I/O ends with a
     * {@code ClosedByInterruptException} that {@link #perform} reports like any other failure, and the loop leaves on
     * the next {@code take()}, so nothing half-written is ever moved into place.
     *
     * <p>
     * It exists because an abandoned worker still owns the save root's fixed {@code .tmp} names. A second run over
     * the same folder must not start a second thread beside it (design-v0.2 §8.3 replaces {@code registry.dat}
     * through one temporary file), so the caller either gets rid of this one or keeps the next run in RAM.
     *
     * @return true if the thread is gone
     */
    public boolean terminate(long timeoutMillis) {
        stopping = true;
        Thread worker = thread;
        if (worker == null) {
            return true;
        }
        worker.interrupt();
        try {
            worker.join(Math.max(1L, timeoutMillis));
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return false;
        }
        return !worker.isAlive();
    }

    private boolean offer(Task task) {
        if (stopping) {
            return false;
        }
        if (queue.offer(task)) {
            listener.onQueued();
            return true;
        }
        drop();
        return false;
    }

    /** Flush and poison must not be dropped, so they wait a bounded time for room. */
    private boolean offerControl(Task task) {
        if (queue.offer(task)) {
            return true;
        }
        try {
            return queue.offer(task, CONTROL_OFFER_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return false;
        }
    }

    private void drop() {
        droppedTotal++;
        listener.onDropped();
        long now = nanos.getAsLong();
        if (dropWarnArmed || now - lastDropWarnNanos >= DROP_WARN_INTERVAL_NANOS) {
            dropWarnArmed = false;
            lastDropWarnNanos = now;
            listener.onDropWarning(droppedTotal, capacity);
        }
    }

    // --- the thread ---

    private final class Runner implements Runnable {

        @Override
        public void run() {
            while (true) {
                Task task;
                try {
                    task = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                    return;
                }
                if (task.kind == Kind.POISON) {
                    completedTotal++;
                    return;
                }
                perform(task);
                completedTotal++;
            }
        }
    }

    private void perform(Task task) {
        try {
            switch (task.kind) {
                case CREATE:
                    store.createHistory(task.id, task.bytes);
                    break;
                case WRITE_SLOT:
                    store.writeSlot(task.id, task.index, task.bytes);
                    break;
                case DELETE:
                    store.deleteHistory(task.id);
                    break;
                case WRITE_REGISTRY:
                    store.writeRegistry(task.bytes);
                    break;
                case QUARANTINE_REGISTRY:
                    store.quarantineRegistry(task.text);
                    break;
                case LOAD:
                    load(task.id);
                    break;
                case FLUSH:
                    break;
                default:
                    throw new IllegalStateException("unhandled task " + task.kind);
            }
        } catch (Throwable error) {
            errorsTotal++;
            report(task);
            listener.onError(task.describe(), error);
        } finally {
            if (task.done != null) {
                task.done.countDown();
            }
        }
    }

    /**
     * A task that threw still has to answer the server thread, or the sender waits for ever. A failed load becomes a
     * {@link LoadResult#failed()} result - never an absent one, because the file may well be whole - and a failed
     * file write becomes a {@link WriteFailure}. The other kinds need no answer: a failed delete leaves a file the
     * next expiry takes again, and the registry is rewritten in full on the next save.
     */
    private void report(Task task) {
        switch (task.kind) {
            case LOAD:
                loaded.add(new LoadResult(task.id, null, HistoryFileCodec.Status.OK, true));
                break;
            case CREATE:
            case WRITE_SLOT:
                writeFailures.add(new WriteFailure(task.id, task.kind == Kind.CREATE));
                break;
            default:
                break;
        }
    }

    /** Design-v0.2 §8.2 header rules: a file that is not OK is renamed aside, never deleted, and a new one is made. */
    private void load(UUID id) throws Exception {
        byte[] file = store.readHistory(id);
        if (file == null) {
            loaded.add(new LoadResult(id, null, HistoryFileCodec.Status.OK));
            return;
        }
        HistoryFileCodec.Status status = HistoryFileCodec.inspect(file, id);
        if (status != HistoryFileCodec.Status.OK) {
            int version = file.length >= HistoryFileCodec.HEADER_BYTES ? HistoryFileCodec.decodeHeader(file, 0)
                .formatVersion() : 0;
            store.quarantineHistory(id, status.suffix(version, System.currentTimeMillis()));
            loaded.add(new LoadResult(id, null, status));
            return;
        }
        loaded.add(new LoadResult(id, file, HistoryFileCodec.Status.OK));
    }

    @Override
    public String toString() {
        return "HistoryIo{queued " + queue
            .size() + "/" + capacity + ", dropped " + droppedTotal + ", errors " + errorsTotal + "}";
    }
}
