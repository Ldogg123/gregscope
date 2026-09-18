package io.github.ldogg123.gregscope.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The counting rules of design-v0.3 section 3, over fake handlers. The separate question of whether this mover makes
 * the <em>same calls</em> as GT's own {@code moveFluid} is not answerable here, because that method cannot be loaded
 * without Minecraft: the in-game {@code FlowSpikeTests.moverMatchesGtCallSequence} runs both against identical
 * recording handlers and compares.
 */
class FluidMoverTest {

    private static final Object WATER = "water";
    private static final Object LAVA = "lava";

    /** A tank that holds one fluid and records every call, so the order and arguments can be asserted. */
    private static final class Tank implements FluidMover.Source, FluidMover.Sink {

        private final List<String> calls = new ArrayList<>();
        private Object fluid;
        private int amount;
        private int capacity = Integer.MAX_VALUE;
        /** When set, the real drain hands back less than asked for - a tank someone else drew from in between. */
        private int realDrainCap = -1;
        /** When set, the real fill accepts less than it simulated - the case that voids fluid. */
        private int realFillCap = -1;

        Tank(Object fluid, int amount) {
            this.fluid = fluid;
            this.amount = amount;
        }

        @Override
        public FluidParcel drain(int maxAmount, boolean real) {
            calls.add("drain(" + maxAmount + "," + real + ")");
            if (fluid == null || amount <= 0) {
                return null;
            }
            int take = Math.min(maxAmount, amount);
            if (real && realDrainCap >= 0) {
                take = Math.min(take, realDrainCap);
            }
            if (take <= 0) {
                return null;
            }
            if (real) {
                amount -= take;
            }
            return FluidParcel.of(fluid, take);
        }

        @Override
        public int fill(FluidParcel parcel, boolean real) {
            calls.add("fill(" + (parcel == null ? "null" : parcel.amount()) + "," + real + ")");
            if (parcel == null) {
                return 0;
            }
            if (fluid != null && fluid != parcel.fluid()) {
                return 0;
            }
            int room = capacity - amount;
            int take = Math.min(parcel.amount(), room);
            if (real && realFillCap >= 0) {
                take = Math.min(take, realFillCap);
            }
            if (take <= 0) {
                return 0;
            }
            if (real) {
                fluid = parcel.fluid();
                amount += take;
            }
            return take;
        }
    }

    @Test
    void aPlainMoveCountsWhatTheDestinationTook() {
        Tank source = new Tank(WATER, 1000);
        Tank dest = new Tank(null, 0);
        FluidMover.Move move = FluidMover.move(source, dest, 100, null);

        assertEquals(100, move.attempted(), "attempted is the simulated drain, capped at the rate");
        assertEquals(100, move.accepted(), "accepted is what the real fill reported");
        assertEquals(0, move.voided());
        assertEquals(FluidMover.Outcome.MOVED, move.outcome());
        assertEquals(900, source.amount, "the source really lost it");
        assertEquals(100, dest.amount, "the destination really gained it");
    }

    @Test
    void neverMoreThanTheRate() {
        Tank source = new Tank(WATER, 1_000_000);
        Tank dest = new Tank(null, 0);
        FluidMover.Move move = FluidMover.move(source, dest, 64, null);

        assertEquals(64, move.attempted(), "the rate caps the attempt");
        assertEquals(64, move.accepted());
        assertEquals(64, dest.amount);
    }

    @Test
    void anEmptySourceIsIdleAndNotBlocked() {
        Tank source = new Tank(WATER, 0);
        Tank dest = new Tank(null, 0);
        FluidMover.Move move = FluidMover.move(source, dest, 100, null);

        assertEquals(FluidMover.Outcome.IDLE, move.outcome(), "nothing to give is idle, not backpressure");
        assertEquals(0, move.attempted(), "an idle op attempted nothing");
        assertEquals(0, move.accepted());
    }

    @Test
    void aFullDestinationIsBlockedAndStillCountsTheAttempt() {
        Tank source = new Tank(WATER, 1000);
        Tank dest = new Tank(WATER, 100);
        dest.capacity = 100;
        FluidMover.Move move = FluidMover.move(source, dest, 100, null);

        assertEquals(FluidMover.Outcome.BLOCKED, move.outcome());
        assertEquals(100, move.attempted(), "the source still offered it, which is what backpressure means");
        assertEquals(0, move.accepted());
        assertEquals(1000, source.amount, "a blocked op must not drain the source");
    }

    @Test
    void aPartlyFullDestinationGivesAttemptedAboveAccepted() {
        Tank source = new Tank(WATER, 1000);
        Tank dest = new Tank(WATER, 50);
        dest.capacity = 100;
        FluidMover.Move move = FluidMover.move(source, dest, 160, null);

        assertEquals(160, move.attempted(), "design-v0.3 section 10 S1's go criterion: attempted is the offer");
        assertEquals(50, move.accepted(), "only the room that was left");
        assertEquals(0, move.voided());
        assertEquals(FluidMover.Outcome.MOVED, move.outcome());
    }

    @Test
    void aFluidMismatchIsBlockedWithoutDraining() {
        Tank source = new Tank(WATER, 1000);
        Tank dest = new Tank(LAVA, 10);
        FluidMover.Move move = FluidMover.move(source, dest, 100, null);

        assertEquals(FluidMover.Outcome.BLOCKED, move.outcome());
        assertEquals(0, move.accepted());
        assertEquals(1000, source.amount, "the source must be untouched when the fluids do not match");
    }

    @Test
    void aFilterThatRefusesBlocksAndDrainsNothing() {
        Tank source = new Tank(WATER, 1000);
        Tank dest = new Tank(null, 0);
        FluidMover.Move move = FluidMover.move(source, dest, 100, parcel -> false);

        assertEquals(FluidMover.Outcome.BLOCKED, move.outcome());
        assertEquals(100, move.attempted());
        assertEquals(0, move.accepted());
        assertEquals(1000, source.amount, "a refused move must not drain");
        assertEquals("[drain(100,false)]", source.calls + "", "the source is only ever simulated");
        assertEquals("[fill(100,false)]", dest.calls + "", "and the destination too: no real call may run");
    }

    /** GT tests its filter against the amount the simulated fill wrote back, not the amount offered. */
    @Test
    void theFilterSeesWhatWouldMoveNotWhatWasOffered() {
        Tank source = new Tank(WATER, 1000);
        Tank dest = new Tank(WATER, 50);
        dest.capacity = 100;
        int[] seen = new int[1];
        FluidMover.move(source, dest, 160, parcel -> {
            seen[0] = parcel.amount();
            return true;
        });
        assertEquals(50, seen[0], "the filter must see the fillable amount, the way GT's does");
    }

    /** The real drain can hand back less than the simulation promised; nothing is voided, because nothing was lost. */
    @Test
    void aShortRealDrainIsNotVoided() {
        Tank source = new Tank(WATER, 1000);
        source.realDrainCap = 30;
        Tank dest = new Tank(null, 0);
        FluidMover.Move move = FluidMover.move(source, dest, 100, null);

        assertEquals(100, move.attempted());
        assertEquals(30, move.accepted(), "only what was really drained could be filled");
        assertEquals(0, move.voided(), "the fluid stayed in the source, so none was lost");
    }

    /** A real drain that returns nothing still gets its fill call, because GT makes it. */
    @Test
    void aNullRealDrainStillCallsFillTheWayGtDoes() {
        Tank source = new Tank(WATER, 1000);
        source.realDrainCap = 0;
        Tank dest = new Tank(null, 0);
        FluidMover.Move move = FluidMover.move(source, dest, 100, null);

        assertEquals(0, move.accepted());
        assertEquals(0, move.voided());
        assertTrue(
            dest.calls.contains("fill(null,true)"),
            "GT passes the drain result straight into fill, so a null reaches the handler: " + dest.calls);
    }

    /** The loss case of section 3: drained for real, and the destination then took less. */
    @Test
    void aRealFillBelowTheSimulationVoidsTheDifference() {
        Tank source = new Tank(WATER, 1000);
        Tank dest = new Tank(null, 0);
        dest.realFillCap = 20;
        FluidMover.Move move = FluidMover.move(source, dest, 100, null);

        assertEquals(100, move.attempted());
        assertEquals(20, move.accepted());
        assertEquals(80, move.voided(), "drained 100, filled 20, and nothing puts the rest back");
        assertEquals(900, source.amount, "the source really lost the whole 100");
    }

    @Test
    void theCallSequenceIsTheOneGtMakes() {
        Tank source = new Tank(WATER, 1000);
        Tank dest = new Tank(null, 0);
        FluidMover.move(source, dest, 100, null);

        assertEquals("[drain(100,false), drain(100,true)]", source.calls + "", "source calls");
        assertEquals("[fill(100,false), fill(100,true)]", dest.calls + "", "destination calls");
    }

    @Test
    void missingHandlersAreNoTargetRatherThanIdle() {
        assertEquals(
            FluidMover.Outcome.NO_TARGET,
            FluidMover.move(null, new Tank(null, 0), 100, null)
                .outcome());
        assertEquals(
            FluidMover.Outcome.NO_TARGET,
            FluidMover.move(new Tank(WATER, 1), null, 100, null)
                .outcome());
        assertEquals(
            FluidMover.Outcome.NO_TARGET,
            FluidMover.move(new Tank(WATER, 1), new Tank(null, 0), 0, null)
                .outcome(),
            "a rate of zero has no target to speak of either");
    }

    @Test
    void aParcelIsNeverEmptyOrUnknown() {
        assertNull(FluidParcel.of(WATER, 0), "zero is nothing, the way Forge reports an empty drain");
        assertNull(FluidParcel.of(WATER, -5));
        assertNull(FluidParcel.of(null, 100), "a parcel with no fluid is not a parcel");
        assertNull(
            FluidParcel.of(WATER, 10)
                .withAmount(0));
        assertEquals(
            7,
            FluidParcel.of(WATER, 10)
                .withAmount(7)
                .amount());
        assertEquals(
            WATER,
            FluidParcel.of(WATER, 10)
                .withAmount(7)
                .fluid(),
            "withAmount keeps the fluid");
    }
}
