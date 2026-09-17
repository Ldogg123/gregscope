package io.github.ldogg123.gregscope.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every key of {@code config/gregscope.cfg} (design-v0.2 §12.3), with its default and its allowed values. [pure]
 *
 * <p>
 * The {@code exporter} category is reserved for v0.4 and is neither read nor written by v0.2.
 */
public final class ConfigKeys {

    public static final String SAMPLING = "sampling";
    public static final String LIMITS = "limits";
    public static final String HISTORY = "history";
    public static final String PERMISSIONS = "permissions";
    public static final String HUB = "hub";

    public static final Key SAMPLING_ENABLED = Key.bool(SAMPLING, "enabled", true, "Sample LIVE sensors.");
    public static final Key SAMPLING_INTERVAL_TICKS = Key.choice(
        SAMPLING,
        "intervalTicks",
        20,
        new int[] { 20, 40, 60, 100 },
        "Server ticks between two samples of one sensor.");
    public static final Key SAMPLING_TICK_BUDGET_MICROS = Key
        .range(SAMPLING, "tickBudgetMicros", 1000, 100, 5000, "Hard sampling budget per server tick, in microseconds.");

    public static final Key LIMITS_MAX_SENSORS = Key.range(
        LIMITS,
        "maxSensors",
        256,
        16,
        1024,
        "Sensors sampled at most, server-wide. Extra sensors are OVER_CAP.");
    public static final Key LIMITS_MAX_SENSORS_PER_TEAM = Key
        .range(LIMITS, "maxSensorsPerTeam", 64, 0, 1024, "Sensors sampled at most per owner team.");
    public static final Key LIMITS_MAX_OPEN_HUB_VIEWS = Key
        .range(LIMITS, "maxOpenHubViews", 32, 1, 256, "Telemetry Hub GUIs open at the same time, server-wide.");

    public static final Key HISTORY_PERSIST = Key
        .bool(HISTORY, "persist", true, "Write minute history and the registry under <world>/gregscope/.");
    public static final Key HISTORY_REMOVED_RETENTION_HOURS = Key
        .range(HISTORY, "removedRetentionHours", 24, 1, 168, "Hours the history of a removed sensor is kept.");
    public static final Key HISTORY_STALE_EXPIRY_DAYS = Key
        .range(HISTORY, "staleExpiryDays", 30, 1, 365, "Days an unloaded sensor is kept before it expires.");
    public static final Key HISTORY_IO_QUEUE_CAPACITY = Key.range(
        HISTORY,
        "ioQueueCapacity",
        4096,
        256,
        65536,
        "Pending disk writes; further writes are dropped and counted.");

    public static final Key PERMISSIONS_OP_LEVEL = Key.range(
        PERMISSIONS,
        "opLevel",
        2,
        0,
        4,
        "Permission level that counts as operator for GregScope. 1-4: players on the server's ops list with at least this level. 0: every player.");
    public static final Key PERMISSIONS_RENAME_REQUIRES_OFFICER = Key
        .bool(PERMISSIONS, "renameRequiresOfficer", false, "Team members must be officers to rename a sensor.");

    public static final Key HUB_RENAME_COOLDOWN_SECONDS = Key
        .range(HUB, "renameCooldownSeconds", 5, 0, 300, "Seconds a player waits between two label changes.");

    /** Every key, in file order. */
    public static final List<Key> ALL = Collections.unmodifiableList(
        new ArrayList<>(
            Arrays.asList(
                SAMPLING_ENABLED,
                SAMPLING_INTERVAL_TICKS,
                SAMPLING_TICK_BUDGET_MICROS,
                LIMITS_MAX_SENSORS,
                LIMITS_MAX_SENSORS_PER_TEAM,
                LIMITS_MAX_OPEN_HUB_VIEWS,
                HISTORY_PERSIST,
                HISTORY_REMOVED_RETENTION_HOURS,
                HISTORY_STALE_EXPIRY_DAYS,
                HISTORY_IO_QUEUE_CAPACITY,
                PERMISSIONS_OP_LEVEL,
                PERMISSIONS_RENAME_REQUIRES_OFFICER,
                HUB_RENAME_COOLDOWN_SECONDS)));

    private ConfigKeys() {}

    /** One config key. Integer keys have either a closed range or a set of allowed values. */
    public static final class Key {

        private final String category;
        private final String name;
        private final boolean bool;
        private final boolean defaultBool;
        private final int defaultInt;
        private final int min;
        private final int max;
        private final int[] allowed;
        private final String description;

        private Key(String category, String name, boolean bool, boolean defaultBool, int defaultInt, int min, int max,
            int[] allowed, String description) {
            this.category = category;
            this.name = name;
            this.bool = bool;
            this.defaultBool = defaultBool;
            this.defaultInt = defaultInt;
            this.min = min;
            this.max = max;
            this.allowed = allowed;
            this.description = description;
        }

        static Key bool(String category, String name, boolean defaultValue, String description) {
            return new Key(category, name, true, defaultValue, 0, 0, 0, null, description);
        }

        static Key range(String category, String name, int defaultValue, int min, int max, String description) {
            return new Key(category, name, false, false, defaultValue, min, max, null, description);
        }

        static Key choice(String category, String name, int defaultValue, int[] allowed, String description) {
            int[] copy = allowed.clone();
            Arrays.sort(copy);
            return new Key(
                category,
                name,
                false,
                false,
                defaultValue,
                copy[0],
                copy[copy.length - 1],
                copy,
                description);
        }

        public String category() {
            return category;
        }

        public String name() {
            return name;
        }

        /** {@code category.name}, as used in log messages and in the raw value map. */
        public String path() {
            return category + "." + name;
        }

        public boolean isBoolean() {
            return bool;
        }

        public boolean defaultBoolean() {
            return defaultBool;
        }

        public int defaultInt() {
            return defaultInt;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        /** The allowed values in ascending order, or null for a closed range. */
        public int[] allowedValues() {
            return allowed == null ? null : allowed.clone();
        }

        /** The comment written above the key, including its allowed values and default. */
        public String comment() {
            if (bool) {
                return description + " [default: " + defaultBool + "]";
            }
            if (allowed != null) {
                StringBuilder values = new StringBuilder();
                for (int value : allowed) {
                    if (values.length() > 0) {
                        values.append(", ");
                    }
                    values.append(value);
                }
                return description + " [allowed: " + values + ", default: " + defaultInt + "]";
            }
            return description + " [range: " + min + " ~ " + max + ", default: " + defaultInt + "]";
        }

        /** True if {@code value} is allowed for this integer key. */
        public boolean accepts(int value) {
            if (allowed != null) {
                return Arrays.binarySearch(allowed, value) >= 0;
            }
            return value >= min && value <= max;
        }

        /**
         * The allowed value closest to {@code value}: a range clamps, a set snaps to the nearest member (a tie goes to
         * the larger value, the less frequent schedule).
         */
        public int nearest(int value) {
            if (allowed == null) {
                return Math.max(min, Math.min(max, value));
            }
            int best = allowed[0];
            for (int candidate : allowed) {
                long distance = Math.abs((long) candidate - value);
                long bestDistance = Math.abs((long) best - value);
                if (distance <= bestDistance) {
                    best = candidate;
                }
            }
            return best;
        }

        @Override
        public String toString() {
            return path();
        }
    }
}
