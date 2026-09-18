package io.github.ldogg123.gregscope.sampling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.registry.SensorState;

/**
 * The immutable snapshot of the whole registry that the sampler publishes once per interval (design-v0.2 section 7.7),
 * handed to readers through a {@code static volatile} field. [pure]
 *
 * <p>
 * Readers are the Hub view model (server thread), the OpenComputers direct callbacks (computer threads) and the v0.4
 * HTTP thread. A reader takes the field once and then holds one consistent frame; nothing it does can reach the live
 * registry. The sensor list is unmodifiable and sorted by sensor UUID, so paging is stable between frames.
 *
 * <p>
 * Rings are not part of a frame: history is read on the server thread only.
 */
public final class TelemetryFrame {

    /** Sensor UUID order, so a reader's paging is stable across frames. */
    private static final Comparator<SensorView> BY_ID = new Comparator<SensorView>() {

        @Override
        public int compare(SensorView a, SensorView b) {
            return a.id()
                .compareTo(b.id());
        }
    };

    /**
     * The frame a reader sees before the sampler ever published one - which is every reader in the first sampling
     * interval of a server run, and again after a stop. Its {@link #stats()} and {@link #limits()} are real,
     * zero-valued views rather than nulls: {@code /gregscope stats}, the Hub and the OpenComputers callbacks read
     * them without asking whether a frame has been published, and one of them dereferencing a null here would
     * answer an ordinary command with an exception.
     */
    public static final TelemetryFrame EMPTY = new TelemetryFrame(
        0L,
        0L,
        0L,
        0,
        Collections.<SensorView>emptyList(),
        new SamplerStatsView(new SamplerStats(), 0L, 0L),
        new LimitsView(Settings.DEFAULTS));

    private final long sequence;
    private final long publishedNanos;
    private final long publishedEpochMillis;
    private final int intervalTicks;
    private final List<SensorView> sensors;
    private final SamplerStatsView stats;
    private final LimitsView limits;

    /**
     * Copies {@code sensors} into an unmodifiable, id-sorted list, so a later change to the caller's collection cannot
     * reach the frame.
     */
    public TelemetryFrame(long sequence, long publishedNanos, long publishedEpochMillis, int intervalTicks,
        List<SensorView> sensors, SamplerStatsView stats, LimitsView limits) {
        this.sequence = sequence;
        this.publishedNanos = publishedNanos;
        this.publishedEpochMillis = publishedEpochMillis;
        this.intervalTicks = intervalTicks;
        List<SensorView> copy = new ArrayList<>(sensors);
        Collections.sort(copy, BY_ID);
        this.sensors = Collections.unmodifiableList(copy);
        this.stats = stats;
        this.limits = limits;
    }

    /** Strictly increasing within a server run; 0 for {@link #EMPTY}. */
    public long sequence() {
        return sequence;
    }

    /** {@code System.nanoTime()} when the frame was published; only differences are meaningful. */
    public long publishedNanos() {
        return publishedNanos;
    }

    /** Wall-clock publication time, from the GregScope {@link Clock}. */
    public long publishedEpochMillis() {
        return publishedEpochMillis;
    }

    /** The sampling interval in effect when the frame was built. */
    public int intervalTicks() {
        return intervalTicks;
    }

    /** Unmodifiable, sorted by sensor UUID. */
    public List<SensorView> sensors() {
        return sensors;
    }

    /** The first sensor with this id, or null. Linear: a frame holds at most {@code limits.maxSensors} rows. */
    public SensorView sensor(UUID id) {
        if (id == null) {
            return null;
        }
        for (SensorView view : sensors) {
            if (id.equals(view.id())) {
                return view;
            }
        }
        return null;
    }

    /** Never null; all-zero on {@link #EMPTY}. */
    public SamplerStatsView stats() {
        return stats;
    }

    /** Never null; the configured defaults on {@link #EMPTY}. */
    public LimitsView limits() {
        return limits;
    }

    /** How many sensors are in one lifecycle state. */
    public int count(SensorState state) {
        int n = 0;
        for (SensorView view : sensors) {
            if (view.state() == state) {
                n++;
            }
        }
        return n;
    }

    @Override
    public String toString() {
        return "TelemetryFrame{#" + sequence + ", " + sensors.size() + " sensors, interval " + intervalTicks + "}";
    }
}
