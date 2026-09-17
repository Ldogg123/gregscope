package io.github.ldogg123.gregscope.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, normalized machine snapshot (schema v1). The map view contains only Integer, Long, Double, Boolean, String
 * and unmodifiable List&lt;String&gt; values, never null, in {@link SnapshotKeys#ORDER}.
 */
public final class MachineSnapshot {

    public static final int SCHEMA_VERSION = 1;

    private final Map<String, Object> map;
    private final MachineKind kind;
    private final MachineState state;
    private final String statusId;

    private MachineSnapshot(Map<String, Object> map, MachineKind kind, MachineState state, String statusId) {
        this.map = map;
        this.kind = kind;
        this.state = state;
        this.statusId = statusId;
    }

    /** Unmodifiable, ordered view of all present keys. */
    public Map<String, Object> toMap() {
        return map;
    }

    public MachineKind kind() {
        return kind;
    }

    public MachineState state() {
        return state;
    }

    public String statusId() {
        return statusId;
    }

    @Override
    public String toString() {
        return "MachineSnapshot" + map;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects values by key; unset (or null) optional keys are omitted from the built snapshot. */
    public static final class Builder {

        private final Map<String, Object> values = new HashMap<>();
        private MachineKind kind;
        private MachineState state;

        public Builder() {
            values.put(SnapshotKeys.SCHEMA_VERSION, SCHEMA_VERSION);
        }

        private Builder put(String key, Object value) {
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
            return this;
        }

        public Builder kind(MachineKind kind) {
            this.kind = kind;
            return put(SnapshotKeys.KIND, kind == null ? null : kind.id());
        }

        public Builder state(MachineState state) {
            this.state = state;
            return put(SnapshotKeys.STATE, state == null ? null : state.id());
        }

        public Builder statusId(String statusId) {
            return put(SnapshotKeys.STATUS_ID, statusId);
        }

        public Builder statusText(String statusText) {
            return put(SnapshotKeys.STATUS_TEXT, statusText);
        }

        public Builder name(String name) {
            return put(SnapshotKeys.NAME, name);
        }

        public Builder metaName(String metaName) {
            return put(SnapshotKeys.META_NAME, metaName);
        }

        public Builder metaId(int metaId) {
            return put(SnapshotKeys.META_ID, metaId);
        }

        public Builder machineClass(String machineClass) {
            return put(SnapshotKeys.MACHINE_CLASS, machineClass);
        }

        public Builder dimension(int dimension) {
            return put(SnapshotKeys.DIMENSION, dimension);
        }

        public Builder x(int x) {
            return put(SnapshotKeys.X, x);
        }

        public Builder y(int y) {
            return put(SnapshotKeys.Y, y);
        }

        public Builder z(int z) {
            return put(SnapshotKeys.Z, z);
        }

        public Builder active(boolean active) {
            return put(SnapshotKeys.ACTIVE, active);
        }

        public Builder allowedToWork(boolean allowedToWork) {
            return put(SnapshotKeys.ALLOWED_TO_WORK, allowedToWork);
        }

        public Builder hasThingsToDo(boolean hasThingsToDo) {
            return put(SnapshotKeys.HAS_THINGS_TO_DO, hasThingsToDo);
        }

        public Builder wasShutdown(boolean wasShutdown) {
            return put(SnapshotKeys.WAS_SHUTDOWN, wasShutdown);
        }

        public Builder progressTicks(int progressTicks) {
            return put(SnapshotKeys.PROGRESS_TICKS, progressTicks);
        }

        public Builder maxProgressTicks(int maxProgressTicks) {
            return put(SnapshotKeys.MAX_PROGRESS_TICKS, maxProgressTicks);
        }

        public Builder progress(double progress) {
            return put(SnapshotKeys.PROGRESS, progress);
        }

        /** Stores an unmodifiable defensive copy; null elements are rejected. */
        public Builder warnings(List<String> warnings) {
            if (warnings == null) {
                return put(SnapshotKeys.WARNINGS, null);
            }
            List<String> copy = new ArrayList<>(warnings);
            if (copy.contains(null)) {
                throw new IllegalArgumentException("warnings must not contain null");
            }
            return put(SnapshotKeys.WARNINGS, Collections.unmodifiableList(copy));
        }

        public Builder shutdownReasonId(String shutdownReasonId) {
            return put(SnapshotKeys.SHUTDOWN_REASON_ID, shutdownReasonId);
        }

        public Builder shutdownCritical(boolean shutdownCritical) {
            return put(SnapshotKeys.SHUTDOWN_CRITICAL, shutdownCritical);
        }

        public Builder euPerTick(long euPerTick) {
            return put(SnapshotKeys.EU_PER_TICK, euPerTick);
        }

        public Builder steamPerTick(long steamPerTick) {
            return put(SnapshotKeys.STEAM_PER_TICK, steamPerTick);
        }

        public Builder energyStored(long energyStored) {
            return put(SnapshotKeys.ENERGY_STORED, energyStored);
        }

        public Builder energyCapacity(long energyCapacity) {
            return put(SnapshotKeys.ENERGY_CAPACITY, energyCapacity);
        }

        public Builder steamStored(long steamStored) {
            return put(SnapshotKeys.STEAM_STORED, steamStored);
        }

        public Builder steamCapacity(long steamCapacity) {
            return put(SnapshotKeys.STEAM_CAPACITY, steamCapacity);
        }

        public Builder formed(boolean formed) {
            return put(SnapshotKeys.FORMED, formed);
        }

        public Builder maintenanceIssues(int maintenanceIssues) {
            return put(SnapshotKeys.MAINTENANCE_ISSUES, maintenanceIssues);
        }

        public Builder maintenanceChecksEnabled(boolean maintenanceChecksEnabled) {
            return put(SnapshotKeys.MAINTENANCE_CHECKS_ENABLED, maintenanceChecksEnabled);
        }

        public Builder efficiency(double efficiency) {
            return put(SnapshotKeys.EFFICIENCY, efficiency);
        }

        public Builder pollutionEmissionFactor(double pollutionEmissionFactor) {
            return put(SnapshotKeys.POLLUTION_EMISSION_FACTOR, pollutionEmissionFactor);
        }

        public Builder recipesCompleted(long recipesCompleted) {
            return put(SnapshotKeys.RECIPES_COMPLETED, recipesCompleted);
        }

        public Builder controllerAgeTicks(long controllerAgeTicks) {
            return put(SnapshotKeys.CONTROLLER_AGE_TICKS, controllerAgeTicks);
        }

        public Builder ticksSinceLastWork(long ticksSinceLastWork) {
            return put(SnapshotKeys.TICKS_SINCE_LAST_WORK, ticksSinceLastWork);
        }

        public Builder recipeCheckResultId(String recipeCheckResultId) {
            return put(SnapshotKeys.RECIPE_CHECK_RESULT_ID, recipeCheckResultId);
        }

        public Builder recipeCheckSuccessful(boolean recipeCheckSuccessful) {
            return put(SnapshotKeys.RECIPE_CHECK_SUCCESSFUL, recipeCheckSuccessful);
        }

        public Builder recipeCheckResultText(String recipeCheckResultText) {
            return put(SnapshotKeys.RECIPE_CHECK_RESULT_TEXT, recipeCheckResultText);
        }

        public Builder outputBlockedTicks(int outputBlockedTicks) {
            return put(SnapshotKeys.OUTPUT_BLOCKED_TICKS, outputBlockedTicks);
        }

        public Builder stuttering(boolean stuttering) {
            return put(SnapshotKeys.STUTTERING, stuttering);
        }

        /**
         * @throws IllegalStateException if any key in {@link SnapshotKeys#REQUIRED} is unset
         */
        public MachineSnapshot build() {
            for (String key : SnapshotKeys.REQUIRED) {
                if (!values.containsKey(key)) {
                    throw new IllegalStateException("Missing required snapshot key: " + key);
                }
            }
            Map<String, Object> ordered = new LinkedHashMap<>();
            for (String key : SnapshotKeys.ORDER) {
                Object value = values.get(key);
                if (value != null) {
                    ordered.put(key, value);
                }
            }
            return new MachineSnapshot(
                Collections.unmodifiableMap(ordered),
                kind,
                state,
                (String) values.get(SnapshotKeys.STATUS_ID));
        }
    }
}
