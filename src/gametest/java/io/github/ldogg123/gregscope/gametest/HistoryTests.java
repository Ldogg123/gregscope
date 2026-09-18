package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.WorldEvent;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.AfterBatch;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.GregScopeWorldEvents;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapRanges;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.HistoryFileCodec;
import io.github.ldogg123.gregscope.history.HistoryPersistence;
import io.github.ldogg123.gregscope.history.MinuteAccumulator;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.history.NioFileStore;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.RegistryNbtCodec;
import io.github.ldogg123.gregscope.registry.RegistryPersistence;
import io.github.ldogg123.gregscope.registry.RunsTable;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.FakeClock;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;

/**
 * GS-110 (design-v0.2 sections 7.5, 8.1, 8.3, 8.4 and 14): persistence on a real dedicated server. Minutes really
 * reach {@code <world>/gregscope/history/<uuid>.gsh}, exactly one 64-byte write per closed minute; a chunk unload
 * closes the open minute as a partial slot and nothing is written afterwards; a simulated restart in the same JVM
 * reads the registry and the rings back and leaves a {@code server_offline} gap over the downtime; an expired sensor
 * loses its file; and {@code history.persist=false} writes no {@code .gsh} while the registry is still saved.
 *
 * <p>
 * A Horizon-QA warp never advances server time (errata E2), so time is a {@link FakeClock} installed through the
 * section 13.1 hooks and history is driven with {@code sampleNow}. Every test empties the registry first, because
 * Horizon-QA keeps finished cells loaded and their covers keep heartbeating; a fake clock that jumps would otherwise
 * be visible to sensors this batch knows nothing about.
 *
 * <p>
 * <b>Batch names.</b> Horizon-QA runs batches alphabetically and keeps a finished cell loaded, so a batch that sorts
 * before another takes cells that batch's chunks would otherwise have to themselves (GS109-T1). Both batches here
 * sort after every existing one, and the chunk-unload tests sit in their own batch that sorts after the rest of this
 * holder, so no test whose assertions still matter can have its chunk evicted.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class HistoryTests {

    /** Sorts after {@code gregscope.storage}, which is itself after every other batch. */
    private static final String BATCH = "gregscope.storage.history";
    /**
     * The two chunk-unload tests get a batch each, and both sort after {@link #BATCH}. Horizon-QA runs the tests of
     * one batch together and hands out neighbouring cells, which can share a chunk: with both in one batch the second
     * test's setup loaded the chunk the first had just unloaded, and the first saw its sensor still LIVE (observed on
     * a fresh world, cells (0, 88) and (8, 88), both in chunk [0, 5]). One test per batch is what the existing reload
     * tests do for the same reason.
     */
    private static final String UNLOAD_BATCH = "gregscope.storage.history.unload";
    private static final String NO_WRITE_BATCH = "gregscope.storage.history.unloadwrites";

    private static final TestPos MACHINE = at(1, 0, 1);
    private static final ForgeDirection COVERED = ForgeDirection.UP;
    /**
     * 2033-05-18T03:34:00Z: exactly on a minute boundary, so a whole minute is exactly 60 one-second samples, and
     * <em>after</em> the real clock, so the run this process started under the system clock cannot stop "before" it
     * ({@code RunsTable.stopRun} never lets a run stop before it started).
     */
    private static final long BASE_EPOCH_SEC = 33_333_334L * 60L;
    private static final int SAMPLES_PER_MINUTE = 60;
    private static final int MINUTES = 3;
    /** Ticks waited for a chunk to really be gone; the same figure the other reload tests use. */
    private static final int UNLOAD_TICKS = 1;
    /** Downtime the simulated restart puts between the two runs, in seconds. */
    private static final int DOWNTIME_SEC = 300;

    private HistoryTests() {}

    @AfterBatch(BATCH)
    public static void cleanUpAfterHistory() {
        SensorCleanup.detachAllAndPurge();
    }

    @AfterBatch(UNLOAD_BATCH)
    public static void cleanUpAfterHistoryUnload() {
        SensorCleanup.detachAllAndPurge();
    }

    @AfterBatch(NO_WRITE_BATCH)
    public static void cleanUpAfterHistoryNoWrites() {
        SensorCleanup.detachAllAndPurge();
    }

    // --- writing (design-v0.2 sections 8.2 and 8.4) ---

    /**
     * Design-v0.2 section 14 {@code threeMinutesWrittenToFile}: three whole minutes of one sample per second land in
     * the real file, each as 60 samples whose per-state counts add up, and each closed minute costs exactly one
     * 64-byte write.
     */
    @GameTest(batch = BATCH, timeoutTicks = 200)
    public static void threeMinutesWrittenToFile(GameTestHelper helper) {
        FakeClock clock = freshRun(helper);
        UUID id = liveSensor(helper);
        SensorEntry entry = entry(helper, id);
        HistoryPersistence history = history(helper);
        long writesBefore = history.slotsWritten();
        long closedBefore = history.minutesClosed();
        long droppedBefore = history.tasksDropped();
        helper.assertTrue(history.filePresent(id), "the history file was not created before the first minute closed");

        int firstMinute = MinuteAccumulator.epochMinute(clock.epochSec());
        // One sample past the third minute, because a minute closes when a sample for the next one arrives.
        record(helper, clock, id, MINUTES * SAMPLES_PER_MINUTE + 1);
        flush(helper);

        helper.assertEquals(MINUTES, history.minutesClosed() - closedBefore, "three whole minutes must have closed");
        helper.assertEquals(
            MINUTES,
            history.slotsWritten() - writesBefore,
            "exactly one slot write per closed minute per sensor");
        helper.assertEquals(droppedBefore, history.tasksDropped(), "no task may be dropped in this test");

        byte[] file = read(helper, id);
        helper.assertEquals(HistoryFileCodec.FILE_BYTES, file.length, "a history file is 92,224 B");
        helper.assertEquals(
            HistoryFileCodec.Status.OK,
            HistoryFileCodec.inspect(file, id),
            "the file this server wrote does not pass its own header rules");
        for (int i = 0; i < MINUTES; i++) {
            int minute = firstMinute + i;
            MinuteSlot onDisk = MinuteSlot.decode(file, HistoryFileCodec.slotOffset(Math.floorMod(minute, 1440)));
            helper.assertNotNull(onDisk, "minute " + i + " is not in the file");
            helper.assertEquals(minute, onDisk.epochMinute(), "the slot landed at the wrong index");
            helper.assertEquals(SAMPLES_PER_MINUTE, onDisk.samples(), "samples in minute " + i);
            helper.assertEquals(0, onDisk.gapMask(), "a fully sampled minute must carry no gap reason");
            int stateSum = 0;
            for (int code = 0; code < StateCodes.COUNT; code++) {
                stateSum += onDisk.stateSamples(code);
            }
            helper.assertEquals(SAMPLES_PER_MINUTE, stateSum, "the per-state counts of minute " + i + " must add up");
            helper.assertEquals(
                onDisk,
                entry.minutes()
                    .slot(minute),
                "the file and the RAM ring disagree about minute " + i);
        }
        Snapshots.log(
            "history#1 three minutes on disk",
            "file=" + file.length + " B writes=" + (history.slotsWritten() - writesBefore));
        helper.succeed();
    }

    /**
     * Design-v0.2 section 14 {@code simulatedRestartRestoresHistory}: stopping and starting GregScope's services in
     * this JVM rebuilds the registry from {@code registry.dat} and the minute ring from the {@code .gsh}, and the
     * downtime in between reads as {@code server_offline} (section 7.5 rule 3).
     */
    @GameTest(batch = BATCH, timeoutTicks = 200)
    public static void simulatedRestartRestoresHistory(GameTestHelper helper) {
        FakeClock clock = freshRun(helper);
        MachineSensorCover cover = placeSensor(helper);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        settle(helper);
        SensorEntry before = entry(helper, id);
        int firstMinute = MinuteAccumulator.epochMinute(clock.epochSec());
        record(helper, clock, id, 2 * SAMPLES_PER_MINUTE + 1);
        flush(helper);
        List<MinuteSlot> recorded = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            MinuteSlot slot = before.minutes()
                .slot(firstMinute + i);
            helper.assertNotNull(slot, "minute " + i + " was not recorded before the restart");
            recorded.add(slot);
        }

        // --- the restart, with real downtime in between ---
        long stopped = clock.epochSec();
        helper.assertTrue(GregScopeTestHooks.stopServicesNow(), "GregScope test hooks are disabled");
        helper.assertNull(GregScope.registry(), "the registry must be gone while the services are stopped");
        clock.advanceSeconds(DOWNTIME_SEC);
        long restarted = clock.epochSec();
        helper.assertTrue(GregScopeTestHooks.startServicesNow(), "GregScope test hooks are disabled");
        flush(helper);
        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");
        flush(helper);

        SensorEntry after = entry(helper, id);
        helper.assertNotSame(before, after, "the restart must build a new entry from the file");
        // Design-v0.2 section 8.3: LIVE is never persisted.
        helper.assertEquals(SensorState.UNLOADED, after.state(), "a restored sensor starts UNLOADED");
        helper.assertTrue(after.historyLoaded(), "the history file was not merged after the restart");
        for (MinuteSlot slot : recorded) {
            helper.assertEquals(
                slot,
                after.minutes()
                    .slot(slot.epochMinute()),
                "minute " + slot.epochMinute() + " did not survive the restart");
        }

        // The runs table: the previous run is closed, this one is open, and the downtime belongs to neither.
        RunsTable runs = registry(helper).core()
            .runs();
        helper.assertTrue(runs.size() >= 2, "the runs table must hold the previous run too: " + runs);
        RunsTable.Row previous = runs.rows()
            .get(runs.size() - 2);
        helper.assertFalse(previous.isOpen(), "the previous run must be closed: " + runs);
        helper.assertEquals(stopped, previous.stop(), "the previous run must stop when the services did");
        helper.assertEquals(
            restarted,
            runs.currentRun()
                .start(),
            "this run must start when the services did");

        List<GapRanges.Range> gaps = GapRanges.minutes(
            after.minutes(),
            firstMinute,
            MinuteAccumulator.epochMinute(restarted),
            registry(helper).core()
                .gapContext(id, restarted));
        GapRanges.Range offline = null;
        for (GapRanges.Range range : gaps) {
            if (range.reason == GapReason.SERVER_OFFLINE) {
                helper.assertNull(offline, "more than one server_offline range: " + gaps);
                offline = range;
            }
        }
        helper.assertNotNull(offline, "the downtime must read as server_offline, got " + gaps);
        // Ranges are whole minutes: the window ends at the start of the minute the server came back in.
        helper.assertEquals(
            MinuteAccumulator.epochMinute(restarted) * 60L,
            offline.to,
            "the offline range must run up to the window's end");
        helper.assertTrue(
            offline.from <= (MinuteAccumulator.epochMinute(stopped) + 1L) * 60L,
            "the offline range must start at the first whole minute after the stop, got " + offline
                + " for a stop at "
                + stopped);

        // And the sensor comes back LIVE on its next heartbeat, with the same UUID and the same history.
        MachineSensorCover live = sensorCover(helper);
        helper.assertEquals(
            id,
            live.identity()
                .id(),
            "the cover lost its UUID across the restart");
        heartbeat(helper, live);
        helper.assertEquals(SensorState.LIVE, entry(helper, id).state(), "the sensor must come back LIVE");
        Snapshots.log(
            "history#2 simulated restart",
            "runs=" + runs.size() + " offline=" + offline + " minutes=" + recorded.size());
        helper.succeed();
    }

    /**
     * Design-v0.2 section 14 {@code removedFileDeletedAfterRetention}: a detached sensor is a tombstone, its file
     * survives {@code history.removedRetentionHours}, and the housekeeping sweep after that deletes it.
     */
    @GameTest(batch = BATCH, timeoutTicks = 200)
    public static void removedFileDeletedAfterRetention(GameTestHelper helper) {
        FakeClock clock = freshRun(helper);
        helper.assertTrue(
            GregScopeTestHooks.overrideSettings(
                Settings.builder()
                    .removedRetentionHours(1)
                    .build()),
            "GregScope test hooks are disabled");
        UUID id = liveSensor(helper);
        record(helper, clock, id, 10);
        flush(helper);
        Path file = historyFile(helper, id);
        helper.assertTrue(Files.isRegularFile(file), "no history file at " + file);

        // Detaching the cover makes the entry a REMOVED tombstone (section 4.3) and closes its partial minute.
        TileEntity tile = helper.assertTileEntityPresent(MACHINE);
        IGregTechTileEntity machine = helper.assertInstanceOf(IGregTechTileEntity.class, tile, "machine");
        machine.detachCover(COVERED);
        flush(helper);
        SensorEntry tombstone = entry(helper, id);
        helper.assertTrue(
            tombstone.state()
                .isTombstone(),
            "the detached sensor must be a tombstone, not " + tombstone.state());
        helper.assertTrue(Files.isRegularFile(file), "the file must survive the removal itself");
        helper.assertEquals(0, GregScopeTestHooks.housekeepingNow(), "nothing may expire inside the retention window");

        clock.advanceSeconds(2 * 3600L);
        helper.assertEquals(1, GregScopeTestHooks.housekeepingNow(), "the tombstone must expire after one hour");
        flush(helper);
        helper.assertNull(
            registry(helper).core()
                .entry(id),
            "the expired entry must be gone from the registry");
        helper.assertFalse(Files.exists(file), "the expired sensor's history file must be deleted: " + file);
        Snapshots.log("history#3 retention delete", "file=" + file.getFileName());
        helper.succeed();
    }

    /**
     * Design-v0.2 section 8.4: {@code history.persist=false} writes no {@code .gsh} at all, keeps the minutes in RAM,
     * and still saves the registry ("no .gsh files, RAM rings only. The registry is still saved.").
     */
    @GameTest(batch = BATCH, timeoutTicks = 200)
    public static void persistFalseWritesNoGsh(GameTestHelper helper) {
        FakeClock clock = freshRun(helper);
        helper.assertTrue(
            GregScopeTestHooks.overrideSettings(
                Settings.builder()
                    .historyPersist(false)
                    .build()),
            "GregScope test hooks are disabled");
        HistoryPersistence history = history(helper);
        helper.assertFalse(history.enabled(), "the history service must be off with history.persist=false");
        long writesBefore = history.slotsWritten();
        long createsBefore = history.filesCreated();
        long loadsBefore = history.loadsRequested();

        MachineSensorCover cover = placeSensor(helper);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        settle(helper);
        int firstMinute = MinuteAccumulator.epochMinute(clock.epochSec());
        record(helper, clock, id, 2 * SAMPLES_PER_MINUTE + 1);
        flush(helper);

        SensorEntry entry = entry(helper, id);
        helper.assertEquals(
            SAMPLES_PER_MINUTE,
            entry.minutes()
                .slot(firstMinute)
                .samples(),
            "the minute ring must still hold the history in RAM");
        helper.assertEquals(writesBefore, history.slotsWritten(), "no slot may be written");
        helper.assertEquals(createsBefore, history.filesCreated(), "no history file may be created");
        helper.assertEquals(loadsBefore, history.loadsRequested(), "no history file may be read");
        Path file = historyFile(helper, id);
        helper.assertFalse(Files.exists(file), "a .gsh was written although history.persist is false: " + file);

        // The registry is still saved: that is the other half of the section 8.4 sentence.
        helper.assertTrue(GregScopeTestHooks.saveRegistryNow(true), "the registry write was not queued");
        flush(helper);
        Path registry = store(helper).registryFile();
        helper.assertTrue(Files.isRegularFile(registry), "registry.dat must still be written: " + registry);
        Snapshots.log("history#4 persist=false", "registry=" + registry.getFileName() + " no .gsh for " + id);
        helper.succeed();
    }

    /**
     * Design-v0.2 section 8.3 "Saving": the registry is written when it is dirty, on the <em>overworld's</em>
     * {@code WorldEvent.Save}, and not written again while nothing changed. The event is posted on the real Forge bus,
     * exactly as Minecraft posts it on every world save, so what is under test is the subscription as well as the
     * handler. A world that is not dimension 0 is ignored; that half is driven through the handler directly, because
     * posting another dimension's save on the shared bus would make every other mod save it too.
     *
     * <p>
     * This is the only automated cover for the section 8.3 trigger: Horizon-QA's {@code mode=ci} finishes with
     * {@code FMLCommonHandler.exitJava}, so {@code FMLServerStoppingEvent}, {@code FMLServerStoppedEvent} and the
     * overworld's {@code WorldEvent.Unload} never fire in a suite run (observed in the GS-110 run log). The stop path
     * itself is covered by {@code simulatedRestartRestoresHistory}, which runs exactly those two bodies through the
     * test hooks.
     */
    @GameTest(batch = BATCH, timeoutTicks = 200)
    public static void overworldSaveWritesTheRegistry(GameTestHelper helper) {
        freshRun(helper);
        UUID id = liveSensor(helper);
        RegistryPersistence persistence = GregScope.registryPersistence();
        helper.assertNotNull(persistence, "GregScope has no registry persistence; is the server running?");
        helper.assertTrue(
            registry(helper).core()
                .dirty(),
            "a fresh registration must mark the registry dirty");
        long savesBefore = persistence.savesQueued();
        WorldServer overworld = helper.getWorld();
        helper.assertEquals(0, overworld.provider.dimensionId, "the test grid must be in the overworld");

        MinecraftForge.EVENT_BUS.post(new WorldEvent.Save(overworld));
        flush(helper);

        helper.assertEquals(
            savesBefore + 1L,
            persistence.savesQueued(),
            "the overworld's WorldEvent.Save must write the registry");
        helper.assertFalse(
            registry(helper).core()
                .dirty(),
            "a save must clear the dirty flag");
        Path file = store(helper).registryFile();
        helper.assertTrue(Files.isRegularFile(file), "registry.dat was not written: " + file);

        // What landed on disk really is this registry: decode it with the shipped codec and find the sensor.
        RegistryNbtCodec.Loaded loaded = decodeRegistry(helper, file);
        helper.assertEquals(RegistryNbtCodec.VERSION, loaded.version(), "format version");
        SensorEntry saved = null;
        for (SensorEntry entry : loaded.entries()) {
            if (entry.id()
                .equals(id)) {
                saved = entry;
            }
        }
        helper.assertNotNull(saved, "the live sensor is not in the file that was just written");
        // Design-v0.2 section 8.3: LIVE is never persisted.
        helper.assertEquals(SensorState.UNLOADED, saved.state(), "a LIVE sensor must be written as UNLOADED");
        helper.assertTrue(
            loaded.runs()
                .size() >= 1,
            "the runs table must be written too");

        // Nothing changed since, so nothing is written again (section 8.3 "when dirty").
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Save(overworld));
        flush(helper);
        helper.assertEquals(savesBefore + 1L, persistence.savesQueued(), "a clean registry must not be written again");

        // Another dimension's save is not GregScope's: everything lives under the overworld save root (section 8.1).
        WorldServer other = null;
        for (int dim : new int[] { -1, 1 }) {
            WorldServer candidate = DimensionManager.getWorld(dim);
            if (candidate != null) {
                other = candidate;
            }
        }
        if (other == null) {
            Snapshots.log("history#7 registry save", "no second dimension is running; the dim filter is not exercised");
        } else {
            registry(helper).core()
                .markDirty();
            GregScopeWorldEvents.instance()
                .onWorldSave(new WorldEvent.Save(other));
            helper.assertEquals(
                savesBefore + 1L,
                persistence.savesQueued(),
                "a save of dimension " + other.provider.dimensionId + " must not write GregScope's registry");
            helper.assertTrue(
                registry(helper).core()
                    .dirty(),
                "the dirty flag must survive another dimension's save");
        }
        Snapshots.log("history#7 registry save", "saves=" + persistence.savesQueued());
        helper.succeed();
    }

    // --- chunk unload (its own batch: these evict a chunk) ---

    /**
     * Design-v0.2 section 14 {@code unloadClosesPartialMinuteWithChunkUnloaded}: the minute that was open when the
     * chunk went away is written as a partial slot carrying {@code chunk_unloaded}, and it is the last thing written.
     */
    @GameTest(batch = UNLOAD_BATCH, timeoutTicks = 200)
    public static void unloadClosesPartialMinuteWithChunkUnloaded(GameTestHelper helper) {
        FakeClock clock = freshRun(helper);
        MachineSensorCover cover = placeSensor(helper);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        settle(helper);
        SensorEntry entry = entry(helper, id);
        int minute = MinuteAccumulator.epochMinute(clock.epochSec());
        int samples = 10;
        record(helper, clock, id, samples);
        flush(helper);
        // Per-sensor, not the global counters: other cells keep heartbeating while this test idles a tick.
        byte[] before = read(helper, id);
        ChunkReload chunks = ChunkReload.ofChunkAt(helper, MACHINE);

        helper.startSequence()
            .thenExecute("unload the chunk", chunks::unload)
            .thenIdle(UNLOAD_TICKS)
            .thenExecute("the partial minute is on disk", () -> {
                helper.assertEquals(SensorState.UNLOADED, entry.state(), "state after the unload");
                flush(helper);
                byte[] file = read(helper, id);
                helper.assertEquals(
                    1,
                    changedSlots(before, file),
                    "the unload must change exactly one slot of this sensor's file");
                MinuteSlot partial = MinuteSlot.decode(file, HistoryFileCodec.slotOffset(Math.floorMod(minute, 1440)));
                helper.assertNotNull(partial, "the partial minute is not in the file");
                helper.assertEquals(minute, partial.epochMinute(), "epochMinute");
                helper.assertEquals(samples, partial.samples(), "the samples taken before the unload");
                helper.assertTrue(
                    partial.hasFlag(MinuteSlot.FLAG_PARTIAL_MINUTE),
                    "the slot must be flagged partial, flags " + partial.flags());
                helper.assertTrue(
                    (partial.gapMask() & GapReason.CHUNK_UNLOADED.mask()) != 0,
                    "the slot must carry chunk_unloaded, mask " + partial.gapMask());
                Snapshots.log("history#5 partial minute at unload", partial.toString());
            })
            .thenExecute("reload", chunks::reload)
            .thenSucceed();
    }

    /**
     * Design-v0.2 section 14 {@code noSlotsWhileUnloaded}: once the sensor is UNLOADED nothing more is written for it,
     * however many intervals run and however far the clock moves.
     */
    @GameTest(batch = NO_WRITE_BATCH, timeoutTicks = 200)
    public static void noSlotsWhileUnloaded(GameTestHelper helper) {
        FakeClock clock = freshRun(helper);
        MachineSensorCover cover = placeSensor(helper);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        settle(helper);
        SensorEntry entry = entry(helper, id);
        record(helper, clock, id, 30);
        flush(helper);
        ChunkReload chunks = ChunkReload.ofChunkAt(helper, MACHINE);

        helper.startSequence()
            .thenExecute("unload the chunk", chunks::unload)
            .thenIdle(UNLOAD_TICKS)
            .thenExecute("nothing more is written", () -> {
                helper.assertEquals(SensorState.UNLOADED, entry.state(), "state after the unload");
                flush(helper);
                byte[] before = read(helper, id);
                helper.assertFalse(
                    entry.accumulator()
                        .isOpen(),
                    "an unloaded sensor must have no open minute");
                for (int i = 0; i < 5; i++) {
                    clock.advanceSeconds(60L);
                    helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");
                }
                flush(helper);
                helper.assertEquals(
                    0,
                    changedSlots(before, read(helper, id)),
                    "an unloaded sensor must write nothing, however many intervals run");
                helper.assertFalse(
                    entry.accumulator()
                        .isOpen(),
                    "an unloaded sensor must still have no open minute");
                Snapshots.log("history#6 no writes while unloaded", "5 intervals, 0 slots changed");
            })
            .thenExecute("reload", chunks::reload)
            .thenSucceed();
    }

    // --- helpers ---

    /**
     * Empties the registry and installs a fake clock at a known minute boundary, restoring both afterwards. Every
     * test starts from here, because Horizon-QA keeps finished cells loaded and their covers keep heartbeating.
     */
    private static FakeClock freshRun(GameTestHelper helper) {
        helper.assertTrue(GregScopeTestHooks.purgeAllNow() >= 0, "GregScope test hooks are disabled");
        FakeClock clock = FakeClock.atEpochSec(BASE_EPOCH_SEC);
        helper.assertTrue(GregScopeTestHooks.setClock(clock), "GregScope test hooks are disabled");
        helper.afterTest(() -> {
            GregScopeTestHooks.clearSettingsOverride();
            GregScopeTestHooks.setClock(null);
            GregScopeTestHooks.purgeAllNow();
        });
        return clock;
    }

    /** Places a machine, attaches a sensor, heartbeats it and lets its (absent) history file be created. */
    private static UUID liveSensor(GameTestHelper helper) {
        MachineSensorCover cover = placeSensor(helper);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        settle(helper);
        return id;
    }

    /**
     * Lets the async history load land and its answer (a fresh file) be written, the way the sampler's per-interval
     * drain does on a running server.
     */
    private static void settle(GameTestHelper helper) {
        flush(helper);
        // Not runIntervalNow: that would also take a sample, and every minute here is meant to hold exactly 60.
        helper.assertTrue(GregScopeTestHooks.applyHistoryLoadsNow() >= 0, "GregScope test hooks are disabled");
        flush(helper);
    }

    /** One sample per second of fake time, which is what the 1 Hz sampler does on a running server. */
    private static void record(GameTestHelper helper, FakeClock clock, UUID id, int seconds) {
        for (int i = 0; i < seconds; i++) {
            helper.assertTrue(GregScopeTestHooks.sampleNow(id), "the sample did not resolve its target");
            clock.advanceSeconds(1L);
        }
    }

    private static void flush(GameTestHelper helper) {
        helper.assertTrue(GregScopeTestHooks.flushIo(), "the I/O queue did not drain");
    }

    private static SensorRegistry registry(GameTestHelper helper) {
        SensorRegistry registry = GregScope.registry();
        helper.assertNotNull(registry, "GregScope has no registry; is the server running?");
        return registry;
    }

    private static HistoryPersistence history(GameTestHelper helper) {
        HistoryPersistence history = GregScope.history();
        helper.assertNotNull(history, "GregScope has no history persistence; is the server running?");
        return history;
    }

    private static NioFileStore store(GameTestHelper helper) {
        NioFileStore store = GregScope.fileStore();
        helper.assertNotNull(store, "no file store; the save root was not resolved");
        return store;
    }

    private static SensorEntry entry(GameTestHelper helper, UUID id) {
        SensorEntry entry = registry(helper).core()
            .entry(id);
        helper.assertNotNull(entry, "no registry entry for sensor " + id);
        return entry;
    }

    private static Path historyFile(GameTestHelper helper, UUID id) {
        return store(helper).historyFile(id);
    }

    /** Reads the file with plain NIO: {@code NioFileStore} itself refuses the server thread under {@code -ea}. */
    private static byte[] read(GameTestHelper helper, UUID id) {
        Path file = historyFile(helper, id);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            helper.fail("cannot read " + file + ": " + e);
            return new byte[0];
        }
    }

    /** Reads and decodes {@code registry.dat} with the shipped codec; plain NIO, because the store refuses us. */
    private static RegistryNbtCodec.Loaded decodeRegistry(GameTestHelper helper, Path file) {
        try {
            NBTTagCompound root = CompressedStreamTools
                .readCompressed(new ByteArrayInputStream(Files.readAllBytes(file)));
            return RegistryNbtCodec.decode(root, RegistryNbtCodec.Source.PRIMARY);
        } catch (IOException e) {
            helper.fail("cannot read " + file + ": " + e);
            return null;
        }
    }

    /** How many of the 1,440 64-byte slots differ between two reads of the same file. */
    private static int changedSlots(byte[] before, byte[] after) {
        int changed = 0;
        for (int index = 0; index < 1440; index++) {
            int off = HistoryFileCodec.slotOffset(index);
            for (int i = 0; i < MinuteSlot.SIZE; i++) {
                if (before[off + i] != after[off + i]) {
                    changed++;
                    break;
                }
            }
        }
        return changed;
    }

    private static void heartbeat(GameTestHelper helper, MachineSensorCover cover) {
        helper.assertTrue(GregScopeTestHooks.heartbeatNow(cover), "GregScope test hooks are disabled");
    }

    private static MachineSensorCover placeSensor(GameTestHelper helper) {
        IGregTechTileEntity machine = GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_E_Furnace.get(1L));
        helper.assertTrue(
            SensorPlacementTests.placeViaCoverPlacer(helper, machine, COVERED),
            "the sensor was refused on " + COVERED);
        return sensorCover(helper);
    }

    private static MachineSensorCover sensorCover(GameTestHelper helper) {
        TileEntity tile = helper.assertTileEntityPresent(MACHINE);
        IGregTechTileEntity machine = helper.assertInstanceOf(IGregTechTileEntity.class, tile, "machine");
        MachineSensorCover cover = helper
            .assertInstanceOf(MachineSensorCover.class, machine.getCoverAtSide(COVERED), "sensor cover on " + COVERED);
        helper.assertNotNull(cover.identity(), "the attached cover has no identity");
        return cover;
    }
}
