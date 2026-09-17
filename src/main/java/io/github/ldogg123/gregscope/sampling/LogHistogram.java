package io.github.ldogg123.gregscope.sampling;

import java.util.Arrays;

/**
 * A 1,024-bucket log-linear histogram of non-negative values, used for microseconds per tick (design-v0.2 §6.3).
 * [pure]
 *
 * <p>
 * Values 0..15 have their own bucket. Above that, each power of two is split into 16 equal sub-buckets, so a reported
 * quantile is at most 1/16 (6.25 %) above the true value. Every long fits: the largest bucket index used is 959
 * (Long.MAX_VALUE).
 * Quantiles report the bucket's upper bound, capped at the exact maximum. The caller owns the 60-second window: it
 * keeps the last completed window with {@link #copyFrom} and calls {@link #clear()} to start a new one. Not
 * thread-safe.
 */
public final class LogHistogram {

    public static final int BUCKETS = 1024;
    private static final int SUB_BITS = 4;
    private static final int SUB = 1 << SUB_BITS;

    private final long[] counts = new long[BUCKETS];
    private long count;
    private long max;

    static int bucket(long value) {
        if (value < SUB) {
            return (int) value;
        }
        int exponent = 63 - Long.numberOfLeadingZeros(value);
        int sub = (int) (value >>> (exponent - SUB_BITS)) & (SUB - 1);
        return SUB + (exponent - SUB_BITS) * SUB + sub;
    }

    /** Largest value that falls into {@code bucket}. */
    static long upperBound(int bucket) {
        if (bucket < SUB) {
            return bucket;
        }
        int exponent = (bucket - SUB) / SUB + SUB_BITS;
        int sub = (bucket - SUB) % SUB;
        long lower = (long) (SUB + sub) << (exponent - SUB_BITS);
        long upper = lower + (1L << (exponent - SUB_BITS)) - 1;
        return upper < lower ? Long.MAX_VALUE : upper;
    }

    /** Records a value; negative values count as 0. */
    public void record(long value) {
        long v = Math.max(0, value);
        counts[bucket(v)]++;
        count++;
        if (v > max) {
            max = v;
        }
    }

    public long count() {
        return count;
    }

    /** The exact maximum, 0 when empty. */
    public long max() {
        return max;
    }

    /**
     * The smallest bucket upper bound at or below which at least a fraction {@code q} of the values lie
     * ({@code 0 < q <= 1}), capped at {@link #max()}; 0 when empty.
     */
    public long quantile(double q) {
        if (!(q > 0.0 && q <= 1.0)) {
            throw new IllegalArgumentException("quantile " + q);
        }
        if (count == 0) {
            return 0;
        }
        long rank = (long) Math.ceil(q * count);
        long seen = 0;
        for (int b = 0; b < BUCKETS; b++) {
            seen += counts[b];
            if (seen >= rank) {
                return Math.min(upperBound(b), max);
            }
        }
        return max;
    }

    public void clear() {
        Arrays.fill(counts, 0);
        count = 0;
        max = 0;
    }

    /** Replaces this histogram's contents with {@code other}'s (to keep the last completed window). */
    public void copyFrom(LogHistogram other) {
        System.arraycopy(other.counts, 0, counts, 0, BUCKETS);
        count = other.count;
        max = other.max;
    }
}
