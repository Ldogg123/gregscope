package io.github.ldogg123.gregscope.registry;

import java.io.IOException;
import java.util.List;

import org.apache.logging.log4j.Logger;

import io.github.ldogg123.gregscope.history.FileStore;
import io.github.ldogg123.gregscope.history.HistoryFileCodec;
import io.github.ldogg123.gregscope.history.HistoryIo;

/**
 * GS-110: the {@code registry.dat} half of design-v0.2 section 8 - loading it at server start, appending this
 * process's run, and saving it again. The Minecraft adapter that owns the file, beside {@link RegistryNbtCodec} which
 * owns its bytes; listed in {@code PureSourcesTest.IMPURE} for the same reason.
 *
 * <p>
 * <b>Order at start</b> (design-v0.2 section 8.3, and the GS-110 follow-up recorded in the GS-107 notes): the codec
 * builds the {@link RunsTable} through {@link RunsTable#loaded}, which closes every unclean run at the {@code saved}
 * that was stored with it, <em>before</em> {@link RunsTable#startRun} appends this process's run and before anything
 * is written. That order is structural - {@code RunsTable.loaded} is the only way to build a table from a file - so
 * {@link #load} cannot get it wrong.
 *
 * <p>
 * <b>Reading happens on the server thread</b>, in {@code serverStarting}, before the world runs; section 8.4 lists no
 * read task and {@code NioFileStore} excludes exactly those two methods from its {@code -ea} check. Everything else
 * (the write, and renaming an unsupported file aside) is queued on the I/O thread.
 *
 * <p>
 * <b>{@code history.persist}</b> does not switch this off: section 8.4 says the registry is saved even when no
 * {@code .gsh} file is. Without a save root there is no store and nothing is read or written at all.
 */
public final class RegistryPersistence {

    private final FileStore store;
    private final HistoryIo io;
    private final Logger log;

    private RegistryNbtCodec.Preserved preserved = RegistryNbtCodec.Preserved.empty();
    private RegistryNbtCodec.Source source = RegistryNbtCodec.Source.NONE;
    private long savesQueued;
    private long lastSavedEpochSec;

    /**
     * @param store the file store, or null when there is no save root; then nothing is read or written
     * @param io    the I/O thread the writes are queued on, or null
     */
    public RegistryPersistence(FileStore store, HistoryIo io, Logger log) {
        if (log == null) {
            throw new IllegalArgumentException("log");
        }
        this.store = store;
        this.io = io;
        this.log = log;
    }

    /** Where the last load came from (design-v0.2 section 8.3 "Loading"). */
    public RegistryNbtCodec.Source source() {
        return source;
    }

    /** What the last load could not understand and every save writes back unchanged (design-v0.3 A3). */
    public RegistryNbtCodec.Preserved preserved() {
        return preserved;
    }

    public long savesQueued() {
        return savesQueued;
    }

    /** The {@code saved} timestamp of the last queued save, or 0. */
    public long lastSavedEpochSec() {
        return lastSavedEpochSec;
    }

    /**
     * Reads {@code registry.dat} (else {@code .bak}, else nothing), puts every entry back into {@code core}, installs
     * the runs table with every unclean run already closed, and appends this process's run.
     *
     * @param nowEpochSec the start time of this run
     * @return how many entries were restored
     */
    public int load(SensorRegistryCore core, long nowEpochSec) {
        RegistryNbtCodec.Loaded loaded = read();
        source = loaded.source();
        preserved = loaded.preserved();
        switch (source) {
            case BACKUP:
                log.warn("registry.dat could not be read; GregScope fell back to registry.dat.bak");
                break;
            case UNREADABLE:
                log.warn(
                    "neither registry.dat nor registry.dat.bak could be read; GregScope starts with an empty"
                        + " registry. Both files are kept. Sensors re-register from their covers.");
                break;
            case UNSUPPORTED:
                log.warn(
                    "registry.dat is format v{}, which this GregScope cannot read; it is renamed aside and the"
                        + " registry starts empty.",
                    Integer.valueOf(loaded.version()));
                quarantine(HistoryFileCodec.Status.UNSUPPORTED.suffix(loaded.version(), 0L));
                break;
            default:
                break;
        }
        if (loaded.droppedEntries() > 0) {
            log.warn("{} registry rows had no sensor id and were skipped", Integer.valueOf(loaded.droppedEntries()));
        }

        int restored = 0;
        for (SensorEntry entry : loaded.entries()) {
            if (core.restore(entry)) {
                restored++;
            }
        }
        // Section 8.3: the unclean runs were closed while decoding; only now may this run be appended.
        RunsTable runs = loaded.runs();
        runs.startRun(nowEpochSec);
        core.setRuns(runs);
        List<RunsTable.Row> rows = runs.rows();
        log.info(
            "GregScope registry: {}, {} entries restored, {} kept for another version, {} recorded runs, this run"
                + " starts at {}",
            source,
            Integer.valueOf(restored),
            Integer.valueOf(
                loaded.preserved()
                    .foreignCount()),
            Integer.valueOf(rows.size()),
            Long.valueOf(nowEpochSec));
        return restored;
    }

    /**
     * Serializes the registry on the server thread and queues the write (design-v0.2 section 8.3).
     *
     * @param force save although nothing is marked dirty; the shutdown save does, because {@code seen} advances every
     *              second without marking the registry dirty (GS-107-04)
     * @return true if a write was queued
     */
    public boolean save(SensorRegistryCore core, long nowEpochSec, boolean force) {
        if (io == null || store == null) {
            return false;
        }
        if (!force && !core.dirty()) {
            return false;
        }
        byte[] bytes;
        try {
            bytes = RegistryNbtCodec.encodeGzipped(core.entries(), core.runs(), nowEpochSec, preserved);
        } catch (IOException e) {
            log.error("GregScope could not serialize its registry; it is not saved this time", e);
            return false;
        }
        if (!io.queueRegistry(bytes)) {
            log.warn("the GregScope registry write was dropped: the I/O queue is full");
            return false;
        }
        core.clearDirty();
        savesQueued++;
        lastSavedEpochSec = nowEpochSec;
        return true;
    }

    private RegistryNbtCodec.Loaded read() {
        if (store == null) {
            return RegistryNbtCodec.decode(null, RegistryNbtCodec.Source.NONE);
        }
        try {
            return RegistryNbtCodec.load(store);
        } catch (IOException e) {
            log.warn("GregScope could not read registry.dat; starting with an empty registry", e);
            return RegistryNbtCodec.decode(null, RegistryNbtCodec.Source.NONE);
        }
    }

    private void quarantine(String suffix) {
        if (io == null) {
            return;
        }
        // Renaming is file I/O, so it goes on the I/O thread like every other write (section 8.4).
        if (!io.queueQuarantineRegistry(suffix)) {
            log.warn("the GregScope registry could not be renamed aside: the I/O queue is full");
        }
    }

    @Override
    public String toString() {
        return "RegistryPersistence{" + source + ", " + savesQueued + " saves}";
    }
}
