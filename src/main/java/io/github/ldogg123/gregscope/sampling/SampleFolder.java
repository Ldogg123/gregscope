package io.github.ldogg123.gregscope.sampling;

import java.util.Map;

import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.MinuteAccumulator;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.history.SecondRing;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.SnapshotKeys;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.SensorEntry;

/**
 * Folds one machine sample, or one gap second, into a registry entry's history (design-v0.2 section 6.2): the
 * 300-second ring, the open minute, the per-sensor counters and the entry's cached machine metadata. Server thread
 * only. [pure]
 *
 * <p>
 * The class is deliberately pure, so the arithmetic of a sample can be unit tested with a hand-built
 * {@link MachineSnapshot} and no Minecraft at all; only the probe call above it needs a real machine. It is not named
 * in design-v0.2 section 2, which lists {@code TelemetrySampler} as the whole tick handler; splitting the pure half
 * out is what makes section 6.2 testable.
 *
 * <p>
 * One instance is reused for every sample, so a fold allocates nothing beyond what the accumulator itself does.
 * {@link #closedMinute()} and {@link #metadataChanged()} describe the last fold and must be read before the next one.
 */
public final class SampleFolder {

    private MinuteSlot closedMinute;
    private boolean metadataChanged;
    private long clockSkewRefusedDelta;

    /** The minute the last fold closed, or null. Its bytes are what the caller stores and queues for the file. */
    public MinuteSlot closedMinute() {
        return closedMinute;
    }

    /** True if the last fold changed the entry's cached machine metadata, which makes the registry dirty. */
    public boolean metadataChanged() {
        return metadataChanged;
    }

    /**
     * How many samples or gap seconds the last fold's accumulator refused because their minute was older than the
     * newest written one (design-v0.2 section 6.2 clock skew). Reported as a delta rather than read back off the
     * accumulators when a frame is built, so the section 7.6 total stays correct once an entry is purged, expires or
     * becomes a tombstone and its accumulator is freed.
     */
    public long clockSkewRefusedDelta() {
        return clockSkewRefusedDelta;
    }

    /**
     * Folds one observed sample.
     *
     * @param entry           a LIVE entry with its rings allocated
     * @param snapshot        what the probe read, never null
     * @param epochSec        the sample's wall-clock second, from the GregScope {@link Clock}
     * @param intervalTicks   the sampling interval, for the EU estimates of section 7.6
     * @param serverTickDelta server ticks since this sensor's previous sample, for the minute's {@code serverTicks}
     */
    public void fold(SensorEntry entry, MachineSnapshot snapshot, long epochSec, int intervalTicks,
        int serverTickDelta) {
        closedMinute = null;
        metadataChanged = false;
        clockSkewRefusedDelta = 0L;
        Map<String, Object> map = snapshot.toMap();
        int stateCode = StateCodes.code(snapshot.state());
        boolean hasEu = map.containsKey(SnapshotKeys.EU_PER_TICK);
        long euPerTick = longOf(map, SnapshotKeys.EU_PER_TICK, 0L);
        boolean hasEnergy = map.containsKey(SnapshotKeys.ENERGY_STORED);
        long energyStored = longOf(map, SnapshotKeys.ENERGY_STORED, 0L);
        boolean formedKnown = map.containsKey(SnapshotKeys.FORMED);
        int maintenance = intOf(map, SnapshotKeys.MAINTENANCE_ISSUES, 0);
        long recipesCompleted = map.containsKey(SnapshotKeys.RECIPES_COMPLETED)
            ? longOf(map, SnapshotKeys.RECIPES_COMPLETED, 0L)
            : -1L;

        SecondRing seconds = entry.seconds();
        if (seconds != null) {
            int flags = 0;
            if (boolOf(map, SnapshotKeys.ACTIVE)) {
                flags |= SecondRing.FLAG_ACTIVE;
            }
            if (boolOf(map, SnapshotKeys.ALLOWED_TO_WORK)) {
                flags |= SecondRing.FLAG_ALLOWED_TO_WORK;
            }
            if (formedKnown) {
                flags |= SecondRing.FLAG_FORMED_KNOWN;
                if (boolOf(map, SnapshotKeys.FORMED)) {
                    flags |= SecondRing.FLAG_FORMED;
                }
            }
            if (boolOf(map, SnapshotKeys.WAS_SHUTDOWN)) {
                flags |= SecondRing.FLAG_WAS_SHUTDOWN;
            }
            if (hasEu) {
                flags |= SecondRing.FLAG_HAS_EU;
            }
            if (hasEnergy) {
                flags |= SecondRing.FLAG_HAS_ENERGY;
            }
            seconds.appendSample(
                (int) epochSec,
                stateCode,
                flags,
                maintenance,
                doubleOf(map, SnapshotKeys.PROGRESS, 0.0),
                euPerTick,
                energyStored);
        }

        MinuteAccumulator accumulator = entry.accumulator();
        if (accumulator != null) {
            accumulator.addServerTicks(serverTickDelta);
            long skewBefore = accumulator.clockSkewRefused();
            closedMinute = accumulator
                .sample(epochSec, stateCode, maintenance, hasEu, euPerTick, hasEnergy, energyStored, recipesCompleted);
            clockSkewRefusedDelta = accumulator.clockSkewRefused() - skewBefore;
            if (accumulator.lastSampleResetRecipes() && entry.counters() != null) {
                entry.counters()
                    .onRecipesCounterReset();
            }
        }
        if (entry.counters() != null) {
            entry.counters()
                .onSample(stateCode, hasEu, euPerTick, intervalTicks);
        }

        entry.setLastSnapshot(snapshot);
        entry.setLastSampleEpochSec(epochSec);
        entry.setLastSeenEpochSec(epochSec);
        entry.setLastGapReason(SensorEntry.NO_GAP);
        metadataChanged = entry.setMachineMetadata(
            intOf(map, SnapshotKeys.META_ID, 0),
            stringOf(map, SnapshotKeys.META_NAME),
            stringOf(map, SnapshotKeys.NAME),
            snapshot.statusId());
    }

    /**
     * Records one gap second with a stored reason: a {@code sampling_skipped} budget skip, a {@code probe_error}, or
     * any other stored reason the caller attributes. Leaves {@code lastSnapshot} and the machine metadata alone, so a
     * reader still sees what the machine last looked like.
     */
    public void gap(SensorEntry entry, GapReason reason, long epochSec) {
        closedMinute = null;
        metadataChanged = false;
        clockSkewRefusedDelta = 0L;
        if (reason == null || !reason.isStored()) {
            throw new IllegalArgumentException("not a stored gap reason: " + reason);
        }
        if (entry.seconds() != null) {
            entry.seconds()
                .appendGap((int) epochSec, reason);
        }
        if (entry.counters() != null) {
            entry.counters()
                .onGap(reason, 1L);
        }
        MinuteAccumulator accumulator = entry.accumulator();
        if (accumulator != null) {
            long skewBefore = accumulator.clockSkewRefused();
            closedMinute = accumulator.gap(epochSec, reason);
            clockSkewRefusedDelta = accumulator.clockSkewRefused() - skewBefore;
        }
        entry.setLastGapReason(reason.bit());
    }

    // --- typed map reads; every value is an Integer, Long, Double, Boolean, String or List<String> ---

    private static long longOf(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static int intOf(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static double doubleOf(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static boolean boolOf(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static String stringOf(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : "";
    }
}
