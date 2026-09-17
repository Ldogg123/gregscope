package io.github.ldogg123.gregscope.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Immutable GregScope settings (design-v0.2 §12.3). Read once in preInit; a change needs a restart. [pure]
 *
 * <p>
 * {@link #fromRaw} turns the raw strings of the config file into settings: out-of-range integers are clamped (or
 * snapped to the nearest allowed value), unparseable values fall back to the default, and every adjustment is reported
 * in a single warning. {@link Builder#build} instead rejects out-of-range values, because it serves code and test
 * hooks, not users.
 */
public final class Settings {

    public static final Settings DEFAULTS = builder().build();

    private final boolean samplingEnabled;
    private final int intervalTicks;
    private final int tickBudgetMicros;
    private final int maxSensors;
    private final int maxSensorsPerTeam;
    private final int maxOpenHubViews;
    private final boolean historyPersist;
    private final int removedRetentionHours;
    private final int staleExpiryDays;
    private final int ioQueueCapacity;
    private final int opLevel;
    private final boolean renameRequiresOfficer;
    private final int renameCooldownSeconds;

    private Settings(Builder b) {
        samplingEnabled = b.samplingEnabled;
        intervalTicks = b.intervalTicks;
        tickBudgetMicros = b.tickBudgetMicros;
        maxSensors = b.maxSensors;
        maxSensorsPerTeam = b.maxSensorsPerTeam;
        maxOpenHubViews = b.maxOpenHubViews;
        historyPersist = b.historyPersist;
        removedRetentionHours = b.removedRetentionHours;
        staleExpiryDays = b.staleExpiryDays;
        ioQueueCapacity = b.ioQueueCapacity;
        opLevel = b.opLevel;
        renameRequiresOfficer = b.renameRequiresOfficer;
        renameCooldownSeconds = b.renameCooldownSeconds;
    }

    /**
     * Builds settings from raw config strings keyed by {@link ConfigKeys.Key#path()}. A missing key takes its default
     * silently. If any present value had to be adjusted, {@code warn} is called exactly once with a message naming
     * every adjusted key; otherwise it is not called.
     */
    public static Settings fromRaw(Map<String, String> raw, Consumer<String> warn) {
        List<String> adjustments = new ArrayList<>();
        Builder b = builder();
        b.samplingEnabled = readBoolean(ConfigKeys.SAMPLING_ENABLED, raw, adjustments);
        b.intervalTicks = readInt(ConfigKeys.SAMPLING_INTERVAL_TICKS, raw, adjustments);
        b.tickBudgetMicros = readInt(ConfigKeys.SAMPLING_TICK_BUDGET_MICROS, raw, adjustments);
        b.maxSensors = readInt(ConfigKeys.LIMITS_MAX_SENSORS, raw, adjustments);
        b.maxSensorsPerTeam = readInt(ConfigKeys.LIMITS_MAX_SENSORS_PER_TEAM, raw, adjustments);
        b.maxOpenHubViews = readInt(ConfigKeys.LIMITS_MAX_OPEN_HUB_VIEWS, raw, adjustments);
        b.historyPersist = readBoolean(ConfigKeys.HISTORY_PERSIST, raw, adjustments);
        b.removedRetentionHours = readInt(ConfigKeys.HISTORY_REMOVED_RETENTION_HOURS, raw, adjustments);
        b.staleExpiryDays = readInt(ConfigKeys.HISTORY_STALE_EXPIRY_DAYS, raw, adjustments);
        b.ioQueueCapacity = readInt(ConfigKeys.HISTORY_IO_QUEUE_CAPACITY, raw, adjustments);
        b.opLevel = readInt(ConfigKeys.PERMISSIONS_OP_LEVEL, raw, adjustments);
        b.renameRequiresOfficer = readBoolean(ConfigKeys.PERMISSIONS_RENAME_REQUIRES_OFFICER, raw, adjustments);
        b.renameCooldownSeconds = readInt(ConfigKeys.HUB_RENAME_COOLDOWN_SECONDS, raw, adjustments);
        if (!adjustments.isEmpty()) {
            StringBuilder message = new StringBuilder("gregscope.cfg: ").append(adjustments.size())
                .append(adjustments.size() == 1 ? " value was" : " values were")
                .append(" invalid or out of range and adjusted: ");
            for (int i = 0; i < adjustments.size(); i++) {
                if (i > 0) {
                    message.append("; ");
                }
                message.append(adjustments.get(i));
            }
            warn.accept(message.toString());
        }
        return b.build();
    }

    private static int readInt(ConfigKeys.Key key, Map<String, String> raw, List<String> adjustments) {
        String text = raw.get(key.path());
        if (text == null) {
            return key.defaultInt();
        }
        int value;
        try {
            // Same parse as Forge's Property.isIntValue/getInt.
            value = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            adjustments.add(key.path() + "=\"" + text + "\" is not an integer, using default " + key.defaultInt());
            return key.defaultInt();
        }
        if (key.accepts(value)) {
            return value;
        }
        int adjusted = key.nearest(value);
        adjustments.add(key.path() + "=" + value + " is not allowed, using " + adjusted);
        return adjusted;
    }

    private static boolean readBoolean(ConfigKeys.Key key, Map<String, String> raw, List<String> adjustments) {
        String text = raw.get(key.path());
        if (text == null) {
            return key.defaultBoolean();
        }
        // Same accepted spellings as Forge's Property.isBooleanValue.
        String lower = text.trim()
            .toLowerCase(Locale.ROOT);
        if ("true".equals(lower)) {
            return true;
        }
        if ("false".equals(lower)) {
            return false;
        }
        adjustments.add(key.path() + "=\"" + text + "\" is not true or false, using default " + key.defaultBoolean());
        return key.defaultBoolean();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.samplingEnabled = samplingEnabled;
        b.intervalTicks = intervalTicks;
        b.tickBudgetMicros = tickBudgetMicros;
        b.maxSensors = maxSensors;
        b.maxSensorsPerTeam = maxSensorsPerTeam;
        b.maxOpenHubViews = maxOpenHubViews;
        b.historyPersist = historyPersist;
        b.removedRetentionHours = removedRetentionHours;
        b.staleExpiryDays = staleExpiryDays;
        b.ioQueueCapacity = ioQueueCapacity;
        b.opLevel = opLevel;
        b.renameRequiresOfficer = renameRequiresOfficer;
        b.renameCooldownSeconds = renameCooldownSeconds;
        return b;
    }

    public boolean samplingEnabled() {
        return samplingEnabled;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    public int tickBudgetMicros() {
        return tickBudgetMicros;
    }

    public int maxSensors() {
        return maxSensors;
    }

    public int maxSensorsPerTeam() {
        return maxSensorsPerTeam;
    }

    public int maxOpenHubViews() {
        return maxOpenHubViews;
    }

    public boolean historyPersist() {
        return historyPersist;
    }

    public int removedRetentionHours() {
        return removedRetentionHours;
    }

    public int staleExpiryDays() {
        return staleExpiryDays;
    }

    public int ioQueueCapacity() {
        return ioQueueCapacity;
    }

    public int opLevel() {
        return opLevel;
    }

    public boolean renameRequiresOfficer() {
        return renameRequiresOfficer;
    }

    public int renameCooldownSeconds() {
        return renameCooldownSeconds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Settings)) {
            return false;
        }
        Settings s = (Settings) o;
        return samplingEnabled == s.samplingEnabled && intervalTicks == s.intervalTicks
            && tickBudgetMicros == s.tickBudgetMicros
            && maxSensors == s.maxSensors
            && maxSensorsPerTeam == s.maxSensorsPerTeam
            && maxOpenHubViews == s.maxOpenHubViews
            && historyPersist == s.historyPersist
            && removedRetentionHours == s.removedRetentionHours
            && staleExpiryDays == s.staleExpiryDays
            && ioQueueCapacity == s.ioQueueCapacity
            && opLevel == s.opLevel
            && renameRequiresOfficer == s.renameRequiresOfficer
            && renameCooldownSeconds == s.renameCooldownSeconds;
    }

    @Override
    public int hashCode() {
        int h = samplingEnabled ? 1 : 0;
        h = 31 * h + intervalTicks;
        h = 31 * h + tickBudgetMicros;
        h = 31 * h + maxSensors;
        h = 31 * h + maxSensorsPerTeam;
        h = 31 * h + maxOpenHubViews;
        h = 31 * h + (historyPersist ? 1 : 0);
        h = 31 * h + removedRetentionHours;
        h = 31 * h + staleExpiryDays;
        h = 31 * h + ioQueueCapacity;
        h = 31 * h + opLevel;
        h = 31 * h + (renameRequiresOfficer ? 1 : 0);
        h = 31 * h + renameCooldownSeconds;
        return h;
    }

    @Override
    public String toString() {
        return "Settings{sampling.enabled=" + samplingEnabled
            + ", sampling.intervalTicks="
            + intervalTicks
            + ", sampling.tickBudgetMicros="
            + tickBudgetMicros
            + ", limits.maxSensors="
            + maxSensors
            + ", limits.maxSensorsPerTeam="
            + maxSensorsPerTeam
            + ", limits.maxOpenHubViews="
            + maxOpenHubViews
            + ", history.persist="
            + historyPersist
            + ", history.removedRetentionHours="
            + removedRetentionHours
            + ", history.staleExpiryDays="
            + staleExpiryDays
            + ", history.ioQueueCapacity="
            + ioQueueCapacity
            + ", permissions.opLevel="
            + opLevel
            + ", permissions.renameRequiresOfficer="
            + renameRequiresOfficer
            + ", hub.renameCooldownSeconds="
            + renameCooldownSeconds
            + "}";
    }

    /** Starts from the defaults. {@link #build} rejects values the config file would not allow. */
    public static final class Builder {

        private boolean samplingEnabled = ConfigKeys.SAMPLING_ENABLED.defaultBoolean();
        private int intervalTicks = ConfigKeys.SAMPLING_INTERVAL_TICKS.defaultInt();
        private int tickBudgetMicros = ConfigKeys.SAMPLING_TICK_BUDGET_MICROS.defaultInt();
        private int maxSensors = ConfigKeys.LIMITS_MAX_SENSORS.defaultInt();
        private int maxSensorsPerTeam = ConfigKeys.LIMITS_MAX_SENSORS_PER_TEAM.defaultInt();
        private int maxOpenHubViews = ConfigKeys.LIMITS_MAX_OPEN_HUB_VIEWS.defaultInt();
        private boolean historyPersist = ConfigKeys.HISTORY_PERSIST.defaultBoolean();
        private int removedRetentionHours = ConfigKeys.HISTORY_REMOVED_RETENTION_HOURS.defaultInt();
        private int staleExpiryDays = ConfigKeys.HISTORY_STALE_EXPIRY_DAYS.defaultInt();
        private int ioQueueCapacity = ConfigKeys.HISTORY_IO_QUEUE_CAPACITY.defaultInt();
        private int opLevel = ConfigKeys.PERMISSIONS_OP_LEVEL.defaultInt();
        private boolean renameRequiresOfficer = ConfigKeys.PERMISSIONS_RENAME_REQUIRES_OFFICER.defaultBoolean();
        private int renameCooldownSeconds = ConfigKeys.HUB_RENAME_COOLDOWN_SECONDS.defaultInt();

        private Builder() {}

        public Builder samplingEnabled(boolean value) {
            samplingEnabled = value;
            return this;
        }

        public Builder intervalTicks(int value) {
            intervalTicks = value;
            return this;
        }

        public Builder tickBudgetMicros(int value) {
            tickBudgetMicros = value;
            return this;
        }

        public Builder maxSensors(int value) {
            maxSensors = value;
            return this;
        }

        public Builder maxSensorsPerTeam(int value) {
            maxSensorsPerTeam = value;
            return this;
        }

        public Builder maxOpenHubViews(int value) {
            maxOpenHubViews = value;
            return this;
        }

        public Builder historyPersist(boolean value) {
            historyPersist = value;
            return this;
        }

        public Builder removedRetentionHours(int value) {
            removedRetentionHours = value;
            return this;
        }

        public Builder staleExpiryDays(int value) {
            staleExpiryDays = value;
            return this;
        }

        public Builder ioQueueCapacity(int value) {
            ioQueueCapacity = value;
            return this;
        }

        public Builder opLevel(int value) {
            opLevel = value;
            return this;
        }

        public Builder renameRequiresOfficer(boolean value) {
            renameRequiresOfficer = value;
            return this;
        }

        public Builder renameCooldownSeconds(int value) {
            renameCooldownSeconds = value;
            return this;
        }

        /** @throws IllegalArgumentException if a value is outside what §12.3 allows */
        public Settings build() {
            check(ConfigKeys.SAMPLING_INTERVAL_TICKS, intervalTicks);
            check(ConfigKeys.SAMPLING_TICK_BUDGET_MICROS, tickBudgetMicros);
            check(ConfigKeys.LIMITS_MAX_SENSORS, maxSensors);
            check(ConfigKeys.LIMITS_MAX_SENSORS_PER_TEAM, maxSensorsPerTeam);
            check(ConfigKeys.LIMITS_MAX_OPEN_HUB_VIEWS, maxOpenHubViews);
            check(ConfigKeys.HISTORY_REMOVED_RETENTION_HOURS, removedRetentionHours);
            check(ConfigKeys.HISTORY_STALE_EXPIRY_DAYS, staleExpiryDays);
            check(ConfigKeys.HISTORY_IO_QUEUE_CAPACITY, ioQueueCapacity);
            check(ConfigKeys.PERMISSIONS_OP_LEVEL, opLevel);
            check(ConfigKeys.HUB_RENAME_COOLDOWN_SECONDS, renameCooldownSeconds);
            return new Settings(this);
        }

        private static void check(ConfigKeys.Key key, int value) {
            if (!key.accepts(value)) {
                throw new IllegalArgumentException(key.path() + "=" + value + " is not allowed: " + key.comment());
            }
        }
    }
}
