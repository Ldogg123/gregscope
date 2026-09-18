package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.AfterBatch;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.common.items.IDMetaTool01;
import gregtech.common.items.MetaGeneratedTool01;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.registry.RemovalCause;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorNbtCodec;

/**
 * GS-107 (design-v0.2 §4, §14): what the sensor registry makes of a sensor as it is placed, moved, unloaded, broken
 * and duplicated in a real world.
 *
 * <p>
 * The registry installed here is the mod's own ({@code GregScope.registry()}), not a stub, so every assertion is about
 * the shipped state machine. Heartbeats and validations are driven with the {@code GregScopeTestHooks} of §13.1
 * rather than by waiting: a Horizon-QA warp does not advance {@code MinecraftServer.getTickCounter()} (errata E2), so
 * GT would only tick a cover on a real server tick that is a multiple of its rate.
 *
 * <p>
 * Horizon-QA keeps finished cells loaded, so sensors from earlier tests stay registered and keep heartbeating. Every
 * assertion here is therefore about a named sensor UUID, except the cap test, which empties the registry first and
 * then runs inside a single server tick, so no foreign cover can heartbeat into it.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class SensorLifecycleTests {

    private static final String BATCH = "gregscope.sensor.lifecycle";
    /** Chunk unload/reload takes the whole cell down, so it gets its own batch. */
    private static final String RELOAD_BATCH = "gregscope.sensor.lifecycle.reload";
    /** Changes process-wide settings and empties the registry, so it runs alone (design-v0.2 §13.2). */
    private static final String CAPS_BATCH = "gregscope.sensor.lifecycle.caps";

    private static final String EBF_TEMPLATE = ElectricBlastFurnaceSnapshotTests.TEMPLATE;
    private static final TestPos EBF_CONTROLLER = at(1, 0, 0);
    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos MOVED = at(3, 0, 3);
    private static final TestPos SECOND_MACHINE = at(3, 0, 1);

    private static final ForgeDirection COVERED = ForgeDirection.UP;
    private static final int WARP_RANGE = 4;
    /** The cap test's own limit; 16 is the smallest {@code limits.maxSensors} the config allows. */
    private static final int CAP = 16;

    private SensorLifecycleTests() {}

    /**
     * GS107-T3: the sensors this batch left in its cells would keep heartbeating into the registry and, together with
     * every other batch's, fill the shared unowned {@code limits.maxSensorsPerTeam} quota. See {@link SensorCleanup}.
     */
    @AfterBatch(BATCH)
    public static void cleanUpAfterLifecycle() {
        SensorCleanup.detachAllAndPurge();
    }

    /**
     * GS107-T3: the sensors this batch left in its cells would keep heartbeating into the registry and, together with
     * every other batch's, fill the shared unowned {@code limits.maxSensorsPerTeam} quota. See {@link SensorCleanup}.
     */
    @AfterBatch(RELOAD_BATCH)
    public static void cleanUpAfterLifecycleReload() {
        SensorCleanup.detachAllAndPurge();
    }

    /**
     * GS107-T3: the sensors this batch left in its cells would keep heartbeating into the registry and, together with
     * every other batch's, fill the shared unowned {@code limits.maxSensorsPerTeam} quota. See {@link SensorCleanup}.
     */
    @AfterBatch(CAPS_BATCH)
    public static void cleanUpAfterCaps() {
        SensorCleanup.detachAllAndPurge();
    }

    // --- registration ---

    /** §4.3 unknown + heartbeat: the sensor becomes LIVE, at its position and side, with rings allocated. */
    @GameTest(batch = BATCH)
    public static void heartbeatRegistersLiveSensor(GameTestHelper helper) {
        IGregTechTileEntity machine = placeMachine(helper, MACHINE);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        UUID id = cover.identity()
            .id();
        helper.assertNull(
            registry(helper).core()
                .entry(id),
            "the sensor registered before its first heartbeat");

        heartbeat(helper, cover);

        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.LIVE, entry.state(), "state");
        helper.assertEquals(RemovalCause.NONE, entry.removalCause(), "removal cause");
        helper.assertEquals(
            helper.absolute(MACHINE)
                .x(),
            entry.x(),
            "x");
        helper.assertEquals(
            helper.absolute(MACHINE)
                .y(),
            entry.y(),
            "y");
        helper.assertEquals(
            helper.absolute(MACHINE)
                .z(),
            entry.z(),
            "z");
        helper.assertEquals(COVERED.ordinal(), entry.side(), "side");
        helper.assertNotNull(entry.seconds(), "second ring");
        helper.assertNotNull(entry.minutes(), "minute ring");
        helper.assertEquals(
            id,
            registry(helper).core()
                .idAt(entry.dim(), entry.x(), entry.y(), entry.z(), entry.side()),
            "the (position, side) reverse index");
        // §3.4: the registry's word reaches the cover description.
        helper.assertEquals(SensorState.LIVE.label(), cover.availability(), "availability");
        helper.assertEquals(
            "GregScope sensor " + cover.identity()
                .shortId() + " (live)",
            cover.getDescription(),
            "description");
        helper.succeed();
    }

    // --- removal ---

    /** §4.3: a crowbar detaches the cover, so the entry becomes a REMOVED tombstone and frees its rings. */
    @GameTest(batch = BATCH)
    public static void crowbarDropCoverMarksRemoved(GameTestHelper helper) {
        IGregTechTileEntity machine = placeMachine(helper, MACHINE);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        helper.assertEquals(SensorState.LIVE, entry(helper, id).state(), "state before the crowbar");

        ItemStack crowbar = MetaGeneratedTool01.INSTANCE
            .getToolWithStats(IDMetaTool01.CROWBAR.ID, 1, Materials.Steel, Materials.Wood, null);
        helper.assertTrue(
            SensorPlacementTests.rightClick(helper, MACHINE, COVERED, crowbar, true),
            "GT did not handle the crowbar click");
        helper.assertFalse(machine.hasCoverAtSide(COVERED), "the crowbar left the cover on the machine");

        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.REMOVED, entry.state(), "state");
        helper.assertEquals(RemovalCause.DETACHED, entry.removalCause(), "removal cause");
        helper.assertNull(entry.seconds(), "a tombstone must free its second ring");
        helper.assertNull(entry.minutes(), "a tombstone must free its minute ring");
        helper.assertNull(
            registry(helper).core()
                .idAt(entry.dim(), entry.x(), entry.y(), entry.z(), entry.side()),
            "a tombstone must leave the reverse index");
        helper.succeed();
    }

    /** §4.3: a multiblock controller drops covers from its new front face, which is a DETACHED removal. */
    @GameTest(template = EBF_TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void facingChangeDropRemoves(GameTestHelper helper) {
        TileEntity tile = helper.assertTileEntityPresent(EBF_CONTROLLER);
        IGregTechTileEntity controller = helper.assertInstanceOf(IGregTechTileEntity.class, tile, "EBF controller");
        ForgeDirection front = controller.getFrontFacing();
        ForgeDirection side = front == ForgeDirection.UP ? ForgeDirection.NORTH : ForgeDirection.UP;
        MachineSensorCover cover = attach(helper, controller, side);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        helper.assertEquals(SensorState.LIVE, entry(helper, id).state(), "state before the facing change");

        // GT drops covers its allowCoverOnSide no longer allows, in the tick after the facing changed.
        controller.setFrontFacing(side);
        helper.gtnh()
            .withWarpRange(WARP_RANGE)
            .fastForwardTicks(5);
        helper.assertFalse(controller.hasCoverAtSide(side), "GT kept the cover on its new front face");

        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.REMOVED, entry.state(), "state");
        helper.assertEquals(RemovalCause.DETACHED, entry.removalCause(), "removal cause");
        helper.succeed();
    }

    /**
     * §4.3: a survival break writes the cover into the machine's drop (IN_ITEM), and placing that item elsewhere lets
     * the same UUID resume as LIVE at the new position, with its entry and history kept.
     */
    @GameTest(batch = BATCH)
    public static void survivalGetDropsMarksInItemAndReplaceResumes(GameTestHelper helper) {
        IGregTechTileEntity machine = placeMachine(helper, MACHINE);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        SensorIdentity identity = cover.identity();
        UUID id = identity.id();
        heartbeat(helper, cover);
        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.LIVE, entry.state(), "state before the break");

        List<ItemStack> drops = new ArrayList<>(machine.getDrops());
        helper.assertEquals(1L, drops.size(), "machine drops");
        helper.assertEquals(SensorState.IN_ITEM, entry(helper, id).state(), "state after the break");
        helper.assertEquals(RemovalCause.IN_ITEM, entry(helper, id).removalCause(), "removal cause");
        helper.destroyBlock(MACHINE);

        IGregTechTileEntity moved = GtPlacement.placeMachine(helper, MOVED, drops.get(0));
        MachineSensorCover movedCover = sensorCover(helper, moved, COVERED);
        helper.assertEquals(
            id,
            movedCover.identity()
                .id(),
            "the moved machine lost the sensor UUID");
        heartbeat(helper, movedCover);

        SensorEntry resumed = entry(helper, id);
        helper.assertSame(entry, resumed, "the entry object must survive the move, so history continues");
        helper.assertEquals(SensorState.LIVE, resumed.state(), "state after the move");
        helper.assertEquals(RemovalCause.NONE, resumed.removalCause(), "removal cause after the move");
        helper.assertEquals(
            helper.absolute(MOVED)
                .x(),
            resumed.x(),
            "x after the move");
        helper.assertEquals(
            helper.absolute(MOVED)
                .z(),
            resumed.z(),
            "z after the move");
        helper.assertNull(
            registry(helper).core()
                .idAt(
                    resumed.dim(),
                    helper.absolute(MACHINE)
                        .x(),
                    helper.absolute(MACHINE)
                        .y(),
                    helper.absolute(MACHINE)
                        .z(),
                    COVERED.ordinal()),
            "the old (position, side) key must be gone");
        helper.succeed();
    }

    /**
     * §4.3: the block is gone without any cover hook running (another mod, a world edit), so three validations in a
     * row strike the entry out and it becomes MISSING.
     */
    @GameTest(batch = BATCH)
    public static void destroyBlockBecomesMissingAfterThreeSamples(GameTestHelper helper) {
        IGregTechTileEntity machine = placeMachine(helper, MACHINE);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        SensorEntry entry = entry(helper, id);
        helper.assertTrue(GregScopeTestHooks.sampleNow(id), "the sensor did not validate while it was there");
        helper.assertEquals(0, entry.strikes(), "strikes on a healthy sensor");

        // setBlock to air runs GT's breakBlock but not getDrops, so no cover hook fires: the registry only finds out
        // by validating (design-v0.2 section 6.2).
        helper.destroyBlock(MACHINE);
        helper.assertEquals(SensorState.LIVE, entry.state(), "a destroyed block must not change the state by itself");

        helper.assertFalse(GregScopeTestHooks.sampleNow(id), "validation 1 found a target");
        helper.assertEquals(1, entry.strikes(), "strikes after 1 validation");
        helper.assertEquals(SensorState.LIVE, entry.state(), "state after 1 strike");
        helper.assertFalse(GregScopeTestHooks.sampleNow(id), "validation 2 found a target");
        helper.assertEquals(2, entry.strikes(), "strikes after 2 validations");
        helper.assertEquals(SensorState.LIVE, entry.state(), "state after 2 strikes");
        helper.assertFalse(GregScopeTestHooks.sampleNow(id), "validation 3 found a target");

        helper.assertEquals(SensorState.MISSING, entry.state(), "state after 3 strikes");
        helper.assertEquals(RemovalCause.TARGET_MISSING, entry.removalCause(), "removal cause");
        helper.assertNull(entry.seconds(), "a MISSING tombstone must free its rings");
        helper.succeed();
    }

    /**
     * §4.3 duplicate row and design-v0.3 §5.1 A1: a second machine carrying the same sensor UUID (a copied item, a
     * creative duplicate) is re-keyed, and the original keeps its UUID, its position and its history.
     */
    @GameTest(batch = BATCH)
    public static void duplicateUuidRekeysNewcomer(GameTestHelper helper) {
        IGregTechTileEntity machine = placeMachine(helper, MACHINE);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        SensorIdentity original = cover.identity();
        heartbeat(helper, cover);
        long rekeysBefore = registry(helper).core()
            .duplicatesRekeyedTotal();

        // The same identity on another machine, built the way a copied machine item would restore it.
        IGregTechTileEntity copy = placeMachineWithSensorNbt(helper, SECOND_MACHINE, original);
        MachineSensorCover copyCover = sensorCover(helper, copy, COVERED);
        helper.assertEquals(
            original.id(),
            copyCover.identity()
                .id(),
            "the copy did not carry the same UUID (test setup)");

        heartbeat(helper, copyCover);

        UUID fresh = copyCover.identity()
            .id();
        helper.assertNotEquals(original.id(), fresh, "the duplicate kept the original UUID");
        helper.assertEquals(
            rekeysBefore + 1L,
            registry(helper).core()
                .duplicatesRekeyedTotal(),
            "duplicatesRekeyedTotal");
        helper.assertEquals(SensorState.LIVE, entry(helper, original.id()).state(), "the original must stay LIVE");
        helper.assertEquals(
            helper.absolute(MACHINE)
                .x(),
            entry(helper, original.id()).x(),
            "the original must not move");
        helper.assertEquals(SensorState.LIVE, entry(helper, fresh).state(), "the re-keyed copy must be LIVE");
        helper.assertEquals(
            helper.absolute(SECOND_MACHINE)
                .x(),
            entry(helper, fresh).x(),
            "the copy's position");
        // The fresh UUID is written back into the cover NBT, so it survives a save.
        NBTTagCompound saved = coverData(helper, savedCoverEntry(helper, SECOND_MACHINE, COVERED));
        helper.assertEquals(fresh.getMostSignificantBits(), saved.getLong(SensorNbtCodec.ID_MSB), "idM in the NBT");
        helper.assertEquals(fresh.getLeastSignificantBits(), saved.getLong(SensorNbtCodec.ID_LSB), "idL in the NBT");
        helper.succeed();
    }

    // --- chunk reload ---

    /** §4.3: a chunk unload makes the entry UNLOADED, and the heartbeat after the reload makes it LIVE again. */
    @GameTest(batch = RELOAD_BATCH, timeoutTicks = 200)
    public static void chunkUnloadMarksUnloadedAndReloadLive(GameTestHelper helper) {
        IGregTechTileEntity machine = placeMachine(helper, MACHINE);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.LIVE, entry.state(), "state before the unload");
        ChunkReload chunks = ChunkReload.ofChunkAt(helper, MACHINE);

        helper.startSequence()
            .thenExecute("unload", chunks::unload)
            .thenIdle(1)
            .thenExecute("unloaded", () -> {
                helper.assertEquals(SensorState.UNLOADED, entry.state(), "state after the unload");
                helper.assertNull(entry.seconds(), "an unloaded sensor drops its second ring");
                helper.assertNotNull(entry.minutes(), "an unloaded sensor keeps its minute ring");
                helper.assertEquals(-1, entry.bucket(), "an unloaded sensor is not sampled");
            })
            .thenExecute("reload and heartbeat", () -> {
                chunks.reload();
                TileEntity tile = helper.assertTileEntityPresent(MACHINE);
                IGregTechTileEntity reloaded = helper
                    .assertInstanceOf(IGregTechTileEntity.class, tile, "reloaded tile");
                MachineSensorCover after = sensorCover(helper, reloaded, COVERED);
                helper.assertEquals(
                    id,
                    after.identity()
                        .id(),
                    "the reloaded cover lost the sensor UUID");
                heartbeat(helper, after);
                helper.assertSame(entry, entry(helper, id), "the entry must survive the reload");
                helper.assertEquals(SensorState.LIVE, entry.state(), "state after the reload");
                helper.assertNotNull(entry.seconds(), "a live sensor gets a second ring again");
            })
            .thenSucceed();
    }

    // --- caps ---

    /**
     * §4.2/§4.3: with {@code limits.maxSensors = 16}, the seventeenth sensor is refused, gets no entry and says so on
     * the cover. The registry is emptied first and everything happens inside one server tick, so no sensor from an
     * earlier cell can take a slot in between.
     */
    @GameTest(batch = CAPS_BATCH, timeoutTicks = 200)
    public static void globalCapGivesOverCap(GameTestHelper helper) {
        SensorRegistry registry = registry(helper);
        helper.assertTrue(
            GregScopeTestHooks.overrideSettings(
                Settings.builder()
                    .maxSensors(CAP)
                    .build()),
            "GregScope test hooks are disabled");
        helper.afterTest(GregScopeTestHooks::clearSettingsOverride);
        helper.assertTrue(GregScopeTestHooks.purgeAllNow() >= 0, "the registry could not be emptied");
        helper.assertEquals(
            0,
            registry.core()
                .countedSensors(),
            "the registry is not empty");
        long refusedBefore = registry.core()
            .quotaRefusedTotal();

        List<MachineSensorCover> covers = new ArrayList<>();
        for (int i = 0; i <= CAP; i++) {
            TestPos pos = at(i % 5, 0, i / 5);
            IGregTechTileEntity machine = placeMachine(helper, pos);
            MachineSensorCover cover = attach(helper, machine, COVERED);
            covers.add(cover);
            heartbeat(helper, cover);
        }

        int live = 0;
        int refused = 0;
        for (MachineSensorCover cover : covers) {
            SensorEntry entry = registry.core()
                .entry(
                    cover.identity()
                        .id());
            if (entry == null) {
                refused++;
                helper.assertEquals(SensorState.OVER_CAP.label(), cover.availability(), "availability of a refusal");
            } else {
                live++;
                helper.assertEquals(SensorState.LIVE, entry.state(), "state of an accepted sensor");
            }
        }
        helper.assertEquals(CAP, live, "accepted sensors");
        helper.assertEquals(1, refused, "refused sensors");
        helper.assertEquals(
            CAP,
            registry.core()
                .countedSensors(),
            "the cap must never be exceeded");
        helper.assertEquals(
            refusedBefore + 1L,
            registry.core()
                .quotaRefusedTotal(),
            "quotaRefusedTotal");
        helper.succeed();
    }

    // --- helpers ---

    private static SensorRegistry registry(GameTestHelper helper) {
        SensorRegistry registry = GregScope.registry();
        helper.assertNotNull(registry, "GregScope has no registry; is the server running?");
        return registry;
    }

    private static SensorEntry entry(GameTestHelper helper, UUID id) {
        SensorEntry entry = registry(helper).core()
            .entry(id);
        helper.assertNotNull(entry, "no registry entry for sensor " + id);
        return entry;
    }

    private static void heartbeat(GameTestHelper helper, MachineSensorCover cover) {
        helper.assertTrue(GregScopeTestHooks.heartbeatNow(cover), "GregScope test hooks are disabled");
    }

    private static IGregTechTileEntity placeMachine(GameTestHelper helper, TestPos pos) {
        IGregTechTileEntity holder = GtPlacement.placeMachine(helper, pos, ItemList.Machine_LV_E_Furnace.get(1L));
        MTEBasicMachine mte = helper
            .assertInstanceOf(MTEBasicMachine.class, holder.getMetaTileEntity(), "basic machine");
        helper.assertNotEquals(COVERED, mte.mMainFacing, "the covered face is the machine's main facing");
        return holder;
    }

    private static MachineSensorCover attach(GameTestHelper helper, IGregTechTileEntity holder, ForgeDirection side) {
        helper.assertTrue(
            SensorPlacementTests.placeViaCoverPlacer(helper, holder, side),
            "the sensor was refused on " + side);
        MachineSensorCover cover = sensorCover(helper, holder, side);
        helper.assertNotNull(cover.identity(), "the attached cover has no identity");
        return cover;
    }

    private static MachineSensorCover sensorCover(GameTestHelper helper, ICoverable holder, ForgeDirection side) {
        return helper
            .assertInstanceOf(MachineSensorCover.class, holder.getCoverAtSide(side), "sensor cover on " + side);
    }

    /** A GT machine placed from an item whose NBT already carries this exact sensor identity on {@link #COVERED}. */
    private static IGregTechTileEntity placeMachineWithSensorNbt(GameTestHelper helper, TestPos pos,
        SensorIdentity identity) {
        return SensorFixtures.placeMachineWithSensorNbt(helper, pos, COVERED, identity);
    }

    private static NBTTagCompound savedCoverEntry(GameTestHelper helper, TestPos pos, ForgeDirection side) {
        NBTTagList covers = helper.getTileNBT(pos)
            .getTagList(GTValues.NBT.COVERS, 10);
        for (int i = 0; i < covers.tagCount(); i++) {
            NBTTagCompound entry = covers.getCompoundTagAt(i);
            if (entry.getByte("s") == (byte) side.ordinal()) {
                return entry;
            }
        }
        helper.fail("no saved cover on " + side + " at " + pos);
        return new NBTTagCompound();
    }

    private static NBTTagCompound coverData(GameTestHelper helper, NBTTagCompound coverEntry) {
        NBTBase data = coverEntry.getTag("d");
        return helper.assertInstanceOf(NBTTagCompound.class, data, "cover data compound");
    }
}
