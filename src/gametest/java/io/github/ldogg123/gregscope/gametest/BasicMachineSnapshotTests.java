package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;

/**
 * Drives a real LV Electric Furnace (an {@link MTEBasicMachine}) through basic machine states and checks what
 * GregScope's probe reports. Every inspected snapshot is printed to stdout.
 *
 * <p>
 * The furnace uses GT's {@code FurnaceBackend}, which builds its recipe on the fly from vanilla smelting
 * (cobblestone to stone here), so no recipe has to be injected or cleaned up. EU is written straight into the
 * machine's buffer ({@code increaseStoredEnergyUnits}); there is no cable in the test cell. All ticking uses
 * Horizon-QA's time warp inside one game tick, with the warp range narrowed to the test's own cell.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class BasicMachineSnapshotTests {

    private static final String BATCH = "gregscope.basic";
    private static final TestPos MACHINE = at(1, 0, 1);
    /** Covers the machine at (1,0,1) and stops well short of the next cell (8 blocks away). */
    private static final int WARP_RANGE = 2;
    private static final int START_TIMEOUT_TICKS = 20;

    private BasicMachineSnapshotTests() {}

    // 1
    @GameTest(batch = BATCH)
    public static void poweredIdle(GameTestHelper helper) {
        IGregTechTileEntity holder = placeFurnace(helper);
        fillBuffer(holder);
        warp(helper, 10);

        Map<String, Object> s = Snapshots.probe(helper, MACHINE, "basic#1 powered idle");
        Snapshots.assertStatus(helper, s, "idle", "none");
        helper.assertEquals("singleblock", s.get("kind"), "kind in " + s);
        Snapshots.assertAbsent(helper, s, "euPerTick");
        Snapshots.assertPresent(helper, s, "energyStored", "energyCapacity");
        helper.assertTrue(Snapshots.number(helper, s, "energyStored") > 0, "energyStored in " + s);
        helper.assertTrue(Snapshots.number(helper, s, "energyCapacity") > 0, "energyCapacity in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "active"), "active in " + s);
        helper.succeed();
    }

    // 2
    @GameTest(batch = BATCH)
    public static void runningRecipe(GameTestHelper helper) {
        IGregTechTileEntity holder = placeFurnace(helper);
        fillBuffer(holder);
        MTEBasicMachine machine = basic(helper, holder);
        insertInput(holder, machine, new ItemStack(Blocks.cobblestone, 8));
        startAndActivate(helper, holder, machine);
        warp(helper, 2);

        Map<String, Object> s = Snapshots.probe(helper, MACHINE, "basic#2 running");
        Snapshots.assertStatus(helper, s, "running", "running");
        helper.assertTrue(Snapshots.bool(helper, s, "active"), "active in " + s);
        helper.assertTrue(Snapshots.number(helper, s, "euPerTick") > 0, "euPerTick in " + s);
        long progress = Snapshots.number(helper, s, "progressTicks");
        helper.assertTrue(
            progress > 0 && progress < Snapshots.number(helper, s, "maxProgressTicks"),
            "progressTicks in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "stuttering"), "stuttering in " + s);
        helper.succeed();
    }

    // 3
    @GameTest(batch = BATCH)
    public static void disabledWhileIdle(GameTestHelper helper) {
        IGregTechTileEntity holder = placeFurnace(helper);
        fillBuffer(holder);
        warp(helper, 5);
        holder.disableWorking();
        warp(helper, 5);

        Map<String, Object> s = Snapshots.probe(helper, MACHINE, "basic#3 disabled while idle");
        Snapshots.assertStatus(helper, s, "disabled", "disabled");
        helper.assertFalse(Snapshots.bool(helper, s, "allowedToWork"), "allowedToWork in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "active"), "active in " + s);
        helper.succeed();
    }

    // 4
    @GameTest(batch = BATCH)
    public static void drainedMidRecipeIsPowerStarved(GameTestHelper helper) {
        IGregTechTileEntity holder = placeFurnace(helper);
        fillBuffer(holder);
        MTEBasicMachine machine = basic(helper, holder);
        insertInput(holder, machine, new ItemStack(Blocks.cobblestone, 8));
        startAndActivate(helper, holder, machine);
        warp(helper, 2);
        Snapshots.probe(helper, MACHINE, "basic#4 running before drain");

        // Empty the buffer mid-recipe: GT's next drain fails, it stutters and throws progress back to -100.
        holder.decreaseStoredEnergyUnits(holder.getStoredEU(), true);
        helper.assertEquals(0L, holder.getStoredEU(), "buffer not drained");
        warp(helper, 3);

        Map<String, Object> s = Snapshots.probe(helper, MACHINE, "basic#4 power starved");
        Snapshots.assertStatus(helper, s, "power_starved", "power_starved");
        helper.assertTrue(Snapshots.number(helper, s, "maxProgressTicks") > 0, "maxProgressTicks in " + s);
        helper.assertTrue(
            Snapshots.number(helper, s, "progressTicks") < 0 || Snapshots.bool(helper, s, "stuttering"),
            "progressTicks/stuttering in " + s);
        helper.assertTrue(Snapshots.bool(helper, s, "allowedToWork"), "allowedToWork in " + s);
        Snapshots.assertAbsent(helper, s, "euPerTick");
        helper.succeed();
    }

    // 5
    @GameTest(batch = BATCH)
    public static void foreignItemInOutputSlotIsOutputBlocked(GameTestHelper helper) {
        IGregTechTileEntity holder = placeFurnace(helper);
        fillBuffer(holder);
        MTEBasicMachine machine = basic(helper, holder);
        // Stone cannot stack onto dirt, so GT's canOutput fails and it counts mOutputBlocked up instead of starting.
        holder.setInventorySlotContents(machine.getOutputSlot(), new ItemStack(Blocks.dirt, 1));
        insertInput(holder, machine, new ItemStack(Blocks.cobblestone, 8));
        warp(helper, 10);

        Map<String, Object> s = Snapshots.probe(helper, MACHINE, "basic#5 output slot blocked");
        Snapshots.assertStatus(helper, s, "output_blocked", "item_output_full");
        helper.assertTrue(Snapshots.number(helper, s, "outputBlockedTicks") > 0, "outputBlockedTicks in " + s);
        helper.assertEquals(0L, Snapshots.number(helper, s, "maxProgressTicks"), "maxProgressTicks in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "active"), "active in " + s);
        helper.assertEquals(8L, (long) holder.getStackInSlot(machine.getInputSlot()).stackSize, "input untouched");
        helper.succeed();
    }

    private static IGregTechTileEntity placeFurnace(GameTestHelper helper) {
        helper.gtnh()
            .withWarpRange(WARP_RANGE);
        return GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_E_Furnace.get(1));
    }

    private static MTEBasicMachine basic(GameTestHelper helper, IGregTechTileEntity holder) {
        return helper.assertInstanceOf(
            MTEBasicMachine.class,
            holder.getMetaTileEntity(),
            "LV Electric Furnace is not an MTEBasicMachine");
    }

    private static void fillBuffer(IGregTechTileEntity holder) {
        holder.increaseStoredEnergyUnits(holder.getEUCapacity() - holder.getStoredEU(), true);
    }

    /** Setting the slot through the holder marks the inventory modified, which triggers GT's next recipe check. */
    private static void insertInput(IGregTechTileEntity holder, MTEBasicMachine machine, ItemStack stack) {
        holder.setInventorySlotContents(machine.getInputSlot(), stack);
    }

    private static void startAndActivate(GameTestHelper helper, IGregTechTileEntity holder, MTEBasicMachine machine) {
        for (int i = 0; i < START_TIMEOUT_TICKS && !holder.isActive(); i++) {
            warp(helper, 1);
        }
        helper.assertTrue(
            holder.isActive() && machine.mMaxProgresstime > 0,
            "LV Electric Furnace did not start smelting cobblestone");
    }

    private static void warp(GameTestHelper helper, int ticks) {
        helper.gtnh()
            .fastForwardTicks(ticks);
    }
}
