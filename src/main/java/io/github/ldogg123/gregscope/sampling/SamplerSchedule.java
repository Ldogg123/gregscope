package io.github.ldogg123.gregscope.sampling;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import io.github.ldogg123.gregscope.registry.SensorEntry;

/**
 * The sampler schedule of design-v0.2 section 6.1 and the per-tick budget of section 6.3: which sensors are due on
 * which tick, what carries over when the budget runs out, and what is given up on. Server thread only. [pure]
 *
 * <p>
 * <b>Buckets.</b> There are {@code sampling.intervalTicks} buckets, so every sensor is served once per interval. A
 * sensor that becomes LIVE goes into the least-loaded bucket (ties go to the lowest index) and is removed with an O(1)
 * swap-remove, which is why the entry remembers both its bucket and its slot inside it.
 *
 * <p>
 * <b>Budget.</b> Each tick serves the carry-over list first and then {@code bucket[ownTick % interval]}.
 * {@code nanoTime} is read once at the start and again before every sample; when the elapsed time has reached the
 * budget, the rest of the tick's work is carried over to the next tick and the tick counts as budget-exceeded. A
 * sensor that has been carried for a whole interval is given up on for that interval: the caller records a
 * {@code sampling_skipped} gap for it. With {@code sampling.enabled=false} nothing is sampled and everything due is
 * skipped the same way (design-v0.2 section 7.2, gap bit 3).
 *
 * <p>
 * <b>No allocation per tick.</b> The two carry-over lists are swapped rather than rebuilt, the due tick of a carried
 * sensor lives on the entry, and {@link #tick} fills one reusable {@link Result}. A tick with an empty carry-over list
 * and an empty bucket returns before {@code nanoTime} is ever read, so a server with no live sensors pays two
 * emptiness checks per tick and nothing else.
 */
public final class SamplerSchedule {

    /** What one tick of the schedule did. The instance is reused; read it before the next {@link #tick}. */
    public static final class Result {

        private int sampled;
        private int skipped;
        private int carried;
        private long cycleNanos;
        private boolean budgetExceeded;
        private boolean intervalEnded;
        private boolean worked;

        /** Sensors sampled on this tick. */
        public int sampled() {
            return sampled;
        }

        /** Sensors given up on for their interval ({@code sampling_skipped}). */
        public int skipped() {
            return skipped;
        }

        /** Sensors still waiting after this tick. */
        public int carried() {
            return carried;
        }

        /** How long the per-sensor work of this tick took; 0 on an idle tick. */
        public long cycleNanos() {
            return cycleNanos;
        }

        /** True if the budget stopped this tick before everything due was served. */
        public boolean budgetExceeded() {
            return budgetExceeded;
        }

        /** True on the last bucket index of an interval: the caller sweeps minutes and publishes a frame. */
        public boolean intervalEnded() {
            return intervalEnded;
        }

        /** True if the tick had per-sensor work to do at all. */
        public boolean worked() {
            return worked;
        }

        @Override
        public String toString() {
            return "Result{sampled=" + sampled
                + ", skipped="
                + skipped
                + ", carried="
                + carried
                + ", budgetExceeded="
                + budgetExceeded
                + ", intervalEnded="
                + intervalEnded
                + "}";
        }
    }

    /** What the schedule asks the sampler to do with one sensor. */
    public interface Sink {

        /** Take one sample. Called only while sampling is enabled and the budget allows it. */
        void sample(SensorEntry entry);

        /** The sensor was not served within its interval: record a {@code sampling_skipped} gap. */
        void skip(SensorEntry entry);
    }

    private final int intervalTicks;
    private final LongSupplier nanoTime;
    private final List<List<SensorEntry>> buckets;
    private List<SensorEntry> carry = new ArrayList<>();
    private List<SensorEntry> carryNext = new ArrayList<>();
    private final Result result = new Result();

    private long ownTick;
    private int size;

    /**
     * @param intervalTicks one of the design-v0.2 section 12.3 values (20, 40, 60, 100); fixed for the life of the
     *                      schedule, because {@code sampling.intervalTicks} needs a restart
     * @param nanoTime      the monotonic clock the budget is measured with ({@code System::nanoTime} in production)
     */
    public SamplerSchedule(int intervalTicks, LongSupplier nanoTime) {
        if (intervalTicks <= 0) {
            throw new IllegalArgumentException("intervalTicks " + intervalTicks);
        }
        if (nanoTime == null) {
            throw new IllegalArgumentException("nanoTime");
        }
        this.intervalTicks = intervalTicks;
        this.nanoTime = nanoTime;
        this.buckets = new ArrayList<>(intervalTicks);
        for (int i = 0; i < intervalTicks; i++) {
            buckets.add(new ArrayList<SensorEntry>());
        }
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    /** How many sensors are scheduled. */
    public int size() {
        return size;
    }

    public int bucketSize(int index) {
        return buckets.get(index)
            .size();
    }

    /** How many sensors are waiting for a later tick because the budget ran out. */
    public int carriedOver() {
        return carry.size();
    }

    /** The schedule's own tick counter: the number of {@link #tick} calls so far. */
    public long ownTick() {
        return ownTick;
    }

    /**
     * Puts a sensor into the least-loaded bucket (ties go to the lowest index). A sensor that is already scheduled is
     * left where it is, so a repeated {@code sensorLive} cannot schedule it twice.
     */
    public void add(SensorEntry entry) {
        if (entry == null || entry.bucket() >= 0) {
            return;
        }
        int best = 0;
        int bestSize = buckets.get(0)
            .size();
        for (int i = 1; i < intervalTicks && bestSize > 0; i++) {
            int n = buckets.get(i)
                .size();
            if (n < bestSize) {
                best = i;
                bestSize = n;
            }
        }
        List<SensorEntry> bucket = buckets.get(best);
        entry.setBucket(best);
        entry.setBucketSlot(bucket.size());
        bucket.add(entry);
        size++;
    }

    /** O(1) swap-remove from the bucket, plus a drop from the carry-over list if it is waiting there. */
    public void remove(SensorEntry entry) {
        if (entry == null) {
            return;
        }
        carry.remove(entry);
        carryNext.remove(entry);
        int index = entry.bucket();
        if (index < 0) {
            return;
        }
        List<SensorEntry> bucket = buckets.get(index);
        int slot = entry.bucketSlot();
        if (slot < 0 || slot >= bucket.size() || bucket.get(slot) != entry) {
            // Defensive: fall back to a scan rather than corrupting the bucket.
            slot = bucket.indexOf(entry);
        }
        if (slot >= 0) {
            SensorEntry last = bucket.remove(bucket.size() - 1);
            if (slot < bucket.size()) {
                bucket.set(slot, last);
                last.setBucketSlot(slot);
            }
            size--;
        }
        entry.setBucket(-1);
        entry.setBucketSlot(-1);
    }

    /** Empties every bucket and the carry-over list (server stop, or a purge of everything). */
    public void clear() {
        for (List<SensorEntry> bucket : buckets) {
            for (SensorEntry entry : bucket) {
                entry.setBucket(-1);
                entry.setBucketSlot(-1);
            }
            bucket.clear();
        }
        carry.clear();
        carryNext.clear();
        size = 0;
    }

    /**
     * Runs one tick of the schedule.
     *
     * @param budgetNanos the per-tick budget ({@code sampling.tickBudgetMicros x 1000}); read live, so a config or
     *                    test-hook change applies at once
     * @param enabled     {@code sampling.enabled}; when false everything due is skipped instead of sampled
     * @param sink        what actually samples and records skips
     * @return the reused result of this tick
     */
    public Result tick(long budgetNanos, boolean enabled, Sink sink) {
        long current = ownTick++;
        int index = (int) (current % intervalTicks);
        result.sampled = 0;
        result.skipped = 0;
        result.budgetExceeded = false;
        result.intervalEnded = index == intervalTicks - 1;
        List<SensorEntry> bucket = buckets.get(index);
        if (carry.isEmpty() && bucket.isEmpty()) {
            // The idle path: no nanoTime, no clock, no allocation.
            result.carried = 0;
            result.cycleNanos = 0L;
            result.worked = false;
            return result;
        }
        result.worked = true;
        long start = nanoTime.getAsLong();
        boolean stopped = serveCarry(current, start, budgetNanos, enabled, sink);
        // The due bucket is always visited, even when the carry-over list already spent the budget: its sensors then
        // carry over too. Skipping them outright would drop a whole interval for them with no gap to show for it.
        stopped = serveBucket(bucket, current, start, budgetNanos, enabled, sink, stopped) || stopped;
        List<SensorEntry> swap = carry;
        carry = carryNext;
        carryNext = swap;
        carryNext.clear();
        result.budgetExceeded = stopped;
        result.carried = carry.size();
        result.cycleNanos = nanoTime.getAsLong() - start;
        return result;
    }

    /**
     * @return true if the budget stopped the tick
     *
     *         <p>
     *         Serving a sensor can take it out of the schedule: a sample that finds its chunk gone or its cover
     *         missing turns the entry UNLOADED or into a tombstone, and the registry reports that back through
     *         {@link #remove}. Both loops therefore re-read the list position after serving instead of blindly
     *         advancing, so the element that moved into the freed slot is still served. Only the sensor being served
     *         can be removed this way: the registry touches exactly the id it was asked about.
     */
    private boolean serveCarry(long current, long start, long budgetNanos, boolean enabled, Sink sink) {
        boolean stopped = false;
        int i = 0;
        while (i < carry.size()) {
            SensorEntry entry = carry.get(i);
            if (entry.bucket() < 0) {
                // Removed from the schedule while it waited.
                i++;
                continue;
            }
            if (current - entry.sampleDueTick() >= intervalTicks) {
                // Carried for a whole interval: give this interval up (design-v0.2 section 6.3).
                result.skipped++;
                sink.skip(entry);
            } else if (stopped || !serve(entry, start, budgetNanos, enabled, sink)) {
                stopped = true;
                carryNext.add(entry);
            }
            if (i < carry.size() && carry.get(i) == entry) {
                i++;
            }
        }
        carry.clear();
        return stopped;
    }

    /** @return true if the budget stopped the tick; see {@link #serveCarry} for the re-read. */
    private boolean serveBucket(List<SensorEntry> bucket, long current, long start, long budgetNanos, boolean enabled,
        Sink sink, boolean alreadyStopped) {
        int i = 0;
        while (i < bucket.size()) {
            SensorEntry entry = bucket.get(i);
            entry.setSampleDueTick(current);
            if (alreadyStopped || !serve(entry, start, budgetNanos, enabled, sink)) {
                for (int rest = i; rest < bucket.size(); rest++) {
                    SensorEntry carried = bucket.get(rest);
                    carried.setSampleDueTick(current);
                    carryNext.add(carried);
                }
                return true;
            }
            if (i < bucket.size() && bucket.get(i) == entry) {
                i++;
            }
        }
        return false;
    }

    /** @return false if the budget is spent and the sensor must be carried */
    private boolean serve(SensorEntry entry, long start, long budgetNanos, boolean enabled, Sink sink) {
        if (!enabled) {
            // sampling.enabled=false is a skip, not a carry: the gap says so and nothing accumulates.
            result.skipped++;
            sink.skip(entry);
            return true;
        }
        if (nanoTime.getAsLong() - start >= budgetNanos) {
            return false;
        }
        result.sampled++;
        sink.sample(entry);
        return true;
    }
}
