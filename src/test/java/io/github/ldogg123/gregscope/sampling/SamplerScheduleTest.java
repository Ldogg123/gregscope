package io.github.ldogg123.gregscope.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-108 (design-v0.2 sections 6.1 and 6.3, ticket section 14): bucket balance, least-loaded assignment after
 * removals, the per-tick budget with a fake {@code nanoTime}, carry-over, and giving a sensor up after it has been
 * carried for a whole interval.
 *
 * <p>
 * The clock the budget is measured with is a test double that only moves when a sample is taken, so the budget
 * arithmetic is exact rather than timing-dependent.
 */
class SamplerScheduleTest {

    private static final long MILLISECOND = 1_000_000L;
    private static final long PER_SAMPLE_NANOS = 400_000L;

    /** A monotonic clock a test moves by hand, counting how often the schedule read it. */
    private static final class FakeNanos {

        long now;
        int reads;

        long get() {
            reads++;
            return now;
        }
    }

    /** Records what the schedule asked for and advances the fake clock by one sample's cost. */
    private static final class RecordingSink implements SamplerSchedule.Sink {

        final List<SensorEntry> sampled = new ArrayList<>();
        final List<SensorEntry> skipped = new ArrayList<>();
        private final FakeNanos nanos;
        private final long cost;

        RecordingSink(FakeNanos nanos, long cost) {
            this.nanos = nanos;
            this.cost = cost;
        }

        @Override
        public void sample(SensorEntry entry) {
            sampled.add(entry);
            nanos.now += cost;
        }

        @Override
        public void skip(SensorEntry entry) {
            skipped.add(entry);
        }

        void clear() {
            sampled.clear();
            skipped.clear();
        }
    }

    private static SensorEntry entry(int x) {
        SensorIdentity identity = new SensorIdentity(UUID.randomUUID(), "", null, null, 1_700_000_000L);
        return new SensorEntry(identity, SensorKind.MACHINE, 0, x, 64, 0, 1, SensorState.LIVE, 1_700_000_000L);
    }

    private static List<SensorEntry> fill(SamplerSchedule schedule, int count) {
        List<SensorEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            SensorEntry entry = entry(i);
            entries.add(entry);
            schedule.add(entry);
        }
        return entries;
    }

    private static int[] bucketSizes(SamplerSchedule schedule) {
        int[] sizes = new int[schedule.intervalTicks()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = schedule.bucketSize(i);
        }
        return sizes;
    }

    // --- bucket assignment (design-v0.2 section 6.1) ---

    @Test
    void bucketsStayBalancedAtEverySize() {
        for (int count : new int[] { 0, 1, 19, 20, 21, 256, 1024 }) {
            SamplerSchedule schedule = new SamplerSchedule(20, new FakeNanos()::get);
            fill(schedule, count);
            assertEquals(count, schedule.size(), "scheduled sensors for " + count);
            int min = Integer.MAX_VALUE;
            int max = 0;
            int total = 0;
            for (int size : bucketSizes(schedule)) {
                min = Math.min(min, size);
                max = Math.max(max, size);
                total += size;
            }
            assertEquals(count, total, "sensors in buckets for " + count);
            assertTrue(max - min <= 1, "buckets unbalanced for " + count + ": " + max + " vs " + min);
        }
    }

    @Test
    void theFirstSensorsGoIntoTheLowestBucketsInOrder() {
        SamplerSchedule schedule = new SamplerSchedule(20, new FakeNanos()::get);
        List<SensorEntry> entries = fill(schedule, 20);
        for (int i = 0; i < 20; i++) {
            assertEquals(
                i,
                entries.get(i)
                    .bucket(),
                "sensor " + i + " bucket");
        }
    }

    @Test
    void aFreedBucketIsFilledFirstAgain() {
        SamplerSchedule schedule = new SamplerSchedule(20, new FakeNanos()::get);
        List<SensorEntry> entries = fill(schedule, 40);
        // Every bucket holds two; free bucket 7 completely.
        List<SensorEntry> inSeven = new ArrayList<>();
        for (SensorEntry entry : entries) {
            if (entry.bucket() == 7) {
                inSeven.add(entry);
            }
        }
        assertEquals(2, inSeven.size(), "bucket 7 before the removals");
        for (SensorEntry entry : inSeven) {
            schedule.remove(entry);
            assertEquals(-1, entry.bucket(), "a removed sensor keeps a bucket");
            assertEquals(-1, entry.bucketSlot(), "a removed sensor keeps a slot");
        }
        assertEquals(0, schedule.bucketSize(7));
        assertEquals(38, schedule.size());

        SensorEntry first = entry(100);
        schedule.add(first);
        assertEquals(7, first.bucket(), "the emptied bucket is the least loaded");
        SensorEntry second = entry(101);
        schedule.add(second);
        assertEquals(7, second.bucket(), "the emptied bucket still has room");
        SensorEntry third = entry(102);
        schedule.add(third);
        assertEquals(0, third.bucket(), "ties go to the lowest index");
    }

    @Test
    void removalIsASwapAndKeepsEverySlotValid() {
        SamplerSchedule schedule = new SamplerSchedule(20, new FakeNanos()::get);
        List<SensorEntry> entries = fill(schedule, 100);
        Set<SensorEntry> removed = new HashSet<>();
        for (int i = 0; i < 100; i += 3) {
            schedule.remove(entries.get(i));
            removed.add(entries.get(i));
        }
        assertEquals(100 - removed.size(), schedule.size());
        // Every surviving sensor must still be findable at its recorded slot, which is what makes removal O(1).
        for (SensorEntry entry : entries) {
            if (removed.contains(entry)) {
                assertEquals(-1, entry.bucket());
                continue;
            }
            assertTrue(entry.bucket() >= 0, "surviving sensor lost its bucket");
            assertTrue(
                entry.bucketSlot() >= 0 && entry.bucketSlot() < schedule.bucketSize(entry.bucket()),
                "slot out of range: " + entry.bucketSlot());
        }
        // And a second removal of the same sensor must not disturb anything.
        int size = schedule.size();
        schedule.remove(entries.get(0));
        assertEquals(size, schedule.size());
    }

    @Test
    void addingTheSameSensorTwiceScheduleItOnce() {
        SamplerSchedule schedule = new SamplerSchedule(20, new FakeNanos()::get);
        SensorEntry entry = entry(1);
        schedule.add(entry);
        schedule.add(entry);
        assertEquals(1, schedule.size());
        assertEquals(1, schedule.bucketSize(0));
    }

    @Test
    void anIntervalOfZeroOrANullClockIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SamplerSchedule(0, new FakeNanos()::get));
        assertThrows(IllegalArgumentException.class, () -> new SamplerSchedule(20, null));
    }

    // --- the tick loop (design-v0.2 section 6.1) ---

    @Test
    void anIdleTickReadsNoClockAndDoesNoWork() {
        FakeNanos nanos = new FakeNanos();
        SamplerSchedule schedule = new SamplerSchedule(20, nanos::get);
        RecordingSink sink = new RecordingSink(nanos, PER_SAMPLE_NANOS);

        SamplerSchedule.Result result = schedule.tick(MILLISECOND, true, sink);

        assertFalse(result.worked(), "an empty schedule must not work");
        assertEquals(0, result.sampled());
        assertEquals(0, result.skipped());
        assertEquals(0L, result.cycleNanos());
        assertEquals(0, nanos.reads, "nanoTime must not be read on an idle tick");
        assertTrue(sink.sampled.isEmpty() && sink.skipped.isEmpty());
    }

    @Test
    void aTickWhoseOwnBucketIsEmptyIsIdleToo() {
        FakeNanos nanos = new FakeNanos();
        SamplerSchedule schedule = new SamplerSchedule(20, nanos::get);
        SensorEntry only = entry(1);
        schedule.add(only);
        RecordingSink sink = new RecordingSink(nanos, PER_SAMPLE_NANOS);

        assertTrue(
            schedule.tick(MILLISECOND, true, sink)
                .worked(),
            "bucket 0 holds the sensor");
        sink.clear();
        nanos.reads = 0;
        for (int i = 1; i < 20; i++) {
            assertFalse(
                schedule.tick(MILLISECOND, true, sink)
                    .worked(),
                "tick " + i + " has an empty bucket");
        }
        assertEquals(0, nanos.reads, "nanoTime read on an idle tick");
        assertTrue(sink.sampled.isEmpty());
    }

    @Test
    void everySensorIsSampledExactlyOncePerInterval() {
        FakeNanos nanos = new FakeNanos();
        SamplerSchedule schedule = new SamplerSchedule(20, nanos::get);
        List<SensorEntry> entries = fill(schedule, 57);
        RecordingSink sink = new RecordingSink(nanos, PER_SAMPLE_NANOS);

        int intervalEnds = 0;
        for (int tick = 0; tick < 20; tick++) {
            SamplerSchedule.Result result = schedule.tick(100 * MILLISECOND, true, sink);
            if (result.intervalEnded()) {
                intervalEnds++;
                assertEquals(19, tick, "the interval must end on the last bucket index");
            }
        }
        assertEquals(1, intervalEnds, "one interval boundary per interval");
        assertEquals(57, sink.sampled.size(), "samples in one interval");
        assertEquals(new HashSet<>(entries), new HashSet<>(sink.sampled), "each sensor exactly once");
    }

    // --- the budget (design-v0.2 section 6.3) ---

    @Test
    void theBudgetStopsTheTickAndCarriesTheRest() {
        FakeNanos nanos = new FakeNanos();
        // One bucket, so every sensor is due on every tick and the budget is the only thing that limits the tick.
        SamplerSchedule schedule = new SamplerSchedule(1, nanos::get);
        fill(schedule, 10);
        RecordingSink sink = new RecordingSink(nanos, PER_SAMPLE_NANOS);

        SamplerSchedule.Result result = schedule.tick(MILLISECOND, true, sink);

        // 400 us per sample against a 1000 us budget: the check before the fourth sample already sees 1200 us.
        assertEquals(3, result.sampled(), "samples in the first tick");
        assertTrue(result.budgetExceeded(), "the budget must be reported as exceeded");
        assertEquals(7, result.carried(), "the rest carries over");
        assertEquals(0, result.skipped(), "nothing is given up on after one tick");
        assertEquals(3, sink.sampled.size());
        assertEquals(1200_000L, result.cycleNanos(), "the tick is timed from its first nanoTime read");
    }

    @Test
    void carryOverIsServedBeforeTheNewBucket() {
        FakeNanos nanos = new FakeNanos();
        SamplerSchedule schedule = new SamplerSchedule(2, nanos::get);
        SensorEntry a = entry(1);
        SensorEntry b = entry(2);
        SensorEntry c = entry(3);
        schedule.add(a);
        schedule.add(b);
        schedule.add(c);
        assertEquals(0, a.bucket());
        assertEquals(1, b.bucket());
        assertEquals(0, c.bucket());
        RecordingSink sink = new RecordingSink(nanos, PER_SAMPLE_NANOS);

        // A budget of exactly one sample: the check before the second sample of a tick always fails.
        SamplerSchedule.Result first = schedule.tick(PER_SAMPLE_NANOS, true, sink);
        assertEquals(1, first.sampled());
        assertSame(a, sink.sampled.get(0), "bucket 0 is served in order");
        assertEquals(1, first.carried(), "the second sensor of bucket 0 carries over");

        sink.clear();
        SamplerSchedule.Result second = schedule.tick(PER_SAMPLE_NANOS, true, sink);
        assertEquals(1, second.sampled());
        assertSame(c, sink.sampled.get(0), "the carried sensor is served before bucket 1");
        assertEquals(1, second.carried(), "bucket 1's sensor now carries over");
    }

    /**
     * Regression (found by {@code SamplerTests.tinyBudgetSkips}): when the carry-over list alone spends the budget,
     * the tick's own bucket must still be carried over. Dropping it silently would lose a whole interval for those
     * sensors with no gap to show for it, and they would never age out into a {@code sampling_skipped} either.
     */
    @Test
    void theDueBucketCarriesOverWhenTheCarryListSpendsTheBudget() {
        FakeNanos nanos = new FakeNanos();
        SamplerSchedule schedule = new SamplerSchedule(2, nanos::get);
        SensorEntry a = entry(1);
        SensorEntry b = entry(2);
        SensorEntry c = entry(3);
        schedule.add(a); // bucket 0
        schedule.add(b); // bucket 1
        schedule.add(c); // bucket 0
        RecordingSink sink = new RecordingSink(nanos, PER_SAMPLE_NANOS);

        // Tick 0: one sample fits, so c carries over.
        schedule.tick(PER_SAMPLE_NANOS, true, sink);
        assertEquals(1, schedule.carriedOver());

        // Tick 1: a zero budget means even the carried c is not served, and bucket 1's b must join the carry list.
        sink.clear();
        SamplerSchedule.Result second = schedule.tick(0L, true, sink);
        assertEquals(0, second.sampled());
        assertTrue(second.budgetExceeded());
        assertEquals(2, second.carried(), "both the carried sensor and the due bucket must wait: " + second);

        // And both age out into skips rather than vanishing.
        for (int tick = 2; tick < 22; tick++) {
            schedule.tick(0L, true, sink);
        }
        assertTrue(sink.skipped.contains(b), "the bucket's sensor was never given up on: " + sink.skipped);
        assertTrue(sink.skipped.contains(c), "the carried sensor was never given up on: " + sink.skipped);
    }

    @Test
    void aSensorCarriedForAWholeIntervalIsGivenUpOn() {
        FakeNanos nanos = new FakeNanos();
        SamplerSchedule schedule = new SamplerSchedule(20, nanos::get);
        SensorEntry entry = entry(1);
        schedule.add(entry);
        RecordingSink sink = new RecordingSink(nanos, PER_SAMPLE_NANOS);

        // A zero budget means the check before the first sample already fails, so nothing is ever sampled.
        for (int tick = 0; tick < 20; tick++) {
            SamplerSchedule.Result result = schedule.tick(0L, true, sink);
            assertEquals(0, result.sampled(), "tick " + tick);
            assertEquals(0, result.skipped(), "tick " + tick + " must not give up yet");
            assertEquals(1, result.carried(), "tick " + tick);
        }

        SamplerSchedule.Result result = schedule.tick(0L, true, sink);
        assertEquals(1, result.skipped(), "carried for a whole interval");
        assertEquals(1, sink.skipped.size());
        assertSame(entry, sink.skipped.get(0));
        assertTrue(sink.sampled.isEmpty(), "nothing was ever sampled");
    }

    @Test
    void disabledSamplingSkipsEverythingDueAndCarriesNothing() {
        FakeNanos nanos = new FakeNanos();
        SamplerSchedule schedule = new SamplerSchedule(20, nanos::get);
        List<SensorEntry> entries = fill(schedule, 40);
        RecordingSink sink = new RecordingSink(nanos, PER_SAMPLE_NANOS);

        SamplerSchedule.Result result = schedule.tick(MILLISECOND, false, sink);

        assertEquals(0, result.sampled(), "nothing is sampled while sampling is off");
        assertEquals(2, result.skipped(), "both sensors of bucket 0 are skipped");
        assertEquals(0, result.carried(), "a disabled sampler carries nothing");
        assertFalse(result.budgetExceeded());
        assertEquals(2, sink.skipped.size());
        assertTrue(entries.containsAll(sink.skipped));
    }

    @Test
    void aSensorRemovedWhileCarriedIsNeitherSampledNorSkipped() {
        FakeNanos nanos = new FakeNanos();
        SamplerSchedule schedule = new SamplerSchedule(1, nanos::get);
        SensorEntry a = entry(1);
        SensorEntry b = entry(2);
        schedule.add(a);
        schedule.add(b);
        RecordingSink sink = new RecordingSink(nanos, PER_SAMPLE_NANOS);

        schedule.tick(PER_SAMPLE_NANOS, true, sink);
        assertSame(a, sink.sampled.get(0));
        assertEquals(1, schedule.carriedOver(), "b carried over");

        schedule.remove(b);
        assertEquals(0, schedule.carriedOver(), "removal must drop the sensor from the carry-over list");

        sink.clear();
        schedule.tick(10 * MILLISECOND, true, sink);
        assertEquals(1, sink.sampled.size(), "only the remaining sensor: " + sink.sampled);
        assertSame(a, sink.sampled.get(0));
        assertTrue(sink.skipped.isEmpty());
    }

    /**
     * A sample can take its own sensor out of the schedule: a chunk that turned out to be gone makes the entry
     * UNLOADED, and the registry reports that back as a removal while the tick is still iterating the bucket. No other
     * sensor may lose its turn because of it.
     */
    @Test
    void aSensorRemovedWhileItIsBeingSampledCostsNoOtherSensorItsTurn() {
        FakeNanos nanos = new FakeNanos();
        SamplerSchedule schedule = new SamplerSchedule(1, nanos::get);
        List<SensorEntry> entries = fill(schedule, 5);
        final List<SensorEntry> sampled = new ArrayList<>();
        // The first sensor served removes itself, exactly as a sample that finds its chunk unloaded does.
        SensorEntry vanishing = entries.get(0);
        SamplerSchedule.Sink sink = new SamplerSchedule.Sink() {

            @Override
            public void sample(SensorEntry entry) {
                sampled.add(entry);
                if (entry == vanishing) {
                    schedule.remove(entry);
                }
            }

            @Override
            public void skip(SensorEntry entry) {
                throw new IllegalStateException("unexpected skip of " + entry);
            }
        };

        schedule.tick(10 * MILLISECOND, true, sink);

        assertEquals(new HashSet<>(entries), new HashSet<>(sampled), "every sensor must still be served: " + sampled);
        assertEquals(4, schedule.size(), "the removed sensor must be gone");
        assertEquals(-1, vanishing.bucket());
    }

    @Test
    void clearEmptiesEveryBucketAndTheCarryOverList() {
        FakeNanos nanos = new FakeNanos();
        SamplerSchedule schedule = new SamplerSchedule(20, nanos::get);
        List<SensorEntry> entries = fill(schedule, 30);
        schedule.tick(0L, true, new RecordingSink(nanos, PER_SAMPLE_NANOS));
        assertTrue(schedule.carriedOver() > 0, "the zero budget must carry something over");

        schedule.clear();

        assertEquals(0, schedule.size());
        assertEquals(0, schedule.carriedOver());
        for (SensorEntry entry : entries) {
            assertEquals(-1, entry.bucket());
            assertEquals(-1, entry.bucketSlot());
        }
    }
}
