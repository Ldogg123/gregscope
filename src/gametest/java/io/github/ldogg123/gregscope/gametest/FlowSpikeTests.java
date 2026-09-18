package io.github.ldogg123.gregscope.gametest;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.util.GTUtility;
import io.github.ldogg123.gregscope.flow.FluidMover;
import io.github.ldogg123.gregscope.flow.FluidParcel;

/**
 * GS-202, the v0.3 spike gate. Design-v0.3 section 10 step S1: prove that GregScope can count a fluid transfer
 * without changing what the transfer does.
 *
 * <p>
 * <b>The problem this is gating.</b> GT's {@code GTUtility.moveFluid} returns {@code void}
 * ({@code GTUtility.java:1054-1064}), so a metering pump cannot simply call it and read back the amount. Tank-level
 * deltas are not a substitute: section 10 S3's competing-pipe case moves fluid through the same tank in the same
 * tick, and a delta would credit that to the meter. So {@link FluidMover} re-implements the sequence and counts as
 * it goes - which is only safe if it makes <em>exactly</em> the same calls GT makes.
 *
 * <p>
 * That is what these tests check, and they are the reason the spike exists: they run GT's own method and GregScope's
 * mover against two handlers built identically, and compare the recorded call sequences argument for argument. A
 * difference here means the fallback in section 10's no-go handling, not a patch.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class FlowSpikeTests {

    private static final String BATCH = "gregscope.flow.spike";

    /** Every test here is synchronous: it drives handlers directly and never waits on the world. */
    private static final int SYNCHRONOUS = 20;

    private static final ForgeDirection DRAIN_SIDE = ForgeDirection.NORTH;
    private static final ForgeDirection FILL_SIDE = ForgeDirection.SOUTH;

    private FlowSpikeTests() {}

    /**
     * A tank that records every call it receives, so two runs can be compared exactly. Deliberately simple: one
     * fluid, a capacity, and optional caps that make the real call hand back less than the simulation promised.
     */
    private static final class RecordingTank implements IFluidHandler {

        private final List<String> calls = new ArrayList<>();
        private Fluid fluid;
        private int amount;
        private final int capacity;
        private int realDrainCap = -1;
        private int realFillCap = -1;

        RecordingTank(Fluid fluid, int amount, int capacity) {
            this.fluid = fluid;
            this.amount = amount;
            this.capacity = capacity;
        }

        @Override
        public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
            calls.add("fill(" + from + "," + describe(resource) + "," + doFill + ")");
            if (resource == null || resource.amount <= 0) {
                return 0;
            }
            if (fluid != null && amount > 0 && fluid != resource.getFluid()) {
                return 0;
            }
            int take = Math.min(resource.amount, capacity - amount);
            if (doFill && realFillCap >= 0) {
                take = Math.min(take, realFillCap);
            }
            if (take <= 0) {
                return 0;
            }
            if (doFill) {
                fluid = resource.getFluid();
                amount += take;
            }
            return take;
        }

        @Override
        public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
            calls.add("drainStack(" + from + "," + describe(resource) + "," + doDrain + ")");
            if (resource == null || fluid != resource.getFluid()) {
                return null;
            }
            return drain(from, resource.amount, doDrain);
        }

        @Override
        public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
            calls.add("drain(" + from + "," + maxDrain + "," + doDrain + ")");
            if (fluid == null || amount <= 0 || maxDrain <= 0) {
                return null;
            }
            int take = Math.min(maxDrain, amount);
            if (doDrain && realDrainCap >= 0) {
                take = Math.min(take, realDrainCap);
            }
            if (take <= 0) {
                return null;
            }
            if (doDrain) {
                amount -= take;
            }
            return new FluidStack(fluid, take);
        }

        @Override
        public boolean canFill(ForgeDirection from, Fluid f) {
            return true;
        }

        @Override
        public boolean canDrain(ForgeDirection from, Fluid f) {
            return true;
        }

        @Override
        public FluidTankInfo[] getTankInfo(ForgeDirection from) {
            return new FluidTankInfo[] { new FluidTankInfo(contents(), capacity) };
        }

        private FluidStack contents() {
            return fluid == null || amount <= 0 ? null : new FluidStack(fluid, amount);
        }

        private static String describe(FluidStack stack) {
            return stack == null ? "null"
                : stack.getFluid()
                    .getName() + "x"
                    + stack.amount;
        }

        /** The state a comparison cares about: what is in the tank afterwards. */
        private String state() {
            return (fluid == null ? "empty" : fluid.getName()) + ":" + amount;
        }
    }

    /** Adapts a {@link RecordingTank} to the pure mover's two tiny interfaces. */
    private static FluidMover.Source sourceOf(RecordingTank tank) {
        return (maxAmount, real) -> {
            FluidStack drained = tank.drain(DRAIN_SIDE, maxAmount, real);
            return drained == null ? null : FluidParcel.of(drained.getFluid(), drained.amount);
        };
    }

    private static FluidMover.Sink sinkOf(RecordingTank tank) {
        return (parcel, real) -> tank.fill(FILL_SIDE, stackOf(parcel), real);
    }

    private static FluidStack stackOf(FluidParcel parcel) {
        return parcel == null ? null : new FluidStack((Fluid) parcel.fluid(), parcel.amount());
    }

    /**
     * The gate. For each scenario, GT's own {@code moveFluid} and GregScope's {@link FluidMover} run against a
     * freshly built, identical pair of tanks; the call sequences on both sides and both resulting tank states must
     * match exactly.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void moverMatchesGtCallSequence(GameTestHelper helper) {
        Fluid water = FluidRegistry.WATER;
        Fluid lava = FluidRegistry.LAVA;
        helper.assertNotNull(water, "no water fluid registered");
        helper.assertNotNull(lava, "no lava fluid registered");

        int checked = 0;
        for (Scenario scenario : scenarios(water, lava)) {
            RecordingTank gtSource = scenario.source();
            RecordingTank gtDest = scenario.dest();
            GTUtility.moveFluid(gtSource, gtDest, DRAIN_SIDE, scenario.rate, null);

            RecordingTank mySource = scenario.source();
            RecordingTank myDest = scenario.dest();
            FluidMover.Move move = FluidMover.move(sourceOf(mySource), sinkOf(myDest), scenario.rate, null);

            helper.assertEquals(
                gtSource.calls.toString(),
                mySource.calls.toString(),
                scenario.name + ": the mover called the SOURCE differently from GTUtility.moveFluid");
            helper.assertEquals(
                gtDest.calls.toString(),
                myDest.calls.toString(),
                scenario.name + ": the mover called the DESTINATION differently from GTUtility.moveFluid");
            helper.assertEquals(
                gtSource.state(),
                mySource.state(),
                scenario.name + ": the source ended up in a different state");
            helper.assertEquals(
                gtDest.state(),
                myDest.state(),
                scenario.name + ": the destination ended up in a different state");
            helper.assertTrue(
                move.accepted() <= move.attempted(),
                scenario.name + ": accepted " + move.accepted() + " above attempted " + move.attempted());
            Snapshots.log("flowspike#parity", scenario.name + " -> " + move);
            checked++;
        }
        helper.assertTrue(checked >= 10, "only " + checked + " scenarios ran; the table did not build");
        helper.succeed();
    }

    /** One comparison case. The two suppliers must build identical tanks, because both runs need a fresh pair. */
    private static final class Scenario {

        private final String name;
        private final int rate;
        private final Fluid sourceFluid;
        private final int sourceAmount;
        private final Fluid destFluid;
        private final int destAmount;
        private final int destCapacity;
        private final int realDrainCap;
        private final int realFillCap;

        Scenario(String name, int rate, Fluid sourceFluid, int sourceAmount, Fluid destFluid, int destAmount,
            int destCapacity, int realDrainCap, int realFillCap) {
            this.name = name;
            this.rate = rate;
            this.sourceFluid = sourceFluid;
            this.sourceAmount = sourceAmount;
            this.destFluid = destFluid;
            this.destAmount = destAmount;
            this.destCapacity = destCapacity;
            this.realDrainCap = realDrainCap;
            this.realFillCap = realFillCap;
        }

        RecordingTank source() {
            RecordingTank tank = new RecordingTank(sourceFluid, sourceAmount, 64_000);
            tank.realDrainCap = realDrainCap;
            return tank;
        }

        RecordingTank dest() {
            RecordingTank tank = new RecordingTank(destFluid, destAmount, destCapacity);
            tank.realFillCap = realFillCap;
            return tank;
        }
    }

    private static List<Scenario> scenarios(Fluid water, Fluid lava) {
        List<Scenario> all = new ArrayList<>();
        all.add(new Scenario("plain move", 1000, water, 8000, null, 0, 64_000, -1, -1));
        all.add(new Scenario("LV rate", 32, water, 8000, null, 0, 64_000, -1, -1));
        all.add(new Scenario("IV rate", 8192, water, 64_000, null, 0, 64_000, -1, -1));
        all.add(new Scenario("empty source", 1000, water, 0, null, 0, 64_000, -1, -1));
        all.add(new Scenario("full destination", 1000, water, 8000, water, 1000, 1000, -1, -1));
        all.add(new Scenario("destination at cap-50", 160, water, 8000, water, 950, 1000, -1, -1));
        all.add(new Scenario("fluid mismatch", 1000, water, 8000, lava, 500, 64_000, -1, -1));
        all.add(new Scenario("short real drain", 1000, water, 8000, null, 0, 64_000, 30, -1));
        all.add(new Scenario("real fill below simulation", 1000, water, 8000, null, 0, 64_000, -1, 20));
        // The real drain hands back NOTHING after promising some: GT still calls fill(side, null, true), and a
        // mover that "defensively" skips that call is no longer faithful. Without this row the parity test
        // cannot see that difference at all - the S6 control proved exactly that.
        all.add(new Scenario("real drain returns null", 1000, water, 8000, null, 0, 64_000, 0, -1));
        return all;
    }

    /**
     * Design-v0.3 section 10 S1's stated go criterion, kept as its own test because it is the number the design
     * commits to: a destination 50 short of full, offered 160, reports attempted 160 and accepted 50.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void destinationNearlyFullGivesAttemptedAboveAccepted(GameTestHelper helper) {
        Fluid water = FluidRegistry.WATER;
        RecordingTank source = new RecordingTank(water, 8000, 64_000);
        RecordingTank dest = new RecordingTank(water, 950, 1000);

        FluidMover.Move move = FluidMover.move(sourceOf(source), sinkOf(dest), 160, null);

        helper.assertEquals(
            160,
            move.attempted(),
            "attempted must be the offer, which is what backpressure is read from");
        helper.assertEquals(50, move.accepted(), "accepted must be what the destination really took");
        helper.assertEquals(0, move.voided(), "nothing should be lost in a plain backpressure case");
        helper.assertEquals(1000, dest.amount, "the destination should be full");
        helper.assertEquals(7950, source.amount, "the source should have lost exactly what was accepted");
        helper.succeed();
    }

    /**
     * The loss case. A destination whose real fill takes less than it simulated leaves fluid drained and unplaced;
     * GT does not put it back, so GregScope must count it as lost rather than quietly under-report.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void fluidTheDestinationDropsIsCountedAsVoided(GameTestHelper helper) {
        Fluid water = FluidRegistry.WATER;
        RecordingTank source = new RecordingTank(water, 8000, 64_000);
        RecordingTank dest = new RecordingTank(null, 0, 64_000);
        dest.realFillCap = 20;

        RecordingTank gtSource = new RecordingTank(water, 8000, 64_000);
        RecordingTank gtDest = new RecordingTank(null, 0, 64_000);
        gtDest.realFillCap = 20;
        GTUtility.moveFluid(gtSource, gtDest, DRAIN_SIDE, 1000, null);

        FluidMover.Move move = FluidMover.move(sourceOf(source), sinkOf(dest), 1000, null);

        helper.assertEquals(1000, move.attempted());
        helper.assertEquals(20, move.accepted());
        helper.assertEquals(980, move.voided(), "drained 1000, placed 20, and GT puts nothing back");
        helper.assertEquals(
            gtSource.state(),
            source.state(),
            "GT loses the same fluid: if these differ, the mover is not faithful");
        helper.assertEquals(gtDest.state(), dest.state(), "and the destination must match too");
        helper.succeed();
    }
}
