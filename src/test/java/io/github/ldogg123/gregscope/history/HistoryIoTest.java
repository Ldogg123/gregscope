package io.github.ldogg123.gregscope.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** GS-109: the single daemon I/O thread of design-v0.2 section 8.4 - bounded queue, order, flush, join, drops. */
class HistoryIoTest {

    private static final UUID ID = new UUID(0x3FA2C1D0A1B24C3DL, -0x5E6F708192A3B4C5L);
    private static final int DEFAULT_CAPACITY = 4096;

    /** An in-memory {@link FileStore} that records what it was asked to do, in order. */
    private static final class RecordingStore implements FileStore {

        private final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        private final Map<UUID, byte[]> files = new HashMap<>();
        private byte[] registry;
        private byte[] backup;
        /** Held open by the test so the I/O thread can be parked mid-task. */
        private CountDownLatch gate;
        private IOException failWith;

        synchronized byte[] file(UUID id) {
            return files.get(id);
        }

        private void enter(String call) throws IOException {
            calls.add(call);
            CountDownLatch latch = gate;
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
            }
            if (failWith != null) {
                throw failWith;
            }
        }

        @Override
        public synchronized byte[] readHistory(UUID id) throws IOException {
            enter("read " + id);
            return files.get(id);
        }

        @Override
        public synchronized boolean historyExists(UUID id) {
            return files.containsKey(id);
        }

        @Override
        public synchronized void createHistory(UUID id, byte[] file) throws IOException {
            enter("create " + id);
            files.put(id, file.clone());
        }

        @Override
        public synchronized void writeSlot(UUID id, int index, byte[] slot) throws IOException {
            enter("slot " + index);
            byte[] file = files.get(id);
            if (file == null) {
                throw new IOException("no file");
            }
            System.arraycopy(slot, 0, file, HistoryFileCodec.slotOffset(index), slot.length);
        }

        @Override
        public synchronized void deleteHistory(UUID id) throws IOException {
            enter("delete " + id);
            files.remove(id);
        }

        @Override
        public synchronized void quarantineHistory(UUID id, String suffix) throws IOException {
            calls.add("quarantine " + suffix);
            files.remove(id);
        }

        @Override
        public synchronized byte[] readRegistry() {
            return registry;
        }

        @Override
        public synchronized byte[] readRegistryBackup() {
            return backup;
        }

        @Override
        public synchronized void writeRegistry(byte[] bytes) throws IOException {
            enter("registry");
            backup = registry;
            registry = bytes.clone();
        }

        @Override
        public synchronized void quarantineRegistry(String suffix) {
            calls.add("quarantineRegistry " + suffix);
            registry = null;
        }
    }

    private static final class CountingListener implements IoListener {

        int queued;
        int dropped;
        int errors;
        final List<Long> warnings = Collections.synchronizedList(new ArrayList<Long>());

        @Override
        public void onQueued() {
            queued++;
        }

        @Override
        public void onDropped() {
            dropped++;
        }

        @Override
        public void onDropWarning(long droppedTotal, int queueCapacity) {
            warnings.add(droppedTotal);
        }

        @Override
        public void onError(String what, Throwable error) {
            errors++;
        }
    }

    /**
     * Design-v0.2 section 14: with the default capacity of 4,096, the 4,097th task is dropped and counted. The thread
     * is deliberately not started, so the queue cannot drain and the boundary is exact rather than a race.
     */
    @Test
    void theTaskAfterTheLastOneThatFitsIsDroppedAndCounted() {
        RecordingStore store = new RecordingStore();
        CountingListener listener = new CountingListener();
        HistoryIo io = new HistoryIo(store, DEFAULT_CAPACITY, () -> 0L);
        io.setListener(listener);
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            assertTrue(io.queueSlot(ID, 0, new byte[64]), "task " + i + " did not fit");
        }
        assertEquals(DEFAULT_CAPACITY, io.queued());
        assertEquals(DEFAULT_CAPACITY, listener.queued);
        assertEquals(0, io.droppedTotal());

        assertFalse(io.queueSlot(ID, 0, new byte[64]), "the 4097th task was accepted");
        assertEquals(1, io.droppedTotal());
        assertEquals(1, listener.dropped);
        assertEquals(DEFAULT_CAPACITY, listener.queued);
        assertEquals(DEFAULT_CAPACITY, io.queued());
        assertEquals(DEFAULT_CAPACITY, io.queueCapacity());
    }

    /** Design-v0.2 section 8.4: a drop is reported at most once per 10 minutes, whatever the drop rate. */
    @Test
    void dropsAreReportedAtMostOncePerTenMinutes() {
        RecordingStore store = new RecordingStore();
        CountingListener listener = new CountingListener();
        long[] now = { 0L };
        HistoryIo io = new HistoryIo(store, 1, () -> now[0]);
        io.setListener(listener);
        assertTrue(io.queueDelete(ID));
        for (int i = 0; i < 100; i++) {
            assertFalse(io.queueDelete(ID));
        }
        assertEquals(100, io.droppedTotal());
        assertEquals(100, listener.dropped);
        assertEquals(1, listener.warnings.size(), "more than one warning inside the window: " + listener.warnings);
        assertEquals(
            1L,
            listener.warnings.get(0)
                .longValue(),
            "the first drop is reported at once");

        now[0] = HistoryIo.DROP_WARN_INTERVAL_NANOS - 1;
        assertFalse(io.queueDelete(ID));
        assertEquals(1, listener.warnings.size());

        now[0] = HistoryIo.DROP_WARN_INTERVAL_NANOS;
        assertFalse(io.queueDelete(ID));
        assertEquals(2, listener.warnings.size());
        assertEquals(
            102L,
            listener.warnings.get(1)
                .longValue());
    }

    @Test
    @Timeout(30)
    void tasksRunInTheOrderTheyWereQueuedAndFlushWaitsForThem() throws IOException {
        RecordingStore store = new RecordingStore();
        CountingListener listener = new CountingListener();
        HistoryIo io = new HistoryIo(store, 64);
        io.setListener(listener);
        io.start();
        try {
            assertTrue(io.isRunning());
            assertTrue(io.isDaemon(), "the I/O thread must be a daemon");
            assertTrue(io.queueCreate(ID, HistoryFileCodec.newFile(ID, 0, 1L)));
            byte[] slot = new byte[64];
            Arrays.fill(slot, (byte) 0x11);
            assertTrue(io.queueSlot(ID, 3, slot));
            assertTrue(io.queueRegistry(new byte[] { 1, 2, 3 }));
            assertTrue(io.queueDelete(ID));
            assertTrue(io.flush(HistoryIo.STOP_TIMEOUT_MILLIS), "the flush timed out");

            assertEquals(Arrays.asList("create " + ID, "slot 3", "registry", "delete " + ID), store.calls);
            assertArrayEquals(new byte[] { 1, 2, 3 }, store.readRegistry());
            assertNull(store.file(ID));
            assertEquals(4, listener.queued);
            assertEquals(0, listener.dropped);
            assertEquals(0, listener.errors);
            assertEquals(0, io.errorsTotal());
        } finally {
            assertTrue(io.stop(), "stop did not finish inside the timeout");
        }
        assertFalse(io.isRunning(), "the thread is still alive after stop()");
        assertFalse(io.queueDelete(ID), "tasks are still accepted after stop()");
    }

    /** A load reads the file on the I/O thread and parks the result for the server thread (section 8.4). */
    @Test
    @Timeout(30)
    void aLoadParksItsResultForTheServerThread() {
        RecordingStore store = new RecordingStore();
        HistoryIo io = new HistoryIo(store, 16);
        io.start();
        try {
            byte[] file = HistoryFileCodec.newFile(ID, 0, 1_759_000_000L);
            io.queueCreate(ID, file);
            io.queueLoad(ID);
            assertTrue(io.flush(HistoryIo.STOP_TIMEOUT_MILLIS));
            List<HistoryIo.LoadResult> results = io.drainLoaded();
            assertEquals(1, results.size());
            HistoryIo.LoadResult result = results.get(0);
            assertEquals(ID, result.id());
            assertEquals(HistoryFileCodec.Status.OK, result.status());
            assertFalse(result.absent());
            assertArrayEquals(file, result.file());
            assertNull(io.pollLoaded());

            // A sensor with no file yet: absent, nothing renamed.
            UUID fresh = new UUID(7L, 9L);
            io.queueLoad(fresh);
            assertTrue(io.flush(HistoryIo.STOP_TIMEOUT_MILLIS));
            HistoryIo.LoadResult missing = io.pollLoaded();
            assertNotNull(missing);
            assertTrue(missing.absent());
            assertNull(missing.file());
            assertFalse(store.calls.contains("quarantine .mismatch"));
        } finally {
            io.stop();
        }
    }

    /** A file that fails the section 8.2 header rules is renamed aside, never deleted, and reported as such. */
    @Test
    @Timeout(30)
    void aCorruptFileIsQuarantinedAndTheLoadSaysSo() {
        RecordingStore store = new RecordingStore();
        HistoryIo io = new HistoryIo(store, 16);
        io.start();
        try {
            byte[] broken = HistoryFileCodec.newFile(ID, 0, 1L);
            broken[0] = 'X';
            io.queueCreate(ID, broken);
            io.queueLoad(ID);
            assertTrue(io.flush(HistoryIo.STOP_TIMEOUT_MILLIS));
            HistoryIo.LoadResult result = io.pollLoaded();
            assertNotNull(result);
            assertEquals(HistoryFileCodec.Status.CORRUPT, result.status());
            assertNull(result.file());
            assertFalse(result.absent());
            boolean quarantined = false;
            for (String call : store.calls) {
                quarantined |= call.startsWith("quarantine .corrupt-");
            }
            assertTrue(quarantined, store.calls.toString());

            // Another sensor's file is a mismatch, not a corruption.
            UUID other = new UUID(3L, 4L);
            io.queueCreate(other, HistoryFileCodec.newFile(ID, 0, 1L));
            io.queueLoad(other);
            assertTrue(io.flush(HistoryIo.STOP_TIMEOUT_MILLIS));
            assertEquals(
                HistoryFileCodec.Status.MISMATCH,
                io.pollLoaded()
                    .status());
            assertTrue(store.calls.contains("quarantine .mismatch"), store.calls.toString());
        } finally {
            io.stop();
        }
    }

    /** One bad task must not stop the queue: it is counted and reported, and the next task still runs. */
    @Test
    @Timeout(30)
    void aFailingTaskIsCountedAndTheThreadCarriesOn() {
        RecordingStore store = new RecordingStore();
        CountingListener listener = new CountingListener();
        HistoryIo io = new HistoryIo(store, 16);
        io.setListener(listener);
        io.start();
        try {
            store.failWith = new IOException("disk on fire");
            io.queueDelete(ID);
            assertTrue(io.flush(HistoryIo.STOP_TIMEOUT_MILLIS));
            assertEquals(1, io.errorsTotal());
            assertEquals(1, listener.errors);
            store.failWith = null;
            io.queueCreate(ID, HistoryFileCodec.newFile(ID, 0, 1L));
            assertTrue(io.flush(HistoryIo.STOP_TIMEOUT_MILLIS));
            assertNotNull(store.file(ID));
            assertEquals(1, io.errorsTotal());
        } finally {
            io.stop();
        }
    }

    /**
     * A read that throws must still answer the server thread. Nothing is known about the file, so the result is
     * neither {@code absent()} nor OK: treating it as absent would let the caller write a fresh image over a file
     * that may be whole, and producing nothing at all would leave the sensor waiting for a result for ever.
     */
    @Test
    @Timeout(30)
    void aFailedLoadStillProducesAResultThatIsNotAbsent() {
        RecordingStore store = new RecordingStore();
        CountingListener listener = new CountingListener();
        HistoryIo io = new HistoryIo(store, 16);
        io.setListener(listener);
        io.start();
        try {
            store.failWith = new IOException("the file is locked");
            assertTrue(io.queueLoad(ID));
            assertTrue(io.flush(HistoryIo.STOP_TIMEOUT_MILLIS));
            HistoryIo.LoadResult result = io.pollLoaded();
            assertNotNull(result, "a load that threw produced no result at all");
            assertEquals(ID, result.id());
            assertTrue(result.failed(), "the result must say the read failed");
            assertFalse(result.absent(), "a failed read must never look like a missing file");
            assertNull(result.file(), "no bytes were read");
            assertEquals(1, io.errorsTotal());
            assertEquals(1, listener.errors);
            assertFalse(store.calls.contains("quarantine .mismatch"), "nothing may be renamed: " + store.calls);

            // The queue carries on, and the same sensor reads back normally once the disk answers again.
            store.failWith = null;
            byte[] file = HistoryFileCodec.newFile(ID, 0, 1L);
            assertTrue(io.queueCreate(ID, file));
            assertTrue(io.queueLoad(ID));
            assertTrue(io.flush(HistoryIo.STOP_TIMEOUT_MILLIS));
            HistoryIo.LoadResult second = io.pollLoaded();
            assertNotNull(second);
            assertFalse(second.failed());
            assertArrayEquals(file, second.file());
        } finally {
            io.stop();
        }
    }

    /**
     * A create or slot write that throws is reported too, so the sender stops believing the file is there. A delete
     * that throws needs no answer: the file simply stays, and the next expiry takes it.
     */
    @Test
    @Timeout(30)
    void aFailedFileWriteIsReportedToTheServerThread() {
        RecordingStore store = new RecordingStore();
        HistoryIo io = new HistoryIo(store, 16);
        io.start();
        try {
            store.failWith = new IOException("no space left on device");
            assertTrue(io.queueCreate(ID, HistoryFileCodec.newFile(ID, 0, 1L)));
            assertTrue(io.queueSlot(ID, 7, new byte[64]));
            assertTrue(io.queueDelete(ID));
            assertTrue(io.queueRegistry(new byte[] { 1 }));
            assertTrue(io.flush(HistoryIo.STOP_TIMEOUT_MILLIS));
            assertEquals(4, io.errorsTotal());

            List<HistoryIo.WriteFailure> failures = io.drainWriteFailures();
            assertEquals(2, failures.size(), "only the two file writes have to be answered: " + failures);
            assertEquals(
                ID,
                failures.get(0)
                    .id());
            assertTrue(
                failures.get(0)
                    .create(),
                "the create comes first");
            assertFalse(
                failures.get(1)
                    .create(),
                "the slot write comes second");
            assertTrue(
                io.drainWriteFailures()
                    .isEmpty(),
                "draining twice must not repeat them");
            assertNull(io.pollLoaded(), "a write failure is not a load result");
        } finally {
            io.stop();
        }
    }

    /**
     * A worker that outlived its {@link HistoryIo#stop()} still owns the save root's temporary files, so the next
     * run has to be able to get rid of it. {@code terminate} interrupts it and joins.
     */
    @Test
    @Timeout(60)
    void anAbandonedThreadCanBeTerminated() {
        RecordingStore store = new RecordingStore();
        store.gate = new CountDownLatch(1);
        HistoryIo io = new HistoryIo(store, 16);
        io.start();
        io.queueDelete(ID);
        assertFalse(io.flush(200L), "the gate must hold the task");
        assertTrue(io.isRunning(), "the worker is parked, not gone");
        assertTrue(io.terminate(HistoryIo.STOP_TIMEOUT_MILLIS), "the interrupted worker did not go");
        assertFalse(io.isRunning(), "the thread is still alive after terminate()");
        assertFalse(io.queueDelete(ID), "a terminated thread must accept nothing more");
        assertTrue(io.terminate(10L), "terminating twice is not an error");
        store.gate.countDown();
    }

    /**
     * The design-v0.2 section 8.4 promise the Horizon-QA CI step depends on: the thread is a daemon, and a stop that
     * cannot finish gives up after {@value HistoryIo#STOP_TIMEOUT_MILLIS} ms instead of hanging. A task parked on a
     * gate stands in for a stalled disk.
     */
    @Test
    @Timeout(60)
    void aStuckTaskDoesNotHangTheStopAndTheThreadIsADaemon() {
        RecordingStore store = new RecordingStore();
        store.gate = new CountDownLatch(1);
        HistoryIo io = new HistoryIo(store, 16);
        io.start();
        assertTrue(io.isDaemon());
        io.queueDelete(ID);
        long started = System.nanoTime();
        assertFalse(io.flush(500L), "a flush behind a stuck task must time out");
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsed < HistoryIo.STOP_TIMEOUT_MILLIS, "the flush waited " + elapsed + " ms");
        store.gate.countDown();
        assertTrue(io.stop());
    }

    @Test
    void theConstructorRefusesNonsense() {
        RecordingStore store = new RecordingStore();
        assertThrows(IllegalArgumentException.class, () -> new HistoryIo(null, 16));
        assertThrows(IllegalArgumentException.class, () -> new HistoryIo(store, 0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryIo(store, 16, null));
        HistoryIo io = new HistoryIo(store, 16);
        assertFalse(io.isRunning());
        assertFalse(io.isDaemon());
        assertTrue(io.stop(), "stopping an I/O thread that never started is not an error");
        io.start();
        assertThrows(IllegalStateException.class, io::start);
        io.stop();
    }

    @Test
    void theIoThreadNameIsWhatTheFileStoreAsserts() {
        assertEquals("GregScope-IO", HistoryIo.THREAD_NAME);
        assertFalse(HistoryIo.onIoThread(), "the JUnit thread must not pass for the I/O thread");
    }
}
