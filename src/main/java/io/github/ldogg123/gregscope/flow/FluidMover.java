package io.github.ldogg123.gregscope.flow;

/**
 * A counting re-implementation of GT's {@code GTUtility.moveFluid}, call for call. [pure]
 *
 * <p>
 * <b>Why this class exists at all.</b> The obvious way to meter a pump would be to let GT move the fluid and read
 * back how much went. GT does not offer that: {@code GTUtility.moveFluid} (GT5U 5.09.54.133,
 * {@code GTUtility.java:1054-1064}) returns {@code void} and reports nothing. Reading tank levels before and after
 * is not a substitute either - design-v0.3 section 10 S3 is the case that proves it, where a competing pipe moves
 * fluid through the same tank in the same tick and a level delta attributes that to the meter.
 *
 * <p>
 * So GregScope has to perform the moves itself and count as it goes, which is only safe if it does <em>exactly</em>
 * what GT does. That is the contract this class is written to, and {@code FluidMoverTest} enforces it by running
 * this mover and GT's own method against identical recording handlers and comparing the call sequences.
 *
 * <p>
 * <b>GT's sequence, and the three details that are easy to get wrong.</b>
 *
 * <pre>
 * FluidStack liquid = source.drain(drainSide, maxAmount, false); // simulate
 * if (liquid == null) return;
 * liquid = liquid.copy();
 * liquid.amount = dest.fill(fillSide, liquid, false); // simulate
 * if (liquid.amount &gt; 0 &amp;&amp; (allowMove == null || allowMove.test(liquid))) {
 *     dest.fill(fillSide, source.drain(drainSide, liquid.amount, true), true);
 * }
 * </pre>
 *
 * <ol>
 * <li>The filter is tested against the stack <b>after</b> the simulated fill has overwritten its amount, so it sees
 * what would actually move, not what was offered.</li>
 * <li>The real drain is an <b>argument</b> to the real fill, so the drain happens first and its result - which may
 * be {@code null}, or less than asked for - is passed straight through. GT never checks it, so a handler that
 * returns {@code null} there still gets a {@code fill(side, null, true)} call. This mover makes that same call,
 * because a handler is entitled to count it.</li>
 * <li>Nothing re-inserts a shortfall. If the real fill accepts less than the real drain removed, the difference is
 * <b>gone</b> - section 3 calls that {@code voided} and counts it as loss.</li>
 * </ol>
 */
public final class FluidMover {

    private FluidMover() {}

    /** What one move did, in the vocabulary of design-v0.3 section 3. Immutable. */
    public static final class Move {

        private final int attempted;
        private final int accepted;
        private final int voided;
        private final Outcome outcome;

        private Move(int attempted, int accepted, int voided, Outcome outcome) {
            this.attempted = attempted;
            this.accepted = accepted;
            this.voided = voided;
            this.outcome = outcome;
        }

        /** The simulated drain, capped at the rate: what the source offered. Exact, per section 3. */
        public int attempted() {
            return attempted;
        }

        /** What the destination's real {@code fill} reported taking. Never above {@link #attempted()}. */
        public int accepted() {
            return accepted;
        }

        /** Drained but not filled, and not put back: lost fluid. */
        public int voided() {
            return voided;
        }

        public Outcome outcome() {
            return outcome;
        }

        @Override
        public String toString() {
            return outcome + "(attempted=" + attempted + ", accepted=" + accepted + ", voided=" + voided + ")";
        }
    }

    /** The section 3 op partition, for the one op this mover performs. */
    public enum Outcome {
        /** Fluid reached the destination. */
        MOVED,
        /** There was fluid to move and the destination or the filter refused all of it: backpressure. */
        BLOCKED,
        /** The source had nothing to give. */
        IDLE,
        /** One of the two handlers was absent. */
        NO_TARGET
    }

    /** The two calls GT makes on the draining side. */
    public interface Source {

        /**
         * @param maxAmount the most to take
         * @param real      false to simulate, true to actually remove
         * @return what was (or would be) taken, or {@code null} for nothing
         */
        FluidParcel drain(int maxAmount, boolean real);
    }

    /** The one call GT makes on the filling side. */
    public interface Sink {

        /**
         * @param parcel what to put in; <b>may be {@code null}</b>, exactly as GT can pass it
         * @param real   false to simulate, true to actually add
         * @return how much was (or would be) taken
         */
        int fill(FluidParcel parcel, boolean real);
    }

    /** GT's {@code allowMove} predicate; {@code null} there means "everything is allowed". */
    public interface Filter {

        boolean allows(FluidParcel parcel);
    }

    private static final Move NO_TARGET = new Move(0, 0, 0, Outcome.NO_TARGET);
    private static final Move IDLE = new Move(0, 0, 0, Outcome.IDLE);

    /**
     * Moves up to {@code maxAmount} from {@code source} to {@code sink}, making the same calls in the same order as
     * {@code GTUtility.moveFluid}, and reports what moved.
     *
     * @param filter may be {@code null}, which allows everything, as GT's {@code allowMove} does
     */
    public static Move move(Source source, Sink sink, int maxAmount, Filter filter) {
        if (source == null || sink == null || maxAmount <= 0) {
            return NO_TARGET;
        }
        FluidParcel offered = source.drain(maxAmount, false);
        if (offered == null) {
            return IDLE;
        }
        int attempted = offered.amount();
        int fillable = sink.fill(offered, false);
        // GT tests the filter against the stack whose amount the simulated fill has just overwritten.
        FluidParcel movable = offered.withAmount(fillable);
        if (fillable <= 0 || (filter != null && !filter.allows(movable))) {
            return new Move(attempted, 0, 0, Outcome.BLOCKED);
        }
        // GT evaluates the real drain as the argument of the real fill, so the drain happens first and whatever it
        // returns - including null - goes straight into fill. Both calls are made either way.
        FluidParcel drawn = source.drain(fillable, true);
        int accepted = sink.fill(drawn, true);
        int removed = drawn == null ? 0 : drawn.amount();
        int voided = removed - accepted;
        return new Move(attempted, accepted, voided < 0 ? 0 : voided, accepted > 0 ? Outcome.MOVED : Outcome.BLOCKED);
    }
}
