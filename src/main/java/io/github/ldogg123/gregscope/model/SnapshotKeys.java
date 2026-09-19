package io.github.ldogg123.gregscope.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Snapshot map keys, their canonical order, and the keys every snapshot must contain. [pure] */
public final class SnapshotKeys {

    public static final String SCHEMA_VERSION = "schemaVersion";
    public static final String KIND = "kind";
    public static final String STATE = "state";
    public static final String STATUS_ID = "statusId";
    public static final String STATUS_TEXT = "statusText";
    public static final String NAME = "name";
    public static final String META_NAME = "metaName";
    public static final String META_ID = "metaId";
    public static final String MACHINE_CLASS = "machineClass";
    public static final String DIMENSION = "dimension";
    public static final String X = "x";
    public static final String Y = "y";
    public static final String Z = "z";
    public static final String ACTIVE = "active";
    public static final String ALLOWED_TO_WORK = "allowedToWork";
    public static final String HAS_THINGS_TO_DO = "hasThingsToDo";
    public static final String WAS_SHUTDOWN = "wasShutdown";
    public static final String PROGRESS_TICKS = "progressTicks";
    public static final String MAX_PROGRESS_TICKS = "maxProgressTicks";
    public static final String PROGRESS = "progress";
    public static final String WARNINGS = "warnings";

    public static final String SHUTDOWN_REASON_ID = "shutdownReasonId";
    public static final String SHUTDOWN_CRITICAL = "shutdownCritical";
    public static final String EU_PER_TICK = "euPerTick";
    public static final String STEAM_PER_TICK = "steamPerTick";
    public static final String ENERGY_STORED = "energyStored";
    public static final String ENERGY_CAPACITY = "energyCapacity";
    public static final String STEAM_STORED = "steamStored";
    public static final String STEAM_CAPACITY = "steamCapacity";
    public static final String FORMED = "formed";
    public static final String MAINTENANCE_ISSUES = "maintenanceIssues";
    public static final String MAINTENANCE_CHECKS_ENABLED = "maintenanceChecksEnabled";
    public static final String EFFICIENCY = "efficiency";
    public static final String POLLUTION_EMISSION_FACTOR = "pollutionEmissionFactor";
    public static final String RECIPES_COMPLETED = "recipesCompleted";
    public static final String CONTROLLER_AGE_TICKS = "controllerAgeTicks";
    public static final String TICKS_SINCE_LAST_WORK = "ticksSinceLastWork";
    public static final String RECIPE_CHECK_RESULT_ID = "recipeCheckResultId";
    public static final String RECIPE_CHECK_SUCCESSFUL = "recipeCheckSuccessful";
    public static final String RECIPE_CHECK_RESULT_TEXT = "recipeCheckResultText";
    public static final String OUTPUT_BLOCKED_TICKS = "outputBlockedTicks";
    public static final String STUTTERING = "stuttering";

    // --- v0.3 machine buffers (design-v0.3-buffers section 3). Additive: schema stays v1. ---

    /**
     * What the machine holds on its input side, as {@code key=amount/capacity} strings, biggest first, at most
     * {@code BufferCollector.TOP_K} of them plus an {@code other=} rollup. The capacity is omitted when the holder
     * reports none. A list of strings rather than a nested table because a snapshot value is a scalar or a
     * {@code List<String>}, and that shape is what every consumer - OpenComputers included - already handles.
     */
    public static final String INPUTS = "inputs";
    /** The same for the output side. */
    public static final String OUTPUTS = "outputs";
    /** Everything on the input side, summed, including what the {@code other} rollup covers. */
    public static final String INPUT_TOTAL = "inputTotal";
    public static final String OUTPUT_TOTAL = "outputTotal";
    /** Room across every input buffer that reported one; 0 when none did. */
    public static final String INPUT_CAPACITY = "inputCapacity";
    public static final String OUTPUT_CAPACITY = "outputCapacity";
    /**
     * How full the input side is, 0.0-1.0, or {@code NaN} when nothing reported a capacity. The number worth
     * alerting on: falling, on a machine that is running, is a machine about to starve. It is a <b>level</b>, never
     * a rate - GregScope cannot measure throughput, and section 1 of the design says why.
     */
    public static final String INPUT_SATURATION = "inputSaturation";
    public static final String OUTPUT_SATURATION = "outputSaturation";
    /**
     * How many inputs come from an ME network. Their fluids carry real network amounts; their item busses do not
     * yet, so a non-zero count is also the answer to "why does this machine look empty".
     */
    public static final String ME_INPUTS = "meInputs";

    /** Canonical key order of every snapshot map. */
    public static final List<String> ORDER = Collections.unmodifiableList(
        Arrays.asList(
            SCHEMA_VERSION,
            KIND,
            STATE,
            STATUS_ID,
            STATUS_TEXT,
            NAME,
            META_NAME,
            META_ID,
            MACHINE_CLASS,
            DIMENSION,
            X,
            Y,
            Z,
            ACTIVE,
            ALLOWED_TO_WORK,
            HAS_THINGS_TO_DO,
            WAS_SHUTDOWN,
            PROGRESS_TICKS,
            MAX_PROGRESS_TICKS,
            PROGRESS,
            WARNINGS,
            SHUTDOWN_REASON_ID,
            SHUTDOWN_CRITICAL,
            EU_PER_TICK,
            STEAM_PER_TICK,
            ENERGY_STORED,
            ENERGY_CAPACITY,
            STEAM_STORED,
            STEAM_CAPACITY,
            FORMED,
            MAINTENANCE_ISSUES,
            MAINTENANCE_CHECKS_ENABLED,
            EFFICIENCY,
            POLLUTION_EMISSION_FACTOR,
            RECIPES_COMPLETED,
            CONTROLLER_AGE_TICKS,
            TICKS_SINCE_LAST_WORK,
            RECIPE_CHECK_RESULT_ID,
            RECIPE_CHECK_SUCCESSFUL,
            RECIPE_CHECK_RESULT_TEXT,
            OUTPUT_BLOCKED_TICKS,
            STUTTERING,
            INPUTS,
            OUTPUTS,
            INPUT_TOTAL,
            OUTPUT_TOTAL,
            INPUT_CAPACITY,
            OUTPUT_CAPACITY,
            INPUT_SATURATION,
            OUTPUT_SATURATION,
            ME_INPUTS));

    /** Keys present on every snapshot. */
    public static final Set<String> REQUIRED = Collections.unmodifiableSet(
        new LinkedHashSet<>(
            Arrays.asList(
                SCHEMA_VERSION,
                KIND,
                STATE,
                STATUS_ID,
                STATUS_TEXT,
                NAME,
                META_NAME,
                META_ID,
                MACHINE_CLASS,
                DIMENSION,
                X,
                Y,
                Z,
                ACTIVE,
                ALLOWED_TO_WORK,
                HAS_THINGS_TO_DO,
                WAS_SHUTDOWN,
                PROGRESS_TICKS,
                MAX_PROGRESS_TICKS,
                PROGRESS,
                WARNINGS)));

    private SnapshotKeys() {}
}
