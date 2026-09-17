package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;
import static gregtech.api.util.GTRecipeConstants.COIL_HEAT;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.api.gt.Multiblock;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.TierEU;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.probe.GregTechMachineProbe;
import tectech.thing.CustomItemList;
import tectech.thing.casing.TTCasingsContainer;
import tectech.thing.metaTileEntity.multi.MTEActiveTransformer;

/**
 * GS-102 probe cost benchmark (design-v0.2 §6.3 gate, §14 GS-102). Opt-in: every test skips unless the server JVM has
 * {@code -Dgregscope.bench=true}, so normal CI runs report these tests as skipped, not failed.
 *
 * <p>
 * Each scenario builds a real machine in a real state, then calls {@code PROBE.snapshot(te)} 1,000 times to warm up
 * and 10,000 timed times ({@link System#nanoTime()} around each call), all inside one server tick so the machine does
 * not change between calls. It logs p50/p99/max in µs (nearest-rank percentiles) with the prefix
 * {@code [GregScope bench]}. Only completion is asserted (plus the machine state the scenario is named after, so the
 * numbers describe what they claim to); the numbers are recorded in {@code docs/testing.md}.
 *
 * <p>
 * Run with:
 * {@code ./gradlew --no-daemon runServer --mcJvmArgs=-Dhorizonqa.mode=ci --mcJvmArgs=-Dhorizonqa.tests=gregscope:ProbeBenchmarkTests --mcJvmArgs=-Dgregscope.bench=true "--mcJvmArgs=-Dhorizonqa.reportDir=$(pwd -W)/build/horizonqa"}
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class ProbeBenchmarkTests {

    static final String BENCH_PROPERTY = "gregscope.bench";
    static final int WARMUP_CALLS = 1_000;
    static final int TIMED_CALLS = 10_000;

    private static final String BATCH = "gregscope.bench";
    /** Same probe class the OC driver and (from GS-108) the sampler use; stateless. */
    private static final GregTechMachineProbe PROBE = new GregTechMachineProbe();

    private static final TestPos FURNACE = at(1, 0, 1);
    private static final TestPos EBF_CONTROLLER = at(1, 0, 0);
    /** Empty 5x3x5 cell: without a template a cell is one block high and the cube would leak out of it. */
    private static final String TRANSFORMER_TEMPLATE = "gregscope:empty_5x3x5";
    /** Active Transformer controller in the middle of the cell, so the 3x3x3 cube fits whichever way GT faces it. */
    private static final TestPos TRANSFORMER = at(2, 1, 2);

    private static final int FURNACE_WARP_RANGE = 2;
    private static final int MULTI_WARP_RANGE = 4;
    private static final int START_TIMEOUT_TICKS = 40;

    private ProbeBenchmarkTests() {}

    // 1
    @GameTest(batch = BATCH)
    public static void lvElectricFurnaceIdle(GameTestHelper helper) {
        requireBenchEnabled(helper);
        IGregTechTileEntity holder = placeFurnace(helper);
        holder.increaseStoredEnergyUnits(holder.getEUCapacity() - holder.getStoredEU(), true);
        warp(helper, 10);

        Map<String, Object> s = Snapshots.probe(helper, FURNACE, "bench lv furnace idle");
        Snapshots.assertStatus(helper, s, "idle", "none");
        benchmark(helper, "lvElectricFurnaceIdle", tile(helper, FURNACE));
        helper.succeed();
    }

    // 2
    @GameTest(batch = BATCH)
    public static void lvElectricFurnaceRunning(GameTestHelper helper) {
        requireBenchEnabled(helper);
        IGregTechTileEntity holder = placeFurnace(helper);
        holder.increaseStoredEnergyUnits(holder.getEUCapacity() - holder.getStoredEU(), true);
        MTEBasicMachine machine = helper
            .assertInstanceOf(MTEBasicMachine.class, holder.getMetaTileEntity(), "LV Electric Furnace MTE");
        holder.setInventorySlotContents(machine.getInputSlot(), new ItemStack(Blocks.cobblestone, 8));
        for (int i = 0; i < START_TIMEOUT_TICKS && !holder.isActive(); i++) {
            warp(helper, 1);
        }
        warp(helper, 2);

        Map<String, Object> s = Snapshots.probe(helper, FURNACE, "bench lv furnace running");
        Snapshots.assertStatus(helper, s, "running", "running");
        benchmark(helper, "lvElectricFurnaceRunning", tile(helper, FURNACE));
        helper.succeed();
    }

    // 3
    @GameTest(template = ElectricBlastFurnaceSnapshotTests.TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void formedRunningEbf(GameTestHelper helper) {
        requireBenchEnabled(helper);
        Multiblock ebf = helper.gtnh()
            .withWarpRange(MULTI_WARP_RANGE)
            .multiblock(EBF_CONTROLLER);
        ebf.fixMaintenance();
        ebf.assertFormed();
        helper.gtnh()
            .withTestRecipe(
                ebf,
                GTValues.RA.stdBuilder()
                    .itemInputs(new ItemStack(Blocks.dirt, 1))
                    .itemOutputs(new ItemStack(Blocks.cobblestone, 1))
                    .duration(400)
                    .eut(TierEU.RECIPE_EV)
                    .metadata(COIL_HEAT, 1_200));
        ebf.inputBus(0)
            .insert(new ItemStack(Blocks.dirt, 1));
        ebf.energyHatch(0)
            .supply(TierEU.EV, 1, 600);
        for (int i = 0; i < START_TIMEOUT_TICKS && !ebf.isProcessing(); i++) {
            warp(helper, 1);
        }
        warp(helper, 20);

        Map<String, Object> s = Snapshots.probe(helper, EBF_CONTROLLER, "bench ebf formed running");
        Snapshots.assertStatus(helper, s, "running", "running");
        benchmark(helper, "formedRunningEbf", tile(helper, EBF_CONTROLLER));
        helper.succeed();
    }

    // 4
    @GameTest(template = TRANSFORMER_TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void formedTecTechActiveTransformer(GameTestHelper helper) {
        requireBenchEnabled(helper);
        helper.gtnh()
            .withWarpRange(MULTI_WARP_RANGE);
        buildActiveTransformer(helper);
        Multiblock transformer = helper.gtnh()
            .multiblock(TRANSFORMER);
        // Tools and enableWorking only; the structure is left to TecTech's own startup check (see Multiblock
        // .assertFormed's note on TecTech controllers).
        transformer.fixMaintenance();
        warp(helper, 120);

        MTEMultiBlockBase controller = helper.gtnh()
            .multiBlockController(TRANSFORMER);
        helper.assertInstanceOf(MTEActiveTransformer.class, controller, "controller class");
        helper.assertTrue(controller.mMachine, "Active Transformer did not form");
        // TecTech's onFirstTick_EM disables a controller that is not formed on its first tick, which is always the case
        // here (the startup check runs later). Switch it back on, as a player would. Observed afterwards (2026-09-17):
        // state waiting, recipeCheckResultId no_routing (no EU is supplied), so the probe reads the recipe-check text
        // too.
        controller.getBaseMetaTileEntity()
            .enableWorking();
        warp(helper, 40);
        Map<String, Object> s = Snapshots.probe(helper, TRANSFORMER, "bench tectech active transformer formed");
        helper.assertTrue(Snapshots.bool(helper, s, "formed"), "formed in " + s);
        helper.assertTrue(Snapshots.bool(helper, s, "allowedToWork"), "allowedToWork in " + s);
        benchmark(helper, "formedTecTechActiveTransformer", tile(helper, TRANSFORMER));
        helper.succeed();
    }

    private static void requireBenchEnabled(GameTestHelper helper) {
        helper.assumeTrue(
            Boolean.getBoolean(BENCH_PROPERTY),
            "probe benchmark is opt-in: run the server with -D" + BENCH_PROPERTY + "=true");
    }

    /** 1,000 warm-up calls, then 10,000 timed calls; logs and returns {p50, p99, max} in nanoseconds. */
    static long[] benchmark(GameTestHelper helper, String scenario, TileEntity tile) {
        MachineSnapshot last = null;
        for (int i = 0; i < WARMUP_CALLS; i++) {
            last = PROBE.snapshot(tile);
        }
        helper.assertNotNull(last, scenario + ": probe returned null during warm-up");
        long[] nanos = new long[TIMED_CALLS];
        int nulls = 0;
        for (int i = 0; i < TIMED_CALLS; i++) {
            long start = System.nanoTime();
            last = PROBE.snapshot(tile);
            nanos[i] = System.nanoTime() - start;
            if (last == null) {
                nulls++;
            }
        }
        helper.assertEquals(0, nulls, scenario + ": probe returned null during timed calls");
        Arrays.sort(nanos);
        long p50 = percentile(nanos, 50);
        long p99 = percentile(nanos, 99);
        long max = nanos[nanos.length - 1];
        System.out.println(
            String.format(
                Locale.ROOT,
                "[GregScope bench] %s: calls=%d warmup=%d p50=%.1f us p99=%.1f us max=%.1f us (java %s, %s)",
                scenario,
                TIMED_CALLS,
                WARMUP_CALLS,
                p50 / 1000.0,
                p99 / 1000.0,
                max / 1000.0,
                System.getProperty("java.version"),
                System.getProperty("java.vm.name")));
        return new long[] { p50, p99, max };
    }

    /** Nearest-rank percentile of an ascending array: the value at rank ceil(p/100 * n). */
    static long percentile(long[] sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    private static IGregTechTileEntity placeFurnace(GameTestHelper helper) {
        helper.gtnh()
            .withWarpRange(FURNACE_WARP_RANGE);
        return GtPlacement.placeMachine(helper, FURNACE, ItemList.Machine_LV_E_Furnace.get(1));
    }

    /**
     * TecTech Active Transformer (shape "main": a 3x3x3 cube, controller in the middle of the front face, a
     * superconducting coil block in the centre, TecTech high-power casings elsewhere, at least one energy hatch). The
     * controller is placed like a player would, then the cube is built behind whatever horizontal facing it got; the
     * EV energy hatch replaces the casing directly above the controller.
     */
    private static void buildActiveTransformer(GameTestHelper helper) {
        IGregTechTileEntity controller = GtPlacement
            .placeMachine(helper, TRANSFORMER, CustomItemList.Machine_Multi_Transformer.get(1));
        ForgeDirection front = controller.getFrontFacing();
        if (front.offsetY != 0) {
            controller.setFrontFacing(ForgeDirection.NORTH);
            front = controller.getFrontFacing();
        }
        helper.assertTrue(front.offsetY == 0, "controller facing is not horizontal: " + front);
        ForgeDirection back = front.getOpposite();
        ForgeDirection side = back.getRotation(ForgeDirection.UP);
        TestPos c = helper.absolute(TRANSFORMER);
        World world = helper.getWorld();
        TestPos hatch = null;
        for (int depth = 0; depth <= 2; depth++) {
            for (int up = -1; up <= 1; up++) {
                for (int lateral = -1; lateral <= 1; lateral++) {
                    int x = c.x() + back.offsetX * depth + side.offsetX * lateral;
                    int y = c.y() + up;
                    int z = c.z() + back.offsetZ * depth + side.offsetZ * lateral;
                    if (depth == 0 && up == 0 && lateral == 0) {
                        continue;
                    }
                    if (depth == 1 && up == 0 && lateral == 0) {
                        world.setBlock(x, y, z, GregTechAPI.sBlockCasings1, 15, 3);
                    } else if (depth == 0 && up == 1 && lateral == 0) {
                        hatch = at(
                            TRANSFORMER.x() + (x - c.x()),
                            TRANSFORMER.y() + (y - c.y()),
                            TRANSFORMER.z() + (z - c.z()));
                    } else {
                        world.setBlock(x, y, z, TTCasingsContainer.sBlockCasingsTT, 0, 3);
                    }
                }
            }
        }
        helper.assertNotNull(hatch, "no energy hatch position");
        GtPlacement.placeMachine(helper, hatch, ItemList.Hatch_Energy_EV.get(1));
    }

    private static TileEntity tile(GameTestHelper helper, TestPos local) {
        TestPos abs = helper.absolute(local);
        return helper.getWorld()
            .getTileEntity(abs.x(), abs.y(), abs.z());
    }

    private static void warp(GameTestHelper helper, int ticks) {
        helper.gtnh()
            .fastForwardTicks(ticks);
    }
}
