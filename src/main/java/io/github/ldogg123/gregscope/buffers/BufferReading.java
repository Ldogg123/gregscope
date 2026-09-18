package io.github.ldogg123.gregscope.buffers;

/**
 * One resource a machine is holding: how much, and how much room there is for it. Immutable. [pure]
 *
 * <p>
 * The key follows the identity rules design-v0.3-buffers section 3 fixes - {@code f:<fluid>} for a fluid and
 * {@code i:<registryName>:<meta>} for an item, never a numeric ID, because numeric IDs are pack-dependent and a
 * saved history or an exported metric outlives the pack that produced it.
 */
public final class BufferReading {

    /** What {@link #capacity()} reports when the holder does not say: a bus slot, or an unbounded handler. */
    public static final long UNKNOWN_CAPACITY = 0L;

    private final String key;
    private final long amount;
    private final long capacity;

    private BufferReading(String key, long amount, long capacity) {
        this.key = key;
        this.amount = amount;
        this.capacity = capacity;
    }

    /**
     * @param key      the resource identity; blank yields {@code null}
     * @param amount   how much is held; negative is clamped to 0
     * @param capacity room for it, or {@link #UNKNOWN_CAPACITY}
     */
    public static BufferReading of(String key, long amount, long capacity) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        return new BufferReading(key, Math.max(0L, amount), Math.max(UNKNOWN_CAPACITY, capacity));
    }

    public String key() {
        return key;
    }

    public long amount() {
        return amount;
    }

    /** Room for this resource, or {@link #UNKNOWN_CAPACITY} when the holder does not report one. */
    public long capacity() {
        return capacity;
    }

    /**
     * How full this one buffer is, {@code 0.0}-{@code 1.0}, or {@code NaN} when there is no capacity to measure
     * against. {@code NaN} rather than {@code 0.0} on purpose: "not measurable" and "empty" are different facts and
     * a chart that renders them the same is the kind of confident wrong number this feature exists to avoid.
     */
    public double fill() {
        if (capacity <= 0L) {
            return Double.NaN;
        }
        double ratio = (double) amount / (double) capacity;
        return ratio > 1.0D ? 1.0D : ratio;
    }

    @Override
    public String toString() {
        return key + " " + amount + (capacity > 0L ? "/" + capacity : "");
    }
}
