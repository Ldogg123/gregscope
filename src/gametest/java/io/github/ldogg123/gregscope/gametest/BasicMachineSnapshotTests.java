package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.gtnewhorizons.horizonqa.api.GameTestArguments;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.api.annotation.MethodSource;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;

/**
 * Drives real LV, MV and HV Electric Furnaces (each an {@link MTEBasicMachine}) through basic machine states and
 * checks what GregScope's probe reports. Every test runs once per tier; every inspected snapshot is printed to stdout.
 *
 * <p>
 * The furnace uses GT's {@code FurnaceBackend}, which builds its recipe on the fly from vanilla smelting
 * (cobblestone to stone here, 4 EU/t for 128 ticks), so no recipe has to be injected or cleaned up. EU is written
 * straight into the machine's buffer ({@code increaseStoredEnergyUnits}); there is no cable in the test cell. All
 * ticking uses Horizon-QA's time warp inside one game tick, with the warp range narrowed to the test's own cell.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class BasicMachineSnapshotTests {

    private static final String BATCH = "gregscope.basic";
    private static final TestPos MACHINE = at(1, 0, 1);
    /** Covers the machine at (1,0,1) and stops well short of the next cell (8 blocks away). */
    private static final int WARP_RANGE = 2;
    private static final int START_TIMEOUT_TICKS = 20;

    private BasicMachineSnapshotTests() {}

    /**
     * One Electric Furnace tier and what GT gives it for the 4 EU/t, 128-tick smelting recipe.
     *
     * <p>
     * Capacity is {@code MTEBasicMachine.maxEUStore() = V[tier] * 64}. GT's {@code OverclockCalculator} treats the ULV
     * recipe as LV and overclocks once per voltage tier above LV (EU/t x4, duration /2): LV none, MV one, HV two.
     * These formula values match what the server reported (2026-09-17): 2048 / 8192 / 32768 EU capacity, 4 / 16 / 64
     * EU/t and 128 / 64 / 32 ticks.
     */
    public static final class Tier {

        final String label;
        final ItemList item;
        final String metaName;
        final long energyCapacity;
        final long euPerTick;
        final long durationTicks;

        Tier(String label, ItemList item, String metaName, long energyCapacity, long euPerTick, long durationTicks) {
            this.label = label;
            this.item = item;
            this.metaName = metaName;
            this.energyCapacity = energyCapacity;
            this.euPerTick = euPerTick;
            this.durationTicks = durationTicks;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final Tier LV = new Tier(
        "LV",
        ItemList.Machine_LV_E_Furnace,
        "basicmachine.e_furnace.tier.01",
        32L * 64,
        4,
        128);
    private static final Tier MV = new Tier(
        "MV",
        ItemList.Machine_MV_E_Furnace,
        "basicmachine.e_furnace.tier.02",
        128L * 64,
        16,
        64);
    private static final Tier HV = new Tier(
        "HV",
        ItemList.Machine_HV_E_Furnace,
        "basicmachine.e_furnace.tier.03",
        512L * 64,
        64,
        32);

    private static Object[] tiers() {
        return new Object[] { GameTestArguments.named("lv", LV), GameTestArguments.named("mv", MV),
            GameTestArguments.named("hv", HV) };
    }

    public static Object[] poweredIdle() {
        return tiers();
    }

    public static Object[] runningRecipe() {
        return tiers();
    }

    public static Object[] disabledWhileIdle() {
        return tiers();
    }

    public static Object[] drainedMidRecipeIsPowerStarved() {
        return tiers();
    }

    public static Object[] foreignItemInOutputSlotIsOutputBlocked() {
        return tiers();
    }

    // 1
    @GameTest(batch = BATCH)
    @MethodSource
    public static void poweredIdle(GameTestHelper helper, Tier tier) {
        IGregTechTileEntity holder = placeFurnace(helper, tier);
        fillBuffer(holder);
        warp(helper, 10);

        Map<String, Object> s = Snapshots.probe(helper, MACHINE, "basic#1[" + tier + "] powered idle");
        Snapshots.assertStatus(helper, s, "idle", "none");
        helper.assertEquals("singleblock", s.get("kind"), "kind in " + s);
        helper.assertEquals(tier.metaName, s.get("metaName"), "metaName in " + s);
        Snapshots.assertAbsent(helper, s, "euPerTick");
        Snapshots.assertPresent(helper, s, "energyStored", "energyCapacity");
        helper
            .assertEquals(tier.energyCapacity, Snapshots.number(helper, s, "energyCapacity"), "energyCapacity in " + s);
        // Nothing to do, so the full buffer is untouched.
        helper.assertEquals(tier.energyCapacity, Snapshots.number(helper, s, "energyStored"), "energyStored in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "active"), "active in " + s);
        helper.succeed();
    }

    // 2
    @GameTest(batch = BATCH)
    @MethodSource
    public static void runningRecipe(GameTestHelper helper, Tier tier) {
        IGregTechTileEntity holder = placeFurnace(helper, tier);
        fillBuffer(holder);
        MTEBasicMachine machine = basic(helper, holder, tier);
        insertInput(holder, machine, new ItemStack(Blocks.cobblestone, 8));
        startAndActivate(helper, holder, machine, tier);
        warp(helper, 2);

        Map<String, Object> s = Snapshots.probe(helper, MACHINE, "basic#2[" + tier + "] running");
        Snapshots.assertStatus(helper, s, "running", "running");
        helper.assertTrue(Snapshots.bool(helper, s, "active"), "active in " + s);
        helper.assertEquals(tier.euPerTick, Snapshots.number(helper, s, "euPerTick"), "euPerTick in " + s);
        helper.assertEquals(
            tier.durationTicks,
            Snapshots.number(helper, s, "maxProgressTicks"),
            "maxProgressTicks in " + s);
        long progress = Snapshots.number(helper, s, "progressTicks");
        helper.assertTrue(
            progress > 0 && progress < Snapshots.number(helper, s, "maxProgressTicks"),
            "progressTicks in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "stuttering"), "stuttering in " + s);
        helper.succeed();
    }

    // 3
    @GameTest(batch = BATCH)
    @MethodSource
    public static void disabledWhileIdle(GameTestHelper helper, Tier tier) {
        IGregTechTileEntity holder = placeFurnace(helper, tier);
        fillBuffer(holder);
        warp(helper, 5);
        holder.disableWorking();
        warp(helper, 5);

        Map<String, Object> s = Snapshots.probe(helper, MACHINE, "basic#3[" + tier + "] disabled while idle");
        Snapshots.assertStatus(helper, s, "disabled", "disabled");
        helper.assertFalse(Snapshots.bool(helper, s, "allowedToWork"), "allowedToWork in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "active"), "active in " + s);
        helper.succeed();
    }

    // 4
    @GameTest(batch = BATCH)
    @MethodSource
    public static void drainedMidRecipeIsPowerStarved(GameTestHelper helper, Tier tier) {
        IGregTechTileEntity holder = placeFurnace(helper, tier);
        fillBuffer(holder);
        MTEBasicMachine machine = basic(helper, holder, tier);
        insertInput(holder, machine, new ItemStack(Blocks.cobblestone, 8));
        startAndActivate(helper, holder, machine, tier);
        warp(helper, 2);
        Snapshots.probe(helper, MACHINE, "basic#4[" + tier + "] running before drain");

        // Empty the buffer mid-recipe: GT's next drain fails, it stutters and throws progress back to -100.
        holder.decreaseStoredEnergyUnits(holder.getStoredEU(), true);
        helper.assertEquals(0L, holder.getStoredEU(), "buffer not drained");
        warp(helper, 3);

        Map<String, Object> s = Snapshots.probe(helper, MACHINE, "basic#4[" + tier + "] power starved");
        Snapshots.assertStatus(helper, s, "power_starved", "power_starved");
        helper.assertTrue(Snapshots.number(helper, s, "maxProgressTicks") > 0, "maxProgressTicks in " + s);
        // Observed on all three tiers: GT keeps the recipe (and its overclocked duration) while it waits for EU.
        helper.assertEquals(
            tier.durationTicks,
            Snapshots.number(helper, s, "maxProgressTicks"),
            "maxProgressTicks in " + s);
        helper.assertTrue(
            Snapshots.number(helper, s, "progressTicks") < 0 || Snapshots.bool(helper, s, "stuttering"),
            "progressTicks/stuttering in " + s);
        helper.assertTrue(Snapshots.bool(helper, s, "allowedToWork"), "allowedToWork in " + s);
        Snapshots.assertAbsent(helper, s, "euPerTick");
        helper.succeed();
    }

    // 5
    @GameTest(batch = BATCH)
    @MethodSource
    public static void foreignItemInOutputSlotIsOutputBlocked(GameTestHelper helper, Tier tier) {
        IGregTechTileEntity holder = placeFurnace(helper, tier);
        fillBuffer(holder);
        MTEBasicMachine machine = basic(helper, holder, tier);
        // Stone cannot stack onto dirt, so GT's canOutput fails and it counts mOutputBlocked up instead of starting.
        holder.setInventorySlotContents(machine.getOutputSlot(), new ItemStack(Blocks.dirt, 1));
        insertInput(holder, machine, new ItemStack(Blocks.cobblestone, 8));
        warp(helper, 10);

        Map<String, Object> s = Snapshots.probe(helper, MACHINE, "basic#5[" + tier + "] output slot blocked");
        Snapshots.assertStatus(helper, s, "output_blocked", "item_output_full");
        helper.assertTrue(Snapshots.number(helper, s, "outputBlockedTicks") > 0, "outputBlockedTicks in " + s);
        helper.assertEquals(0L, Snapshots.number(helper, s, "maxProgressTicks"), "maxProgressTicks in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "active"), "active in " + s);
        helper.assertEquals(8L, (long) holder.getStackInSlot(machine.getInputSlot()).stackSize, "input untouched");
        helper.succeed();
    }

    private static IGregTechTileEntity placeFurnace(GameTestHelper helper, Tier tier) {
        helper.gtnh()
            .withWarpRange(WARP_RANGE);
        return GtPlacement.placeMachine(helper, MACHINE, tier.item.get(1));
    }

    private static MTEBasicMachine basic(GameTestHelper helper, IGregTechTileEntity holder, Tier tier) {
        return helper.assertInstanceOf(
            MTEBasicMachine.class,
            holder.getMetaTileEntity(),
            tier + " Electric Furnace is not an MTEBasicMachine");
    }

    private static void fillBuffer(IGregTechTileEntity holder) {
        holder.increaseStoredEnergyUnits(holder.getEUCapacity() - holder.getStoredEU(), true);
    }

    /** Setting the slot through the holder marks the inventory modified, which triggers GT's next recipe check. */
    private static void insertInput(IGregTechTileEntity holder, MTEBasicMachine machine, ItemStack stack) {
        holder.setInventorySlotContents(machine.getInputSlot(), stack);
    }

    private static void startAndActivate(GameTestHelper helper, IGregTechTileEntity holder, MTEBasicMachine machine,
        Tier tier) {
        for (int i = 0; i < START_TIMEOUT_TICKS && !holder.isActive(); i++) {
            warp(helper, 1);
        }
        helper.assertTrue(
            holder.isActive() && machine.mMaxProgresstime > 0,
            tier + " Electric Furnace did not start smelting cobblestone");
    }

    private static void warp(GameTestHelper helper, int ticks) {
        helper.gtnh()
            .fastForwardTicks(ticks);
    }
}
