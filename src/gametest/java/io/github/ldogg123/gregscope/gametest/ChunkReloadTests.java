package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;
import static gregtech.api.util.GTRecipeConstants.COIL_HEAT;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.api.gt.Multiblock;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.TierEU;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.probe.GregTechMachineProbe;
import li.cil.oc.api.Network;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.ManagedEnvironment;

/**
 * Chunk unload/reload of GT machines watched through GregScope's probe and OpenComputers (GS-004 "chunk unload/reload,
 * server restart").
 *
 * <p>
 * The chunks are really unloaded and reloaded by the server ({@link ChunkReload}): GT and OC tile entities are written
 * to chunk NBT, receive {@code onChunkUnload()} in the next world update, and come back as <em>new</em> tile entity
 * objects read from that NBT. Each test asserts the new tile is a different object, so a no-op unload or Forge's
 * dormant chunk cache (which would reuse the old objects) cannot pass silently.
 *
 * <p>
 * <b>What this covers of a server restart:</b> the same tile-entity NBT round trip a restart uses (GT recreating the
 * meta tile entity from its saved ID and restoring its saved fields, OC's Adapter restoring its node and per-driver
 * component addresses and re-attaching its drivers, including GregScope's {@code worksWith} on a freshly loaded
 * machine), the chunk-serialization code of the mods installed in the dev pack (e.g. Hodgepodge's chunk unload, with
 * its
 * fresh-generation unload protection cleared for the test chunks, see {@link HodgepodgeChunkGen}), and GT's
 * non-persisted state ({@code mMachine}, startup check) after load.
 *
 * <p>
 * <b>Not covered:</b> a JVM restart itself: static and global state being rebuilt (OC's driver registry, GT's global
 * trackers, AE/wireless networks), the world and ForgeChunkManager tickets being loaded at startup, spawn-area
 * preloading before the first tick, OC computer state persisted across processes, and other mods' world saved data.
 * GregScope keeps no saved data or global state. Starting GregScope on a dedicated server is covered by the CI run
 * itself, which runs these tests on one.
 *
 * <p>
 * {@link #adapterInOtherChunkSeesMachineAfterItsChunkReloads} places the Adapter and the machine on opposite sides of a
 * chunk border and unloads only the machine's chunk: the Adapter keeps its merged component, and GregScope's
 * position-bound environment reads the reloaded machine through it (before the fix, a tile-bound environment kept
 * returning {@code machine unavailable}).
 *
 * <p>
 * Each test runs in its own batch: unloading a chunk also unloads anything else in it, including cells of tests that
 * already finished (Horizon-QA keeps them forced until the run ends).
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope" })
public class ChunkReloadTests {

    private static final GregTechMachineProbe PROBE = new GregTechMachineProbe();

    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos ADAPTER = at(2, 0, 1);

    private static final TestPos EBF_CONTROLLER = at(1, 0, 0);
    private static final TestPos EBF_MIN = at(0, 0, 0);
    private static final TestPos EBF_MAX = at(2, 3, 2);
    private static final int EBF_WARP_RANGE = 4;
    /** Long enough that the recipe cannot finish during the test. */
    private static final int RUNNING_RECIPE_TICKS = 1_000;

    /**
     * GT's {@code mStartUpCheck}: a freshly loaded multiblock controller checks its structure after this many ticks.
     */
    private static final int GT_STARTUP_CHECK_TICKS = 100;
    /** Scheduling slack for tick counts after a reload (real ticks between sequence steps, update order). */
    private static final int TICK_SLACK = 5;

    /** Upper bound for OpenComputers' scheduled network join of the reloaded Adapter (observed within 1-2 ticks). */
    private static final int OC_JOIN_TIMEOUT_TICKS = 20;

    /** Keys GregScope derives from GT's identity and persisted state; they must survive the NBT round trip. */
    private static final String[] PERSISTED_KEYS = { "schemaVersion", "kind", "name", "metaName", "metaId",
        "machineClass", "dimension", "x", "y", "z", "allowedToWork", "wasShutdown", "progressTicks",
        "maxProgressTicks" };

    private ChunkReloadTests() {}

    @GameTest(batch = "gregscope.reload.basic", timeoutTicks = 100)
    public static void basicMachineSurvivesChunkReload(GameTestHelper helper) {
        IGregTechTileEntity oldHolder = GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_Macerator.get(1));
        TileEntity oldAdapter = OcComponents.placeBlock(helper, ADAPTER, "adapter");
        Network.joinOrCreateNetwork(oldAdapter);
        ChunkReload chunks = ChunkReload.of(helper, MACHINE, ADAPTER);
        State st = new State();

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the machine",
                OC_JOIN_TIMEOUT_TICKS,
                () -> {
                    st.adapterComponent = OcComponents.adapterSnapshotComponent(helper, (Environment) oldAdapter);
                })
            .thenExecute("unload", () -> {
                st.before = Snapshots.probe(helper, MACHINE, "reload#1 before unload");
                Snapshots.assertStatus(helper, st.before, "idle", "none");
                st.env = OcComponents.adapterEnvironment(helper, MACHINE);
                assertSnapshotOf(helper, "old environment before unload", st.env, st.before);
                assertSnapshot(
                    helper,
                    "Adapter component before unload",
                    OcComponents.invoke(helper, st.adapterComponent, "getSnapshot"),
                    st.before);
                st.address = st.adapterComponent.address();
                st.adapterAddress = ((Environment) oldAdapter).node()
                    .address();
                st.name = st.adapterComponent.name();

                st.startTick = helper.getWorld()
                    .getTotalWorldTime();
                st.startNanos = System.nanoTime();
                chunks.unload();
                // Same tick: tile entities are only marked for unload, but the chunk is gone.
                helper.assertNull(PROBE.snapshot((TileEntity) oldHolder), "probe snapshot of the unloaded machine");
                assertUnavailable(helper, "old environment right after unload", st.env);
                assertUnavailable(helper, "Adapter component right after unload", st.adapterComponent);
                helper.assertFalse(chunks.anyLoaded(), "probe or OC call loaded " + chunks + " again");
            })
            .thenIdle(1)
            .thenExecute("unloaded, deferred tile entity unload done", () -> {
                assertOldMachineGone(helper, chunks, oldHolder);
                assertUnavailable(helper, "old environment while unloaded", st.env);
                assertUnavailable(helper, "Adapter component while unloaded", st.adapterComponent);
                helper.assertNull(
                    ((Environment) oldAdapter).node()
                        .network(),
                    "old Adapter node still in a network after onChunkUnload");
                // Observed: only the Adapter's own node is removed. The merged component and its per-driver nodes stay
                // connected to each other in a leftover network that no longer contains the Adapter.
                helper.assertTrue(
                    st.adapterComponent.network() == null || st.adapterComponent.network()
                        .node(st.adapterAddress) == null,
                    "old Adapter component still networked with the unloaded Adapter");
                helper.assertFalse(chunks.anyLoaded(), "OC call loaded " + chunks + " again");

                chunks.reload();
                IGregTechTileEntity holder = assertReloadedMachine(helper, MACHINE, oldHolder);
                Map<String, Object> after = Snapshots.probe(helper, MACHINE, "reload#1 after reload");
                assertPersisted(helper, st.before, after);
                // An idle basic machine has no non-persisted state that affects its status.
                helper.assertEquals(st.before, after, "idle basic machine snapshot across reload");

                ManagedEnvironment fresh = OcComponents.adapterEnvironment(helper, MACHINE);
                assertSnapshotOf(helper, "fresh environment after reload", fresh, after);
                // The environment is bound to the position, not the tile: one created before the unload reads the
                // reloaded machine.
                assertSnapshotOf(helper, "old environment after reload", st.env, after);
                helper.assertNull(PROBE.snapshot((TileEntity) oldHolder), "probe snapshot of the old holder");
                helper.assertTrue(holder.canAccessData(), "reloaded holder cannot access data");
                st.after = after;
            })
            .thenWaitUntil("reloaded Adapter joined and exposes the machine", OC_JOIN_TIMEOUT_TICKS, () -> {
                TileEntity adapter = helper.assertTileEntityPresent(ADAPTER);
                helper.assertNotSame(oldAdapter, adapter, "Adapter tile entity object after reload");
                st.newAdapterComponent = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter);
            })
            .thenExecute("reloaded Adapter component", () -> {
                long ticks = helper.getWorld()
                    .getTotalWorldTime() - st.startTick;
                long ms = (System.nanoTime() - st.startNanos) / 1_000_000L;
                System.out.println(
                    "[GregScope gametest] reload#1 unload -> reloaded Adapter component: " + ticks
                        + " ticks, "
                        + ms
                        + " ms");
                // OC restores the merged component's address from the Adapter's NBT when the driver set is unchanged.
                helper.assertEquals(st.address, st.newAdapterComponent.address(), "Adapter component address");
                helper.assertEquals(st.name, st.newAdapterComponent.name(), "Adapter component name");
                assertSnapshot(
                    helper,
                    "reloaded Adapter component",
                    OcComponents.invoke(helper, st.newAdapterComponent, "getSnapshot"),
                    st.after);
                // The unloaded Adapter's component object is no longer connected to any Adapter (no computer reaches
                // it), but its position-bound GregScope environment still reads the machine if called from Java.
                assertSnapshot(
                    helper,
                    "old Adapter component after reload (Java call)",
                    OcComponents.invoke(helper, st.adapterComponent, "getSnapshot"),
                    st.after);
            })
            .thenSucceed();
    }

    @GameTest(template = ElectricBlastFurnaceSnapshotTests.TEMPLATE, batch = "gregscope.reload.ebf", timeoutTicks = 200)
    public static void ebfControllerRestartsStartupCheckAfterChunkReload(GameTestHelper helper) {
        Multiblock ebf = helper.gtnh()
            .withWarpRange(EBF_WARP_RANGE)
            .multiblock(EBF_CONTROLLER);
        ebf.fixMaintenance();
        ebf.assertFormed();
        helper.gtnh()
            .fastForwardTicks(10);
        IGregTechTileEntity oldHolder = helper.gtnh()
            .gtTile(EBF_CONTROLLER);
        ChunkReload chunks = ChunkReload.of(helper, EBF_MIN, EBF_MAX);
        State st = new State();

        helper.startSequence()
            .thenExecute("unload", () -> {
                st.before = Snapshots.probe(helper, EBF_CONTROLLER, "reload#2 formed idle EBF before unload");
                helper.assertEquals("idle", st.before.get("state"), "state in " + st.before);
                helper.assertTrue(Snapshots.bool(helper, st.before, "formed"), "formed in " + st.before);
                Snapshots.assertPresent(helper, st.before, "energyStored", "energyCapacity");
                st.env = OcComponents.adapterEnvironment(helper, EBF_CONTROLLER);
                assertSnapshotOf(helper, "old environment before unload", st.env, st.before);

                st.startTick = helper.getWorld()
                    .getTotalWorldTime();
                st.startNanos = System.nanoTime();
                chunks.unload();
                helper.assertNull(PROBE.snapshot((TileEntity) oldHolder), "probe snapshot of the unloaded controller");
                assertUnavailable(helper, "old environment right after unload", st.env);
                helper.assertFalse(chunks.anyLoaded(), "probe or OC call loaded " + chunks + " again");
            })
            .thenIdle(1)
            .thenExecute("unloaded, then reloaded", () -> {
                assertOldMachineGone(helper, chunks, oldHolder);
                assertUnavailable(helper, "old environment while unloaded", st.env);
                helper.assertFalse(chunks.anyLoaded(), "OC call loaded " + chunks + " again");

                chunks.reload();
                assertReloadedMachine(helper, EBF_CONTROLLER, oldHolder);
                Map<String, Object> after = Snapshots.probe(helper, EBF_CONTROLLER, "reload#2 right after reload");
                assertPersisted(helper, st.before, after);
                // GT does not persist mMachine and restarts its 100-tick startup countdown on load.
                Snapshots.assertStatus(helper, after, "starting", "startup_check");
                helper.assertFalse(Snapshots.bool(helper, after, "formed"), "formed in " + after);
                // GT has not re-registered the energy hatches yet, so GregScope omits the energy keys.
                Snapshots.assertAbsent(helper, after, "energyStored", "energyCapacity");

                ManagedEnvironment fresh = OcComponents.adapterEnvironment(helper, EBF_CONTROLLER);
                assertSnapshotOf(helper, "fresh environment after reload", fresh, after);
                // Position-bound: the environment created before the unload reads the reloaded controller.
                assertSnapshotOf(helper, "old environment after reload", st.env, after);
                st.freshEnv = fresh;
            })
            // One real server tick with the reloaded chunk, as after a real load; the warps below add 50 + 70 ticks.
            .thenIdle(1)
            .thenExecute("startup check still pending", () -> {
                helper.gtnh()
                    .withWarpRange(EBF_WARP_RANGE)
                    .fastForwardTicks(50);
                Map<String, Object> s = Snapshots.probe(helper, EBF_CONTROLLER, "reload#2 50 ticks after reload");
                Snapshots.assertStatus(helper, s, "starting", "startup_check");
            })
            .thenExecute("startup check done", () -> {
                helper.gtnh()
                    .withWarpRange(EBF_WARP_RANGE)
                    .fastForwardTicks(70);
                Map<String, Object> s = Snapshots.probe(helper, EBF_CONTROLLER, "reload#2 120 ticks after reload");
                helper.assertEquals("idle", s.get("state"), "state in " + s);
                helper.assertEquals(st.before.get("statusId"), s.get("statusId"), "statusId in " + s);
                helper.assertTrue(Snapshots.bool(helper, s, "formed"), "formed in " + s);
                Snapshots.assertPresent(helper, s, "energyStored", "energyCapacity");
                assertPersisted(helper, st.before, s);
                assertSnapshotOf(helper, "fresh environment after startup check", st.freshEnv, s);
                long ticks = helper.getWorld()
                    .getTotalWorldTime() - st.startTick;
                long ms = (System.nanoTime() - st.startNanos) / 1_000_000L;
                System.out.println(
                    "[GregScope gametest] reload#2 unload -> re-formed: " + ticks + " real ticks, " + ms + " ms");
            })
            .thenSucceed();
    }

    @GameTest(
        template = ElectricBlastFurnaceSnapshotTests.TEMPLATE,
        batch = "gregscope.reload.ebf.running",
        timeoutTicks = 200)
    public static void runningEbfKeepsRecipeProgressAcrossChunkReload(GameTestHelper helper) {
        Multiblock ebf = helper.gtnh()
            .withWarpRange(EBF_WARP_RANGE)
            .multiblock(EBF_CONTROLLER);
        ebf.fixMaintenance();
        ebf.assertFormed();
        helper.gtnh()
            .withTestRecipe(
                ebf,
                GTValues.RA.stdBuilder()
                    .itemInputs(new ItemStack(Blocks.pumpkin, 1))
                    .itemOutputs(new ItemStack(Blocks.melon_block, 1))
                    .duration(RUNNING_RECIPE_TICKS)
                    .eut(TierEU.RECIPE_EV)
                    .metadata(COIL_HEAT, 1_200));
        ebf.inputBus(0)
            .insert(new ItemStack(Blocks.pumpkin, 1));
        // Position-based Horizon-QA supply job: it only runs during time warps and feeds the reloaded hatch too.
        ebf.energyHatch(0)
            .supply(TierEU.EV, 1, 400);
        for (int i = 0; i < 40 && !ebf.isProcessing(); i++) {
            helper.gtnh()
                .fastForwardTicks(1);
        }
        helper.assertTrue(ebf.isProcessing(), "EBF did not start the synthetic recipe");
        helper.gtnh()
            .fastForwardTicks(20);
        IGregTechTileEntity oldHolder = helper.gtnh()
            .gtTile(EBF_CONTROLLER);
        // Soft disable: GT keeps running the held recipe but starts no new one. The switch is then false, not its
        // default, so the reload shows GT restores it rather than resetting it.
        oldHolder.disableWorking();
        ChunkReload chunks = ChunkReload.of(helper, EBF_MIN, EBF_MAX);
        State st = new State();

        helper.startSequence()
            .thenExecute("unload", () -> {
                st.before = Snapshots.probe(helper, EBF_CONTROLLER, "reload#3 running EBF before unload");
                Snapshots.assertStatus(helper, st.before, "running", "running");
                helper.assertFalse(Snapshots.bool(helper, st.before, "allowedToWork"), "allowedToWork in " + st.before);
                helper.assertTrue(Snapshots.bool(helper, st.before, "active"), "active in " + st.before);
                helper.assertTrue(Snapshots.number(helper, st.before, "progressTicks") > 0, "progress in " + st.before);
                Snapshots.assertPresent(helper, st.before, "euPerTick", "energyStored", "energyCapacity");
                st.env = OcComponents.adapterEnvironment(helper, EBF_CONTROLLER);
                chunks.unload();
                helper.assertNull(PROBE.snapshot((TileEntity) oldHolder), "probe snapshot of the unloaded controller");
                assertUnavailable(helper, "running EBF old environment right after unload", st.env);
            })
            .thenIdle(1)
            .thenExecute("unloaded, then reloaded", () -> {
                assertOldMachineGone(helper, chunks, oldHolder);
                chunks.reload();
                assertReloadedMachine(helper, EBF_CONTROLLER, oldHolder);
                Map<String, Object> after = Snapshots.probe(helper, EBF_CONTROLLER, "reload#3 right after reload");
                // progressTicks, maxProgressTicks and allowedToWork (false here) are persisted keys: GT saves
                // mProgresstime, mMaxProgresstime and the work switch. The startup check still wins over the held
                // recipe.
                assertPersisted(helper, st.before, after);
                Snapshots.assertStatus(helper, after, "starting", "startup_check");
                helper.assertFalse(Snapshots.bool(helper, after, "formed"), "formed in " + after);
                helper.assertTrue(Snapshots.bool(helper, after, "hasThingsToDo"), "hasThingsToDo in " + after);
                // During the startup check the controller still reports the held recipe's activity and EU/t ...
                helper.assertEquals(st.before.get("active"), after.get("active"), "active in " + after);
                helper.assertEquals(st.before.get("euPerTick"), after.get("euPerTick"), "euPerTick in " + after);
                // ... but not the hatches' energy, which GT re-registers only with the structure check.
                Snapshots.assertAbsent(helper, after, "energyStored", "energyCapacity");
                assertSnapshotOf(
                    helper,
                    "running EBF fresh environment after reload",
                    OcComponents.adapterEnvironment(helper, EBF_CONTROLLER),
                    after);
                st.startTick = helper.getWorld()
                    .getTotalWorldTime();
            })
            // One real server tick with the reloaded chunk; the warps below add 50 + 70 ticks.
            .thenIdle(1)
            .thenExecute("startup check still pending", () -> {
                helper.gtnh()
                    .withWarpRange(EBF_WARP_RANGE)
                    .fastForwardTicks(50);
                Map<String, Object> s = Snapshots.probe(helper, EBF_CONTROLLER, "reload#3 50 ticks after reload");
                Snapshots.assertStatus(helper, s, "starting", "startup_check");
                // The held recipe does not advance while GT's startup check is pending.
                helper.assertEquals(
                    st.before.get("progressTicks"),
                    s.get("progressTicks"),
                    "progressTicks during the startup check in " + s);
            })
            .thenExecute("startup check done", () -> {
                helper.gtnh()
                    .withWarpRange(EBF_WARP_RANGE)
                    .fastForwardTicks(70);
                Map<String, Object> s = Snapshots.probe(helper, EBF_CONTROLLER, "reload#3 120 ticks after reload");
                Snapshots.assertStatus(helper, s, "running", "running");
                helper.assertTrue(Snapshots.bool(helper, s, "formed"), "formed in " + s);
                helper.assertFalse(Snapshots.bool(helper, s, "allowedToWork"), "allowedToWork in " + s);
                Snapshots.assertPresent(helper, s, "energyStored", "energyCapacity");
                long before = Snapshots.number(helper, st.before, "progressTicks");
                long now = Snapshots.number(helper, s, "progressTicks");
                // Ticks the reloaded controller has run: real ticks since the reload plus 50 + 70 warped. GT runs the
                // recipe only after its startup check, so progress resumed (not restarted) and advanced by about the
                // ticks left after the check. Had it also advanced during the check, it would exceed this bound.
                long realTicks = helper.getWorld()
                    .getTotalWorldTime() - st.startTick;
                long ticksSinceReload = realTicks + 50 + 70;
                long maxAdvance = ticksSinceReload - GT_STARTUP_CHECK_TICKS + TICK_SLACK;
                System.out.println(
                    "[GregScope gametest] reload#3 progressTicks " + before
                        + " -> "
                        + now
                        + " over "
                        + realTicks
                        + " real + 120 warped ticks since reload");
                helper.assertTrue(
                    now > before && now <= before + maxAdvance,
                    "progressTicks " + now
                        + " after reload vs "
                        + before
                        + " before: expected more, but at most +"
                        + maxAdvance
                        + " (recipe paused during the startup check)");
                helper.assertEquals(
                    st.before.get("maxProgressTicks"),
                    s.get("maxProgressTicks"),
                    "maxProgressTicks in " + s);
            })
            .thenSucceed();
    }

    /** Empty 17 x 1 x 5 template: any 17 consecutive x coordinates contain a chunk border. */
    private static final String WIDE_TEMPLATE = "gregscope:empty_17x1x5";
    private static final int WIDE_ROW_Z = 2;
    private static final long CROSS_CHUNK_EU = 64;

    /**
     * Adapter and machine on opposite sides of a chunk border; only the machine's chunk unloads and reloads while the
     * Adapter's chunk (and so the Adapter, its network and its cached merged component) stays loaded. Calls go through
     * the merged component of the real placed Adapter.
     */
    @GameTest(template = WIDE_TEMPLATE, batch = "gregscope.reload.crosschunk", timeoutTicks = 100)
    public static void adapterInOtherChunkSeesMachineAfterItsChunkReloads(GameTestHelper helper) {
        // First x >= origin + 1 on a chunk border: the machine is the last block of one chunk, the Adapter the first
        // of the next. Both are inside the 17-wide cell.
        int borderX = ((helper.getOriginX() >> 4) + 1) << 4;
        TestPos machine = at(borderX - 1 - helper.getOriginX(), 0, WIDE_ROW_Z);
        TestPos adapterPos = at(borderX - helper.getOriginX(), 0, WIDE_ROW_Z);
        helper.assertNotEquals(
            (long) (helper.absolute(machine)
                .x() >> 4),
            (long) (helper.absolute(adapterPos)
                .x() >> 4),
            "machine and Adapter must be in different chunks");

        IGregTechTileEntity oldHolder = GtPlacement.placeMachine(helper, machine, ItemList.Machine_LV_Macerator.get(1));
        helper.assertTrue(oldHolder.increaseStoredEnergyUnits(CROSS_CHUNK_EU, true), "could not charge the machine");
        TileEntity adapter = OcComponents.placeBlock(helper, adapterPos, "adapter");
        Network.joinOrCreateNetwork(adapter);
        ChunkReload chunks = ChunkReload.ofChunkAt(helper, machine);
        State st = new State();

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the machine",
                OC_JOIN_TIMEOUT_TICKS,
                () -> { st.adapterComponent = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter); })
            .thenExecute("unload the machine's chunk only", () -> {
                st.before = Snapshots.probe(helper, machine, "crosschunk before unload");
                assertSnapshot(
                    helper,
                    "crosschunk Adapter component before unload",
                    OcComponents.invoke(helper, st.adapterComponent, "getSnapshot"),
                    st.before);
                helper.assertEquals(
                    CROSS_CHUNK_EU,
                    OcComponents.asLong(
                        helper,
                        logStoredEu(helper, "crosschunk OC getStoredEU before unload", st.adapterComponent),
                        "getStoredEU"),
                    "OC getStoredEU before unload");
                st.address = st.adapterComponent.address();
                st.startTick = helper.getWorld()
                    .getTotalWorldTime();
                st.startNanos = System.nanoTime();

                chunks.unload();
                helper.assertTrue(ChunkReload.isChunkLoaded(helper, adapterPos), "Adapter chunk unloaded too");
                assertUnavailable(helper, "crosschunk Adapter component right after unload", st.adapterComponent);
                helper.assertFalse(chunks.anyLoaded(), "getSnapshot loaded " + chunks + " again");
            })
            .thenIdle(1)
            .thenExecute("unloaded, then reloaded", () -> {
                assertOldMachineGone(helper, chunks, oldHolder);
                assertUnavailable(helper, "crosschunk Adapter component while unloaded", st.adapterComponent);
                // OC's energy environment reads the dead holder, which GT reports as 0; it does not load the chunk
                // either.
                helper.assertEquals(
                    0L,
                    OcComponents.asLong(
                        helper,
                        logStoredEu(helper, "crosschunk OC getStoredEU while unloaded", st.adapterComponent),
                        "getStoredEU"),
                    "OC getStoredEU while unloaded");
                helper.assertFalse(chunks.anyLoaded(), "OC call loaded " + chunks + " again");
                helper.assertTrue(ChunkReload.isChunkLoaded(helper, adapterPos), "Adapter chunk unloaded");
                helper.assertSame(
                    adapter,
                    helper.assertTileEntityPresent(adapterPos),
                    "Adapter tile entity object while the machine's chunk is unloaded");

                chunks.reload();
                assertReloadedMachine(helper, machine, oldHolder);
                st.after = Snapshots.probe(helper, machine, "crosschunk after reload");
                assertPersisted(helper, st.before, st.after);
                // Same tick as the reload: the Adapter was not notified of anything and still holds the component it
                // built before the unload, yet getSnapshot resolves the new tile entity at the position.
                assertSnapshot(
                    helper,
                    "crosschunk Adapter component right after reload",
                    OcComponents.invoke(helper, st.adapterComponent, "getSnapshot"),
                    st.after);
            })
            .thenIdle(2)
            .thenExecute("Adapter component after reload", () -> {
                long ticks = helper.getWorld()
                    .getTotalWorldTime() - st.startTick;
                long ms = (System.nanoTime() - st.startNanos) / 1_000_000L;
                System.out.println(
                    "[GregScope gametest] crosschunk unload -> reload -> +2 ticks: " + ticks + " ticks, " + ms + " ms");
                // Observed (with this and with the old tile-bound GregScope environment): OpenComputers never rebuilds
                // the component, because the Adapter's chunk stayed loaded and no neighbour change reached it.
                helper.assertSame(
                    st.adapterComponent,
                    OcComponents.adapterSnapshotComponent(helper, (Environment) adapter),
                    "Adapter kept its merged component object across the machine's chunk reload");
                helper.assertEquals(st.address, st.adapterComponent.address(), "component address");
                assertSnapshot(
                    helper,
                    "crosschunk Adapter component 2 ticks after reload",
                    OcComponents.invoke(helper, st.adapterComponent, "getSnapshot"),
                    st.after);

                // OpenComputers' own energy environment (not changed by GregScope) still holds the unloaded tile
                // entity: getStoredEU on the same component reads the dead holder, which GT reports as 0, while the
                // reloaded machine holds the charge.
                long reloadedEu = helper.gtnh()
                    .gtTile(machine)
                    .getStoredEU();
                Object ocEu = logStoredEu(helper, "crosschunk OC getStoredEU after reload", st.adapterComponent);
                Snapshots.log("crosschunk GT getStoredEU of the reloaded tile", reloadedEu);
                helper.assertEquals(CROSS_CHUNK_EU, reloadedEu, "reloaded machine's stored EU");
                helper.assertEquals(0L, OcComponents.asLong(helper, ocEu, "getStoredEU"), "OC getStoredEU (stale)");
            })
            .thenSucceed();
    }

    private static Object logStoredEu(GameTestHelper helper, String label, Component component) {
        Object[] result = OcComponents.invoke(helper, component, "getStoredEU");
        Object value = result == null || result.length == 0 ? null : result[0];
        Snapshots.log(label, value);
        return value;
    }

    /** Mutable values shared between sequence steps. */
    private static final class State {

        Map<String, Object> before;
        Map<String, Object> after;
        ManagedEnvironment env;
        ManagedEnvironment freshEnv;
        Component adapterComponent;
        Component newAdapterComponent;
        String address;
        String adapterAddress;
        String name;
        long startTick;
        long startNanos;
    }

    private static void assertOldMachineGone(GameTestHelper helper, ChunkReload chunks, IGregTechTileEntity oldHolder) {
        helper.assertFalse(chunks.anyLoaded(), chunks + " loaded again before the deferred unload check");
        helper.assertTrue(oldHolder.isDead(), "old GT holder not dead after onChunkUnload");
        helper.assertFalse(oldHolder.canAccessData(), "old GT holder can still access data after onChunkUnload");
        helper.assertNull(PROBE.snapshot((TileEntity) oldHolder), "probe snapshot of the dead old holder");
    }

    private static IGregTechTileEntity assertReloadedMachine(GameTestHelper helper, TestPos local,
        IGregTechTileEntity oldHolder) {
        TileEntity tile = helper.assertTileEntityPresent(local);
        IGregTechTileEntity holder = helper
            .assertInstanceOf(IGregTechTileEntity.class, tile, "reloaded tile is not a GT machine");
        // A reused object would mean the chunk never really unloaded (e.g. Forge's dormant chunk cache).
        helper.assertNotSame(oldHolder, holder, "GT holder object after reload");
        helper.assertNotSame(
            oldHolder.getMetaTileEntity(),
            holder.getMetaTileEntity(),
            "GT meta tile entity object after reload");
        return holder;
    }

    private static void assertPersisted(GameTestHelper helper, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> expected = new LinkedHashMap<>();
        Map<String, Object> actual = new LinkedHashMap<>();
        for (String key : PERSISTED_KEYS) {
            expected.put(key, before.get(key));
            actual.put(key, after.get(key));
        }
        helper.assertEquals(expected, actual, "persisted snapshot keys across reload");
    }

    private static void assertSnapshotOf(GameTestHelper helper, String label, ManagedEnvironment env,
        Map<String, Object> probe) {
        assertSnapshot(
            helper,
            label,
            OcComponents.invoke(helper, OcComponents.component(helper, env), "getSnapshot"),
            probe);
    }

    /** OC's result for getSnapshot matches the probe's snapshot in identity, coordinates and status. */
    private static void assertSnapshot(GameTestHelper helper, String label, Object[] result,
        Map<String, Object> probe) {
        helper.assertTrue(
            result != null && result.length == 1 && result[0] != null,
            label + ": getSnapshot did not return a snapshot: " + (result == null ? null : Arrays.asList(result)));
        Map<String, Object> s = OcComponents.asMap(helper, result[0]);
        Snapshots.log("reload " + label, s);
        helper.assertEquals(probe.get("metaName"), s.get("metaName"), label + ": metaName");
        for (String key : new String[] { "metaId", "x", "y", "z" }) {
            helper.assertEquals(
                OcComponents.asLong(helper, probe.get(key), key),
                OcComponents.asLong(helper, s.get(key), key),
                label + ": " + key);
        }
        helper.assertEquals(probe.get("state"), s.get("state"), label + ": state");
        helper.assertEquals(probe.get("statusId"), s.get("statusId"), label + ": statusId");
    }

    private static void assertUnavailable(GameTestHelper helper, String label, ManagedEnvironment env) {
        assertUnavailable(helper, label, OcComponents.component(helper, env));
    }

    private static void assertUnavailable(GameTestHelper helper, String label, Component component) {
        OcComponents.assertUnavailable(helper, "reload " + label, component);
    }
}
