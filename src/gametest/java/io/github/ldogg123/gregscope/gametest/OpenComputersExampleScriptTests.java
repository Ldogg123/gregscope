package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;
import static gregtech.api.util.GTRecipeConstants.COIL_HEAT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.gtnewhorizons.horizonqa.api.GameTestArguments;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.api.annotation.MethodSource;
import com.gtnewhorizons.horizonqa.api.gt.Multiblock;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.TierEU;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.server.machine.luac.NativeLua52Architecture;
import li.cil.oc.server.machine.luac.NativeLua53Architecture;
import li.cil.oc.server.machine.luac.NativeLua54Architecture;
import li.cil.oc.server.machine.luaj.LuaJLuaArchitecture;

/**
 * Runs the documented example script {@code docs/examples/gregscope-snapshot.lua} on a real OpenComputers computer
 * booting the stock OpenOS floppy, headless, and checks its printed output against GregScope's probe.
 *
 * <p>
 * {@link OpenOsComputer} builds and drives the computer: Gradle copies the repo file byte for byte into the game test
 * resources, Java writes it onto the computer's hard drive through OC's filesystem callbacks, starts the computer and
 * reads the captured output back. What is left here is the scenario and the expected output; GS-116 moved the rest out
 * so {@code HubExampleScriptTests} could run {@code docs/examples/gregscope-hub.lua} the same way.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope" })
public class OpenComputersExampleScriptTests {

    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos ADAPTER = at(2, 0, 1);
    private static final TestPos CASE = at(3, 0, 1);

    /**
     * GT's EBF template placed with rotation 2: the controller moves from local (1,0,0) to (1,0,2) and faces +z, so the
     * Adapter and the computer fit in the cell padding in front of it (every other controller neighbour is a hatch,
     * casing or coil).
     */
    private static final int EBF_ROTATION = 2;
    private static final TestPos EBF_CONTROLLER = at(1, 0, 2);
    private static final TestPos EBF_ADAPTER = at(1, 0, 3);
    private static final TestPos EBF_CASE = at(1, 0, 4);
    private static final int EBF_WARP_RANGE = 4;
    /** Long enough that the recipe can never finish during the test (GT would then stop: work is disabled). */
    private static final int EBF_RECIPE_TICKS = 1_000_000;
    /**
     * Progress fraction the recipe is advanced to. {@code %.3f} prints {@code 0.375} for every fraction in
     * [0.3745, 0.3755), so the printed progress stays exact for 900 real ticks after this point.
     */
    private static final double EBF_PROGRESS_FRACTION = 0.3746;
    private static final double EBF_PROGRESS_BUCKET_END = 0.3755;

    private static final String SCRIPT = "gregscope-snapshot.lua";
    /** The callback the runner waits to see on a component before it starts the script. */
    private static final String AWAIT = "getSnapshot";

    private static final String INDENT = "         ";

    /**
     * Every CPU architecture shipped in OpenComputers 1.12.61-GTNH, with the {@code _VERSION} OpenOS' machine.lua
     * reports for it. The CPU item's NBT selects the architecture (as the in-game CPU mode switch does), so Lua 5.4 and
     * LuaJ run even though the default config registers only Lua 5.3 and Lua 5.2 for the switch.
     */
    public static Object[] exampleScriptRunsOnOpenOs() {
        return new Object[] { GameTestArguments.named("lua53", NativeLua53Architecture.class.getName(), "Lua 5.3"),
            GameTestArguments.named("lua52", NativeLua52Architecture.class.getName(), "Lua 5.2"),
            GameTestArguments.named("lua54", NativeLua54Architecture.class.getName(), "Lua 5.4"),
            GameTestArguments.named("luaj", LuaJLuaArchitecture.class.getName(), "Luaj") };
    }

    @GameTest(batch = "gregscope.oc.openos", timeoutTicks = OpenOsComputer.BATCH_TIMEOUT_TICKS)
    @MethodSource
    public static void exampleScriptRunsOnOpenOs(GameTestHelper helper, String architectureClass,
        String expectedVersion) {
        Class<? extends Architecture> architecture = OpenOsComputer.architectureClass(helper, architectureClass);
        OpenOsComputer.assumeArchitectureLoadable(helper, architecture);
        String label = "oc#5[" + architecture.getSimpleName() + "]";

        // GT machine first, so the Adapter wraps it as soon as it joins the network.
        GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_Macerator.get(1));
        OpenOsComputer.run(
            helper,
            label,
            ADAPTER,
            CASE,
            architecture,
            expectedVersion,
            SCRIPT,
            AWAIT,
            run -> run.componentAddress.substring(0, 8),
            () -> {},
            run -> {
                Map<String, Object> probe = Snapshots.probe(helper, MACHINE, label + " probe for the OpenOS machine");
                List<String> expected = expectedSummary(run.componentAddress, probe);
                helper.assertEquals(String.join("\n", expected) + "\n", run.output, "example script summary output");

                expected.add("Full snapshot of " + run.componentAddress + ":");
                expected.addAll(expectedDump(probe));
                helper.assertEquals(String.join("\n", expected) + "\n", run.dump, "example script dump output");
            });
    }

    /**
     * A running EBF with maintenance and work-disabled warnings, non-zero {@code euPerTick} and fractional progress,
     * read by the example script on a Lua 5.3 CPU while GT keeps ticking in real time.
     *
     * <p>
     * Everything the summary prints is held constant for the whole run: one missing maintenance tool (efficiency 9000
     * from the recipe start, so {@code euPerTick} is fixed), a soft disable (GT keeps running the current recipe but
     * starts no other), a recipe far longer than the test, progress parked inside one {@code %.3f} bucket, and an
     * energy hatch refilled every tick (Horizon-QA's {@code supply()} only feeds time warps). A probe taken just before
     * the computer starts and one after the script finished must agree on every summary line; the script output is
     * then compared exactly. The full dump also holds values that change every tick; those lines are range-checked
     * between the two probes instead (see {@link #assertDumpMatches}).
     */
    @GameTest(
        template = ElectricBlastFurnaceSnapshotTests.TEMPLATE,
        rotation = EBF_ROTATION,
        batch = "gregscope.oc.openos.ebf",
        timeoutTicks = OpenOsComputer.BATCH_TIMEOUT_TICKS)
    public static void exampleScriptShowsRunningEbfWithWarnings(GameTestHelper helper) {
        String label = "oc#7 running EBF";
        // The expected output relies on Lua 5.3 (integer formatting, _VERSION), so skip like the lua53 matrix case
        // where OpenComputers cannot load the native library, instead of failing with a LuaJ version mismatch.
        OpenOsComputer.assumeArchitectureLoadable(helper, NativeLua53Architecture.class);
        Multiblock ebf = helper.gtnh()
            .withWarpRange(EBF_WARP_RANGE)
            .multiblock(EBF_CONTROLLER);
        ebf.fixMaintenance();
        ebf.assertFormed();
        MTEMultiBlockBase controller = helper.gtnh()
            .multiBlockController(EBF_CONTROLLER);
        // One missing tool: a maintenance warning, efficiency capped at 9000, and GT keeps running.
        controller.mCrowbar = false;

        helper.gtnh()
            .withTestRecipe(
                ebf,
                GTValues.RA.stdBuilder()
                    .itemInputs(new ItemStack(Blocks.gravel, 1))
                    .itemOutputs(new ItemStack(Blocks.sandstone, 1))
                    .duration(EBF_RECIPE_TICKS)
                    .eut(TierEU.RECIPE_EV)
                    .metadata(COIL_HEAT, 1_200));
        ebf.inputBus(0)
            .insert(new ItemStack(Blocks.gravel, 1));
        helper.assertTrue(controller.mEnergyHatches.size() == 1, "EBF template energy hatches");
        IGregTechTileEntity hatch = controller.mEnergyHatches.get(0)
            .getBaseMetaTileEntity();
        Snapshots.log(label + " energy hatch before warp", hatch.getStoredEU() + "/" + hatch.getEUCapacity());
        // Observed: the template's energy hatch starts empty, and a 1 A EV job (2048 EU/t) cannot cover the 2133 EU/t
        // this recipe drains with one maintenance issue: GT stopped with power_loss during the warp. 2 A covers it.
        ebf.energyHatch(0)
            .supply(TierEU.EV, 2, 40);
        for (int i = 0; i < 40 && !ebf.isProcessing(); i++) {
            helper.gtnh()
                .fastForwardTicks(1);
        }
        helper.assertTrue(ebf.isProcessing(), "EBF did not start the synthetic recipe");
        helper.assertEquals(
            (long) EBF_RECIPE_TICKS,
            (long) controller.mMaxProgresstime,
            "recipe duration (no overclock expected)");

        // Soft disable: GT finishes the current recipe, which now never ends within the test.
        controller.disableWorking();
        // Stands in for fast-forwarding ~374,600 ticks of the same recipe, which is far too slow to warp.
        controller.mProgresstime = (int) Math.round(EBF_PROGRESS_FRACTION * controller.mMaxProgresstime);
        // GT rolls random maintenance damage once mRuntime passes 1000 running ticks; the test runs far fewer.
        controller.mRuntime = 0;
        helper.gtnh()
            .fastForwardTicks(2);

        // Horizon-QA's supply() only feeds time warps; in real time GT would drain the buffer in ~8 ticks.
        Runnable topUp = () -> {
            long missing = hatch.getEUCapacity() - hatch.getStoredEU();
            if (missing > 0) {
                hatch.increaseStoredEnergyUnits(missing, true);
            }
        };
        topUp.run();
        helper.onEachTick("ebf energy hatch top-up", topUp);
        helper.assertTrue(
            ebf.isProcessing() && controller.getBaseMetaTileEntity()
                .isActive(),
            "EBF stopped during setup: " + Snapshots.probe(helper, EBF_CONTROLLER, label + " after setup"));
        long maxStableProgressTicks = (long) Math.ceil(EBF_PROGRESS_BUCKET_END * controller.mMaxProgresstime) - 1;

        Probes probes = new Probes();
        OpenOsComputer.run(
            helper,
            label,
            EBF_ADAPTER,
            EBF_CASE,
            NativeLua53Architecture.class,
            "Lua 5.3",
            SCRIPT,
            AWAIT,
            run -> run.componentAddress.substring(0, 8),
            () -> probes.pre = Snapshots.probe(helper, EBF_CONTROLLER, label + " probe before computer start"),
            run -> {
                Map<String, Object> pre = probes.pre;
                Map<String, Object> post = Snapshots.probe(helper, EBF_CONTROLLER, label + " probe after the script");
                helper.assertTrue(
                    Snapshots.number(helper, post, "progressTicks") <= maxStableProgressTicks,
                    "scenario ran too long: progress left the printed 0.375 bucket in " + post);
                List<String> expected = expectedSummary(run.componentAddress, post);
                helper.assertEquals(
                    expectedSummary(run.componentAddress, pre),
                    expected,
                    "scenario not stable: summary lines differ between the probes before and after the script");

                // The values the scenario exists to cover.
                Snapshots.assertStatus(helper, post, "running", "running");
                helper.assertEquals(
                    Arrays.asList("maintenance", "work_disabled"),
                    Snapshots.warnings(helper, post),
                    "warnings in " + post);
                // GT drains -mEUt * 10000 / mEfficiency: 1920 * 10000 / 9000.
                helper.assertEquals(2_133L, Snapshots.number(helper, post, "euPerTick"), "euPerTick in " + post);
                helper.assertEquals(
                    INDENT + "state=running statusId=running progress=0.375 euPerTick=2133",
                    expected.get(1),
                    "expected summary line");
                helper.assertEquals(INDENT + "warnings: maintenance, work_disabled", expected.get(3), "warnings line");

                helper.assertEquals(String.join("\n", expected) + "\n", run.output, "example script summary output");

                String dumpHeader = String.join("\n", expected) + "\nFull snapshot of " + run.componentAddress + ":\n";
                helper.assertTrue(
                    run.dump.startsWith(dumpHeader),
                    "example script dump header: expected\n" + dumpHeader + "but got\n" + run.dump);
                assertDumpMatches(
                    helper,
                    pre,
                    post,
                    Arrays.asList(
                        run.dump.substring(dumpHeader.length())
                            .split("\n", -1)));
            });
    }

    /**
     * Compares the dump lines of the running-EBF scenario against the post-script probe. The script's getSnapshot runs
     * at a different server tick than either Java probe, so three keys are range-checked; every other line must match
     * exactly:
     * <ul>
     * <li>{@code controllerAgeTicks} and {@code progressTicks} grow by one every tick: between the pre and post probe.
     * <li>{@code energyStored}: the hatch is refilled at the end of every tick and GT drains {@code euPerTick} during
     * it,
     * so it reads the capacity or up to two ticks' drain below it depending on tile update order.
     * </ul>
     */
    private static void assertDumpMatches(GameTestHelper helper, Map<String, Object> pre, Map<String, Object> post,
        List<String> actualWithTrailer) {
        List<String> expected = expectedDump(post);
        helper.assertEquals(
            "",
            actualWithTrailer.get(actualWithTrailer.size() - 1),
            "dump output must end with a newline");
        List<String> actual = actualWithTrailer.subList(0, actualWithTrailer.size() - 1);
        helper.assertEquals((long) expected.size(), (long) actual.size(), "dump line count: " + actual);
        long capacity = Snapshots.number(helper, post, "energyCapacity");
        long drain = Snapshots.number(helper, post, "euPerTick");
        for (int i = 0; i < expected.size(); i++) {
            String want = expected.get(i);
            String got = actual.get(i);
            String key = dumpKey(want);
            helper.assertEquals(key, dumpKey(got), "dump key at line " + i + ": " + got);
            switch (key) {
                case "controllerAgeTicks":
                case "progressTicks":
                    assertDumpLong(
                        helper,
                        got,
                        Snapshots.number(helper, pre, key),
                        Snapshots.number(helper, post, key));
                    break;
                case "energyStored":
                    assertDumpLong(helper, got, capacity - 2 * drain, capacity);
                    break;
                default:
                    helper.assertEquals(want, got, "dump line " + key);
            }
        }
    }

    /** Key of a dump line printed as {@code "  %-26s %s"}. */
    private static String dumpKey(String line) {
        String trimmed = line.substring(2);
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private static void assertDumpLong(GameTestHelper helper, String line, long min, long max) {
        String text = line.length() > 29 ? line.substring(29) : "";
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            helper.fail("dump line is not an integer: '" + line + "'");
            return;
        }
        helper.assertTrue(value >= min && value <= max, "dump line '" + line + "' not in [" + min + ", " + max + "]");
    }

    /** The summary lines gregscope-snapshot.lua prints for one machine, formatted as its fmt() does. */
    private static List<String> expectedSummary(String address, Map<String, Object> s) {
        List<String> lines = new ArrayList<>();
        lines.add(
            String.format(Locale.ROOT, "%s %-11s %s", address.substring(0, 8), fmt(s.get("kind")), fmt(s.get("name"))));
        lines.add(
            String.format(
                Locale.ROOT,
                INDENT + "state=%s statusId=%s progress=%s euPerTick=%s",
                fmt(s.get("state")),
                fmt(s.get("statusId")),
                fmt(s.get("progress")),
                fmt(s.get("euPerTick"))));
        lines.add(INDENT + fmt(s.get("statusText")));
        Object warnings = s.get("warnings");
        if (warnings instanceof List && !((List<?>) warnings).isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Object warning : (List<?>) warnings) {
                parts.add(String.valueOf(warning));
            }
            lines.add(INDENT + "warnings: " + String.join(", ", parts));
        }
        return lines;
    }

    /**
     * The example's dump(): keys sorted (byte order), scalar values through fmt(), lists joined raw in brackets exactly
     * as {@code table.concat} does (fmt() is not applied to list elements).
     */
    private static List<String> expectedDump(Map<String, Object> s) {
        List<String> keys = new ArrayList<>(s.keySet());
        Collections.sort(keys);
        List<String> lines = new ArrayList<>();
        for (String key : keys) {
            Object value = s.get(key);
            String text;
            if (value instanceof List) {
                List<String> parts = new ArrayList<>();
                for (Object element : (List<?>) value) {
                    parts.add(concatElement(key, element));
                }
                text = "[" + String.join(", ", parts) + "]";
            } else {
                text = fmt(value);
            }
            lines.add(String.format(Locale.ROOT, "  %-26s %s", key, text));
        }
        return lines;
    }

    /**
     * What Lua's {@code table.concat} prints for one list element. Only strings and Java integer types are mirrored:
     * table.concat raises on booleans/nil, and float formatting differs between OC architectures (Lua 5.3 prints
     * {@code 1.0}, LuaJ prints {@code 1}), so any other element type fails loudly instead of guessing.
     */
    private static String concatElement(String key, Object element) {
        if (element instanceof String || element instanceof Integer
            || element instanceof Long
            || element instanceof Short
            || element instanceof Byte) {
            return String.valueOf(element);
        }
        throw new IllegalStateException(
            "list '" + key
                + "' holds a "
                + (element == null ? "null"
                    : element.getClass()
                        .getName())
                + " element; extend concatElement() to mirror table.concat for it");
    }

    /**
     * Java mirror of the example's fmt(): integers exact, integral doubles as %.0f, other doubles as %.3f. On Lua 5.2
     * and
     * LuaJ every number is a double, so Java integer types take the %.0f path there; both print the same digits for
     * magnitudes below 2^53, which every value in these scenarios is.
     */
    private static String fmt(Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.floor(d) && Math.abs(d) < 9007199254740992.0) {
                return String.format(Locale.ROOT, "%.0f", d);
            }
            return String.format(Locale.ROOT, "%.3f", d);
        }
        return String.valueOf(value);
    }

    private static final class Probes {

        Map<String, Object> pre;
    }

    private OpenComputersExampleScriptTests() {}
}
