package io.github.ldogg123.gregscope.buffers;

import java.util.Collections;
import java.util.List;

/**
 * What a machine holds in one direction (its inputs, or its outputs), summarised. Immutable. [pure]
 *
 * <p>
 * <b>Bounded on purpose.</b> A machine can hold more distinct resources than anyone wants in a GUI line, a Lua
 * table or a metric label set, and an unbounded list would make cardinality depend on what a player happens to have
 * plugged in. So the largest {@link BufferCollector#TOP_K} are kept by name and everything else is rolled into
 * {@link #otherAmount()} and {@link #otherCount()}. The totals stay exact either way, which is what
 * {@link #saturation()} is computed from.
 */
public final class BufferSet {

    /** Nothing held, nothing measurable: what a machine with no buffers of this kind reads as. */
    public static final BufferSet EMPTY = new BufferSet(Collections.<BufferReading>emptyList(), 0L, 0, 0L, 0L, 0);

    private final List<BufferReading> top;
    private final long otherAmount;
    private final int otherCount;
    private final long totalAmount;
    private final long totalCapacity;
    private final int meBacked;

    BufferSet(List<BufferReading> top, long otherAmount, int otherCount, long totalAmount, long totalCapacity,
        int meBacked) {
        this.top = Collections.unmodifiableList(top);
        this.otherAmount = otherAmount;
        this.otherCount = otherCount;
        this.totalAmount = totalAmount;
        this.totalCapacity = totalCapacity;
        this.meBacked = meBacked;
    }

    /** The biggest holdings, largest first, at most {@link BufferCollector#TOP_K}. Never null, never modifiable. */
    public List<BufferReading> top() {
        return top;
    }

    /** Everything that did not fit in {@link #top()}, summed. */
    public long otherAmount() {
        return otherAmount;
    }

    /** How many distinct resources that sum covers. */
    public int otherCount() {
        return otherCount;
    }

    /** Every resource in this direction, including the ones rolled into "other". */
    public long totalAmount() {
        return totalAmount;
    }

    /** Room across every buffer that reported one. */
    public long totalCapacity() {
        return totalCapacity;
    }

    /**
     * How many inputs are backed by an ME hatch, whose readable contents are <b>not</b> the supply available to the
     * machine (GT says so itself on {@code getStoredFluidsForColor}). These are deliberately not counted in the
     * amounts: a number that looks authoritative and is wrong is worse than an honest "ME".
     */
    public int meBacked() {
        return meBacked;
    }

    /** True when the machine draws this direction from an ME network, so the amounts are only what is buffered. */
    public boolean hasMeBacked() {
        return meBacked > 0;
    }

    /**
     * How full this direction is overall, {@code 0.0}-{@code 1.0}, or {@code NaN} when nothing reported a capacity.
     * This is the one number worth alerting on: a falling input saturation on a machine that is {@code running} is
     * a machine about to starve.
     */
    public double saturation() {
        if (totalCapacity <= 0L) {
            return Double.NaN;
        }
        double ratio = (double) totalAmount / (double) totalCapacity;
        return ratio > 1.0D ? 1.0D : ratio;
    }

    /**
     * True when there is nothing at all to report - not merely when nothing is held.
     *
     * <p>
     * An empty tank with room in it is <em>not</em> empty by this definition: "this machine has a 16,000 L oxygen
     * input and it is at zero" is the most useful thing this feature can say, and a set that called itself empty
     * would let a caller skip reporting exactly the state worth alerting on. Likewise an ME-backed input, where the
     * true statement is "ME" rather than a number.
     */
    public boolean isEmpty() {
        return top.isEmpty() && otherCount == 0 && meBacked == 0 && totalCapacity == 0L;
    }

    @Override
    public String toString() {
        return "BufferSet" + top
            + (otherCount > 0 ? " +" + otherCount + " other=" + otherAmount : "")
            + (meBacked > 0 ? " me=" + meBacked : "")
            + " "
            + totalAmount
            + "/"
            + totalCapacity;
    }
}
