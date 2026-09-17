package io.github.ldogg123.gregscope.probe;

import io.github.ldogg123.gregscope.model.MachineKind;
import io.github.ldogg123.gregscope.model.MachineState;
import io.github.ldogg123.gregscope.model.StatusIds;
import io.github.ldogg123.gregscope.probe.Classification.TextSource;

/** Maps raw readings to a normalized state. Rules are evaluated in order; the first match wins (see docs R1-R13). */
public final class StateClassifier {

    private StateClassifier() {}

    public static Classification classify(MachineReadings r) {
        MachineKind kind = r.kind();
        if (kind == null) {
            throw new IllegalStateException("readings have no kind");
        }
        boolean multi = kind == MachineKind.MULTIBLOCK;
        boolean basic = kind == MachineKind.SINGLEBLOCK;
        String rid = r.recipeCheckResultId() == null ? StatusIds.NONE : r.recipeCheckResultId();
        String shutdownId = r.shutdownReasonId() == null ? StatusIds.NONE : r.shutdownReasonId();
        boolean ridSuccessful = Boolean.TRUE.equals(r.recipeCheckSuccessful());

        // R1
        if (multi && r.startupPending()) {
            return gs(MachineState.STARTING, StatusIds.STARTUP_CHECK);
        }
        // R2
        if (multi && !Boolean.TRUE.equals(r.formed())) {
            return gs(MachineState.UNFORMED, StatusIds.STRUCTURE_INCOMPLETE);
        }
        // R3
        if (r.wasShutdown() && (r.shutdownCritical() || !StatusIds.NONE.equals(shutdownId))) {
            return new Classification(MachineState.SHUTDOWN, shutdownId, TextSource.SHUTDOWN_REASON);
        }
        // R4
        if (multi && !r.allowedToWork() && r.recipeCheckPersistsOnShutdown()) {
            return new Classification(MachineState.SHUTDOWN, rid, TextSource.RECIPE_CHECK);
        }
        // R5
        if (basic && r.allowedToWork()
            && r.maxProgressTicks() > 0
            && (Boolean.TRUE.equals(r.stuttering()) || r.progressTicks() < 0)) {
            return gs(MachineState.POWER_STARVED, r.steamPowered() ? StatusIds.STEAM_STARVED : StatusIds.POWER_STARVED);
        }
        // R5b
        if (basic && r.active() && r.machineErrors()) {
            return gs(MachineState.WAITING, StatusIds.MACHINE_ERROR);
        }
        // R6
        if (r.active()) {
            return gs(MachineState.RUNNING, StatusIds.RUNNING);
        }
        // R7
        if (!r.allowedToWork()) {
            return gs(MachineState.DISABLED, StatusIds.DISABLED);
        }
        // R8
        if (basic && r.steamVentBlocked()) {
            return gs(MachineState.OUTPUT_BLOCKED, StatusIds.STEAM_VENT_BLOCKED);
        }
        // R9
        if (basic && r.outputBlockedTicks() != null && r.outputBlockedTicks() > 0 && r.maxProgressTicks() <= 0) {
            return gs(MachineState.OUTPUT_BLOCKED, StatusIds.ITEM_OUTPUT_FULL);
        }
        if (multi) {
            // R10
            if (ReasonIds.isOutputFull(rid)) {
                return new Classification(MachineState.OUTPUT_BLOCKED, rid, TextSource.RECIPE_CHECK);
            }
            // R11
            if (!ReasonIds.isBenignWhenIdle(rid, ridSuccessful)) {
                return new Classification(MachineState.WAITING, rid, TextSource.RECIPE_CHECK);
            }
            // R12
            return new Classification(MachineState.IDLE, rid, TextSource.RECIPE_CHECK);
        }
        // R13
        return gs(MachineState.IDLE, StatusIds.NONE);
    }

    private static Classification gs(MachineState state, String statusId) {
        return new Classification(state, statusId, TextSource.GREGSCOPE);
    }
}
