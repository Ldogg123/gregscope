package io.github.ldogg123.gregscope.model;

/**
 * Status and warning IDs owned by GregScope. GT-derived IDs (shutdown reasons, recipe check results) are passed through
 * verbatim and are not listed here. [pure]
 */
public final class StatusIds {

    public static final String MACHINE_UNAVAILABLE = "machine_unavailable";
    public static final String STARTUP_CHECK = "startup_check";
    public static final String STRUCTURE_INCOMPLETE = "structure_incomplete";
    public static final String POWER_STARVED = "power_starved";
    public static final String STEAM_STARVED = "steam_starved";
    public static final String RUNNING = "running";
    public static final String DISABLED = "disabled";
    public static final String MACHINE_ERROR = "machine_error";
    public static final String STEAM_VENT_BLOCKED = "steam_vent_blocked";
    public static final String ITEM_OUTPUT_FULL = "item_output_full";
    public static final String FLUID_OUTPUT_FULL = "fluid_output_full";
    public static final String NONE = "none";
    public static final String NO_RECIPE = "no_recipe";
    public static final String SIMPLE_RESULT = "simple_result";

    public static final String WARNING_MAINTENANCE = "maintenance";
    public static final String WARNING_WORK_DISABLED = "work_disabled";

    private StatusIds() {}
}
