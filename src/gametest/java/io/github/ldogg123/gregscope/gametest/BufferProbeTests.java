package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.api.gt.Multiblock;

import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import io.github.ldogg123.gregscope.buffers.BufferCollector;
import io.github.ldogg123.gregscope.buffers.BufferSet;
import io.github.ldogg123.gregscope.probe.BufferProbe;

/**
 * GS-302: the buffer walk reads a real machine, and - the point of this class - it does so <b>without writing to
 * it</b>.
 *
 * <p>
 * The read-only claim is not taken on faith from reading GT's source. {@link #theWalkDoesNotWriteToTheMachine}
 * records each input hatch's {@code mRecipeMap}, runs GregScope's walk, asserts nothing moved, and then calls GT's
 * own {@code getStoredFluids()} on the same machine and asserts that it <em>does</em> change the field. That second
 * half is what makes the first half worth anything: it proves the hazard is real in this GT build and that the
 * assertion can detect it, rather than passing because nothing was ever going to change.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class BufferProbeTests {

    private static final String BATCH = "gregscope.buffers";

    /** Forming and filling an EBF takes real ticks; the walk itself is synchronous. */
    private static final int TIMEOUT = 200;

    /** GT's own formed EBF template, the one ElectricBlastFurnaceSnapshotTests drives. */
    private static final String TEMPLATE = ElectricBlastFurnaceSnapshotTests.TEMPLATE;

    private static final TestPos CONTROLLER = at(1, 0, 0);

    /** Cell-local warp range, as the EBF tests use. */
    private static final int WARP_RANGE = 4;

    private BufferProbeTests() {}

    /** What an EBF is holding shows up in the walk, feed-agnostically. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void theWalkReadsWhatTheMachineHolds(GameTestHelper helper) {
        Multiblock ebf = formedEbf(helper);
        ebf.inputHatch(0)
            .fill(new FluidStack(FluidRegistry.WATER, 4000));
        ebf.inputBus(0)
            .insert(new ItemStack(Blocks.cobblestone, 17));

        BufferSet inputs = BufferProbe.readMultiInputs(controller(helper), new BufferCollector());

        helper.assertFalse(inputs.isEmpty(), "the EBF holds things, so the walk must report something");
        helper
            .assertTrue(keys(inputs).contains("f:water"), "the water in the input hatch was not read: " + keys(inputs));
        helper.assertTrue(
            keys(inputs).contains("i:minecraft:cobblestone:0"),
            "the cobblestone in the input bus was not read: " + keys(inputs));
        helper.assertEquals(4000L + 17L, inputs.totalAmount(), "amounts must be exact");
        helper.assertTrue(
            inputs.totalCapacity() >= 4000L,
            "the hatch reports a capacity, so saturation must be measurable: " + inputs);
        helper.assertTrue(!Double.isNaN(inputs.saturation()), "a machine with a real tank must have a real saturation");
        Snapshots.log("buffers#read", inputs.toString());
        helper.succeed();
    }

    /**
     * The guarantee this whole approach rests on, with its own control.
     *
     * <p>
     * GregScope walks the hatch lists rather than calling {@code getStoredFluids()} precisely because that method
     * assigns {@code hatch.mRecipeMap} through {@code setHatchRecipeMap}. If GregScope's walk ever acquires the
     * same habit - or if a future GT makes one of the getters it uses write something - this fails.
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void theWalkDoesNotWriteToTheMachine(GameTestHelper helper) {
        Multiblock ebf = formedEbf(helper);
        ebf.inputHatch(0)
            .fill(new FluidStack(FluidRegistry.WATER, 1000));
        MTEMultiBlockBase controller = controller(helper);

        // Clear the field first, so "unchanged" is a meaningful statement rather than "it was already set".
        for (MTEHatchInput hatch : controller.mInputHatches) {
            if (hatch != null) {
                hatch.mRecipeMap = null;
            }
        }

        BufferProbe.readMultiInputs(controller, new BufferCollector());
        BufferProbe.readMultiOutputs(controller, new BufferCollector());

        List<String> written = new ArrayList<>();
        for (int i = 0; i < controller.mInputHatches.size(); i++) {
            MTEHatchInput hatch = controller.mInputHatches.get(i);
            if (hatch != null && hatch.mRecipeMap != null) {
                written.add("hatch " + i + " -> " + hatch.mRecipeMap.unlocalizedName);
            }
        }
        helper.assertTrue(
            written.isEmpty(),
            "GregScope's buffer walk WROTE to the machine, which breaks the read-only guarantee: " + written);

        // The control: GT's own getStoredFluids does write, so the assertion above can actually catch a write.
        controller.getStoredFluids();
        int set = 0;
        for (MTEHatchInput hatch : controller.mInputHatches) {
            if (hatch != null && hatch.mRecipeMap != null) {
                set++;
            }
        }
        helper.assertTrue(
            set > 0,
            "getStoredFluids() did not set mRecipeMap on this GT build, so the check above proves nothing - "
                + "re-read MTEMultiBlockBase.setHatchRecipeMap and fix this test before trusting it");
        Snapshots.log("buffers#readonly", "walk wrote nothing; getStoredFluids set mRecipeMap on " + set + " hatches");
        helper.succeed();
    }

    /** An empty machine still reports its capacity, because "0 of 16,000" is the useful statement. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void anEmptyMachineReportsCapacityNotNothing(GameTestHelper helper) {
        formedEbf(helper);
        BufferSet inputs = BufferProbe.readMultiInputs(controller(helper), new BufferCollector());

        helper.assertEquals(0L, inputs.totalAmount(), "nothing was put in");
        helper.assertTrue(inputs.totalCapacity() > 0L, "but the hatches have room, and that is the point");
        helper.assertFalse(inputs.isEmpty(), "an empty tank with room is a reportable state, not an absent one");
        helper.assertEquals(0.0D, inputs.saturation(), "empty with room is 0%, never NaN");
        helper.succeed();
    }

    /** The collector is reused across machines in the sampler, so a stale carry-over would misattribute fluid. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = TIMEOUT)
    public static void areusedCollectorDoesNotCarryOneMachineIntoTheNext(GameTestHelper helper) {
        Multiblock ebf = formedEbf(helper);
        ebf.inputHatch(0)
            .fill(new FluidStack(FluidRegistry.WATER, 3000));
        MTEMultiBlockBase controller = controller(helper);

        BufferCollector shared = new BufferCollector();
        BufferSet first = BufferProbe.readMultiInputs(controller, shared);
        helper.assertEquals(3000L, first.totalAmount(), "the first read sees the water");

        BufferSet outputs = BufferProbe.readMultiOutputs(controller, shared);
        helper.assertEquals(
            0L,
            outputs.totalAmount(),
            "the output read reused the collector and carried the input's water into it: " + outputs);
        helper.succeed();
    }

    // --- helpers ---

    private static List<String> keys(BufferSet set) {
        List<String> keys = new ArrayList<>();
        set.top()
            .forEach(reading -> keys.add(reading.key()));
        return keys;
    }

    private static MTEMultiBlockBase controller(GameTestHelper helper) {
        return helper.gtnh()
            .multiBlockController(CONTROLLER);
    }

    private static Multiblock formedEbf(GameTestHelper helper) {
        Multiblock ebf = helper.gtnh()
            .withWarpRange(WARP_RANGE)
            .multiblock(CONTROLLER);
        ebf.fixMaintenance();
        ebf.assertFormed();
        return ebf;
    }
}
