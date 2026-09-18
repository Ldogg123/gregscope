package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.AfterBatch;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.covers.CoverContext;
import gregtech.api.covers.CoverPlacer;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.client.ResourceUtils;
import gregtech.common.covers.Cover;
import gregtech.common.covers.CoverConveyor;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeAssets;
import io.github.ldogg123.gregscope.sensor.ItemMachineSensor;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorCover;
import io.github.ldogg123.gregscope.sensor.SensorCovers;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-105 (design-v0.2 §3.2, §14) and the GS-201 hook from design-v0.3 §11: the Machine Sensor item is a registered GT
 * cover and goes only where §3.2 allows.
 *
 * <p>
 * Placement goes through GT's own code in two ways. {@link #placeViaCoverPlacer} is the §13.1 path: it applies the
 * gate GT's {@code BaseMetaTileEntity.onRightclick} applies before placing a cover (no cover on that face, the stack
 * is a cover, the placer's predicate, the machine's {@code allowCoverOnSide}; GT5U 5.09.54.133
 * {@code BaseMetaTileEntity.java:1565-1572}) and then calls {@code CoverRegistry.getCoverPlacer(stack).placeCover}.
 * {@link #sneakRightClick} is the player path: a sneaking FakePlayer holding the stack activates GT's machine block
 * on a chosen face, hitting the face centre, so GT's own gate decides. Each rejection test also shows which check
 * rejects, so a pass cannot come from an unrelated check.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class SensorPlacementTests {

    private static final String BATCH = "gregscope.sensor";
    private static final String EBF_TEMPLATE = ElectricBlastFurnaceSnapshotTests.TEMPLATE;
    private static final TestPos EBF_CONTROLLER = at(1, 0, 0);
    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos OTHER_MACHINE = at(2, 0, 1);
    private static final String PLAYER = "gregscope-sensor-placer";
    /** Covers a test cell (templates up to 3x4x3 here) and stops short of the next cell. */
    private static final int WARP_RANGE = 4;

    private SensorPlacementTests() {}

    /**
     * GS107-T3: the sensors this batch left in its cells would keep heartbeating into the registry and, together with
     * every other batch's, fill the shared unowned {@code limits.maxSensorsPerTeam} quota. See {@link SensorCleanup}.
     */
    @AfterBatch(BATCH)
    public static void cleanUpAfterPlacement() {
        SensorCleanup.detachAllAndPurge();
    }

    /** §3.2 registration, §12.2 assets and lang, as a dedicated server sees them. */
    @GameTest(batch = BATCH)
    public static void sensorItemIsRegisteredCover(GameTestHelper helper) {
        ItemStack stack = SensorCovers.sensorStack();
        Item registered = GameRegistry.findItem(GregScope.MODID, GregScopeAssets.REGISTRY_MACHINE_SENSOR);
        helper.assertSame(ItemMachineSensor.INSTANCE, registered, "gregscope:machine_sensor");
        helper.assertTrue(CoverRegistry.isCover(stack), "CoverRegistry.isCover(sensor stack)");
        helper.assertEquals(64, stack.getMaxStackSize(), "max stack size");
        // Item.getCreativeTab() is client-only (stripped on a dedicated server); the tab icon is checked instead.
        helper.assertSame(
            ItemMachineSensor.INSTANCE,
            GregScope.creativeTab()
                .getTabIconItem(),
            "creative tab icon");
        helper.assertEquals("item.gregscope.machine_sensor", stack.getUnlocalizedName(), "unlocalized name");
        // Dedicated servers load mod lang too (§12.2), so the English names resolve here.
        helper.assertEquals("Machine Sensor", stack.getDisplayName(), "display name");
        List<String> tooltip = new ArrayList<>();
        ItemMachineSensor.INSTANCE.addInformation(stack, null, tooltip, false);
        helper.assertEquals(3, tooltip.size(), "tooltip lines " + tooltip);
        helper.assertEquals(
            "Attach to a GT machine or multiblock controller (sneak + right-click)",
            tooltip.get(0),
            "tooltip 1");
        helper.assertEquals("Rename in an anvil to set a label", tooltip.get(1), "tooltip 2");
        helper.assertEquals("Read-only: passes power, items, fluids and redstone", tooltip.get(2), "tooltip 3");
        helper.assertEquals("Running", StatCollector.translateToLocal("gregscope.state.running"), "state lang");

        // The placer: a predicate, and GUI-clickable, so the machine GUI still opens and basic machines accept it on
        // their main face.
        CoverPlacer placer = CoverRegistry.getCoverPlacer(stack);
        helper.assertTrue(placer.isGuiClickable(), "placer blocks the machine GUI");
        helper.assertFalse(placer.allowOnPrimitiveBlock(), "placer allows primitive blocks");

        // The factory builds a Machine Sensor cover (kind machine, inert until GS-106) that is read-only.
        Cover built = CoverRegistry.buildCover(stack, ForgeDirection.NORTH, null);
        MachineSensorCover cover = helper.assertInstanceOf(MachineSensorCover.class, built, "built cover");
        helper.assertTrue(cover.isValid(), "built cover is valid");
        helper.assertEquals(SensorKind.MACHINE, cover.sensorKind(), "sensor kind");
        helper.assertNull(cover.identity(), "identity before GS-106");
        assertReadOnly(helper, cover);

        // Overlay: the E1 icon key, a valid texture, and GT's own resource location resolves to a PNG in the mod jar.
        ITexture texture = CoverRegistry.getCoverTexture(stack);
        helper.assertNotNull(texture, "cover texture");
        helper.assertTrue(texture.isValidTexture(), "cover texture is valid");
        helper.assertNotSame(Textures.BlockIcons.ERROR_RENDERING[0], texture, "cover texture fell back to ERROR");
        helper.assertEquals("iconsets/GREGSCOPE_SENSOR_OVERLAY", GregScopeAssets.BLOCK_ICON_SENSOR_OVERLAY, "icon key");
        ResourceLocation overlay = ResourceUtils
            .getCompleteBlockTextureResourceLocation(GregScopeAssets.DOMAIN, GregScopeAssets.BLOCK_ICON_SENSOR_OVERLAY);
        helper.assertEquals(
            "gregscope:textures/blocks/iconsets/GREGSCOPE_SENSOR_OVERLAY.png",
            overlay.toString(),
            "GT resource location");
        assertModResource(helper, overlay);
        assertModResource(
            helper,
            new ResourceLocation(
                GregScopeAssets.DOMAIN,
                "textures/items/" + GregScopeAssets.ITEM_ICON_MACHINE_SENSOR + ".png"));
        helper.succeed();
    }

    /** A sneaking player places the sensor on an LV machine through GT's block activation. */
    @GameTest(batch = BATCH)
    public static void placesOnLvMachine(GameTestHelper helper) {
        IGregTechTileEntity holder = placeLvMachine(helper, MACHINE);
        // GT's face rules accept the sensor on every face, the main face included (GUI-clickable placer).
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            helper.assertTrue(
                SensorCovers.isPlaceable(side, SensorCovers.sensorStack(), holder),
                "predicate on empty machine, side " + side);
            helper.assertTrue(
                holder.getMetaTileEntity()
                    .allowCoverOnSide(side, SensorCovers.sensorStack()),
                "GT allowCoverOnSide, side " + side);
        }
        ItemStack held = stackOf(2);
        boolean handled = sneakRightClick(helper, MACHINE, ForgeDirection.UP, held);
        helper.assertTrue(handled, "GT did not handle the sneak right-click");
        MachineSensorCover cover = helper.assertInstanceOf(
            MachineSensorCover.class,
            holder.getCoverAtSide(ForgeDirection.UP),
            "cover on the clicked face");
        helper.assertEquals(ForgeDirection.UP, cover.getSide(), "cover side");
        helper.assertSame(holder, cover.getTile(), "cover tile");
        helper.assertEquals(1, held.stackSize, "one sensor consumed");
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (side != ForgeDirection.UP) {
                helper.assertFalse(holder.hasCoverAtSide(side), "unexpected cover on " + side);
            }
        }
        assertReadOnly(helper, cover);
        helper.succeed();
    }

    /** A formed EBF controller accepts the sensor on a face other than its front. */
    @GameTest(template = EBF_TEMPLATE, batch = BATCH)
    public static void placesOnEbfControllerSide(GameTestHelper helper) {
        IGregTechTileEntity controller = controller(helper);
        ForgeDirection front = controller.getFrontFacing();
        ForgeDirection side = notFront(front);
        helper.assertTrue(placeViaCoverPlacer(helper, controller, side), "sensor refused on controller side " + side);
        helper.assertInstanceOf(MachineSensorCover.class, controller.getCoverAtSide(side), "cover on " + side);
        helper.assertFalse(controller.hasCoverAtSide(front), "cover on the front");
        helper.succeed();
    }

    /** GT refuses any cover on a controller's front; the sensor's own predicate would accept that face. */
    @GameTest(template = EBF_TEMPLATE, batch = BATCH)
    public static void rejectedOnControllerFront(GameTestHelper helper) {
        IGregTechTileEntity controller = controller(helper);
        ForgeDirection front = controller.getFrontFacing();
        ItemStack stack = SensorCovers.sensorStack();
        helper.assertTrue(SensorCovers.isPlaceable(front, stack, controller), "predicate itself on the front");
        helper.assertFalse(
            controller.getMetaTileEntity()
                .allowCoverOnSide(front, stack),
            "GT allowCoverOnSide on the controller front");
        helper.assertFalse(placeViaCoverPlacer(helper, controller, front), "placed via the placer gate");
        ItemStack held = stackOf(1);
        // GT handles (returns true for) a sneak right-click with a cover item even when its gate refuses the cover.
        helper.assertTrue(sneakRightClick(helper, EBF_CONTROLLER, front, held), "GT did not handle the right-click");
        helper.assertFalse(controller.hasCoverAtSide(front), "a cover was placed on the front by a player");
        helper.assertEquals(1, held.stackSize, "sensor consumed");
        helper.succeed();
    }

    /**
     * Hatches are not supported machines. Positive control: the same hatch face accepts a GT conveyor module, so GT's
     * own rules do not reject covers there.
     */
    @GameTest(batch = BATCH)
    public static void rejectedOnHatch(GameTestHelper helper) {
        IGregTechTileEntity hatch = GtPlacement.placeMachine(helper, MACHINE, ItemList.Hatch_Energy_LV.get(1L));
        ForgeDirection side = notFront(hatch.getFrontFacing());
        ItemStack stack = SensorCovers.sensorStack();
        helper.assertTrue(
            hatch.getMetaTileEntity()
                .allowCoverOnSide(side, stack),
            "GT allowCoverOnSide on hatch side " + side);
        helper.assertFalse(SensorCovers.isPlaceable(side, stack, hatch), "predicate accepted a hatch");
        helper.assertFalse(placeViaCoverPlacer(helper, hatch, side), "sensor placed on a hatch");
        helper.assertFalse(hatch.hasCoverAtSide(side), "cover on the hatch");

        ForgeDirection other = side.getOpposite() == hatch.getFrontFacing() ? notFront(side) : side.getOpposite();
        helper.assertTrue(
            placeViaCoverPlacer(helper, hatch, other, ItemList.Conveyor_Module_LV.get(1L)),
            "positive control: GT conveyor refused on hatch side " + other);
        helper.assertInstanceOf(CoverConveyor.class, hatch.getCoverAtSide(other), "conveyor on the hatch");
        helper.succeed();
    }

    /**
     * A non-GT block (a vanilla chest, whose tile is not {@code ICoverable}) gives GT nothing to place on and the
     * predicate refuses it; a GT cable is a GT tile but not a supported machine.
     */
    @GameTest(batch = BATCH)
    public static void rejectedOnNonGtBlock(GameTestHelper helper) {
        TestPos abs = helper.absolute(MACHINE);
        helper.getWorld()
            .setBlock(abs.x(), abs.y(), abs.z(), Blocks.chest);
        TileEntity chest = helper.assertTileEntityPresent(MACHINE);
        helper.assertFalse(chest instanceof ICoverable, "vanilla chest is ICoverable");
        ItemStack stack = SensorCovers.sensorStack();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            helper.assertFalse(SensorCovers.isPlaceable(side, stack, null), "predicate accepted no coverable");
        }
        // GT only offers cover placement from its own tiles (ICoverable), so nothing else can reach the placer; the
        // chest is not activated here because a vanilla chest opens a GUI, which a FakePlayer cannot receive.

        ItemStack cableStack = GTOreDictUnificator.get(OrePrefixes.cableGt01, Materials.Tin, 1L);
        IGregTechTileEntity cable = GtPlacement.placeMachine(helper, OTHER_MACHINE, cableStack);
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            helper.assertFalse(SensorCovers.isPlaceable(side, stack, cable), "predicate accepted a cable, " + side);
        }
        helper.assertFalse(placeViaCoverPlacer(helper, cable, ForgeDirection.UP), "sensor placed on a cable");
        helper.assertFalse(cable.hasCoverAtSide(ForgeDirection.UP), "cover on the cable");
        helper.succeed();
    }

    /** One Machine Sensor per machine, on any face; the next machine still takes its own. */
    @GameTest(batch = BATCH)
    public static void secondSensorOnSameMachineRejected(GameTestHelper helper) {
        IGregTechTileEntity holder = placeLvMachine(helper, MACHINE);
        IGregTechTileEntity neighbour = placeLvMachine(helper, OTHER_MACHINE);
        helper.assertTrue(placeViaCoverPlacer(helper, holder, ForgeDirection.NORTH), "first sensor refused");
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            helper.assertFalse(
                SensorCovers.isPlaceable(side, SensorCovers.sensorStack(), holder),
                "predicate accepted a second sensor on " + side);
            if (side != ForgeDirection.NORTH) {
                helper.assertFalse(placeViaCoverPlacer(helper, holder, side), "second sensor placed on " + side);
                helper.assertFalse(holder.hasCoverAtSide(side), "cover on " + side);
            }
        }
        ItemStack held = stackOf(1);
        helper.assertTrue(
            sneakRightClick(helper, MACHINE, ForgeDirection.SOUTH, held),
            "GT did not handle the right-click");
        helper.assertFalse(holder.hasCoverAtSide(ForgeDirection.SOUTH), "player placed a second sensor");
        helper.assertEquals(1, held.stackSize, "second sensor consumed");
        helper.assertTrue(placeViaCoverPlacer(helper, neighbour, ForgeDirection.NORTH), "neighbour refused a sensor");
        helper.succeed();
    }

    /**
     * design-v0.3 GS-201 (A4): the one-per-machine rule counts only Machine Sensors. A GT conveyor and another kind of
     * {@link SensorCover} (a test-only kind-2 stub) on the same machine do not block it.
     */
    @GameTest(batch = BATCH)
    public static void machineSensorAllowedBesideOtherCovers(GameTestHelper helper) {
        IGregTechTileEntity holder = placeLvMachine(helper, MACHINE);
        helper.assertTrue(
            placeViaCoverPlacer(helper, holder, ForgeDirection.EAST, ItemList.Conveyor_Module_LV.get(1L)),
            "GT conveyor refused");
        helper.assertInstanceOf(CoverConveyor.class, holder.getCoverAtSide(ForgeDirection.EAST), "conveyor");
        holder.attachCover(
            new StubFlowSensorCover(new CoverContext(new ItemStack(Items.stick), ForgeDirection.WEST, holder)));
        SensorCover stub = helper
            .assertInstanceOf(SensorCover.class, holder.getCoverAtSide(ForgeDirection.WEST), "stub sensor cover");
        helper.assertEquals(SensorKind.FLUID_FLOW, stub.sensorKind(), "stub kind");
        helper.assertTrue(holder.hasCoverAtSide(ForgeDirection.WEST), "stub is a valid cover");

        helper.assertTrue(placeViaCoverPlacer(helper, holder, ForgeDirection.UP), "sensor refused beside other covers");
        helper.assertInstanceOf(MachineSensorCover.class, holder.getCoverAtSide(ForgeDirection.UP), "sensor");
        helper.assertFalse(placeViaCoverPlacer(helper, holder, ForgeDirection.DOWN), "second machine sensor placed");
        helper.succeed();
    }

    /** A kind-2 sensor cover standing in for a v0.3 meter; attached directly, never registered. */
    private static final class StubFlowSensorCover extends Cover implements SensorCover {

        private final SensorIdentity identity = new SensorIdentity(UUID.randomUUID(), "", null, null, 0L);

        StubFlowSensorCover(CoverContext context) {
            super(context, null);
        }

        @Override
        public SensorIdentity identity() {
            return identity;
        }

        @Override
        public int sensorKind() {
            return SensorKind.FLUID_FLOW;
        }

        /** The stub is never registered, so the registry never re-keys it or labels it. */
        @Override
        public void rekey(SensorIdentity fresh) {}

        @Override
        public void setAvailability(String availability) {}

        /** Never registered, so no label surface can reach it; a write would be a bug, so it refuses. */
        @Override
        public boolean setLabel(String label) {
            return false;
        }
    }

    // --- helpers ---

    static boolean placeViaCoverPlacer(GameTestHelper helper, IGregTechTileEntity holder, ForgeDirection side) {
        return placeViaCoverPlacer(helper, holder, side, SensorCovers.sensorStack());
    }

    /** The §13.1 path with GT's right-click gate in front of {@code placeCover}; true if a cover was placed. */
    static boolean placeViaCoverPlacer(GameTestHelper helper, IGregTechTileEntity holder, ForgeDirection side,
        ItemStack stack) {
        CoverPlacer placer = CoverRegistry.getCoverPlacer(stack);
        if (holder.hasCoverAtSide(side) || !CoverRegistry.isCover(stack)
            || !placer.isCoverPlaceable(side, stack, holder)
            || !holder.getMetaTileEntity()
                .allowCoverOnSide(side, stack)) {
            return false;
        }
        placer.placeCover(helper.spawnFakePlayer(PLAYER), stack, holder, side);
        helper.assertTrue(holder.hasCoverAtSide(side), "placeCover left no cover on " + side);
        return true;
    }

    /**
     * A sneaking FakePlayer holding {@code held} right-clicks the centre of {@code side} of the block at {@code pos}.
     */
    static boolean sneakRightClick(GameTestHelper helper, TestPos pos, ForgeDirection side, ItemStack held) {
        return rightClick(helper, pos, side, held, true);
    }

    /**
     * A FakePlayer holding {@code held} right-clicks the centre of {@code side} of the block at {@code pos}, sneaking
     * or not, through the block's own {@code onBlockActivated}. Returns whether the block handled the click.
     */
    static boolean rightClick(GameTestHelper helper, TestPos pos, ForgeDirection side, ItemStack held,
        boolean sneaking) {
        TestPos abs = helper.absolute(pos);
        TileEntity tile = helper.getWorld()
            .getTileEntity(abs.x(), abs.y(), abs.z());
        if (tile instanceof IGregTechTileEntity && ((IGregTechTileEntity) tile).getTimer() < 1L) {
            // GT's BlockMachines.onBlockActivated ignores a tile that has never ticked (getTimer() < 1), which would
            // make every rejection pass vacuously. Tick the cell's GT tiles once (warp; no other cell in range).
            helper.gtnh()
                .withWarpRange(WARP_RANGE)
                .fastForwardTicks(2);
            helper.assertTrue(((IGregTechTileEntity) tile).getTimer() >= 1L, "GT tile has still not ticked");
        }
        FakePlayer player = helper.spawnFakePlayer(PLAYER);
        Block block = helper.getWorld()
            .getBlock(abs.x(), abs.y(), abs.z());
        player.inventory.mainInventory[player.inventory.currentItem] = held;
        player.setSneaking(sneaking);
        try {
            return block.onBlockActivated(
                helper.getWorld(),
                abs.x(),
                abs.y(),
                abs.z(),
                player,
                side.ordinal(),
                0.5F,
                0.5F,
                0.5F);
        } finally {
            player.setSneaking(false);
            player.inventory.mainInventory[player.inventory.currentItem] = null;
        }
    }

    static void assertReadOnly(GameTestHelper helper, MachineSensorCover cover) {
        helper.assertTrue(cover.letsEnergyIn(), "letsEnergyIn");
        helper.assertTrue(cover.letsEnergyOut(), "letsEnergyOut");
        helper.assertTrue(cover.letsFluidIn(null), "letsFluidIn");
        helper.assertTrue(cover.letsFluidOut(null), "letsFluidOut");
        helper.assertTrue(cover.letsItemsIn(-1), "letsItemsIn");
        helper.assertTrue(cover.letsItemsOut(-1), "letsItemsOut");
        helper.assertTrue(cover.letsRedstoneGoIn(), "letsRedstoneGoIn");
        helper.assertTrue(cover.letsRedstoneGoOut(), "letsRedstoneGoOut");
        helper.assertFalse(cover.isRedstoneSensitive(0L), "isRedstoneSensitive");
        helper.assertFalse(cover.manipulatesSidedRedstoneOutput(), "manipulatesSidedRedstoneOutput");
        helper.assertFalse(cover.allowsCopyPasteTool(), "allowsCopyPasteTool");
        helper.assertFalse(cover.allowsTickRateAddition(), "allowsTickRateAddition");
        helper.assertFalse(cover.hasCoverGUI(), "hasCoverGUI");
    }

    private static IGregTechTileEntity placeLvMachine(GameTestHelper helper, TestPos pos) {
        return GtPlacement.placeMachine(helper, pos, ItemList.Machine_LV_E_Furnace.get(1L));
    }

    private static IGregTechTileEntity controller(GameTestHelper helper) {
        TileEntity tile = helper.assertTileEntityPresent(EBF_CONTROLLER);
        IGregTechTileEntity controller = helper
            .assertInstanceOf(IGregTechTileEntity.class, tile, "EBF controller tile");
        helper.assertTrue(
            io.github.ldogg123.gregscope.probe.GregTechMachineProbe.isSupportedMte(controller.getMetaTileEntity()),
            "EBF controller is not a supported machine");
        return controller;
    }

    private static ForgeDirection notFront(ForgeDirection front) {
        return front == ForgeDirection.UP || front == ForgeDirection.DOWN ? ForgeDirection.NORTH : ForgeDirection.UP;
    }

    private static ItemStack stackOf(int size) {
        ItemStack stack = SensorCovers.sensorStack();
        stack.stackSize = size;
        return stack;
    }

    private static void assertModResource(GameTestHelper helper, ResourceLocation location) {
        String path = "/assets/" + location.getResourceDomain() + "/" + location.getResourcePath();
        try (InputStream in = GregScope.class.getResourceAsStream(path)) {
            helper.assertNotNull(in, "missing mod resource " + path);
        } catch (java.io.IOException e) {
            helper.fail("cannot read " + path + ": " + e);
        }
    }
}
