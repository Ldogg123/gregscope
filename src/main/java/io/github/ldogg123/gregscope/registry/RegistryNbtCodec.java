package io.github.ldogg123.gregscope.registry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import io.github.ldogg123.gregscope.history.FileStore;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * {@code registry.dat} (design-v0.2 §8.3): format 1, gzipped NBT. The Minecraft adapter of the {@code registry}
 * package, listed in {@code PureSourcesTest.IMPURE} because only {@code NBTTagCompound} can read it.
 *
 * <pre>
 * { v:1, saved:long,
 *   runs:[{start:long, stop:long}] (<=32, oldest dropped, stop 0 = unclean),
 *   entries:[{ idM, idL, kind:b, dim, x, y, z, side:b, owM?, owL?, owN, lbl, mi:i, mn, nm, st,
 *              state:b (0 UNLOADED, 1 MISSING, 2 IN_ITEM, 3 REMOVED), cause:b, ct, seen, since }] }
 * </pre>
 *
 * <p>
 * <b>LIVE is never persisted</b> (§8.3): a LIVE entry is written with state 0, so every non-tombstone entry comes back
 * UNLOADED and has to prove itself with a heartbeat.
 *
 * <p>
 * <b>Rollback safety (design-v0.3 §5.1 A3).</b> An entry whose {@code kind} this build does not register is kept
 * <em>verbatim</em>: it is not decoded into a {@link SensorEntry}, so it is never counted, sampled or expired and its
 * {@code .gsh} is never opened, and it is written back unchanged on the next save. Unknown keys are preserved the same
 * way, both on the root compound and on the entries this build does understand, so running v0.3 data through a v0.2
 * server does not lose anything. {@link Loaded#preserved()} is what the caller holds on to for the next
 * {@link #encode}.
 *
 * <p>
 * <b>Loading order (§8.3).</b> {@link #decode} builds the {@link RunsTable} through {@link RunsTable#loaded}, which
 * closes every unclean run at the {@code saved} that was loaded with it. Appending this process's run is the caller's
 * next step, so it cannot happen first.
 */
public final class RegistryNbtCodec {

    public static final int VERSION = 1;

    public static final String KEY_VERSION = "v";
    public static final String KEY_SAVED = "saved";
    public static final String KEY_RUNS = "runs";
    public static final String KEY_RUN_START = "start";
    public static final String KEY_RUN_STOP = "stop";
    public static final String KEY_ENTRIES = "entries";

    public static final String KEY_ID_MSB = "idM";
    public static final String KEY_ID_LSB = "idL";
    public static final String KEY_KIND = "kind";
    public static final String KEY_DIM = "dim";
    public static final String KEY_X = "x";
    public static final String KEY_Y = "y";
    public static final String KEY_Z = "z";
    public static final String KEY_SIDE = "side";
    public static final String KEY_OWNER_MSB = "owM";
    public static final String KEY_OWNER_LSB = "owL";
    public static final String KEY_OWNER_NAME = "owN";
    public static final String KEY_LABEL = "lbl";
    public static final String KEY_META_ID = "mi";
    public static final String KEY_META_NAME = "mn";
    public static final String KEY_MACHINE_NAME = "nm";
    public static final String KEY_STATUS_ID = "st";
    public static final String KEY_STATE = "state";
    public static final String KEY_CAUSE = "cause";
    public static final String KEY_CREATED = "ct";
    public static final String KEY_SEEN = "seen";
    public static final String KEY_SINCE = "since";

    /** NBT type ids ({@code NBTBase}): 1 byte, 3 int, 4 long, 8 string, 10 compound. */
    private static final int TAG_BYTE = 1;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_COMPOUND = 10;

    private static final Set<String> ROOT_KEYS = new HashSet<>(
        Arrays.asList(KEY_VERSION, KEY_SAVED, KEY_RUNS, KEY_ENTRIES));
    private static final Set<String> ENTRY_KEYS = new HashSet<>(
        Arrays.asList(
            KEY_ID_MSB,
            KEY_ID_LSB,
            KEY_KIND,
            KEY_DIM,
            KEY_X,
            KEY_Y,
            KEY_Z,
            KEY_SIDE,
            KEY_OWNER_MSB,
            KEY_OWNER_LSB,
            KEY_OWNER_NAME,
            KEY_LABEL,
            KEY_META_ID,
            KEY_META_NAME,
            KEY_MACHINE_NAME,
            KEY_STATUS_ID,
            KEY_STATE,
            KEY_CAUSE,
            KEY_CREATED,
            KEY_SEEN,
            KEY_SINCE));

    private RegistryNbtCodec() {}

    /** Where a loaded registry came from (design-v0.2 §8.3 "Loading"). */
    public enum Source {

        /** {@code registry.dat} was read and decoded. */
        PRIMARY,
        /** {@code registry.dat} was missing or unreadable, and {@code registry.dat.bak} took over: log one WARN. */
        BACKUP,
        /** Neither file exists: a new world, or a world that never ran GregScope. */
        NONE,
        /** Both files were unreadable: start empty, keep the files. */
        UNREADABLE,
        /** {@code v > 1}: rename to {@code .unsupported-v<N>} and start empty. */
        UNSUPPORTED
    }

    /** What the caller must keep so that the next save does not lose anything it could not understand (A3). */
    public static final class Preserved {

        private final List<NBTTagCompound> foreignEntries;
        private final Map<UUID, NBTTagCompound> entryExtras;
        private final NBTTagCompound rootExtras;

        Preserved(List<NBTTagCompound> foreignEntries, Map<UUID, NBTTagCompound> entryExtras,
            NBTTagCompound rootExtras) {
            this.foreignEntries = foreignEntries;
            this.entryExtras = entryExtras;
            this.rootExtras = rootExtras;
        }

        public static Preserved empty() {
            return new Preserved(
                new ArrayList<NBTTagCompound>(),
                new LinkedHashMap<UUID, NBTTagCompound>(),
                new NBTTagCompound());
        }

        /** Entries of a {@code kind} this build does not register, exactly as they were read. */
        public List<NBTTagCompound> foreignEntries() {
            return Collections.unmodifiableList(foreignEntries);
        }

        /** Keys of a known entry that this build does not define, by sensor UUID. */
        public Map<UUID, NBTTagCompound> entryExtras() {
            return Collections.unmodifiableMap(entryExtras);
        }

        /** Root keys this build does not define. */
        public NBTTagCompound rootExtras() {
            return rootExtras;
        }

        public int foreignCount() {
            return foreignEntries.size();
        }
    }

    /** The outcome of reading {@code registry.dat}. */
    public static final class Loaded {

        private final Source source;
        private final int version;
        private final long saved;
        private final RunsTable runs;
        private final List<SensorEntry> entries;
        private final Preserved preserved;
        private final int droppedEntries;

        Loaded(Source source, int version, long saved, RunsTable runs, List<SensorEntry> entries, Preserved preserved,
            int droppedEntries) {
            this.source = source;
            this.version = version;
            this.saved = saved;
            this.runs = runs;
            this.entries = entries;
            this.preserved = preserved;
            this.droppedEntries = droppedEntries;
        }

        static Loaded empty(Source source) {
            return new Loaded(source, VERSION, 0L, new RunsTable(), new ArrayList<SensorEntry>(), Preserved.empty(), 0);
        }

        public Source source() {
            return source;
        }

        /** The {@code v} that was read; {@link #VERSION} for an empty registry. */
        public int version() {
            return version;
        }

        /** The {@code saved} timestamp the file carried; every unclean run was already closed at it. */
        public long saved() {
            return saved;
        }

        /** Unclean runs already closed (§8.3); the caller appends this process's run next. */
        public RunsTable runs() {
            return runs;
        }

        /** Decoded entries, all of a registered kind, none LIVE. */
        public List<SensorEntry> entries() {
            return Collections.unmodifiableList(entries);
        }

        public Preserved preserved() {
            return preserved;
        }

        /** Rows that had no usable identity and were skipped; worth one WARN. */
        public int droppedEntries() {
            return droppedEntries;
        }

        /** True if a usable registry was read (from either file). */
        public boolean isLoaded() {
            return source == Source.PRIMARY || source == Source.BACKUP;
        }

        @Override
        public String toString() {
            return "Loaded{" + source
                + " v"
                + version
                + " saved "
                + saved
                + ", "
                + entries.size()
                + " entries, "
                + preserved.foreignCount()
                + " foreign, "
                + runs.size()
                + " runs}";
        }
    }

    // --- encode ---

    /**
     * Builds the root compound. LIVE entries are written as UNLOADED, tombstones keep their state, and everything the
     * last load could not understand is written back unchanged.
     *
     * @param savedEpochSec the {@code saved} timestamp readers use to close this run if the server never stops cleanly
     */
    public static NBTTagCompound encode(Collection<SensorEntry> entries, RunsTable runs, long savedEpochSec,
        Preserved preserved) {
        Preserved keep = preserved == null ? Preserved.empty() : preserved;
        NBTTagCompound root = copyOf(keep.rootExtras);
        root.setInteger(KEY_VERSION, VERSION);
        root.setLong(KEY_SAVED, savedEpochSec);

        NBTTagList runList = new NBTTagList();
        for (long[] row : runs == null ? new ArrayList<long[]>() : runs.toStored()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong(KEY_RUN_START, row[0]);
            tag.setLong(KEY_RUN_STOP, row[1]);
            runList.appendTag(tag);
        }
        root.setTag(KEY_RUNS, runList);

        NBTTagList entryList = new NBTTagList();
        if (entries != null) {
            for (SensorEntry entry : entries) {
                entryList.appendTag(encodeEntry(entry, keep.entryExtras.get(entry.id())));
            }
        }
        for (NBTTagCompound foreign : keep.foreignEntries) {
            entryList.appendTag(copyOf(foreign));
        }
        root.setTag(KEY_ENTRIES, entryList);
        return root;
    }

    /** The bytes written to {@code registry.dat}: the root compound, gzipped (design-v0.2 §8.3). */
    public static byte[] encodeGzipped(Collection<SensorEntry> entries, RunsTable runs, long savedEpochSec,
        Preserved preserved) throws IOException {
        return CompressedStreamTools.compress(encode(entries, runs, savedEpochSec, preserved));
    }

    static NBTTagCompound encodeEntry(SensorEntry entry, NBTTagCompound extras) {
        NBTTagCompound tag = copyOf(extras);
        SensorIdentity identity = entry.identity();
        tag.setLong(
            KEY_ID_MSB,
            identity.id()
                .getMostSignificantBits());
        tag.setLong(
            KEY_ID_LSB,
            identity.id()
                .getLeastSignificantBits());
        tag.setByte(KEY_KIND, (byte) entry.kind());
        tag.setInteger(KEY_DIM, entry.dim());
        tag.setInteger(KEY_X, entry.x());
        tag.setInteger(KEY_Y, entry.y());
        tag.setInteger(KEY_Z, entry.z());
        tag.setByte(KEY_SIDE, (byte) entry.side());
        if (identity.owner() != null) {
            tag.setLong(
                KEY_OWNER_MSB,
                identity.owner()
                    .getMostSignificantBits());
            tag.setLong(
                KEY_OWNER_LSB,
                identity.owner()
                    .getLeastSignificantBits());
            tag.setString(KEY_OWNER_NAME, identity.ownerName() == null ? "" : identity.ownerName());
        } else {
            tag.removeTag(KEY_OWNER_MSB);
            tag.removeTag(KEY_OWNER_LSB);
            tag.removeTag(KEY_OWNER_NAME);
        }
        tag.setString(KEY_LABEL, identity.label());
        tag.setInteger(KEY_META_ID, entry.metaId());
        tag.setString(KEY_META_NAME, entry.metaName());
        tag.setString(KEY_MACHINE_NAME, entry.machineName());
        tag.setString(KEY_STATUS_ID, entry.lastStatusId());
        // LIVE is never persisted (§8.3): it comes back as UNLOADED and has to prove itself with a heartbeat.
        SensorState persisted = entry.state()
            .persistedCode() < 0 ? SensorState.UNLOADED : entry.state();
        tag.setByte(KEY_STATE, (byte) persisted.persistedCode());
        tag.setByte(
            KEY_CAUSE,
            (byte) entry.removalCause()
                .code());
        tag.setLong(KEY_CREATED, identity.createdEpochSec());
        tag.setLong(KEY_SEEN, entry.lastSeenEpochSec());
        tag.setLong(KEY_SINCE, entry.stateSinceEpochSec());
        return tag;
    }

    // --- decode ---

    /** Decodes a root compound that was already read from disk. */
    public static Loaded decode(NBTTagCompound root, Source source) {
        if (root == null) {
            return Loaded.empty(Source.NONE);
        }
        int version = root.hasKey(KEY_VERSION, TAG_INT) ? root.getInteger(KEY_VERSION) : 0;
        if (version != VERSION) {
            return new Loaded(
                Source.UNSUPPORTED,
                version,
                0L,
                new RunsTable(),
                new ArrayList<SensorEntry>(),
                Preserved.empty(),
                0);
        }
        long saved = root.getLong(KEY_SAVED);

        List<long[]> storedRuns = new ArrayList<>();
        NBTTagList runList = root.getTagList(KEY_RUNS, TAG_COMPOUND);
        for (int i = 0; i < runList.tagCount(); i++) {
            NBTTagCompound tag = runList.getCompoundTagAt(i);
            storedRuns.add(new long[] { tag.getLong(KEY_RUN_START), tag.getLong(KEY_RUN_STOP) });
        }
        // §8.3: unclean runs are closed here, before the caller can append this process's run or save anything.
        RunsTable runs = RunsTable.loaded(storedRuns, saved);

        List<SensorEntry> entries = new ArrayList<>();
        List<NBTTagCompound> foreign = new ArrayList<>();
        Map<UUID, NBTTagCompound> extras = new LinkedHashMap<>();
        int dropped = 0;
        NBTTagList entryList = root.getTagList(KEY_ENTRIES, TAG_COMPOUND);
        for (int i = 0; i < entryList.tagCount(); i++) {
            NBTTagCompound tag = entryList.getCompoundTagAt(i);
            if (!tag.hasKey(KEY_ID_MSB, TAG_LONG) || !tag.hasKey(KEY_ID_LSB, TAG_LONG)) {
                dropped++;
                continue;
            }
            int kind = tag.hasKey(KEY_KIND, TAG_BYTE) ? tag.getByte(KEY_KIND) & 0xFF : SensorKind.MACHINE;
            if (!SensorKind.isSupported(kind)) {
                // A3: keep it byte for byte. Not counted, not sampled, not expired; its .gsh is never opened.
                foreign.add(copyOf(tag));
                continue;
            }
            SensorEntry entry = decodeEntry(tag, kind);
            entries.add(entry);
            NBTTagCompound rest = unknownKeys(tag, ENTRY_KEYS);
            if (!rest.hasNoTags()) {
                extras.put(entry.id(), rest);
            }
        }
        return new Loaded(
            source,
            version,
            saved,
            runs,
            entries,
            new Preserved(foreign, extras, unknownKeys(root, ROOT_KEYS)),
            dropped);
    }

    static SensorEntry decodeEntry(NBTTagCompound tag, int kind) {
        UUID id = new UUID(tag.getLong(KEY_ID_MSB), tag.getLong(KEY_ID_LSB));
        UUID owner = tag.hasKey(KEY_OWNER_MSB, TAG_LONG) && tag.hasKey(KEY_OWNER_LSB, TAG_LONG)
            ? new UUID(tag.getLong(KEY_OWNER_MSB), tag.getLong(KEY_OWNER_LSB))
            : null;
        SensorIdentity identity = new SensorIdentity(
            id,
            tag.getString(KEY_LABEL),
            owner,
            tag.getString(KEY_OWNER_NAME),
            tag.getLong(KEY_CREATED));
        SensorState state = SensorState.fromPersistedCode(tag.getByte(KEY_STATE));
        if (state == null) {
            // An unknown state code is not a reason to lose the sensor: UNLOADED is the state every non-tombstone
            // entry gets anyway, and a heartbeat or the stale-expiry window decides what happens next.
            state = SensorState.UNLOADED;
        }
        long seen = tag.getLong(KEY_SEEN);
        long since = tag.getLong(KEY_SINCE);
        SensorEntry entry = new SensorEntry(
            identity,
            kind,
            tag.getInteger(KEY_DIM),
            tag.getInteger(KEY_X),
            tag.getInteger(KEY_Y),
            tag.getInteger(KEY_Z),
            tag.getByte(KEY_SIDE),
            state,
            seen);
        entry.restoreState(state, RemovalCause.fromCode(tag.getByte(KEY_CAUSE)), since, seen);
        entry.setMachineMetadata(
            tag.getInteger(KEY_META_ID),
            tag.getString(KEY_META_NAME),
            tag.getString(KEY_MACHINE_NAME),
            tag.getString(KEY_STATUS_ID));
        return entry;
    }

    // --- files ---

    /**
     * Reads {@code registry.dat}, else {@code registry.dat.bak}, else nothing (design-v0.2 §8.3). A gzip stream that
     * is truncated (an unclean stop during the move, or a half-written backup) throws while it is being read, which is
     * exactly what makes the fallback fire; the caller logs one WARN for a {@link Source#BACKUP} or
     * {@link Source#UNREADABLE} result.
     */
    public static Loaded load(FileStore store) throws IOException {
        byte[] primary = store.readRegistry();
        byte[] backup = store.readRegistryBackup();
        if (primary == null && backup == null) {
            return Loaded.empty(Source.NONE);
        }
        // A primary that decodes wins, even as UNSUPPORTED: a v>1 file is renamed and the world starts empty, never
        // rolled back to an older backup of the same world.
        Loaded first = tryDecode(primary, Source.PRIMARY);
        if (first != null) {
            return first;
        }
        Loaded second = tryDecode(backup, Source.BACKUP);
        return second != null ? second : Loaded.empty(Source.UNREADABLE);
    }

    private static Loaded tryDecode(byte[] bytes, Source source) {
        if (bytes == null) {
            return null;
        }
        try {
            return decode(CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes)), source);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // --- helpers ---

    private static NBTTagCompound copyOf(NBTTagCompound tag) {
        return tag == null ? new NBTTagCompound() : (NBTTagCompound) tag.copy();
    }

    /** A copy of {@code tag} holding only the keys {@code known} does not name. */
    private static NBTTagCompound unknownKeys(NBTTagCompound tag, Set<String> known) {
        NBTTagCompound rest = new NBTTagCompound();
        for (String key : tag.func_150296_c()) {
            if (!known.contains(key)) {
                rest.setTag(
                    key,
                    tag.getTag(key)
                        .copy());
            }
        }
        return rest;
    }
}
