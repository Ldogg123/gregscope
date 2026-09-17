package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;
import static gregtech.api.util.GTRecipeConstants.COIL_HEAT;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.api.gt.Multiblock;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.TierEU;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * Drives GT's own formed Electric Blast Furnace template (the one GT's ElectricBlastFurnaceFormationTests use) through
 * real machine states and checks what GregScope's probe reports. Every inspected snapshot is printed to stdout.
 *
 * <p>
 * Tests in one batch run side by side in cells 8 blocks apart, and Horizon-QA's time warp ticks every GT tile within
 * its warp range (default 32). Each test narrows the range to its own 3x4x3 cell so fast-forwarding one EBF never
 * advances a neighbour's.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class ElectricBlastFurnaceSnapshotTests {

    static final String TEMPLATE = "gregtech:multiblock/electric_blast_furnace/valid";
    private static final String BATCH = "gregscope.ebf";

    private static final TestPos CONTROLLER = at(1, 0, 0);
    private static final TestPos ONE_COIL = at(0, 1, 0);

    /** Cell-local warp range: covers the 3x4x3 template and stops short of the next cell (8 blocks away). */
    private static final int WARP_RANGE = 4;
    private static final int LOW_HEAT = 1_200;
    /** Long enough to observe mid-recipe; EV recipe on the template's EV hatch leaves no room for overclocks. */
    private static final int LONG_RECIPE_TICKS = 400;
    private static final int START_TIMEOUT_TICKS = 40;

    private ElectricBlastFurnaceSnapshotTests() {}

    // 1
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void freshTemplateIsStarting(GameTestHelper helper) {
        // No assertFormed()/fast-forward: those would end GT's 100-tick startup countdown.
        Map<String, Object> s = Snapshots.probe(helper, CONTROLLER, "ebf#1 fresh template");
        Snapshots.assertStatus(helper, s, "starting", "startup_check");
        helper.assertEquals("multiblock", s.get("kind"), "kind in " + s);
        // mMachine is not persisted, so the controller reads unformed until its first structure check. (Observed: GT's
        // template also carries a saved no_repair shutdown, allowedToWork=false and 6 maintenance issues; R1 still
        // wins, and those fields are reported as-is alongside it.)
        helper.assertFalse(Snapshots.bool(helper, s, "formed"), "formed in " + s);
        helper.succeed();
    }

    // 2
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void idleAfterStartup(GameTestHelper helper) {
        Multiblock ebf = ebf(helper);
        ebf.fixMaintenance();
        // Let GT's own startup countdown (mStartUpCheck = 100) run out and form the machine naturally.
        helper.gtnh()
            .fastForwardTicks(120);

        Map<String, Object> s = Snapshots.probe(helper, CONTROLLER, "ebf#2 idle after startup");
        Snapshots.assertStatus(helper, s, "idle", String.valueOf(s.get("recipeCheckResultId")));
        helper.assertTrue(Snapshots.bool(helper, s, "formed"), "formed in " + s);
        helper.assertTrue(
            Arrays.asList("none", "no_recipe")
                .contains(s.get("recipeCheckResultId")),
            "recipeCheckResultId in " + s);
        helper.assertTrue(
            Snapshots.warnings(helper, s)
                .isEmpty(),
            "warnings in " + s);
        Snapshots.assertPresent(helper, s, "energyStored", "energyCapacity");
        helper.assertTrue(Snapshots.number(helper, s, "energyCapacity") > 0, "energyCapacity in " + s);
        Snapshots.assertAbsent(helper, s, "euPerTick");
        helper.succeed();
    }

    // 3
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void runningRecipe(GameTestHelper helper) {
        Multiblock ebf = formedEbf(helper);
        startLongRecipe(helper, ebf, Blocks.dirt, Blocks.cobblestone, LONG_RECIPE_TICKS + 200);
        helper.gtnh()
            .fastForwardTicks(20);

        Map<String, Object> s = Snapshots.probe(helper, CONTROLLER, "ebf#3 running");
        assertRunning(helper, s);
        helper.assertTrue(
            Snapshots.warnings(helper, s)
                .isEmpty(),
            "warnings in " + s);
        helper.succeed();
    }

    // 4
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void disabledWhileIdle(GameTestHelper helper) {
        formedEbf(helper);
        helper.gtnh()
            .fastForwardTicks(10);
        Snapshots.probe(helper, CONTROLLER, "ebf#4 idle before disable");

        controller(helper).disableWorking();
        helper.gtnh()
            .fastForwardTicks(10);

        Map<String, Object> s = Snapshots.probe(helper, CONTROLLER, "ebf#4 disabled while idle");
        Snapshots.assertStatus(helper, s, "disabled", "disabled");
        helper.assertFalse(Snapshots.bool(helper, s, "allowedToWork"), "allowedToWork in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "active"), "active in " + s);
        helper.succeed();
    }

    // 5
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void disabledWhileRunningKeepsRunning(GameTestHelper helper) {
        Multiblock ebf = formedEbf(helper);
        startLongRecipe(helper, ebf, Blocks.brick_block, Blocks.hardened_clay, LONG_RECIPE_TICKS + 200);

        // GT finishes the current recipe after a soft disable; it just won't start another.
        controller(helper).disableWorking();
        helper.gtnh()
            .fastForwardTicks(10);

        Map<String, Object> s = Snapshots.probe(helper, CONTROLLER, "ebf#5 disabled while running");
        assertRunning(helper, s);
        helper.assertFalse(Snapshots.bool(helper, s, "allowedToWork"), "allowedToWork in " + s);
        List<?> warnings = Snapshots.warnings(helper, s);
        helper.assertTrue(warnings.contains("work_disabled"), "warnings in " + s);
        helper.succeed();
    }

    // 6
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void maintenanceWarningWhileRunning(GameTestHelper helper) {
        Multiblock ebf = formedEbf(helper);
        // One missing tool; the template's maintenance hatch holds no tools, so GT cannot repair it on its own.
        controller(helper).mCrowbar = false;
        startLongRecipe(helper, ebf, Blocks.sand, Blocks.glass, LONG_RECIPE_TICKS + 200);
        helper.gtnh()
            .fastForwardTicks(10);

        Map<String, Object> s = Snapshots.probe(helper, CONTROLLER, "ebf#6 running with one maintenance issue");
        assertRunning(helper, s);
        helper.assertTrue(
            Snapshots.warnings(helper, s)
                .contains("maintenance"),
            "warnings in " + s);
        helper.assertEquals(1L, Snapshots.number(helper, s, "maintenanceIssues"), "maintenanceIssues in " + s);
        // GT drains -lEUt * 10000 / mEfficiency, and one issue caps mEfficiency at 9000: euPerTick reports the real
        // drain (observed 2133 for a 1920 EU/t recipe), not the recipe EU/t.
        helper.assertTrue(Snapshots.number(helper, s, "euPerTick") > TierEU.RECIPE_EV, "euPerTick in " + s);
        helper.succeed();
    }

    // 7
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void noMaintenanceShutsDown(GameTestHelper helper) {
        // GT's template is saved already shut down with no_repair, so start from a clean, enabled machine first
        // (fixMaintenance -> enableWorking clears the saved shutdown) and make GT produce the shutdown itself.
        Multiblock ebf = formedEbf(helper);
        // Same as GT's breakMaintenanceIssues: all six tools missing.
        MTEMultiBlockBase multi = controller(helper);
        multi.mWrench = false;
        multi.mScrewdriver = false;
        multi.mSoftMallet = false;
        multi.mHardHammer = false;
        multi.mSolderingTool = false;
        multi.mCrowbar = false;
        Map<String, Object> before = Snapshots.probe(helper, CONTROLLER, "ebf#7 before ticking without maintenance");
        helper.assertFalse(Snapshots.bool(helper, before, "wasShutdown"), "wasShutdown before in " + before);
        helper.assertTrue(Snapshots.bool(helper, before, "allowedToWork"), "allowedToWork before in " + before);
        addRecipe(helper, ebf, Blocks.ice, Blocks.clay, LOW_HEAT, 20, TierEU.RECIPE_MV);
        ebf.inputBus(0)
            .insert(new ItemStack(Blocks.ice, 1));
        ebf.energyHatch(0)
            .supply(TierEU.EV, 1, 100);
        helper.gtnh()
            .fastForwardTicks(100);

        Map<String, Object> s = Snapshots.probe(helper, CONTROLLER, "ebf#7 no maintenance");
        Snapshots.assertStatus(helper, s, "shutdown", "no_repair");
        helper.assertEquals("no_repair", s.get("shutdownReasonId"), "shutdownReasonId in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "shutdownCritical"), "shutdownCritical in " + s);
        helper.assertEquals(6L, Snapshots.number(helper, s, "maintenanceIssues"), "maintenanceIssues in " + s);
        helper.assertTrue(
            Snapshots.warnings(helper, s)
                .contains("maintenance"),
            "warnings in " + s);
        helper.succeed();
    }

    // 8
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void brokenStructureIsUnformed(GameTestHelper helper) {
        formedEbf(helper);
        helper.startSequence()
            .thenExecute(() -> {
                helper.gtnh()
                    .fastForwardTicks(5);
                helper.destroyBlock(ONE_COIL.x(), ONE_COIL.y(), ONE_COIL.z());
            })
            // No forced structure check: GT has to notice the broken coil on its own. Casing breakBlock only queues
            // a machine update (RunnableMachineUpdate), which GT flushes at the end of the server tick; a time warp
            // in the same tick would never see it. Let one real tick pass, then GT's 50-tick mUpdate countdown.
            .thenIdle(1)
            .thenExecute(() -> {
                helper.gtnh()
                    .fastForwardTicks(100);
                Map<String, Object> s = Snapshots.probe(helper, CONTROLLER, "ebf#8 coil destroyed");
                Snapshots.assertStatus(helper, s, "unformed", "structure_incomplete");
                helper.assertFalse(Snapshots.bool(helper, s, "formed"), "formed in " + s);
            })
            .thenSucceed();
    }

    // 9
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void fullOutputBusIsOutputBlocked(GameTestHelper helper) {
        Multiblock ebf = formedEbf(helper);
        addRecipe(helper, ebf, Blocks.soul_sand, Blocks.obsidian, LOW_HEAT, 20, TierEU.RECIPE_MV);
        ebf.inputBus(0)
            .insert(new ItemStack(Blocks.soul_sand, 1));
        ebf.outputBus(0)
            .fillAllSlots(new ItemStack(Blocks.stone, 64));
        ebf.energyHatch(0)
            .supply(TierEU.EV, 1, 150);
        helper.gtnh()
            .fastForwardTicks(100);

        Map<String, Object> s = Snapshots.probe(helper, CONTROLLER, "ebf#9 output bus full");
        Snapshots.assertStatus(helper, s, "output_blocked", "item_output_full");
        helper.assertEquals("item_output_full", s.get("recipeCheckResultId"), "recipeCheckResultId in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "active"), "active in " + s);
        ebf.inputBus(0)
            .assertContains(new ItemStack(Blocks.soul_sand, 1));
        helper.succeed();
    }

    // 10
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void powerLossMidRecipeShutsDown(GameTestHelper helper) {
        Multiblock ebf = formedEbf(helper);
        // EU for 20 ticks only; the EV hatch buffer (16,896 EU) covers roughly ten more ticks of a 400-tick recipe.
        startLongRecipe(helper, ebf, Blocks.netherrack, Blocks.nether_brick, 20);
        helper.gtnh()
            .fastForwardTicks(100);

        Map<String, Object> s = Snapshots.probe(helper, CONTROLLER, "ebf#10 power loss");
        Snapshots.assertStatus(helper, s, "shutdown", "power_loss");
        helper.assertEquals("power_loss", s.get("shutdownReasonId"), "shutdownReasonId in " + s);
        helper.assertTrue(Snapshots.bool(helper, s, "shutdownCritical"), "shutdownCritical in " + s);
        helper.assertFalse(Snapshots.bool(helper, s, "active"), "active in " + s);
        helper.succeed();
    }

    private static Multiblock ebf(GameTestHelper helper) {
        return helper.gtnh()
            .withWarpRange(WARP_RANGE)
            .multiblock(CONTROLLER);
    }

    /** GT's formedEbf: fix maintenance (also enables working), then assert formed (ends the startup countdown). */
    private static Multiblock formedEbf(GameTestHelper helper) {
        Multiblock ebf = ebf(helper);
        ebf.fixMaintenance();
        ebf.assertFormed();
        return ebf;
    }

    private static MTEMultiBlockBase controller(GameTestHelper helper) {
        return helper.gtnh()
            .multiBlockController(CONTROLLER);
    }

    /**
     * Injects a {@link #LONG_RECIPE_TICKS} EV recipe, feeds its input, supplies EV EU for {@code euTicks} ticks and
     * warps tick by tick until the controller reports processing.
     */
    private static void startLongRecipe(GameTestHelper helper, Multiblock ebf, Block input, Block output, int euTicks) {
        addRecipe(helper, ebf, input, output, LOW_HEAT, LONG_RECIPE_TICKS, TierEU.RECIPE_EV);
        ebf.inputBus(0)
            .insert(new ItemStack(input, 1));
        ebf.energyHatch(0)
            .supply(TierEU.EV, 1, euTicks);
        for (int i = 0; i < START_TIMEOUT_TICKS && !ebf.isProcessing(); i++) {
            helper.gtnh()
                .fastForwardTicks(1);
        }
        helper.assertTrue(ebf.isProcessing(), "EBF did not start the synthetic recipe");
    }

    private static void addRecipe(GameTestHelper helper, Multiblock ebf, Block input, Block output, int heat,
        int duration, long eut) {
        helper.gtnh()
            .withTestRecipe(
                ebf,
                GTValues.RA.stdBuilder()
                    .itemInputs(new ItemStack(input, 1))
                    .itemOutputs(new ItemStack(output, 1))
                    .duration(duration)
                    .eut(eut)
                    .metadata(COIL_HEAT, heat));
    }

    private static void assertRunning(GameTestHelper helper, Map<String, Object> s) {
        Snapshots.assertStatus(helper, s, "running", "running");
        helper.assertTrue(Snapshots.bool(helper, s, "active"), "active in " + s);
        double progress = Snapshots.decimal(helper, s, "progress");
        helper.assertTrue(progress > 0.0 && progress < 1.0, "progress in " + s);
        helper.assertTrue(Snapshots.number(helper, s, "progressTicks") > 0, "progressTicks in " + s);
        helper.assertTrue(Snapshots.number(helper, s, "euPerTick") > 0, "euPerTick in " + s);
    }
}
