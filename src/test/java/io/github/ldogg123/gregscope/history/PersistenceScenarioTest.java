package io.github.ldogg123.gregscope.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.RemovalCause;
import io.github.ldogg123.gregscope.registry.RunsTable;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistryCore;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.FakeClock;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-110, design-v0.2 section 14: the persistence scenario over an in-memory {@link FileStore}. One server run
 * records five minutes and stops; a second run in the same JVM loads the files back and gets exactly the same
 * history; the downtime between the two runs is attributed {@code server_offline} by section 7.5 rule 3; and a run
 * that never stopped cleanly is closed at the {@code saved} it was written with (section 8.3).
 *
 * <p>
 * Everything here is the shipped code: the real {@link SensorRegistryCore} state machine, the real
 * {@link HistoryPersistence} service, the real {@link HistoryIo} daemon thread and the real {@link HistoryFileCodec}
 * geometry. Only the disk is faked, which is exactly why {@code FileStore} is a seam.
 *
 * <p>
 * {@code registry.dat} itself is NBT and cannot be encoded without Minecraft, so the runs table is carried from one
 * run to the next as the {@code {start, stop}} rows the codec stores, through {@link RunsTable#loaded} - the same
 * call {@code RegistryNbtCodec.decode} makes. What the NBT codec adds is covered in {@code RegistryCodecTests}.
 */
class PersistenceScenarioTest {

    /** A minute boundary, so a whole minute is exactly 60 samples. */
    private static final long START = 29_333_400L * 60L;
    private static final int MINUTES = 5;
    private static final int SAMPLES_PER_MINUTE = 60;
    /** Downtime between the two runs, in minutes. */
    private static final int DOWNTIME_MINUTES = 10;
    private static final UUID SENSOR = new UUID(0x6753_1100_0000_0001L, 0x0000_0000_0000_0042L);
    private static final int DIM = 0;
    private static final int X = 120;
    private static final int Y = 64;
    private static final int Z = -30;
    private static final int SIDE = 1;

    /** Enough drain/flush rounds for load -> create -> write to finish, whatever order the I/O thread answers in. */
    private static final int SETTLE_ROUNDS = 6;

    /** Creates that throw before writes work again; under MAX_FILE_FAILURES, so nothing is abandoned. */
    private static final int FAILING_CREATES = 3;

    private MemoryFileStore store;
    private HistoryIo io;
    private FakeClock clock;
    private Settings settings = Settings.DEFAULTS;

    @BeforeEach
    void startIo() {
        store = new MemoryFileStore();
        io = new HistoryIo(store, 4096);
        io.start();
        clock = FakeClock.atEpochSec(START);
    }

    @AfterEach
    void stopIo() {
        io.stop();
    }

    // --- the scenario design-v0.2 section 14 asks for ---

    @Test
    void aSecondRunLoadsBackExactlyWhatTheFirstOneWrote() {
        Run first = newRun();
        SensorEntry live = register(first);
        assertEquals(1, first.history.filesCreated(), "the first run must create the history file");
        assertTrue(store.exists(SENSOR), "no .gsh was written");
        assertEquals(HistoryFileCodec.FILE_BYTES, store.history(SENSOR).length, "history file size");

        assertEquals(MINUTES - 1, recordMinutes(first, live, MINUTES).size(), "minutes closed by a rollover");

        long stopped = START + MINUTES * 60L;
        clock.setEpochMillis(stopped * 1000L);
        // Design-v0.2 section 8.4 at stop: every open accumulator becomes a partial slot before the flush.
        assertEquals(1, first.core.flushOpenMinutes(), "the open minute must close as a partial slot");
        flush();
        List<MinuteSlot> recorded = new ArrayList<>();
        for (int i = 0; i < MINUTES; i++) {
            MinuteSlot slot = live.minutes()
                .slot(MinuteAccumulator.epochMinute(START) + i);
            assertNotNull(slot, "minute " + i + " was not stored in the ring");
            recorded.add(slot);
        }

        // Exactly one slot write per closed minute per sensor (design-v0.2 section 14).
        assertEquals(MINUTES, first.history.minutesClosed(), "minutes closed");
        assertEquals(MINUTES, first.history.slotsWritten(), "one slot write per closed minute");
        assertEquals(0, first.history.tasksDropped(), "nothing may be dropped at this queue depth");
        assertEquals(
            HistoryFileCodec.Status.OK,
            HistoryFileCodec.inspect(store.history(SENSOR), SENSOR),
            "the file this run wrote does not pass its own header rules");

        // --- the restart ---
        long restarted = stopped + DOWNTIME_MINUTES * 60L;
        clock.setEpochMillis(restarted * 1000L);
        Run second = newRun();
        second.core.setRuns(loadedRuns(START, RunsTable.OPEN, stopped));
        second.core.runs()
            .startRun(restarted);
        SensorEntry restored = register(second);
        assertEquals(1, second.history.loadsApplied(), "the load result was not applied");

        assertTrue(restored.historyLoaded(), "the entry must be marked historyLoaded after the merge");
        assertEquals(MINUTES, second.history.slotsMerged(), "slots taken from the file");
        assertEquals(0, second.history.filesCreated(), "an existing file must not be recreated");
        for (MinuteSlot written : recorded) {
            MinuteSlot back = restored.minutes()
                .slot(written.epochMinute());
            assertEquals(written, back, "minute " + written.epochMinute() + " did not survive the restart");
        }
        assertEquals(
            SAMPLES_PER_MINUTE,
            restored.minutes()
                .slot(MinuteAccumulator.epochMinute(START))
                .samples(),
            "a full minute must come back with all 60 samples");
    }

    @Test
    void theGapBetweenTwoRunsIsServerOffline() {
        Run first = newRun();
        SensorEntry live = register(first);
        recordMinutes(first, live, MINUTES);
        long stopped = START + MINUTES * 60L;
        clock.setEpochMillis(stopped * 1000L);
        first.core.flushOpenMinutes();
        flush();

        long restarted = stopped + DOWNTIME_MINUTES * 60L;
        clock.setEpochMillis(restarted * 1000L);
        Run second = newRun();
        second.core.setRuns(loadedRuns(START, RunsTable.OPEN, stopped));
        second.core.runs()
            .startRun(restarted);
        register(second);
        SensorEntry restored = second.core.entry(SENSOR);

        List<GapRanges.Range> gaps = GapRanges.minutes(
            restored.minutes(),
            MinuteAccumulator.epochMinute(START),
            MinuteAccumulator.epochMinute(restarted),
            second.core.gapContext(SENSOR, restarted));
        assertEquals(
            Arrays.asList(new GapRanges.Range(stopped, restarted, GapReason.SERVER_OFFLINE)),
            gaps,
            "the downtime between two runs must read as one server_offline range");
    }

    /**
     * Design-v0.2 section 7.5 rule 3, the other branch: a minute nobody recorded <em>inside</em> a run, for a sensor
     * the registry has had UNLOADED since before it, is {@code chunk_unloaded} - not {@code server_offline} and not
     * {@code unknown}.
     */
    @Test
    void aMissingMinuteInsideARunWithAnUnloadedSensorIsChunkUnloaded() {
        Run run = newRun();
        SensorEntry live = register(run);
        recordMinutes(run, live, 1);
        long unloadedAt = START + 60L;
        clock.setEpochMillis(unloadedAt * 1000L);
        assertTrue(run.core.unloaded(SENSOR, DIM, X, Y, Z, SIDE, GapReason.CHUNK_UNLOADED), "the unload was ignored");
        assertEquals(SensorState.UNLOADED, live.state(), "state after the unload");
        run.core.runs()
            .startRun(START);

        long now = START + 5L * 60L;
        List<GapRanges.Range> gaps = GapRanges.minutes(
            live.minutes(),
            MinuteAccumulator.epochMinute(unloadedAt) + 1,
            MinuteAccumulator.epochMinute(now),
            run.core.gapContext(SENSOR, now));
        assertEquals(1, gaps.size(), "gaps: " + gaps);
        assertEquals(GapReason.CHUNK_UNLOADED, gaps.get(0).reason, "an unloaded sensor's missing minutes");
    }

    /** Design-v0.2 section 8.3: a run stored open is closed at the {@code saved} it was written with, never later. */
    @Test
    void anUncleanRunIsClosedAtTheSavedItWasWrittenWith() {
        long saved = START + 300L;
        RunsTable table = loadedRuns(START, RunsTable.OPEN, saved);
        assertEquals(
            saved,
            table.rows()
                .get(0)
                .stop(),
            "an unclean run must be closed at saved");
        // A later save of a later process must not move it: the stop was decided while decoding.
        table.startRun(saved + 10_000L);
        assertEquals(
            saved,
            table.rows()
                .get(0)
                .stop(),
            "appending this run must not move the closed one");
        // A clean run keeps its own stop, whatever saved says.
        assertEquals(
            START + 120L,
            loadedRuns(START, START + 120L, saved).rows()
                .get(0)
                .stop(),
            "a clean run must keep its stop");
        // And a run that starts after saved is never closed before it started.
        assertEquals(
            START + 900L,
            loadedRuns(START + 900L, RunsTable.OPEN, saved).rows()
                .get(0)
                .stop(),
            "a run must never stop before it started");
    }

    // --- the other acceptance criteria ---

    @Test
    void noSlotIsWrittenWhileTheSensorIsUnloaded() {
        Run run = newRun();
        SensorEntry live = register(run);
        recordMinutes(run, live, 2);
        long unloadedAt = START + 2L * 60L;
        clock.setEpochMillis(unloadedAt * 1000L);
        run.core.unloaded(SENSOR, DIM, X, Y, Z, SIDE, GapReason.CHUNK_UNLOADED);
        flush();
        long writesAtUnload = run.history.slotsWritten();
        // The partial minute of the moment of the unload is written; nothing after it is.
        assertEquals(run.history.minutesClosed(), writesAtUnload, "one write per closed minute");

        for (int minute = 3; minute < 10; minute++) {
            clock.setEpochMillis((START + minute * 60L) * 1000L);
            assertNull(
                live.accumulator()
                    .closeBefore(MinuteAccumulator.epochMinute(clock.epochSec())),
                "an unloaded sensor has no open minute to close");
        }
        flush();
        assertEquals(writesAtUnload, run.history.slotsWritten(), "an unloaded sensor must write nothing");
    }

    /** Design-v0.2 section 8.4: with {@code history.persist=false} no {@code .gsh} is read, written or created. */
    @Test
    void persistFalseWritesNoHistoryFile() {
        settings = Settings.builder()
            .historyPersist(false)
            .build();
        Run run = newRun();
        assertFalse(run.history.enabled(), "the service must be off with history.persist=false");
        SensorEntry live = registerWithoutFile(run);
        recordMinutes(run, live, 3);
        clock.setEpochMillis((START + 3L * 60L) * 1000L);
        run.core.flushOpenMinutes();
        flush();
        run.history.drain();

        assertEquals(3, run.history.minutesClosed(), "the minutes are still closed and kept in RAM");
        assertEquals(0, run.history.slotsWritten(), "no slot may be written");
        assertEquals(0, run.history.filesCreated(), "no file may be created");
        assertEquals(0, run.history.loadsRequested(), "no file may be read");
        assertEquals(0, store.size(), "the store must be empty: " + store.names());
        // The RAM ring still has everything, which is what "RAM rings only" means.
        assertNotNull(
            live.minutes()
                .slot(MinuteAccumulator.epochMinute(START)),
            "the minute ring must still hold the history");
    }

    /**
     * Design-v0.2 section 4.3 and section 8: an entry that expires - the {@code removedRetentionHours} tombstone
     * sweep or the {@code staleExpiryDays} sweep - takes its history file with it.
     */
    @Test
    void anExpiredSensorLosesItsHistoryFile() {
        settings = Settings.builder()
            .removedRetentionHours(1)
            .build();
        Run run = newRun();
        SensorEntry live = register(run);
        recordMinutes(run, live, 1);
        long removedAt = START + 60L;
        clock.setEpochMillis(removedAt * 1000L);
        assertTrue(run.core.removed(SENSOR, DIM, X, Y, Z, SIDE, RemovalCause.DETACHED), "the removal was ignored");
        flush();
        assertTrue(store.exists(SENSOR), "the file must survive the removal itself");
        assertEquals(0, run.history.filesDeleted(), "a tombstone is not deleted before its retention runs out");

        clock.setEpochMillis((removedAt + 2L * 3600L) * 1000L);
        assertEquals(1, run.core.housekeeping(), "the tombstone must expire after removedRetentionHours");
        flush();
        assertEquals(1, run.history.filesDeleted(), "the expired sensor's file must be deleted");
        assertFalse(store.exists(SENSOR), "the history file is still there: " + store.names());
    }

    /**
     * Design-v0.2 section 8.4: a load result for a sensor that became a tombstone while the file was being read is
     * discarded, and nothing tries to write into a file that is no longer anybody's.
     */
    @Test
    void aLoadResultForAGoneSensorIsDiscarded() {
        Run run = newRun();
        SensorEntry live = registerWithoutFile(run);
        assertEquals(1, run.history.loadsRequested(), "the load must be queued when the entry becomes LIVE");
        clock.setEpochMillis((START + 30L) * 1000L);
        run.core.removed(SENSOR, DIM, X, Y, Z, SIDE, RemovalCause.IN_ITEM);
        assertNull(live.minutes(), "a tombstone keeps no rings");
        flush();
        assertEquals(0, run.history.drain(), "a result for a tombstone must not be applied");
        assertEquals(1, run.history.loadsDiscarded(), "the discarded result must be counted");
        assertEquals(0, run.history.slotsWritten(), "nothing may be written for a gone sensor");
    }

    /**
     * Design-v0.2 section 8.4: slots already in RAM win over the ones on disk. The same minute is recorded twice with
     * different contents; after the merge the RAM copy is what the ring holds.
     */
    @Test
    void ramWinsOverDiskOnMerge() {
        Run first = newRun();
        SensorEntry live = register(first);
        int minute = MinuteAccumulator.epochMinute(START);
        first.core.storeClosedMinute(
            live,
            MinuteSlot.builder(minute)
                .samples(10)
                .expectedSamples(60)
                .lastStateCode(StateCodes.RUNNING)
                .stateSamples(StateCodes.RUNNING, 10)
                .build());
        flush();

        Run second = newRun();
        SensorEntry restored = registerWithoutDrain(second);
        MinuteSlot inRam = MinuteSlot.builder(minute)
            .samples(55)
            .expectedSamples(60)
            .lastStateCode(StateCodes.IDLE)
            .stateSamples(StateCodes.IDLE, 55)
            .build();
        second.core.storeClosedMinute(restored, inRam);
        flush();
        second.history.drain();

        assertEquals(
            inRam,
            restored.minutes()
                .slot(minute),
            "the slot already in RAM must win over the one on disk");
        assertEquals(0, second.history.slotsMerged(), "the loaded slot must not replace the one in RAM");
        // The minute that closed while the file was still being read is written out once it is.
        assertEquals(1, second.history.slotsWritten(), "the pending minute must reach the file");
        flush();
        MinuteSlot onDisk = MinuteSlot
            .decode(store.history(SENSOR), HistoryFileCodec.slotOffset(MinuteRing.index(minute)));
        assertEquals(inRam, onDisk, "the file must end up holding what RAM has");
    }

    // --- what happens when the disk says no (GS-109/GS-110 follow-ups) ---

    /**
     * A read that throws (a virus scanner holding {@code <uuid>.gsh} open is the everyday Windows case) must not
     * strand the sensor: nothing is known about the file, so it is left alone, the sensor stays REQUESTED, and the
     * next drain asks again. Without the retry the sensor would stay "loading history" and write not one minute to
     * disk for the rest of the run.
     */
    @Test
    void aReadThatThrowsIsRetriedAndLeavesTheFileAlone() {
        Run first = newRun();
        SensorEntry live = register(first);
        recordMinutes(first, live, MINUTES);
        long stopped = START + MINUTES * 60L;
        clock.setEpochMillis(stopped * 1000L);
        first.core.flushOpenMinutes();
        flush();
        byte[] onDisk = store.history(SENSOR);

        long restarted = stopped + DOWNTIME_MINUTES * 60L;
        clock.setEpochMillis(restarted * 1000L);
        Run second = newRun();
        store.failReads.set(1);
        SensorEntry restored = registerWithoutDrain(second);
        flush();
        assertEquals(0, second.history.drain(), "a failed read is not an applied load");
        assertEquals(1, second.history.loadsFailed(), "the failure must be counted");
        assertFalse(restored.historyLoaded(), "nothing was learned about the file");
        assertFalse(second.history.filePresent(SENSOR), "a failed read must not mark the file present");
        assertEquals(0, second.history.filesCreated(), "a file that may be whole must never be overwritten");
        assertEquals(0, store.quarantined(), "a read that threw renames nothing");
        assertArrayEquals(onDisk, store.history(SENSOR), "the file on disk was touched");

        // The retry asks again, and this time the disk answers.
        awaitFileWork(second, restored::historyLoaded, "the retried load was never applied");
        assertEquals(1, second.history.loadsFailed(), "only the first read may have failed");
        assertTrue(restored.historyLoaded(), "the retry must finish the job");
        assertEquals(MINUTES, second.history.slotsMerged(), "the history is back");
        assertEquals(0, second.history.filesCreated(), "an existing file must not be recreated");
        assertEquals(0, second.history.sensorsAbandoned());
        assertArrayEquals(onDisk, store.history(SENSOR), "the file must still be the one the first run wrote");
    }

    /**
     * A {@code CreateFile} that throws leaves no file, so every later minute would queue a slot write into nothing -
     * one error per sensor per minute, for ever. The sensor goes back to REQUESTED instead and the next result
     * writes the whole ring into a fresh file, so the minutes recorded meanwhile are not lost either.
     */
    @Test
    void aCreateThatThrowsDoesNotLeaveTheSensorThinkingItHasAFile() {
        Run run = newRun();
        // Every create throws while this phase lasts. How many are attempted depends on the I/O thread: a retry that
        // finishes inside the same drain queues the next one straight away, which is correct either way.
        store.failWrites.set(FAILING_CREATES);
        SensorEntry live = registerWithoutDrain(run);
        awaitFileWork(run, () -> run.history.writesFailed() > 0, "the failed create was never reported");
        assertTrue(run.history.filesCreated() > 0, "the create was never queued");
        assertFalse(store.exists(SENSOR), "the create threw, so there is no file");
        assertOnlyAnInFlightCreateIsBelieved(run);

        // A minute closes while there is no file: it must not be written as a slot into a file that is not there.
        recordMinutes(run, live, 2);
        flush();
        assertEquals(0, run.history.slotsWritten(), "no slot may be written while there is no file");
        assertFalse(store.exists(SENSOR), "still no file while every create throws");
        assertOnlyAnInFlightCreateIsBelieved(run);

        // Writes work again: the retry reads (nothing), so a whole fresh image is written, minutes and all.
        store.failWrites.set(0);
        long failedCreates = run.history.writesFailed();
        awaitFileWork(run, () -> store.exists(SENSOR), "the file was never recreated");
        assertEquals(failedCreates + 1, run.history.filesCreated(), "every failed create plus the one that landed");
        assertEquals(
            HistoryFileCodec.Status.OK,
            HistoryFileCodec.inspect(store.history(SENSOR), SENSOR),
            "the recreated file does not pass its own header rules");
        MinuteSlot closed = live.minutes()
            .slot(MinuteAccumulator.epochMinute(START));
        assertNotNull(closed, "the minute is in the ring");
        assertEquals(
            closed,
            MinuteSlot
                .decode(store.history(SENSOR), HistoryFileCodec.slotOffset(MinuteRing.index(closed.epochMinute()))),
            "the minute recorded while the file was missing must reach the new file");
        assertEquals(0, run.history.sensorsAbandoned());
    }

    /** A file that fails every time is given up on after {@link HistoryPersistence#MAX_FILE_FAILURES} tries. */
    @Test
    void aFileThatKeepsFailingIsGivenUpOnRatherThanRetriedForEver() {
        Run run = newRun();
        store.failReads.set(1000);
        registerWithoutDrain(run);
        for (int i = 0; i < HistoryPersistence.MAX_FILE_FAILURES + 5; i++) {
            flush();
            run.history.drain();
        }
        assertEquals(HistoryPersistence.MAX_FILE_FAILURES, run.history.loadsFailed(), "the retries must be bounded");
        assertEquals(1, run.history.sensorsAbandoned(), "the sensor must be given up on");
        long requested = run.history.loadsRequested();

        // Nothing more is queued for it, whatever happens next.
        SensorEntry entry = run.core.entry(SENSOR);
        recordMinutes(run, entry, 2);
        flush();
        run.history.drain();
        flush();
        assertEquals(requested, run.history.loadsRequested(), "an abandoned sensor must not be asked again");
        assertEquals(0, run.history.slotsWritten(), "an abandoned sensor writes nothing");
        assertEquals(0, run.history.filesCreated());
        assertFalse(store.exists(SENSOR), "nothing may be written over a file that could not be read");
        assertNotNull(
            entry.minutes()
                .slot(MinuteAccumulator.epochMinute(START)),
            "the RAM ring still holds the history");
    }

    // --- helpers ---

    /** One "server run": its own registry core and its own persistence service over the shared store. */
    private static final class Run {

        final SensorRegistryCore core;
        final HistoryPersistence history;

        Run(SensorRegistryCore core, HistoryPersistence history) {
            this.core = core;
            this.history = history;
        }
    }

    private Run newRun() {
        SensorRegistryCore core = new SensorRegistryCore(() -> settings, clock, new NoTeams());
        HistoryPersistence history = new HistoryPersistence(io, () -> settings, core::entry);
        core.setEvents(history);
        return new Run(core, history);
    }

    /** Registers the sensor, then lets the (absent or existing) file result land, as the sampler's drain would. */
    private SensorEntry register(Run run) {
        SensorEntry entry = registerWithoutDrain(run);
        // The load lands, the result is applied, and whatever it queued (a fresh file) lands too.
        flush();
        run.history.drain();
        flush();
        return entry;
    }

    private SensorEntry registerWithoutFile(Run run) {
        return registerWithoutDrain(run);
    }

    private SensorEntry registerWithoutDrain(Run run) {
        SensorIdentity identity = new SensorIdentity(SENSOR, "EBF North", null, null, START - 1000L);
        SensorRegistryCore.Heartbeat outcome = run.core.heartbeat(identity, SensorKind.MACHINE, DIM, X, Y, Z, SIDE, 0L);
        assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, outcome, "the sensor did not register");
        SensorEntry entry = run.core.entry(SENSOR);
        assertNotNull(entry, "no entry after registration");
        return entry;
    }

    /** Folds {@code minutes} whole minutes of one sample per second, closing each one on the rollover. */
    private List<MinuteSlot> recordMinutes(Run run, SensorEntry entry, int minutes) {
        List<MinuteSlot> closed = new ArrayList<>();
        for (int second = 0; second < minutes * SAMPLES_PER_MINUTE; second++) {
            long epochSec = START + second;
            clock.setEpochMillis(epochSec * 1000L);
            MinuteSlot rolled = entry.accumulator()
                .sample(epochSec, StateCodes.RUNNING, 0, true, -120L, true, 4000L + second, -1L);
            if (rolled != null) {
                run.core.storeClosedMinute(entry, rolled);
                closed.add(rolled);
            }
        }
        // The last minute is still open; the caller closes it (at stop) or leaves it open on purpose.
        return closed;
    }

    private RunsTable loadedRuns(long start, long stop, long saved) {
        List<long[]> stored = new ArrayList<>();
        stored.add(new long[] { start, stop });
        return RunsTable.loaded(stored, saved);
    }

    private void flush() {
        assertTrue(io.flush(10_000L), "the I/O queue did not drain");
    }

    /**
     * Runs drain/flush rounds until {@code until} holds, and fails if it never does.
     *
     * <p>
     * {@code drain()} queues what the last results asked for and then applies whatever the I/O thread has already
     * finished, so whether a result lands in this call or the next one depends on the thread. Both orders are correct,
     * so a test waits for the state it is about instead of counting drains. It stops at the first drain that satisfies
     * the condition, so the state a scenario wants to observe is not run past: a retry is only queued by the drain
     * after the one that applied the failure.
     */
    /**
     * The race-free form of "the sensor stopped believing in the file": for the one sensor these scenarios register,
     * every create ever queued has either had its failure applied or is the one still in flight.
     *
     * <p>
     * Asserting {@code !filePresent} at a moment of the test's choosing is not sound, because
     * {@link HistoryPersistence#drain()} applies write failures first and load results afterwards: the drain that
     * puts the sensor back to REQUESTED also re-queues its load, and if the I/O thread answers that load inside the
     * same drain the sensor legitimately believes in a file again before the call returns. Both orders are correct,
     * so the test asserts the invariant that holds in both instead of the state of one of them.
     */
    private void assertOnlyAnInFlightCreateIsBelieved(Run run) {
        assertEquals(
            run.history.writesFailed() + (run.history.filePresent(SENSOR) ? 1L : 0L),
            run.history.filesCreated(),
            "the sensor believes in a file it did not just queue a create for");
    }

    private void awaitFileWork(Run run, BooleanSupplier until, String message) {
        for (int round = 0; round < SETTLE_ROUNDS && !until.getAsBoolean(); round++) {
            run.history.drain();
            flush();
        }
        assertTrue(until.getAsBoolean(), message);
    }

    /** No teams at all: every sensor here is unowned, which is all the caps need. */
    private static final class NoTeams implements TeamResolver<Object> {

        @Override
        public Object teamOf(UUID player) {
            return null;
        }

        @Override
        public boolean isMember(Object team, UUID player) {
            return false;
        }

        @Override
        public boolean isOfficerOrOwner(Object team, UUID player) {
            return false;
        }
    }

    /**
     * {@code <world>/gregscope/} in a map. Thread-safe, because the real {@link HistoryIo} thread uses it; the
     * counters make it possible to assert that nothing was written at all.
     */
    private static final class MemoryFileStore implements FileStore {

        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        private final AtomicInteger quarantined = new AtomicInteger();
        /** How many further reads must throw; a virus scanner holding the file open, in one field. */
        /**
         * Milliseconds every file call sleeps, so a run can force the other order. Zero by default; set
         * {@code -Dgregscope.test.ioDelayMs=30} to make the I/O thread lose every race. Both orders must pass.
         */
        private final long delayMillis = Long.getLong("gregscope.test.ioDelayMs", 0L);

        private final AtomicInteger failReads = new AtomicInteger();
        /** How many further file writes (create or slot) must throw: a full or unwritable disk. */
        private final AtomicInteger failWrites = new AtomicInteger();

        private static String key(UUID id) {
            return "history/" + id + ".gsh";
        }

        /** True if this call must throw. Counted down, so a test can fail exactly as often as it wants to. */
        /** Sleeps {@link #delayMillis} so a run can force the I/O thread to answer one drain later. */
        private void pause() {
            if (delayMillis <= 0L) {
                return;
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
        }

        private static boolean fails(AtomicInteger budget) {
            while (true) {
                int left = budget.get();
                if (left <= 0) {
                    return false;
                }
                if (budget.compareAndSet(left, left - 1)) {
                    return true;
                }
            }
        }

        boolean exists(UUID id) {
            return files.containsKey(key(id));
        }

        byte[] history(UUID id) {
            byte[] file = files.get(key(id));
            assertNotNull(file, "no history file for " + id + " in " + files.keySet());
            return file.clone();
        }

        int size() {
            return files.size();
        }

        List<String> names() {
            return new ArrayList<>(files.keySet());
        }

        int quarantined() {
            return quarantined.get();
        }

        @Override
        public byte[] readHistory(UUID id) throws IOException {
            pause();
            if (fails(failReads)) {
                throw new IOException("cannot read " + key(id));
            }
            byte[] file = files.get(key(id));
            return file == null ? null : file.clone();
        }

        @Override
        public boolean historyExists(UUID id) {
            return exists(id);
        }

        @Override
        public void createHistory(UUID id, byte[] file) throws IOException {
            if (fails(failWrites)) {
                throw new IOException("cannot create " + key(id));
            }
            if (file == null || file.length != HistoryFileCodec.FILE_BYTES) {
                throw new IOException("bad image");
            }
            files.put(key(id), file.clone());
        }

        @Override
        public void writeSlot(UUID id, int index, byte[] slot) throws IOException {
            if (fails(failWrites)) {
                throw new IOException("cannot write a slot of " + key(id));
            }
            byte[] file = files.get(key(id));
            if (file == null) {
                throw new IOException("no history file for " + id + "; it must be created first");
            }
            if (slot == null || slot.length != HistoryFileCodec.SLOT_SIZE) {
                throw new IOException("a minute slot is " + HistoryFileCodec.SLOT_SIZE + " B");
            }
            System.arraycopy(slot, 0, file, HistoryFileCodec.slotOffset(index), slot.length);
        }

        @Override
        public void deleteHistory(UUID id) {
            files.remove(key(id));
        }

        @Override
        public void quarantineHistory(UUID id, String suffix) {
            byte[] file = files.remove(key(id));
            if (file != null) {
                files.put(key(id) + suffix, file);
                quarantined.incrementAndGet();
            }
        }

        @Override
        public byte[] readRegistry() {
            byte[] file = files.get("registry.dat");
            return file == null ? null : file.clone();
        }

        @Override
        public byte[] readRegistryBackup() {
            byte[] file = files.get("registry.dat.bak");
            return file == null ? null : file.clone();
        }

        @Override
        public void writeRegistry(byte[] bytes) {
            byte[] old = files.get("registry.dat");
            if (old != null) {
                files.put("registry.dat.bak", old);
            }
            files.put("registry.dat", bytes.clone());
        }

        @Override
        public void quarantineRegistry(String suffix) {
            byte[] file = files.remove("registry.dat");
            if (file != null) {
                files.put("registry.dat" + suffix, file);
                quarantined.incrementAndGet();
            }
        }
    }
}
