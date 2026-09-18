package io.github.ldogg123.gregscope.gametest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.history.HistoryFileCodec;
import io.github.ldogg123.gregscope.history.HistoryIo;
import io.github.ldogg123.gregscope.history.NioFileStore;
import io.github.ldogg123.gregscope.registry.RegistryNbtCodec;
import io.github.ldogg123.gregscope.registry.RemovalCause;
import io.github.ldogg123.gregscope.registry.RunsTable;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-109 (design-v0.2 sections 8.1 to 8.4) on a real dedicated server: {@code registry.dat} is NBT, so its codec can
 * only be tested where Minecraft's NBT classes exist. The same batch also proves the I/O thread and the save root that
 * the unit tests can only fake: the thread is a daemon called {@code GregScope-IO}, it writes and reads a real
 * {@code .gsh} under the world folder, and the server thread is refused if it tries to write one itself.
 *
 * <p>
 * Nothing here reaches into the live registry, the sampler or the production I/O queue: the codec is exercised over
 * its own entries and a scratch {@link NioFileStore}, and the one test that wants the real save root runs its own
 * {@link HistoryIo} over it rather than draining results the persistence service is waiting for. So the batch can
 * run beside every other one.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregscope" })
public class RegistryCodecTests {

    /**
     * The name matters. Horizon-QA runs batches in alphabetical order and hands out test cells as tests start, and it
     * keeps a finished cell loaded. A batch that sorts before {@code gregscope.reload.*} therefore takes cells those
     * tests' chunks would otherwise have to themselves, and their chunk unload silently does nothing. Observed with
     * {@code gregscope.persistence}: its {@code v2Unsupported} cell landed at (0, 48), in the same chunk as
     * {@code ChunkReloadTests.basicMachineSurvivesChunkReload} at (8, 48), and that test failed on two fresh worlds
     * (once "expected different instances", once a stale OC address). {@code gregscope.storage} sorts after every
     * existing batch, so every older test keeps the cell it always had.
     */
    private static final String BATCH = "gregscope.storage";
    /** The file {@code tools/gen_fixtures.py} wrote, copied into the gametest resources by addon.gradle. */
    private static final String GOLDEN = "gregscope-gametest/fixtures/v1/registry_v1.nbt.gz.hex";

    private static final UUID GOLDEN_MACHINE = new UUID(0x3FA2C1D0A1B24C3DL, -0x5E6F708192A3B4C5L);
    private static final UUID GOLDEN_TOMBSTONE = new UUID(0x42L, 0x43L);
    private static final UUID GOLDEN_FLOW = new UUID(0xDEADBEEFL, 0xCAFEBABEL);
    private static final long GOLDEN_SAVED = 1_759_107_600L;

    private RegistryCodecTests() {}

    // --- the codec ---

    @GameTest(batch = BATCH)
    public static void roundTrip(GameTestHelper helper) {
        List<SensorEntry> entries = new ArrayList<>();
        SensorEntry live = entry(
            new UUID(1L, 2L),
            "EBF North",
            new UUID(0x1122334455667788L, 0x0A0B0C0D0E0F1011L),
            "Steve",
            SensorState.LIVE);
        live.setMachineMetadata(1001, "Electric Blast Furnace", "EBF", "running");
        entries.add(live);
        SensorEntry tombstone = entry(new UUID(3L, 4L), "", null, null, SensorState.REMOVED);
        entries.add(tombstone);

        RunsTable runs = RunsTable.loaded(stored(100L, 200L), 250L);
        runs.startRun(300L);

        NBTTagCompound root = RegistryNbtCodec.encode(entries, runs, 999L, null);
        RegistryNbtCodec.Loaded loaded = RegistryNbtCodec.decode(root, RegistryNbtCodec.Source.PRIMARY);

        helper.assertEquals(1, loaded.version(), "format version");
        helper.assertEquals(999L, loaded.saved(), "saved");
        helper.assertEquals(
            2,
            loaded.entries()
                .size(),
            "entries decoded");
        helper.assertEquals(0, loaded.droppedEntries(), "dropped entries");
        helper.assertEquals(
            2,
            loaded.runs()
                .size(),
            "runs");
        helper.assertEquals(
            200L,
            loaded.runs()
                .rows()
                .get(0)
                .stop(),
            "clean run stop");
        // The current run was stored open, so the load step closes it at the saved timestamp it was written with.
        helper.assertEquals(
            999L,
            loaded.runs()
                .rows()
                .get(1)
                .stop(),
            "the run that was open must be closed at saved on load");

        SensorEntry decodedLive = byId(loaded.entries(), new UUID(1L, 2L));
        helper.assertNotNull(decodedLive, "the live sensor did not survive");
        // Design-v0.2 section 8.3: LIVE is never persisted.
        helper.assertEquals(SensorState.UNLOADED, decodedLive.state(), "a LIVE entry must come back UNLOADED");
        helper.assertEquals("EBF North", decodedLive.label(), "label");
        helper.assertEquals(new UUID(0x1122334455667788L, 0x0A0B0C0D0E0F1011L), decodedLive.owner(), "owner");
        helper.assertEquals("Steve", decodedLive.ownerName(), "owner name");
        helper.assertEquals(1001L, decodedLive.metaId(), "metaId");
        helper.assertEquals("Electric Blast Furnace", decodedLive.metaName(), "metaName");
        helper.assertEquals("EBF", decodedLive.machineName(), "machineName");
        helper.assertEquals("running", decodedLive.lastStatusId(), "lastStatusId");
        helper.assertEquals(SensorKind.MACHINE, decodedLive.kind(), "kind");
        helper.assertEquals(live.dim(), decodedLive.dim(), "dim");
        helper.assertEquals(live.x(), decodedLive.x(), "x");
        helper.assertEquals(live.y(), decodedLive.y(), "y");
        helper.assertEquals(live.z(), decodedLive.z(), "z");
        helper.assertEquals(live.side(), decodedLive.side(), "side");
        helper.assertEquals(live.createdEpochSec(), decodedLive.createdEpochSec(), "created");
        helper.assertEquals(live.lastSeenEpochSec(), decodedLive.lastSeenEpochSec(), "lastSeen");
        helper.assertEquals(live.stateSinceEpochSec(), decodedLive.stateSinceEpochSec(), "stateSince");

        SensorEntry decodedTombstone = byId(loaded.entries(), new UUID(3L, 4L));
        helper.assertNotNull(decodedTombstone, "the tombstone did not survive");
        helper.assertEquals(SensorState.REMOVED, decodedTombstone.state(), "tombstones keep their state");
        helper.assertEquals(RemovalCause.DETACHED, decodedTombstone.removalCause(), "removal cause");
        helper.assertNull(decodedTombstone.owner(), "an unowned sensor must stay unowned");

        // A second round through the codec changes nothing: encode(decode(encode(x))) equals encode(x).
        NBTTagCompound again = RegistryNbtCodec
            .encode(loaded.entries(), loaded.runs(), loaded.saved(), loaded.preserved());
        RegistryNbtCodec.Loaded twice = RegistryNbtCodec.decode(again, RegistryNbtCodec.Source.PRIMARY);
        helper.assertEquals(
            again,
            RegistryNbtCodec.encode(twice.entries(), twice.runs(), twice.saved(), twice.preserved()),
            "a second round through the codec changed the file");
        helper.succeed();
    }

    /** Design-v0.2 section 8.3: {@code v > 1} is unsupported; the caller renames the file and starts empty. */
    @GameTest(batch = BATCH)
    public static void v2Unsupported(GameTestHelper helper) {
        NBTTagCompound root = RegistryNbtCodec.encode(new ArrayList<SensorEntry>(), new RunsTable(), 1L, null);
        root.setInteger(RegistryNbtCodec.KEY_VERSION, 2);
        RegistryNbtCodec.Loaded loaded = RegistryNbtCodec.decode(root, RegistryNbtCodec.Source.PRIMARY);
        helper.assertEquals(RegistryNbtCodec.Source.UNSUPPORTED, loaded.source(), "source");
        helper.assertEquals(2, loaded.version(), "the version must be reported so the rename can name it");
        helper.assertTrue(
            loaded.entries()
                .isEmpty(),
            "an unsupported registry must load nothing");
        helper.assertFalse(loaded.isLoaded(), "an unsupported registry is not loaded");
        helper.assertEquals(
            ".unsupported-v2",
            HistoryFileCodec.Status.UNSUPPORTED.suffix(loaded.version(), 0L),
            "rename suffix");
        helper.succeed();
    }

    /**
     * Design-v0.2 section 8.3 "Loading": {@code registry.dat}, else {@code .bak}, else empty. A gzip stream cut short
     * by an unclean stop throws while it is read, which is what makes the fallback fire.
     */
    @GameTest(batch = BATCH)
    public static void truncatedGzipFallsBackToBak(GameTestHelper helper) {
        try {
            Path root = scratch("bakfallback");
            NioFileStore store = new NioFileStore(root, () -> true);

            List<SensorEntry> entries = new ArrayList<>();
            entries.add(entry(new UUID(5L, 6L), "from the backup", null, null, SensorState.UNLOADED));
            byte[] good = RegistryNbtCodec.encodeGzipped(entries, new RunsTable(), 77L, null);
            // The backup is what the last good write left behind.
            Files.createDirectories(root);
            Files.write(store.registryBackup(), good);
            // registry.dat was cut short mid-write.
            Files.write(store.registryFile(), Arrays.copyOf(good, good.length / 2));

            RegistryNbtCodec.Loaded loaded = RegistryNbtCodec.load(store);
            helper.assertEquals(RegistryNbtCodec.Source.BACKUP, loaded.source(), "the backup must have taken over");
            helper.assertEquals(77L, loaded.saved(), "saved from the backup");
            helper.assertEquals(
                1,
                loaded.entries()
                    .size(),
                "entries from the backup");
            helper.assertEquals(
                "from the backup",
                loaded.entries()
                    .get(0)
                    .label(),
                "label from the backup");

            // A whole registry.dat wins again, without touching the backup.
            Files.write(store.registryFile(), good);
            helper.assertEquals(
                RegistryNbtCodec.Source.PRIMARY,
                RegistryNbtCodec.load(store)
                    .source(),
                "a readable registry.dat must win");

            // Neither file readable: empty, and both files are still there to be looked at by hand.
            Files.write(store.registryFile(), new byte[] { 1, 2, 3 });
            Files.write(store.registryBackup(), new byte[] { 4, 5 });
            RegistryNbtCodec.Loaded empty = RegistryNbtCodec.load(store);
            helper.assertEquals(RegistryNbtCodec.Source.UNREADABLE, empty.source(), "both files unreadable");
            helper.assertTrue(
                empty.entries()
                    .isEmpty(),
                "an unreadable registry loads nothing");
            helper.assertTrue(Files.isRegularFile(store.registryFile()), "registry.dat was deleted");
            helper.assertTrue(Files.isRegularFile(store.registryBackup()), "registry.dat.bak was deleted");

            // No files at all is a new world, not an error.
            NioFileStore fresh = new NioFileStore(scratch("emptyworld"), () -> true);
            helper.assertEquals(
                RegistryNbtCodec.Source.NONE,
                RegistryNbtCodec.load(fresh)
                    .source(),
                "a world without a registry");
            helper.succeed();
        } catch (IOException e) {
            helper.fail("I/O failed: " + e);
        }
    }

    /**
     * The golden {@code registry.dat} that {@code tools/gen_fixtures.py} writes without any NBT library: decoding it
     * checks the key names and types of design-v0.2 section 8.3 independently of this codec, and it carries the
     * design-v0.3 section 5.1 A3 cases - a kind this build does not register, and keys it does not define.
     */
    @GameTest(batch = BATCH)
    public static void goldenRegistryDecodesAndKeepsWhatItCannotUnderstand(GameTestHelper helper) {
        try {
            byte[] gz = hex(GOLDEN);
            NBTTagCompound root = CompressedStreamTools.readCompressed(new ByteArrayInputStream(gz));
            RegistryNbtCodec.Loaded loaded = RegistryNbtCodec.decode(root, RegistryNbtCodec.Source.PRIMARY);

            helper.assertEquals(1, loaded.version(), "version");
            helper.assertEquals(GOLDEN_SAVED, loaded.saved(), "saved");
            helper.assertEquals(
                2,
                loaded.runs()
                    .size(),
                "runs");
            helper.assertEquals(
                1_759_103_000L,
                loaded.runs()
                    .rows()
                    .get(0)
                    .stop(),
                "the clean run keeps its stop");
            helper.assertEquals(
                GOLDEN_SAVED,
                loaded.runs()
                    .rows()
                    .get(1)
                    .stop(),
                "the unclean run is closed at saved on load");

            // Two machine entries decoded, the flow meter kept verbatim and not decoded.
            helper.assertEquals(
                2,
                loaded.entries()
                    .size(),
                "machine entries");
            helper.assertNotNull(byId(loaded.entries(), GOLDEN_MACHINE), "the machine sensor");
            helper.assertNotNull(byId(loaded.entries(), GOLDEN_TOMBSTONE), "the tombstone");
            helper.assertNull(byId(loaded.entries(), GOLDEN_FLOW), "a kind-1 entry must not be decoded");
            helper.assertEquals(
                1,
                loaded.preserved()
                    .foreignCount(),
                "the kind-1 entry must be kept verbatim");

            SensorEntry machine = byId(loaded.entries(), GOLDEN_MACHINE);
            helper.assertEquals("EBF North", machine.label(), "label");
            helper.assertEquals("Steve", machine.ownerName(), "owner name");
            helper.assertEquals(SensorState.UNLOADED, machine.state(), "state");
            helper.assertEquals(120L, machine.x(), "x");
            helper.assertEquals(-30L, machine.z(), "z");
            helper.assertEquals(5L, machine.side(), "side");
            SensorEntry tombstone = byId(loaded.entries(), GOLDEN_TOMBSTONE);
            helper.assertEquals(SensorState.REMOVED, tombstone.state(), "tombstone state");
            helper.assertEquals(RemovalCause.DETACHED, tombstone.removalCause(), "tombstone cause");
            helper.assertEquals(-1L, tombstone.dim(), "a negative dimension id");

            // Unknown keys survive, on the root and on an entry this build does understand.
            helper.assertEquals(
                "v0.3",
                loaded.preserved()
                    .rootExtras()
                    .getString("future"),
                "an unknown root key");
            helper.assertEquals(
                7L,
                loaded.preserved()
                    .entryExtras()
                    .get(GOLDEN_MACHINE)
                    .getInteger("xtra"),
                "an unknown entry key");

            // Writing it back keeps all three entries and every key a v0.3 server would look for.
            NBTTagCompound written = RegistryNbtCodec
                .encode(loaded.entries(), loaded.runs(), loaded.saved(), loaded.preserved());
            NBTTagList entries = written.getTagList(RegistryNbtCodec.KEY_ENTRIES, 10);
            helper.assertEquals(3, entries.tagCount(), "all three entries must be written back");
            NBTTagCompound flow = null;
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound tag = entries.getCompoundTagAt(i);
                if (tag.getLong(RegistryNbtCodec.KEY_ID_MSB) == GOLDEN_FLOW.getMostSignificantBits()) {
                    flow = tag;
                }
            }
            helper.assertNotNull(flow, "the kind-1 entry was not written back");
            helper.assertEquals(1L, flow.getByte(RegistryNbtCodec.KEY_KIND), "kind");
            helper.assertEquals(4L, flow.getByte("tier"), "a v0.3-only key was lost");
            helper.assertEquals(1L, flow.getByte("lastIo"), "a v0.3-only key was lost");
            helper.assertEquals("v0.3", written.getString("future"), "the unknown root key was lost");
            RegistryNbtCodec.Loaded again = RegistryNbtCodec.decode(written, RegistryNbtCodec.Source.PRIMARY);
            helper.assertEquals(
                2,
                again.entries()
                    .size(),
                "entries after a second round");
            helper.assertEquals(
                1,
                again.preserved()
                    .foreignCount(),
                "foreign entries after a second round");
            helper.succeed();
        } catch (IOException e) {
            helper.fail("cannot read the golden registry: " + e);
        }
    }

    /** A row with no usable identity is skipped and counted, not guessed at. */
    @GameTest(batch = BATCH)
    public static void anEntryWithoutAnIdIsDroppedAndCounted(GameTestHelper helper) {
        NBTTagCompound root = RegistryNbtCodec.encode(new ArrayList<SensorEntry>(), new RunsTable(), 1L, null);
        NBTTagList entries = root.getTagList(RegistryNbtCodec.KEY_ENTRIES, 10);
        NBTTagCompound broken = new NBTTagCompound();
        broken.setInteger(RegistryNbtCodec.KEY_DIM, 0);
        entries.appendTag(broken);
        root.setTag(RegistryNbtCodec.KEY_ENTRIES, entries);

        RegistryNbtCodec.Loaded loaded = RegistryNbtCodec.decode(root, RegistryNbtCodec.Source.PRIMARY);
        helper.assertEquals(
            0,
            loaded.entries()
                .size(),
            "a row without an id must not become an entry");
        helper.assertEquals(1, loaded.droppedEntries(), "the dropped row must be counted");
        helper.succeed();
    }

    // --- the I/O thread on a real server ---

    /**
     * Design-v0.2 sections 8.1 and 8.4 on the running server: the save root is the world folder's
     * {@code gregscope/}, the I/O thread exists, it is a daemon named {@code GregScope-IO}, and a whole 92,224 B
     * history file written through it comes back byte for byte after the {@code flushIo} hook.
     */
    @GameTest(batch = BATCH, timeoutTicks = 200)
    public static void theIoThreadWritesAndReadsARealHistoryFile(GameTestHelper helper) {
        NioFileStore store = GregScope.fileStore();
        HistoryIo io = GregScope.historyIo();
        helper.assertNotNull(store, "no file store; history.persist is off or the save root was not resolved");
        helper.assertNotNull(io, "no I/O thread");
        helper.assertEquals(
            "gregscope",
            store.root()
                .getFileName()
                .toString(),
            "the save root must be <world>/gregscope/");
        helper.assertEquals(GregScope.saveRoot(), store.root(), "the store is not rooted at the save root");
        helper.assertTrue(io.isRunning(), "the I/O thread is not running");
        helper.assertTrue(io.isDaemon(), "the I/O thread must be a daemon so it can never hold up the JVM");
        helper.assertEquals("GregScope-IO", HistoryIo.THREAD_NAME, "thread name");
        helper.assertFalse(HistoryIo.onIoThread(), "the server thread must not pass for the I/O thread");

        // The tasks go through a thread of this test's own over the very same store, never through the production
        // queue: draining that one would swallow the load results of any sensor whose file is being read right now,
        // and HistoryPersistence would leave it "loading history" for the rest of the run - green here, broken
        // there. The file, the store and the save root are the real ones either way. The two threads share no file:
        // this one only ever touches one fresh UUID's .gsh (and its own <uuid>.gsh.tmp) and never the registry.
        HistoryIo own = new HistoryIo(store, 16);
        own.start();
        UUID id = UUID.randomUUID();
        try {
            helper.assertTrue(GregScopeTestHooks.flushIo(), "the flush hook did not drain the production queue");
            byte[] image = HistoryFileCodec.newFile(id, SensorKind.MACHINE, 1_759_000_000L);
            helper.assertEquals(92_224, image.length, "a history file is 92,224 B");
            helper.assertTrue(own.queueCreate(id, image), "the create task was dropped");
            byte[] slot = new byte[HistoryFileCodec.SLOT_SIZE];
            Arrays.fill(slot, (byte) 0x7E);
            helper.assertTrue(own.queueSlot(id, 11, slot), "the slot task was dropped");
            helper.assertTrue(own.queueLoad(id), "the load task was dropped");
            helper.assertTrue(own.flush(HistoryIo.STOP_TIMEOUT_MILLIS), "the queue did not drain");

            Path file = store.historyFile(id);
            helper.assertTrue(Files.isRegularFile(file), "no history file at " + file);
            helper.assertEquals(92_224L, Files.size(file), "the file is not 92,224 B");
            byte[] read = Files.readAllBytes(file);
            helper.assertEquals(
                HistoryFileCodec.Status.OK,
                HistoryFileCodec.inspect(read, id),
                "the file this server wrote does not pass its own header rules");
            helper.assertTrue(
                Arrays.equals(
                    slot,
                    Arrays.copyOfRange(read, HistoryFileCodec.slotOffset(11), HistoryFileCodec.slotOffset(12))),
                "the slot did not land at index 11");

            List<HistoryIo.LoadResult> results = own.drainLoaded();
            helper.assertEquals(1, results.size(), "the async load produced no result: " + results);
            HistoryIo.LoadResult result = results.get(0);
            helper.assertEquals(id, result.id(), "another sensor's result");
            helper.assertEquals(HistoryFileCodec.Status.OK, result.status(), "load status");
            helper.assertFalse(result.failed(), "the load failed");
            helper.assertTrue(Arrays.equals(read, result.file()), "the loaded bytes differ from the file");

            helper.assertTrue(own.queueDelete(id), "the delete task was dropped");
            helper.assertTrue(own.flush(HistoryIo.STOP_TIMEOUT_MILLIS), "the queue did not drain");
            helper.assertFalse(Files.exists(file), "the history file was not deleted");
            helper.succeed();
        } catch (IOException e) {
            own.queueDelete(id);
            own.flush(HistoryIo.STOP_TIMEOUT_MILLIS);
            helper.fail("I/O failed: " + e);
        } finally {
            own.stop();
        }
    }

    /**
     * The design-v0.2 section 14 acceptance criterion, on the machine that matters: the shipped store refuses a write
     * from the server thread under {@code -ea}.
     *
     * <p>
     * Assertions being on is a <b>precondition, not a branch</b>. The check is worth nothing without {@code -ea},
     * and a test that quietly skips the only thing it asserts would turn "the flag was lost" (a one-line change in
     * {@code addon.gradle} or a Gradle upgrade that rewrites the run tasks) into a green run. {@code runServer} and
     * {@code runClient} pass {@code -ea:io.github.ldogg123.gregscope...}; if they stop, this fails and says so.
     */
    @GameTest(batch = BATCH)
    public static void theServerThreadCannotWriteAFile(GameTestHelper helper) {
        NioFileStore store = GregScope.fileStore();
        helper.assertNotNull(store, "no file store");
        boolean assertionsOn = NioFileStore.class.desiredAssertionStatus();
        GregScope.LOG.info(
            "GS-109: assertions are {} in this JVM, so the server-thread file I/O check is {}",
            assertionsOn ? "on" : "off",
            assertionsOn ? "enforced" : "NOT enforced here");
        helper.assertTrue(
            assertionsOn,
            "assertions are off in this JVM, so the server-thread file I/O check cannot fire at all;"
                + " runServer/runClient must pass -ea:io.github.ldogg123.gregscope... (addon.gradle)");
        UUID id = UUID.randomUUID();
        helper.assertThrows(AssertionError.class, () -> {
            try {
                store.deleteHistory(id);
            } catch (IOException e) {
                throw new IllegalStateException("expected an AssertionError, got " + e, e);
            }
        }, "the server thread was allowed to touch the disk");
        // The production store asks HistoryIo, and the server thread is not it.
        helper.assertFalse(HistoryIo.onIoThread(), "the server thread must not be the I/O thread");
        helper.succeed();
    }

    // --- helpers ---

    private static SensorEntry entry(UUID id, String label, UUID owner, String ownerName, SensorState state) {
        SensorIdentity identity = new SensorIdentity(id, label, owner, ownerName, 1_758_000_000L);
        SensorEntry entry = new SensorEntry(identity, SensorKind.MACHINE, 0, 10, 64, -20, 3, state, 1_759_000_000L);
        entry.restoreState(
            state,
            state.isTombstone() ? RemovalCause.DETACHED : RemovalCause.NONE,
            1_759_000_500L,
            1_759_001_000L);
        return entry;
    }

    private static SensorEntry byId(List<SensorEntry> entries, UUID id) {
        for (SensorEntry entry : entries) {
            if (entry.id()
                .equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private static List<long[]> stored(long start, long stop) {
        List<long[]> rows = new ArrayList<>();
        rows.add(new long[] { start, stop });
        return rows;
    }

    /** A scratch directory outside the world, so nothing here can disturb the live persistence or the save folder. */
    private static Path scratch(String name) throws IOException {
        return Files.createTempDirectory("gregscope-" + name);
    }

    /** The same {@code .hex} format the unit-test fixtures use: hex byte pairs, {@code #} starts a comment. */
    private static byte[] hex(String resource) throws IOException {
        InputStream in = RegistryCodecTests.class.getClassLoader()
            .getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("missing fixture " + resource);
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
                raw.write(buffer, 0, n);
            }
        } finally {
            in.close();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String line : new String(raw.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
            int hash = line.indexOf('#');
            String data = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (data.isEmpty()) {
                continue;
            }
            for (String token : data.split("\\s+")) {
                out.write(Integer.parseInt(token, 16));
            }
        }
        return out.toByteArray();
    }
}
