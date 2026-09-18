package io.github.ldogg123.gregscope.buffers;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects what a machine holds in one direction and keeps the largest few. Reusable, and allocation-free while
 * collecting. [pure]
 *
 * <p>
 * <b>Why it is shaped like this.</b> The probe walks a multiblock's hatches on every sample, with up to
 * {@code limits.maxSensors} machines sharing a 1 ms/tick budget, so the walk must not allocate and must not sort.
 * This keeps a fixed array of {@link #TOP_K} and does one bounded pass per entry: compare against the smallest
 * kept, and if it wins, displace it into the "other" rollup. That is O(K) per resource with K = 4, no collection
 * churn, and the totals stay exact regardless of which entries made the cut.
 *
 * <p>
 * One instance is reused across samples: {@link #reset()} then {@link #add} for each buffer, then {@link #build()}.
 * Only {@code build()} allocates, and only the handful of readings that are actually reported.
 */
public final class BufferCollector {

    /** How many resources are reported by name before the rest become "other". */
    public static final int TOP_K = 4;

    private final String[] keys = new String[TOP_K];
    private final long[] amounts = new long[TOP_K];
    private final long[] capacities = new long[TOP_K];
    private int kept;

    private long otherAmount;
    private int otherCount;
    private long totalAmount;
    private long totalCapacity;
    private int meBacked;

    /** Clears the collector for another machine. Keeps the arrays, which is the point of reusing it. */
    public void reset() {
        for (int i = 0; i < TOP_K; i++) {
            keys[i] = null;
            amounts[i] = 0L;
            capacities[i] = 0L;
        }
        kept = 0;
        otherAmount = 0L;
        otherCount = 0;
        totalAmount = 0L;
        totalCapacity = 0L;
        meBacked = 0;
    }

    /**
     * Records one buffer.
     *
     * <p>
     * Capacity is added to the total even when the amount is zero, because an empty tank with room in it is exactly
     * the state this feature exists to show. A blank key is ignored rather than counted under a placeholder name.
     *
     * @param key      the resource identity, {@code f:<fluid>} or {@code i:<name>:<meta>}
     * @param amount   how much is held
     * @param capacity room for it, or {@link BufferReading#UNKNOWN_CAPACITY}
     */
    public void add(String key, long amount, long capacity) {
        long held = Math.max(0L, amount);
        long room = Math.max(0L, capacity);
        totalCapacity += room;
        if (key == null || key.isEmpty() || held <= 0L) {
            return;
        }
        totalAmount += held;

        if (kept < TOP_K) {
            keys[kept] = key;
            amounts[kept] = held;
            capacities[kept] = room;
            kept++;
            return;
        }
        int smallest = 0;
        for (int i = 1; i < TOP_K; i++) {
            if (amounts[i] < amounts[smallest]) {
                smallest = i;
            }
        }
        if (held <= amounts[smallest]) {
            otherAmount += held;
            otherCount++;
            return;
        }
        // The displaced entry becomes "other": the totals do not change, only which names are reported.
        otherAmount += amounts[smallest];
        otherCount++;
        keys[smallest] = key;
        amounts[smallest] = held;
        capacities[smallest] = room;
    }

    /**
     * Records an input the machine draws from an ME network. No amount is taken: GT's own javadoc says the readable
     * contents of an ME hatch are not the supply available, so reporting the local buffer as a level would be a
     * confident wrong number for exactly the AE2 setups this feature is meant to serve.
     */
    public void addMeBacked() {
        meBacked++;
    }

    /** The summary. Allocates once, for the entries actually reported. */
    public BufferSet build() {
        if (kept == 0 && otherCount == 0 && meBacked == 0 && totalCapacity == 0L) {
            return BufferSet.EMPTY;
        }
        List<BufferReading> top = new ArrayList<>(kept);
        // Insertion-sorted into descending order: at most TOP_K entries, so this stays trivial.
        for (int i = 0; i < kept; i++) {
            BufferReading reading = BufferReading.of(keys[i], amounts[i], capacities[i]);
            int at = top.size();
            while (at > 0 && top.get(at - 1)
                .amount() < reading.amount()) {
                at--;
            }
            top.add(at, reading);
        }
        return new BufferSet(top, otherAmount, otherCount, totalAmount, totalCapacity, meBacked);
    }
}
