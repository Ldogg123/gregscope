package io.github.ldogg123.gregscope.history;

import java.util.Locale;

/**
 * Memory and disk ceilings derived from the history layouts (design-v0.2 §7.8), for the startup INFO line and for
 * {@code LayoutSizesTest}. [pure]
 */
public final class SizeCeilings {

    /** History file header (§8.2). */
    public static final int HISTORY_FILE_HEADER_BYTES = 64;
    /** A {@code .gsh} file: header plus 1,440 minute slots, 92,224 B. */
    public static final int HISTORY_FILE_BYTES = HISTORY_FILE_HEADER_BYTES + MinuteRing.BYTES;
    /** §7.8 estimate for the entry, accumulator, counters and last snapshot (about 25 keys). */
    public static final int ENTRY_OVERHEAD_BYTES = 3000;
    /** Per LIVE or UNLOADED sensor. */
    public static final int RAM_BYTES_PER_SENSOR = SecondRing.BYTES + MinuteRing.BYTES + ENTRY_OVERHEAD_BYTES;
    /** §7.8 estimate per tombstone. */
    public static final int RAM_BYTES_PER_TOMBSTONE = 300;
    /** §7.8 estimate of one raw registry entry before gzip. */
    public static final int REGISTRY_BYTES_PER_ENTRY = 250;
    /** One slot written per sensor per minute. */
    public static final int DISK_WRITE_BYTES_PER_SENSOR_MINUTE = MinuteSlot.SIZE;

    private SizeCeilings() {}

    public static long ramBytes(int maxSensors) {
        return (long) maxSensors * RAM_BYTES_PER_SENSOR;
    }

    /** History files for {@code maxSensors}, excluding tombstone files kept for up to the retention time. */
    public static long historyDiskBytes(int maxSensors) {
        return (long) maxSensors * HISTORY_FILE_BYTES;
    }

    public static long registryRawBytes(int maxSensors) {
        return (long) maxSensors * REGISTRY_BYTES_PER_ENTRY;
    }

    public static long diskWriteBytesPerMinute(int maxSensors) {
        return (long) maxSensors * DISK_WRITE_BYTES_PER_SENSOR_MINUTE;
    }

    /** The one startup INFO line (§7.8), in decimal megabytes. */
    public static String describe(int maxSensors) {
        return String.format(
            Locale.ROOT,
            "history ceilings for limits.maxSensors=%d: RAM %.1f MB (+%d B per tombstone), history files %.1f MB"
                + " (+ tombstone files within the retention time), registry %.0f KB raw, writes %.1f KB/min",
            maxSensors,
            ramBytes(maxSensors) / 1e6,
            RAM_BYTES_PER_TOMBSTONE,
            historyDiskBytes(maxSensors) / 1e6,
            registryRawBytes(maxSensors) / 1e3,
            diskWriteBytesPerMinute(maxSensors) / 1e3);
    }
}
