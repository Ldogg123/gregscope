package io.github.ldogg123.gregscope.history;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.registry.RegistryEvents;
import io.github.ldogg123.gregscope.registry.SensorEntry;

/**
 * GS-110: the {@code history/&lt;uuid&gt;.gsh} half of design-v0.2 section 8 - which file exists, when it is read,
 * what is written into it and when it is deleted. Server thread only.
 *
 * <p>
 * This class is deliberately <b>not</b> one of the pure ones (the marker is spelled out in words here, because
 * {@code PureSourcesTest} treats the literal token anywhere in a file as a claim of purity): it names
 * {@link HistoryIo}, the I/O adapter, and is listed in
 * {@code PureSourcesTest.IMPURE} for that reason alone (it imports no game class). It is the registry's
 * {@link RegistryEvents} delegate, so every transition the state machine makes reaches disk through exactly one path.
 *
 * <p>
 * <b>The life of one sensor's file.</b>
 * <ol>
 * <li>The entry becomes LIVE, or is restored from {@code registry.dat} as UNLOADED: a {@code Load} is queued once per
 * sensor per process (design-v0.2 section 8.4 "when an entry first becomes LIVE or UNLOADED in a process, its file is
 * read"). A {@code Load} the queue dropped is retried on the next {@link #drain()}.</li>
 * <li>Minutes that close before the result arrives are remembered by index only; the bytes live in the entry's
 * {@link MinuteRing}, which is the source of truth in RAM.</li>
 * <li>The result arrives and {@link #drain()} applies it on the server thread:
 * <ul>
 * <li>a whole, matching file is merged with {@link MinuteRing#mergeLoaded} - <b>slots already in RAM win</b> - and the
 * minutes remembered in step 2 are written out;</li>
 * <li>no file at all, or one {@code HistoryIo} renamed aside because it was corrupt, unsupported or foreign, gives a
 * fresh file written from the whole ring, so nothing recorded so far is lost.</li>
 * </ul>
 * Either way the entry is marked {@link SensorEntry#historyLoaded()}.</li>
 * <li>Every later closed minute is one 64-byte {@code WriteSlot}: <b>exactly one write per closed minute per
 * sensor</b> (design-v0.2 section 14), which {@link #slotsWritten()} and {@link #minutesClosed()} make checkable.</li>
 * <li>The entry expires or is purged: the file is deleted.</li>
 * </ol>
 *
 * <p>
 * <b>{@code history.persist=false}</b> (design-v0.2 section 8.4) is read live from {@link Settings} on every call: no
 * {@code .gsh} is read, created, written or deleted, and the RAM rings carry the history for the rest of the run. The
 * registry is still saved - that is {@code RegistryPersistence}'s business, not this class's.
 *
 * <p>
 * <b>A dropped task is never a lost ring.</b> The I/O queue is bounded and drops under pressure (section 8.4); the
 * RAM ring keeps the minute either way, so a dropped slot only reads as a gap after a restart.
 *
 * <p>
 * <b>A task that threw is not the same as a task that was dropped.</b> A read that failed says nothing about the
 * file, so the sensor stays REQUESTED and asks again rather than writing a fresh image over a file that may be
 * whole; a create or slot write that failed sends the sensor back to REQUESTED with the whole-ring sentinel, so the
 * next result rewrites the file completely instead of writing slots into a file that is not there. Both are bounded
 * by {@link #MAX_FILE_FAILURES}, after which the sensor is left alone for the rest of the run and counted in
 * {@link #sensorsAbandoned()}.
 */
public final class HistoryPersistence implements RegistryEvents {

    /**
     * Minutes that may be remembered per sensor while its file is being read. Beyond this the whole file is rewritten
     * from the ring instead, which is both cheaper and simpler than a long list of indexes. One minute per sensor per
     * minute closes, so reaching 64 means the I/O thread is an hour behind.
     */
    public static final int MAX_PENDING_SLOTS = 64;

    /**
     * How often one sensor's file may fail (a read that threw, a create or a slot write that threw) before this
     * service gives up on it for the rest of the run. Without a bound, a file whose directory is not writable would
     * be retried once per sampling interval for ever; without any retry at all, one transient lock (a virus scanner
     * holding the file open for a moment is the everyday Windows case) would cost the whole run's history.
     */
    public static final int MAX_FILE_FAILURES = 5;

    /** The {@link Tracked#pending} entry that means "rewrite the whole file from the ring". */
    private static final int REWRITE_ALL = -1;

    private enum FileState {
        /** A {@code Load} is queued (or is waiting to be queued again after a drop); no write may happen yet. */
        REQUESTED,
        /** The file exists, or a {@code CreateFile} for it is queued; slots may be written straight in. */
        PRESENT
    }

    private static final class Tracked {

        FileState state = FileState.REQUESTED;
        /** False while the {@code Load} could not be queued, so {@link HistoryPersistence#drain()} retries it. */
        boolean loadQueued;
        /** Minute ring indexes closed while the file was still being read. */
        final Set<Integer> pending = new LinkedHashSet<>();
        /** Reads and writes of this sensor's file that threw on the I/O thread. */
        int failures;
        /** True once {@link #failures} reached {@link HistoryPersistence#MAX_FILE_FAILURES}: nothing more is queued. */
        boolean abandoned;
    }

    private final HistoryIo io;
    private final Supplier<Settings> settings;
    private final Function<UUID, SensorEntry> entries;
    private final Map<UUID, Tracked> tracked = new HashMap<>();

    private long minutesClosed;
    private long slotsWritten;
    private long filesCreated;
    private long filesDeleted;
    private long loadsRequested;
    private long loadsApplied;
    private long loadsDiscarded;
    private long slotsMerged;
    private long tasksDropped;
    private long loadsFailed;
    private long writesFailed;
    private long sensorsAbandoned;

    /**
     * @param io       the I/O thread, or null when there is none (no save root); then nothing is ever queued
     * @param settings the live settings, so {@code history.persist} applies without a restart
     * @param entries  the registry lookup a finished load is applied to, normally {@code SensorRegistryCore::entry}
     */
    public HistoryPersistence(HistoryIo io, Supplier<Settings> settings, Function<UUID, SensorEntry> entries) {
        if (settings == null || entries == null) {
            throw new IllegalArgumentException("settings and entries are required");
        }
        this.io = io;
        this.settings = settings;
        this.entries = entries;
    }

    /** True while {@code .gsh} files are read and written: there is an I/O thread and {@code history.persist}. */
    public boolean enabled() {
        return io != null && settings.get()
            .historyPersist();
    }

    // --- registry events (design-v0.2 sections 4.3 and 8.4) ---

    @Override
    public void sensorLive(SensorEntry entry) {
        request(entry);
    }

    @Override
    public void sensorInactive(SensorEntry entry) {
        // Nothing: the partial minute was already closed by the transition and arrived through minuteClosed.
    }

    @Override
    public void minuteClosed(SensorEntry entry, MinuteSlot slot) {
        minutesClosed++;
        if (!enabled() || entry == null || slot == null) {
            return;
        }
        Tracked state = tracked.get(entry.id());
        if (state == null) {
            // A minute closed for an entry nothing announced (a tombstone's last partial minute, for example).
            state = request(entry);
            if (state == null) {
                return;
            }
        }
        if (state.abandoned) {
            // This sensor's file failed too often; the ring still holds the minute, and nothing is queued for it.
            return;
        }
        int index = MinuteRing.index(slot.epochMinute());
        if (state.state == FileState.REQUESTED) {
            if (state.pending.size() < MAX_PENDING_SLOTS) {
                state.pending.add(Integer.valueOf(index));
            } else {
                // Too far behind to track minute by minute; the whole ring is rewritten when the load lands.
                state.pending.clear();
                state.pending.add(Integer.valueOf(REWRITE_ALL));
            }
            return;
        }
        writeSlot(entry.id(), index, slot.toBytes());
    }

    @Override
    public void sensorExpired(UUID id, int kind) {
        tracked.remove(id);
        if (!enabled() || id == null) {
            return;
        }
        // Design-v0.2 section 4.3 / section 8: an expired or purged sensor takes its history file with it. This is
        // both the removedRetentionHours path (a tombstone that ran out) and the staleExpiryDays path.
        if (io.queueDelete(id)) {
            filesDeleted++;
        } else {
            tasksDropped++;
        }
    }

    // --- startup and the per-interval drain ---

    /**
     * Queues the history load of every entry restored from {@code registry.dat} (design-v0.2 section 8.4: an entry
     * that is UNLOADED in this process has its file read too, so the Hub shows 24 h after a restart).
     *
     * @return how many loads were requested
     */
    public int requestAll(Collection<SensorEntry> restored) {
        int requested = 0;
        for (SensorEntry entry : restored) {
            if (request(entry) != null) {
                requested++;
            }
        }
        return requested;
    }

    /**
     * Applies everything the I/O thread finished reading, on the server thread (design-v0.2 section 8.4), and retries
     * loads the queue dropped. The sampler calls it once per sampling interval.
     *
     * @return how many load results were applied
     */
    public int drain() {
        if (io == null) {
            return 0;
        }
        applyWriteFailures();
        retryDroppedLoads();
        int applied = 0;
        for (HistoryIo.LoadResult result : io.drainLoaded()) {
            if (apply(result)) {
                applied++;
            }
        }
        return applied;
    }

    /**
     * A {@code CreateFile} or {@code WriteSlot} that threw means the file is not what this class believed: it may be
     * missing, or half of it may be an older image. The sensor goes back to REQUESTED with the whole-ring sentinel,
     * so the file is read again and then rewritten in full from the ring - which is also what heals a file that was
     * never created at all.
     */
    private void applyWriteFailures() {
        for (HistoryIo.WriteFailure failure : io.drainWriteFailures()) {
            Tracked state = tracked.get(failure.id());
            if (state == null || state.abandoned) {
                continue;
            }
            writesFailed++;
            state.state = FileState.REQUESTED;
            state.loadQueued = false;
            state.pending.clear();
            state.pending.add(Integer.valueOf(REWRITE_ALL));
            noteFailure(state);
        }
    }

    private void retryDroppedLoads() {
        if (!enabled()) {
            return;
        }
        for (Map.Entry<UUID, Tracked> row : tracked.entrySet()) {
            Tracked state = row.getValue();
            if (state.state == FileState.REQUESTED && !state.loadQueued && !state.abandoned) {
                state.loadQueued = io.queueLoad(row.getKey());
                if (state.loadQueued) {
                    loadsRequested++;
                }
            }
        }
    }

    /** Counts one failure of this sensor's file and gives up on it once {@link #MAX_FILE_FAILURES} is reached. */
    private void noteFailure(Tracked state) {
        if (++state.failures >= MAX_FILE_FAILURES) {
            state.abandoned = true;
            sensorsAbandoned++;
        }
    }

    private boolean apply(HistoryIo.LoadResult result) {
        Tracked state = tracked.get(result.id());
        SensorEntry entry = entries.apply(result.id());
        if (state == null || entry == null || entry.minutes() == null) {
            // Design-v0.2 section 8.4: a result for an entry that became a tombstone meanwhile is discarded.
            loadsDiscarded++;
            return false;
        }
        if (result.failed()) {
            // The read threw, so nothing is known about the file: it may be whole. Writing a fresh image over it
            // would be the one irreversible mistake available here, so the sensor stays REQUESTED and asks again -
            // MAX_FILE_FAILURES times, after which it is left alone for the rest of the run.
            loadsFailed++;
            state.state = FileState.REQUESTED;
            state.loadQueued = false;
            noteFailure(state);
            return false;
        }
        byte[] file = result.file();
        if (file != null) {
            slotsMerged += entry.minutes()
                .mergeLoaded(file, HistoryFileCodec.HEADER_BYTES);
            if (entry.accumulator() != null) {
                // A minute already on disk must not be reopened by a clock that went backwards (section 7.4).
                entry.accumulator()
                    .noteWritten(
                        entry.minutes()
                            .newestEpochMinute());
            }
            state.state = FileState.PRESENT;
            writePending(entry, state);
        } else {
            // No file, or one HistoryIo renamed aside: start a new one holding everything the ring has.
            createFrom(entry, state);
        }
        entry.setHistoryLoaded(true);
        loadsApplied++;
        return true;
    }

    private void writePending(SensorEntry entry, Tracked state) {
        if (state.pending.contains(Integer.valueOf(REWRITE_ALL))) {
            state.pending.clear();
            createFrom(entry, state);
            return;
        }
        List<Integer> indexes = new ArrayList<>(state.pending);
        state.pending.clear();
        byte[] slot = new byte[MinuteSlot.SIZE];
        for (Integer index : indexes) {
            entry.minutes()
                .copyRaw(index.intValue(), slot, 0);
            writeSlot(entry.id(), index.intValue(), slot.clone());
        }
    }

    /** A whole {@value HistoryFileCodec#FILE_BYTES} B image: the section 8.2 header plus the ring as it stands. */
    private void createFrom(SensorEntry entry, Tracked state) {
        byte[] image = HistoryFileCodec.newFile(entry.id(), entry.kind(), entry.createdEpochSec());
        for (int i = 0; i < MinuteRing.SLOTS; i++) {
            entry.minutes()
                .copyRaw(i, image, HistoryFileCodec.slotOffset(i));
        }
        state.state = FileState.PRESENT;
        state.pending.clear();
        if (io.queueCreate(entry.id(), image)) {
            filesCreated++;
        } else {
            tasksDropped++;
            // The file was not created, so the next closed minute must not try to write into it.
            state.state = FileState.REQUESTED;
            state.loadQueued = false;
        }
    }

    private Tracked request(SensorEntry entry) {
        if (!enabled() || entry == null || entry.minutes() == null) {
            return null;
        }
        Tracked state = tracked.get(entry.id());
        if (state != null) {
            return state;
        }
        state = new Tracked();
        state.loadQueued = io.queueLoad(entry.id());
        if (state.loadQueued) {
            loadsRequested++;
        } else {
            tasksDropped++;
        }
        tracked.put(entry.id(), state);
        return state;
    }

    private void writeSlot(UUID id, int index, byte[] bytes) {
        if (io.queueSlot(id, index, bytes)) {
            slotsWritten++;
        } else {
            tasksDropped++;
        }
    }

    // --- counters (server thread; they make the design-v0.2 section 14 criteria checkable) ---

    /** Minutes the registry closed while this service was installed, whatever {@code history.persist} says. */
    public long minutesClosed() {
        return minutesClosed;
    }

    /** 64-byte slot writes queued. With a file already present this equals {@link #minutesClosed()}. */
    public long slotsWritten() {
        return slotsWritten;
    }

    public long filesCreated() {
        return filesCreated;
    }

    public long filesDeleted() {
        return filesDeleted;
    }

    public long loadsRequested() {
        return loadsRequested;
    }

    public long loadsApplied() {
        return loadsApplied;
    }

    /** Results for sensors that were gone by the time they arrived (design-v0.2 section 8.4). */
    public long loadsDiscarded() {
        return loadsDiscarded;
    }

    /** Slots taken from a loaded file; the ones already in RAM are not counted, because RAM wins. */
    public long slotsMerged() {
        return slotsMerged;
    }

    /** Tasks the bounded queue refused. The rings stay correct; the minutes read as gaps after a restart. */
    public long tasksDropped() {
        return tasksDropped;
    }

    /** Reads that threw on the I/O thread. The file is left alone and the read is retried. */
    public long loadsFailed() {
        return loadsFailed;
    }

    /** File creations and slot writes that threw. The sensor's file is read again and rewritten from the ring. */
    public long writesFailed() {
        return writesFailed;
    }

    /** Sensors whose file failed {@link #MAX_FILE_FAILURES} times; nothing more is queued for them this run. */
    public long sensorsAbandoned() {
        return sensorsAbandoned;
    }

    /** Sensors whose file this process has read or created. */
    public int trackedSensors() {
        return tracked.size();
    }

    /** True once the sensor's file has been read (or created) and slots go straight to disk. */
    public boolean filePresent(UUID id) {
        Tracked state = tracked.get(id);
        return state != null && state.state == FileState.PRESENT;
    }

    @Override
    public String toString() {
        return "HistoryPersistence{" + (enabled() ? "on" : "off")
            + ", "
            + tracked.size()
            + " files, "
            + slotsWritten
            + "/"
            + minutesClosed
            + " slots written, "
            + filesCreated
            + " created, "
            + filesDeleted
            + " deleted, "
            + tasksDropped
            + " dropped}";
    }
}
