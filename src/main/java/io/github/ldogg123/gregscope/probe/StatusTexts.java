package io.github.ldogg123.gregscope.probe;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import io.github.ldogg123.gregscope.model.MachineState;
import io.github.ldogg123.gregscope.model.StatusIds;

/** English fallback texts for GregScope-owned status IDs and states. Never use these for automation. */
public final class StatusTexts {

    private static final Map<String, String> BY_STATUS_ID = new HashMap<>();
    private static final Map<MachineState, String> BY_STATE = new EnumMap<>(MachineState.class);

    static {
        BY_STATUS_ID.put(StatusIds.MACHINE_UNAVAILABLE, "Machine unavailable");
        BY_STATUS_ID.put(StatusIds.STARTUP_CHECK, "Starting up");
        BY_STATUS_ID.put(StatusIds.STRUCTURE_INCOMPLETE, "Structure incomplete");
        BY_STATUS_ID.put(StatusIds.POWER_STARVED, "Not enough energy");
        BY_STATUS_ID.put(StatusIds.STEAM_STARVED, "Not enough steam");
        BY_STATUS_ID.put(StatusIds.RUNNING, "Running");
        BY_STATUS_ID.put(StatusIds.DISABLED, "Disabled");
        BY_STATUS_ID.put(StatusIds.MACHINE_ERROR, "Machine error");
        BY_STATUS_ID.put(StatusIds.STEAM_VENT_BLOCKED, "Steam vent blocked");
        BY_STATUS_ID.put(StatusIds.ITEM_OUTPUT_FULL, "Item output full");
        BY_STATUS_ID.put(StatusIds.FLUID_OUTPUT_FULL, "Fluid output full");
        BY_STATUS_ID.put(StatusIds.NONE, "Idle");
        BY_STATUS_ID.put(StatusIds.NO_RECIPE, "No valid recipe");
        BY_STATUS_ID.put(StatusIds.SIMPLE_RESULT, "Unspecified");

        BY_STATE.put(MachineState.UNAVAILABLE, "Unavailable");
        BY_STATE.put(MachineState.STARTING, "Starting");
        BY_STATE.put(MachineState.UNFORMED, "Unformed");
        BY_STATE.put(MachineState.SHUTDOWN, "Shut down");
        BY_STATE.put(MachineState.POWER_STARVED, "Power starved");
        BY_STATE.put(MachineState.RUNNING, "Running");
        BY_STATE.put(MachineState.DISABLED, "Disabled");
        BY_STATE.put(MachineState.OUTPUT_BLOCKED, "Output blocked");
        BY_STATE.put(MachineState.WAITING, "Waiting");
        BY_STATE.put(MachineState.IDLE, "Idle");
    }

    private StatusTexts() {}

    @Nullable
    public static String forStatusId(@Nullable String statusId) {
        return statusId == null ? null : BY_STATUS_ID.get(statusId);
    }

    @Nullable
    public static String forState(@Nullable MachineState state) {
        return state == null ? null : BY_STATE.get(state);
    }
}
