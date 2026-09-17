package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.AfterBatch;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.items.MetaBaseItem;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEBasicTank;
import gregtech.api.util.GTUtility;
import gregtech.common.covers.Cover;
import gregtech.common.covers.CoverConveyor;
import gregtech.common.covers.CoverNone;
import gregtech.common.items.IDMetaTool01;
import gregtech.common.items.MetaGeneratedTool01;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.sampling.FakeClock;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorCover;
import io.github.ldogg123.gregscope.sensor.SensorCovers;
import io.github.ldogg123.gregscope.sensor.SensorEvents;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorNbtCodec;

/**
 * GS-106 (design-v0.2 §3.3, §3.4, §14): what the Machine Sensor cover does once it is on a machine.
 *
 * <p>
 * The transparency tests move real resources through the covered face with GT's and Minecraft's own code, never by
 * reading a {@code lets*} return value: a GT transformer pushes EU into the machine through that face, a vanilla
 * hopper pushes items into it, a fluid is filled through it with GT's {@code IFluidHandler} entry point, and a
 * redstone block next to it is read back through {@code getInternalInputRedstoneSignal}. Each one also asserts the
 * same path works before the cover is attached (an uncovered face is {@code CoverNone}, which is transparent), so a
 * pass cannot come from a setup where nothing could move anyway. The §13.2 negative control (one {@code lets*} set to
 * false) is recorded in {@code docs/testing.md}.
 *
 * <p>
 * The machine is always prepared with a known main facing and front facing, because GT refuses items and fluids on
 * those two faces whatever the cover says ({@code MTEBasicMachine.allowPutStack} and {@code isLiquidInput}).
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class SensorCoverTests {

    private static final String BATCH = "gregscope.sensor.cover";
    /** Chunk unload/reload takes the whole cell down, so it gets its own batch (as {@code ChunkReloadTests} does). */
    private static final String RELOAD_BATCH = "gregscope.sensor.cover.reload";

    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos NEIGHBOUR = at(2, 0, 1);
    private static final TestPos OTHER_MACHINE = at(0, 0, 1);

    /** {@link #NEIGHBOUR} touches this face of {@link #MACHINE}; free on a prepared machine. */
    private static final ForgeDirection COVERED = ForgeDirection.EAST;
    private static final ForgeDirection MAIN_FACING = ForgeDirection.NORTH;
    private static final ForgeDirection FRONT_FACING = ForgeDirection.SOUTH;
    /** Also free on a prepared machine; used for a second, unrelated cover. */
    private static final ForgeDirection SPARE = ForgeDirection.WEST;

    /** Covers a test cell and stops short of the next one. */
    private static final int WARP_RANGE = 4;
    /** GT refreshes its EU I/O faces only after the holder's 20th tick (BaseMetaTileEntity.java:425-447). */
    private static final int EU_FACE_WARMUP_TICKS = 25;
    private static final int EU_SUPPLY_TICKS = 40;
    /** A vanilla hopper moves one stack every 8 ticks; real ticks, because a warp does not tick vanilla tiles. */
    private static final int HOPPER_TIMEOUT_TICKS = 60;
    /** The cover heartbeat runs on {@code getTickCounter() % 20}, so one is due within 20 real ticks. */
    private static final int HEARTBEAT_TIMEOUT_TICKS = 60;

    private static final String PLAYER = "gregscope-sensor-cover";
    private static final char SECTION = (char) 0xA7;
    private static final long FAKE_NOW_SEC = 1_700_000_000L;

    private SensorCoverTests() {}

    /**
     * GS107-T3: the sensors this batch left in its cells would keep heartbeating into the registry and, together with
     * every other batch's, fill the shared unowned {@code limits.maxSensorsPerTeam} quota. See {@link SensorCleanup}.
     */
    @AfterBatch(BATCH)
    public static void cleanUpAfterCover() {
        SensorCleanup.detachAllAndPurge();
    }

    /**
     * GS107-T3: the sensors this batch left in its cells would keep heartbeating into the registry and, together with
     * every other batch's, fill the shared unowned {@code limits.maxSensorsPerTeam} quota. See {@link SensorCleanup}.
     */
    @AfterBatch(RELOAD_BATCH)
    public static void cleanUpAfterCoverReload() {
        SensorCleanup.detachAllAndPurge();
    }

    /** §3.4 {@code onPlayerAttach}: fresh UUID, creation time from the clock, owner from the machine, no label. */
    @GameTest(batch = BATCH)
    public static void attachSetsIdentityOwnerAndTime(GameTestHelper helper) {
        useFakeClock(helper, FAKE_NOW_SEC);
        IGregTechTileEntity machine = prepareMachine(helper);
        helper.assertNotNull(machine.getOwnerUuid(), "the placed machine has no GT owner (test setup)");

        MachineSensorCover cover = attach(helper, machine, COVERED);
        SensorIdentity identity = cover.identity();
        helper.assertEquals(FAKE_NOW_SEC, identity.createdEpochSec(), "creation time");
        // The placer is a FakePlayer, so the owner is the machine's GT owner, not the fake player (§3.4).
        helper.assertEquals(machine.getOwnerUuid(), identity.owner(), "owner UUID");
        // GT owner names are not capped; a sensor caps its cached copy at 16 UTF-16 units (SensorIdentity).
        String ownerName = machine.getOwnerName();
        if (ownerName.length() > SensorIdentity.MAX_OWNER_NAME) {
            ownerName = ownerName.substring(0, SensorIdentity.MAX_OWNER_NAME);
        }
        helper.assertEquals(ownerName, identity.ownerName(), "owner name");
        helper.assertEquals("", identity.label(), "label");
        helper.assertNull(cover.inertReason(), "inert reason");
        helper.assertNull(cover.availability(), "availability before the registry exists");
        // The identity never reaches a client: GT syncs cover data only when the cover asks for it (design 3.4).
        helper.assertFalse(cover.isDataNeededOnClient(), "isDataNeededOnClient");
        helper.assertEquals("GregScope sensor " + identity.shortId(), cover.getDescription(), "description");
        helper.assertEquals(MachineSensorCover.TICK_RATE, cover.getMinimumTickRate(), "minimum tick rate");
        helper.assertEquals(MachineSensorCover.TICK_RATE, cover.getTickRate(), "tick rate");
        helper.assertEquals(0, cover.getTickRateAddition(), "tick rate addition");

        // A second sensor on another machine gets its own UUID.
        IGregTechTileEntity other = GtPlacement
            .placeMachine(helper, OTHER_MACHINE, ItemList.Machine_LV_E_Furnace.get(1L));
        MachineSensorCover second = attach(helper, other, ForgeDirection.UP);
        helper.assertNotEquals(
            identity.id(),
            second.identity()
                .id(),
            "two sensors share a UUID");
        helper.succeed();
    }

    /** §3.4: an anvil rename before attaching becomes the label, sanitized (§3.5), and survives a save. */
    @GameTest(batch = BATCH)
    public static void anvilNameBecomesLabel(GameTestHelper helper) {
        IGregTechTileEntity machine = prepareMachine(helper);
        ItemStack renamed = SensorCovers.sensorStack();
        renamed.setStackDisplayName("  " + SECTION + "cMain " + SECTION + "rEBF  ");
        helper.assertTrue(
            SensorPlacementTests.placeViaCoverPlacer(helper, machine, COVERED, renamed),
            "renamed sensor refused");
        MachineSensorCover cover = sensorCover(helper, machine, COVERED);
        helper.assertEquals(
            "Main EBF",
            cover.identity()
                .label(),
            "label from the anvil name");
        helper.assertEquals(
            "GregScope sensor " + cover.identity()
                .shortId() + ": Main EBF",
            cover.getDescription(),
            "description");

        NBTTagCompound saved = coverData(helper, savedCoverEntry(helper, MACHINE, COVERED));
        helper.assertEquals("Main EBF", saved.getString(SensorNbtCodec.LABEL), "label in the saved cover NBT");
        helper.assertEquals((byte) 1, saved.getByte(SensorNbtCodec.GS), "gs marker in the saved cover NBT");
        helper.succeed();
    }

    /** §14: a GT transformer next to the covered face charges the machine through it. */
    @GameTest(batch = BATCH, timeoutTicks = 200)
    public static void transparentToEnergy(GameTestHelper helper) {
        IGregTechTileEntity machine = prepareMachine(helper);
        IGregTechTileEntity transformer = GtPlacement
            .placeMachine(helper, NEIGHBOUR, ItemList.Transformer_MV_LV.get(1L));
        // A transformer takes EU on its front and emits on the other five faces, so the front points away.
        transformer.setFrontFacing(COVERED);
        helper.assertEquals(COVERED, transformer.getFrontFacing(), "transformer front facing");
        helper.assertEquals(0L, machine.getStoredEU(), "the machine starts empty");

        // Positive control: the face is an EU input while it is uncovered.
        helper.gtnh()
            .withWarpRange(WARP_RANGE)
            .fastForwardTicks(EU_FACE_WARMUP_TICKS);
        helper.assertTrue(machine.inputEnergyFrom(COVERED), "the uncovered face is not an EU input (test setup)");

        MachineSensorCover cover = attach(helper, machine, COVERED);
        helper.gtnh()
            .withWarpRange(WARP_RANGE)
            .fastForwardTicks(EU_FACE_WARMUP_TICKS);
        helper.assertTrue(machine.inputEnergyFrom(COVERED), "the covered face stopped being an EU input");

        helper.gtnh()
            .supplyEU(NEIGHBOUR, GTValues.V[2], 1L, EU_SUPPLY_TICKS);
        helper.gtnh()
            .withWarpRange(WARP_RANGE)
            .fastForwardTicks(EU_SUPPLY_TICKS);
        helper.assertTrue(transformer.getStoredEU() > 0L, "the transformer got no EU (test setup)");
        helper
            .assertTrue(machine.getStoredEU() > 0L, "no EU crossed the covered face; stored " + machine.getStoredEU());
        helper.assertSame(cover, machine.getCoverAtSide(COVERED), "the cover left the machine");
        helper.succeed();
    }

    /** §14: a vanilla hopper pushes items into the machine through the covered face. */
    @GameTest(batch = BATCH, timeoutTicks = 200)
    public static void transparentToItems(GameTestHelper helper) {
        IGregTechTileEntity machine = prepareMachine(helper);
        ItemStack cobblestone = new ItemStack(Blocks.cobblestone, 8);
        // Positive control: GT takes this item through this face while it is uncovered.
        helper.assertTrue(
            acceptsItemThrough(machine, COVERED, cobblestone),
            "GT refuses cobblestone through the uncovered " + COVERED + " face (test setup)");

        attach(helper, machine, COVERED);
        helper.assertTrue(acceptsItemThrough(machine, COVERED, cobblestone), "the covered face refuses items");

        TestPos abs = helper.absolute(NEIGHBOUR);
        helper.getWorld()
            .setBlock(
                abs.x(),
                abs.y(),
                abs.z(),
                Blocks.hopper,
                COVERED.getOpposite()
                    .ordinal(),
                3);
        helper.assertTileEntityPresent(NEIGHBOUR);
        helper.setSlot(NEIGHBOUR, 0, cobblestone.copy());
        ItemStack template = new ItemStack(Blocks.cobblestone, 1);
        helper.assertEquals(0L, helper.countItems(MACHINE, template), "the machine starts empty");

        helper.startSequence()
            .thenWaitUntil(
                "the hopper pushed cobblestone through the covered face",
                HOPPER_TIMEOUT_TICKS,
                () -> helper
                    .assertTrue(helper.countItems(MACHINE, template) > 0L, "nothing has entered the machine yet"))
            .thenExecute("the hopper gave up its stack", () -> {
                helper
                    .assertTrue(helper.countItems(NEIGHBOUR, template) < 8L, "the hopper still holds the whole stack");
                helper.assertInstanceOf(
                    MachineSensorCover.class,
                    machine.getCoverAtSide(COVERED),
                    "the cover left the machine");
            })
            .thenSucceed();
    }

    /** §14: a fluid is filled into the machine through the covered face, using GT's own IFluidHandler path. */
    @GameTest(batch = BATCH, timeoutTicks = 200)
    public static void transparentToFluids(GameTestHelper helper) {
        IGregTechTileEntity machine = prepareMachine(helper, ItemList.Machine_LV_ChemicalReactor.get(1L));
        helper.assertTrue(
            machine.getMetaTileEntity()
                .getCapacity() > 0,
            "the chemical reactor has no fluid tank (test setup)");
        // GT ignores fill/drain until the holder's 5th tick (BaseMetaTileEntity.java:1892).
        helper.gtnh()
            .withWarpRange(WARP_RANGE)
            .fastForwardTicks(10);
        FluidStack water = new FluidStack(FluidRegistry.WATER, 1000);
        // Positive control: the uncovered face accepts water.
        helper.assertEquals(1000L, machine.fill(COVERED, water.copy(), false), "simulated fill without a cover");

        attach(helper, machine, COVERED);
        helper.assertTrue(machine.canFill(COVERED, FluidRegistry.WATER), "canFill through the covered face");
        helper.assertEquals(1000L, machine.fill(COVERED, water.copy(), true), "filled amount");
        // A basic machine holds an input fluid in its fillable stack; getFluid() is the drainable output tank.
        MTEBasicTank tank = helper
            .assertInstanceOf(MTEBasicTank.class, machine.getMetaTileEntity(), "the reactor has no GT tank");
        FluidStack inTank = tank.getFillableStack();
        helper.assertNotNull(inTank, "the reactor input tank is empty after the fill");
        helper.assertEquals(1000L, inTank.amount, "fluid in the reactor tank");
        helper.assertEquals(
            "water",
            inTank.getFluid()
                .getName(),
            "fluid in the reactor tank");
        helper.succeed();
    }

    /** §14: a redstone block next to the covered face is read back through the cover. */
    @GameTest(batch = BATCH)
    public static void transparentToRedstone(GameTestHelper helper) {
        IGregTechTileEntity machine = prepareMachine(helper);
        TestPos abs = helper.absolute(NEIGHBOUR);
        helper.getWorld()
            .setBlock(abs.x(), abs.y(), abs.z(), Blocks.redstone_block);
        // Positive control: the signal arrives while the face is uncovered.
        helper.assertEquals(15L, machine.getInputRedstoneSignal(COVERED), "raw signal at the uncovered face");
        helper.assertEquals(15L, machine.getInternalInputRedstoneSignal(COVERED), "signal through the uncovered face");

        MachineSensorCover cover = attach(helper, machine, COVERED);
        helper.assertEquals(
            15L,
            machine.getInternalInputRedstoneSignal(COVERED),
            "the covered face blocks the redstone signal");
        // The cover neither reacts to redstone nor drives it (§3.4).
        helper.assertFalse(cover.isRedstoneSensitive(0L), "isRedstoneSensitive");
        helper.assertFalse(cover.manipulatesSidedRedstoneOutput(), "manipulatesSidedRedstoneOutput");
        helper.assertEquals(0L, machine.getOutputRedstoneSignal(COVERED), "the cover drives redstone");
        helper.succeed();
    }

    /** §3.4 and errata E5: the holder's hammer path refuses a tick-rate change, so the rate stays 20. */
    @GameTest(batch = BATCH)
    public static void tickRateLocked(GameTestHelper helper) {
        IGregTechTileEntity machine = prepareMachine(helper);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        helper.assertFalse(cover.allowsTickRateAddition(), "allowsTickRateAddition");

        ItemStack hammer = MetaGeneratedTool01.INSTANCE
            .getToolWithStats(IDMetaTool01.HARDHAMMER.ID, 1, Materials.Steel, Materials.Wood, null);
        helper.assertNotNull(hammer, "no hard hammer (test setup)");
        // Not sneaking: GT's cover branch for a hard hammer or jackhammer (BaseMetaTileEntity.java:1589-1605).
        helper.assertTrue(
            SensorPlacementTests.rightClick(helper, MACHINE, COVERED, hammer, false),
            "GT did not handle the hammer click");
        helper.assertSame(cover, machine.getCoverAtSide(COVERED), "the hammer removed the cover");
        helper.assertEquals(MachineSensorCover.TICK_RATE, cover.getTickRate(), "tick rate after the hammer");
        helper.assertEquals(0, cover.getTickRateAddition(), "tick rate addition after the hammer");
        helper.succeed();
    }

    /** §3.4: GT's Cover Copy/Paste tool refuses to copy the sensor, and copies an ordinary cover in the same setup. */
    @GameTest(batch = BATCH)
    public static void copyPasteDisallowed(GameTestHelper helper) {
        IGregTechTileEntity machine = prepareMachine(helper);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        helper.assertFalse(cover.allowsCopyPasteTool(), "allowsCopyPasteTool");
        helper.assertTrue(
            SensorPlacementTests.placeViaCoverPlacer(helper, machine, SPARE, ItemList.Conveyor_Module_LV.get(1L)),
            "GT conveyor refused (test setup)");
        CoverConveyor conveyor = helper
            .assertInstanceOf(CoverConveyor.class, machine.getCoverAtSide(SPARE), "conveyor");

        ItemStack tool = ItemList.Tool_Cover_Copy_Paste.get(1L);
        MetaBaseItem toolItem = helper
            .assertInstanceOf(MetaBaseItem.class, tool.getItem(), "the cover tool is not a GT meta item");
        toolItem.charge(tool, 1_000_000d, Integer.MAX_VALUE, true, false);
        helper.assertTrue(toolItem.canUse(tool, 100d), "the cover tool has no charge (test setup)");

        helper.assertFalse(copyCoverWithTool(helper, tool, MACHINE, COVERED), "the tool copied the sensor");
        helper.assertEquals(
            0L,
            tool.getTagCompound()
                .getInteger("mCoverId"),
            "the tool stored a cover id for the sensor");

        helper.assertTrue(copyCoverWithTool(helper, tool, MACHINE, SPARE), "the tool refused an ordinary cover");
        helper.assertEquals(
            conveyor.getCoverID(),
            tool.getTagCompound()
                .getInteger("mCoverId"),
            "the tool did not store the conveyor");
        helper.succeed();
    }

    /**
     * §3.4: {@code doCoverThings} heartbeats a cover that has an identity, and never one that is inert. Real ticks,
     * because a warp freezes the server tick counter the cover rate is measured against (errata E2).
     */
    @GameTest(batch = BATCH, timeoutTicks = 200)
    public static void heartbeatReportsIdentifiedCoversOnly(GameTestHelper helper) {
        RecordedEvents events = recordEvents(helper);
        IGregTechTileEntity machine = prepareMachine(helper);
        MachineSensorCover live = attach(helper, machine, COVERED);
        UUID id = live.identity()
            .id();

        // A machine whose saved cover data is not GregScope's: the cover loads inert (§3.3).
        IGregTechTileEntity foreign = placeMachineWithCoverNbt(
            helper,
            OTHER_MACHINE,
            sensorCoverId(),
            new NBTTagInt(42));
        MachineSensorCover inert = sensorCover(helper, foreign, COVERED);
        helper.assertNull(inert.identity(), "the foreign blob produced an identity");

        helper.startSequence()
            .thenWaitUntil(
                "the identified cover heartbeats",
                HEARTBEAT_TIMEOUT_TICKS,
                () -> helper.assertTrue(events.count("heartbeat", id) > 0, "no heartbeat yet: " + events))
            .thenExecute("the inert cover never reported", () -> {
                // Horizon-QA keeps every finished cell loaded, so sensors from other tests heartbeat here too; only
                // this cell's two covers are asserted on.
                helper.assertFalse(events.reportedWithoutIdentity(), "an inert cover reported: " + events);
                helper.assertFalse(events.reportedBy(foreign), "the inert machine's cover reported: " + events);
                Recorded first = events.first("heartbeat", id);
                helper.assertNotNull(first, "no heartbeat recorded for " + id);
                helper.assertEquals(COVERED, first.side, "heartbeat side");
                helper.assertSame(machine, first.holder, "heartbeat holder");
                helper.assertSame(live, first.cover, "heartbeat cover");
            })
            .thenSucceed();
    }

    /** §3.4: a crowbar takes the cover off and the registry is told the sensor was detached. */
    @GameTest(batch = BATCH)
    public static void crowbarRemovalReportsDetached(GameTestHelper helper) {
        RecordedEvents events = recordEvents(helper);
        IGregTechTileEntity machine = prepareMachine(helper);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        UUID id = cover.identity()
            .id();

        ItemStack crowbar = MetaGeneratedTool01.INSTANCE
            .getToolWithStats(IDMetaTool01.CROWBAR.ID, 1, Materials.Steel, Materials.Wood, null);
        helper.assertTrue(
            SensorPlacementTests.rightClick(helper, MACHINE, COVERED, crowbar, true),
            "GT did not handle the crowbar click");
        helper.assertFalse(machine.hasCoverAtSide(COVERED), "the crowbar left the cover on the machine");
        helper.assertEquals(1L, events.count("detached", id), "detached events: " + events);
        helper.assertEquals(0L, events.count("destroyedIntoItem", id), "unexpected destroy event: " + events);
        Recorded detached = events.first("detached", id);
        helper.assertEquals(COVERED, detached.side, "detached side");
        helper.assertSame(machine, detached.holder, "detached holder");
        helper.assertSame(cover, detached.cover, "detached cover");
        helper.succeed();
    }

    /** §3.4 and §14: a survival break writes the identity into the machine's drop and reports IN_ITEM. */
    @GameTest(batch = BATCH)
    public static void dropsCarryUuidAndReportInItem(GameTestHelper helper) {
        RecordedEvents events = recordEvents(helper);
        IGregTechTileEntity machine = prepareMachine(helper);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        SensorIdentity identity = cover.identity();

        List<ItemStack> drops = new ArrayList<>(machine.getDrops());
        helper.assertEquals(1L, drops.size(), "machine drops");
        ItemStack drop = drops.get(0);
        helper.assertNotNull(drop.getTagCompound(), "the drop carries no NBT");
        NBTTagCompound entry = coverEntry(helper, drop.getTagCompound(), COVERED);
        NBTTagCompound data = coverData(helper, entry);
        helper.assertEquals((byte) 1, data.getByte(SensorNbtCodec.GS), "gs marker in the drop");
        helper.assertEquals(
            identity.id()
                .getMostSignificantBits(),
            data.getLong(SensorNbtCodec.ID_MSB),
            "idM in the drop");
        helper.assertEquals(
            identity.id()
                .getLeastSignificantBits(),
            data.getLong(SensorNbtCodec.ID_LSB),
            "idL in the drop");
        helper.assertEquals(sensorCoverId(), entry.getInteger("id"), "the drop's cover id is not the Machine Sensor");
        helper.assertEquals(1L, events.count("destroyedIntoItem", identity.id()), "IN_ITEM events: " + events);
        helper.assertSame(machine, events.first("destroyedIntoItem", identity.id()).holder, "IN_ITEM holder");
        helper.succeed();
    }

    /**
     * §3.3: data written by a newer GregScope leaves the cover inert and is written back verbatim, so a rollback keeps
     * it. A blob that is not GregScope's at all is inert too and is also preserved.
     */
    @GameTest(batch = BATCH)
    public static void foreignAndUnsupportedDataAreInertAndPreserved(GameTestHelper helper) {
        NBTTagCompound future = new NBTTagCompound();
        future.setByte(SensorNbtCodec.GS, (byte) 2);
        future.setLong(SensorNbtCodec.ID_MSB, 1L);
        future.setLong(SensorNbtCodec.ID_LSB, 2L);
        future.setString("zz", "a key this version knows nothing about");

        IGregTechTileEntity machine = placeMachineWithCoverNbt(helper, MACHINE, sensorCoverId(), future);
        MachineSensorCover cover = sensorCover(helper, machine, COVERED);
        helper.assertNull(cover.identity(), "an unsupported version produced an identity");
        helper.assertEquals("unsupported data version", cover.inertReason(), "inert reason");
        helper.assertEquals("GregScope sensor (unsupported data version)", cover.getDescription(), "description");
        helper.assertEquals(
            future,
            coverData(helper, savedCoverEntry(helper, MACHINE, COVERED)),
            "the unsupported compound was not written back verbatim");

        NBTTagInt blob = new NBTTagInt(42);
        IGregTechTileEntity other = placeMachineWithCoverNbt(helper, OTHER_MACHINE, sensorCoverId(), blob);
        MachineSensorCover inert = sensorCover(helper, other, COVERED);
        helper.assertNull(inert.identity(), "a foreign blob produced an identity");
        helper.assertEquals("inactive", inert.inertReason(), "inert reason");
        helper.assertEquals("GregScope sensor (inactive)", inert.getDescription(), "description");
        NBTTagCompound savedEntry = savedCoverEntry(helper, OTHER_MACHINE, COVERED);
        helper.assertEquals(blob, savedEntry.getTag("d"), "the foreign blob was not written back verbatim");
        // Still read-only towards the machine: an inert cover blocks nothing.
        helper.assertTrue(inert.letsEnergyIn() && inert.letsItemsIn(-1), "an inert cover blocks the face");
        helper.succeed();
    }

    /**
     * GS-REV-3: {@link MachineSensorCover#allowsCopyPasteTool()} only refuses GT's <em>copy</em> direction
     * ({@code BehaviourCoverTool.java:73-87}). The paste direction ({@code :88-99}) is gated on the numeric cover id
     * alone and calls {@code ICoverable.updateAttachedCover}, which is {@code readFromNbt} on the live cover
     * ({@code CoverableTileEntity.java:498-502}). A blob that does not read as a GregScope sensor must therefore be
     * ignored rather than allowed to erase a registered sensor's UUID, which would orphan its history and strike it
     * out as a missing target. A write that does read as a sensor is still applied, so nothing else changes.
     */
    @GameTest(batch = BATCH)
    public static void pastedForeignDataDoesNotEraseALiveSensor(GameTestHelper helper) {
        IGregTechTileEntity machine = prepareMachine(helper);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        SensorIdentity identity = cover.identity();
        helper.assertNotNull(identity, "the sensor has no identity (test setup)");

        // A foreign compound, as another cover's data would arrive.
        NBTTagCompound foreign = new NBTTagCompound();
        foreign.setInteger("mode", 3);
        machine.updateAttachedCover(sensorCoverId(), COVERED, wrapCoverData(foreign));
        helper.assertSame(cover, machine.getCoverAtSide(COVERED), "the paste replaced the cover object");
        assertStillIdentified(helper, cover, identity, "a foreign compound");
        helper.assertNull(cover.inertReason(), "the sensor was turned inert by a foreign paste");

        // A bare blob, as a reused numeric id would arrive.
        machine.updateAttachedCover(sensorCoverId(), COVERED, wrapCoverData(new NBTTagInt(42)));
        assertStillIdentified(helper, cover, identity, "a foreign blob");

        // An unsupported GregScope version must not downgrade a live sensor either.
        NBTTagCompound future = new NBTTagCompound();
        future.setByte(SensorNbtCodec.GS, (byte) 2);
        machine.updateAttachedCover(sensorCoverId(), COVERED, wrapCoverData(future));
        assertStillIdentified(helper, cover, identity, "a newer data version");

        // The identity is still what the machine saves, so a reload keeps the sensor.
        NBTTagCompound saved = coverData(helper, savedCoverEntry(helper, MACHINE, COVERED));
        helper.assertEquals(
            identity.id()
                .getMostSignificantBits(),
            saved.getLong(SensorNbtCodec.ID_MSB),
            "idM in the saved cover");

        // Not a blanket refusal of every write: data that does read as a sensor is still applied.
        SensorIdentity replacement = new SensorIdentity(UUID.randomUUID(), "Pasted", null, null, FAKE_NOW_SEC);
        NBTTagCompound valid = new NBTTagCompound();
        valid.setByte(SensorNbtCodec.GS, (byte) 1);
        valid.setLong(
            SensorNbtCodec.ID_MSB,
            replacement.id()
                .getMostSignificantBits());
        valid.setLong(
            SensorNbtCodec.ID_LSB,
            replacement.id()
                .getLeastSignificantBits());
        machine.updateAttachedCover(sensorCoverId(), COVERED, wrapCoverData(valid));
        helper.assertEquals(
            replacement.id(),
            cover.identity()
                .id(),
            "a valid sensor compound was refused");
        helper.succeed();
    }

    /** Fails with a readable message rather than a {@code NullPointerException} when a paste erased the identity. */
    private static void assertStillIdentified(GameTestHelper helper, MachineSensorCover cover, SensorIdentity expected,
        String what) {
        helper.assertNotNull(cover.identity(), what + " erased the sensor identity");
        helper.assertEquals(
            expected.id(),
            cover.identity()
                .id(),
            what + " replaced the sensor UUID");
    }

    /** The wrapper GT hands to {@code Cover.readFromNbt}: only the {@code d} tag, as a paste carries it. */
    private static NBTTagCompound wrapCoverData(NBTBase data) {
        NBTTagCompound wrapper = new NBTTagCompound();
        wrapper.setTag("d", data);
        return wrapper;
    }

    /** §14 and §8.5: a cover id this build does not know (GregScope removed) loads as no cover, not as a sensor. */
    @GameTest(batch = BATCH)
    public static void unknownCoverIdNbtLoadsAsNoCover(GameTestHelper helper) {
        ItemStack stick = new ItemStack(Items.stick);
        int unknownId = GTUtility.stackToInt(stick);
        helper.assertFalse(CoverRegistry.isCover(stick), "the stand-in id belongs to a cover (test setup)");

        NBTTagCompound data = new NBTTagCompound();
        SensorIdentity identity = new SensorIdentity(UUID.randomUUID(), "Main EBF", null, null, FAKE_NOW_SEC);
        data.setByte(SensorNbtCodec.GS, (byte) 1);
        data.setLong(
            SensorNbtCodec.ID_MSB,
            identity.id()
                .getMostSignificantBits());
        data.setLong(
            SensorNbtCodec.ID_LSB,
            identity.id()
                .getLeastSignificantBits());

        IGregTechTileEntity machine = placeMachineWithCoverNbt(helper, MACHINE, unknownId, data);
        Cover loaded = machine.getCoverAtSide(COVERED);
        helper.assertFalse(loaded instanceof SensorCover, "an unknown cover id produced a GregScope sensor");
        helper.assertInstanceOf(CoverNone.class, loaded, "loaded cover");
        // CoverNone is transparent, so the machine keeps working without GregScope.
        helper.assertTrue(loaded.letsEnergyIn() && loaded.letsItemsIn(-1), "the loaded cover blocks the face");
        helper.succeed();
    }

    /** §14: the identity survives a real chunk unload and reload, and the unload is reported. */
    @GameTest(batch = RELOAD_BATCH, timeoutTicks = 200)
    public static void uuidStableAcrossChunkReload(GameTestHelper helper) {
        RecordedEvents events = recordEvents(helper);
        useFakeClock(helper, FAKE_NOW_SEC);
        IGregTechTileEntity machine = prepareMachine(helper);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        SensorIdentity before = cover.identity();
        ChunkReload chunks = ChunkReload.ofChunkAt(helper, MACHINE);

        helper.startSequence()
            .thenExecute("unload", chunks::unload)
            .thenIdle(1)
            .thenExecute("reload and compare", () -> {
                helper.assertEquals(1L, events.count("unloaded", before.id()), "unload events: " + events);
                helper.assertEquals(COVERED, events.first("unloaded", before.id()).side, "unload side");
                chunks.reload();
                TileEntity tile = helper.assertTileEntityPresent(MACHINE);
                helper.assertNotSame(machine, tile, "the machine tile entity object after the reload");
                IGregTechTileEntity reloaded = helper
                    .assertInstanceOf(IGregTechTileEntity.class, tile, "reloaded tile");
                MachineSensorCover after = sensorCover(helper, reloaded, COVERED);
                helper.assertNotSame(cover, after, "the cover object after the reload");
                helper.assertEquals(before, after.identity(), "the identity changed across the reload");
                helper.assertEquals(
                    before.id(),
                    after.identity()
                        .id(),
                    "sensor UUID");
                helper.assertEquals(MachineSensorCover.TICK_RATE, after.getTickRate(), "tick rate after the reload");
            })
            .thenSucceed();
    }

    // --- helpers ---

    private static IGregTechTileEntity prepareMachine(GameTestHelper helper) {
        return prepareMachine(helper, ItemList.Machine_LV_E_Furnace.get(1L));
    }

    /**
     * A basic machine with a known main facing and front facing, so {@link #COVERED} and {@link #SPARE} are faces GT
     * itself allows items, fluids and EU through.
     */
    private static IGregTechTileEntity prepareMachine(GameTestHelper helper, ItemStack machineStack) {
        IGregTechTileEntity holder = GtPlacement.placeMachine(helper, MACHINE, machineStack);
        MTEBasicMachine mte = helper
            .assertInstanceOf(MTEBasicMachine.class, holder.getMetaTileEntity(), "basic machine");
        helper.assertTrue(mte.setMainFacing(MAIN_FACING), "GT refused the main facing " + MAIN_FACING);
        holder.setFrontFacing(FRONT_FACING);
        helper.assertEquals(MAIN_FACING, mte.mMainFacing, "main facing");
        helper.assertEquals(FRONT_FACING, holder.getFrontFacing(), "front facing");
        for (ForgeDirection side : new ForgeDirection[] { COVERED, SPARE }) {
            helper.assertTrue(mte.isLiquidInput(side), side + " is not a free input face after the setup");
        }
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

    private static int sensorCoverId() {
        return GTUtility.stackToInt(SensorCovers.sensorStack());
    }

    /** True if GT would take {@code stack} into some slot through {@code side} (the hopper's own check). */
    private static boolean acceptsItemThrough(IGregTechTileEntity holder, ForgeDirection side, ItemStack stack) {
        for (int slot : holder.getAccessibleSlotsFromSide(side.ordinal())) {
            if (holder.canInsertItem(slot, stack, side.ordinal())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Places a GT machine from an item stack whose NBT carries one cover on {@link #COVERED}: the path a picked-up
     * machine takes ({@code ItemMachines.placeBlockAt} feeds the stack NBT to {@code setInitialValuesAsNBT}).
     */
    private static IGregTechTileEntity placeMachineWithCoverNbt(GameTestHelper helper, TestPos pos, int coverId,
        NBTBase coverData) {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setByte("s", (byte) COVERED.ordinal());
        entry.setInteger("id", coverId);
        entry.setInteger("tra", 0);
        entry.setTag("d", coverData.copy());
        NBTTagList covers = new NBTTagList();
        covers.appendTag(entry);
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setTag(GTValues.NBT.COVERS, covers);
        ItemStack stack = ItemList.Machine_LV_E_Furnace.get(1L);
        stack.setTagCompound(nbt);
        return GtPlacement.placeMachine(helper, pos, stack);
    }

    /** The cover entry for {@code side} in the tile's saved NBT. */
    private static NBTTagCompound savedCoverEntry(GameTestHelper helper, TestPos pos, ForgeDirection side) {
        return coverEntry(helper, helper.getTileNBT(pos), side);
    }

    private static NBTTagCompound coverEntry(GameTestHelper helper, NBTTagCompound tileNbt, ForgeDirection side) {
        NBTTagList covers = tileNbt.getTagList(GTValues.NBT.COVERS, 10);
        for (int i = 0; i < covers.tagCount(); i++) {
            NBTTagCompound entry = covers.getCompoundTagAt(i);
            if (entry.getByte("s") == (byte) side.ordinal()) {
                return entry;
            }
        }
        helper.fail("no saved cover on " + side + " in " + tileNbt);
        return new NBTTagCompound();
    }

    private static NBTTagCompound coverData(GameTestHelper helper, NBTTagCompound coverEntry) {
        NBTBase data = coverEntry.getTag("d");
        return helper.assertInstanceOf(NBTTagCompound.class, data, "cover data compound");
    }

    /** GT's Cover Copy/Paste tool in copy mode; true if it stored a cover. */
    private static boolean copyCoverWithTool(GameTestHelper helper, ItemStack tool, TestPos pos, ForgeDirection side) {
        TestPos abs = helper.absolute(pos);
        FakePlayer player = helper.spawnFakePlayer(PLAYER);
        player.setSneaking(true);
        int before = tool.hasTagCompound() ? tool.getTagCompound()
            .getInteger("mCoverId") : 0;
        try {
            tool.getItem()
                .onItemUseFirst(
                    tool,
                    player,
                    helper.getWorld(),
                    abs.x(),
                    abs.y(),
                    abs.z(),
                    side.ordinal(),
                    0.5F,
                    0.5F,
                    0.5F);
        } finally {
            player.setSneaking(false);
        }
        helper.assertNotNull(tool.getTagCompound(), "the cover tool lost its NBT");
        int after = tool.getTagCompound()
            .getInteger("mCoverId");
        return after != 0 && after != before;
    }

    /** Pins the clock GregScope timestamps with; restored after the test. */
    private static void useFakeClock(GameTestHelper helper, long epochSec) {
        helper.assertTrue(
            GregScopeTestHooks.setClock(FakeClock.atEpochSec(epochSec)),
            "GregScope test hooks are disabled");
        helper.afterTest(() -> GregScopeTestHooks.setClock(null));
    }

    /**
     * Installs a recording listener in place of the registry (GS-107 installs the real one) and puts the registry
     * back afterwards.
     *
     * <p>
     * {@code SensorCovers.events()} is one process-wide field, and Horizon-QA starts every test body in a batch back
     * to back, so a test that returns after {@code startSequence} keeps its recorder installed for many ticks. Two
     * overlapping recorders would therefore restore each other's listener and leave a finished test's recorder in
     * place, silently detaching the registry for the rest of the server run. Rather than save and restore whatever
     * happens to be installed, this asserts that the registry is installed now and restores the registry itself, so
     * an overlap fails loudly in the test that caused it instead of poisoning later batches.
     */
    private static RecordedEvents recordEvents(GameTestHelper helper) {
        SensorRegistry registry = GregScope.registry();
        helper.assertNotNull(registry, "no registry: GS-107 installs it on server start");
        helper.assertSame(
            registry,
            SensorCovers.events(),
            "another test's recorder is still installed; recordEvents must not nest");
        RecordedEvents events = new RecordedEvents();
        helper.assertTrue(GregScopeTestHooks.setSensorEvents(events), "GregScope test hooks are disabled");
        helper.afterTest(() -> GregScopeTestHooks.setSensorEvents(registry));
        return events;
    }

    /** One reported event, with what the cover passed to the listener. */
    private static final class Recorded {

        final String event;
        final SensorCover cover;
        final ICoverable holder;
        final ForgeDirection side;
        final UUID id;

        Recorded(String event, SensorCover cover, ICoverable holder, ForgeDirection side) {
            this.event = event;
            this.cover = cover;
            this.holder = holder;
            this.side = side;
            SensorIdentity identity = cover.identity();
            this.id = identity == null ? null : identity.id();
        }

        @Override
        public String toString() {
            return event + " " + side + " " + (id == null ? "no-identity" : id);
        }
    }

    /**
     * What the covers reported, in order. Every sensor in the world reports here, not only this cell's: Horizon-QA
     * keeps finished cells loaded, so their covers keep heartbeating. Queries therefore filter by sensor UUID or by
     * holder.
     */
    private static final class RecordedEvents implements SensorEvents {

        private final List<Recorded> records = new ArrayList<>();

        @Override
        public void heartbeat(SensorCover cover, ICoverable holder, ForgeDirection side) {
            records.add(new Recorded("heartbeat", cover, holder, side));
        }

        @Override
        public void unloaded(SensorCover cover, ICoverable holder, ForgeDirection side) {
            records.add(new Recorded("unloaded", cover, holder, side));
        }

        @Override
        public void detached(SensorCover cover, ICoverable holder, ForgeDirection side) {
            records.add(new Recorded("detached", cover, holder, side));
        }

        @Override
        public void destroyedIntoItem(SensorCover cover, ICoverable holder, ForgeDirection side) {
            records.add(new Recorded("destroyedIntoItem", cover, holder, side));
        }

        long count(String event, UUID id) {
            long count = 0;
            for (Recorded record : records) {
                if (record.event.equals(event) && id.equals(record.id)) {
                    count++;
                }
            }
            return count;
        }

        Recorded first(String event, UUID id) {
            for (Recorded record : records) {
                if (record.event.equals(event) && id.equals(record.id)) {
                    return record;
                }
            }
            return null;
        }

        /** True if any cover reported while it had no identity, which design section 3.4 forbids. */
        boolean reportedWithoutIdentity() {
            for (Recorded record : records) {
                if (record.id == null) {
                    return true;
                }
            }
            return false;
        }

        boolean reportedBy(ICoverable holder) {
            for (Recorded record : records) {
                if (record.holder == holder) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return records.toString();
        }
    }
}
