package io.github.ldogg123.gregscope.probe;

import io.github.ldogg123.gregscope.model.MachineKind;

/** Healthy idle defaults for tests. */
final class TestReadings {

    private TestReadings() {}

    private static MachineReadings common(MachineKind kind) {
        return new MachineReadings().kind(kind)
            .name("Test Machine")
            .metaName("test.machine")
            .metaId(1234)
            .machineClass("test.TestMachine")
            .dimension(0)
            .x(10)
            .y(64)
            .z(-20)
            .active(false)
            .allowedToWork(true)
            .hasThingsToDo(false)
            .wasShutdown(false)
            .progressTicks(0)
            .maxProgressTicks(0);
    }

    /** Idle electric basic machine with an empty buffer. */
    static MachineReadings basic() {
        return common(MachineKind.SINGLEBLOCK).steamPowered(false)
            .stuttering(false)
            .outputBlockedTicks(0)
            .steamVentBlocked(false)
            .energyStored(0L)
            .energyCapacity(1000L);
    }

    /** Idle steam basic machine. */
    static MachineReadings steamBasic() {
        return common(MachineKind.SINGLEBLOCK).steamPowered(true)
            .stuttering(false)
            .outputBlockedTicks(0)
            .steamVentBlocked(false)
            .steamStored(0L)
            .steamCapacity(32000L);
    }

    /** Formed, idle multiblock with the routine {@code no_recipe} result. */
    static MachineReadings multi() {
        return common(MachineKind.MULTIBLOCK).startupPending(false)
            .formed(true)
            .maintenanceIssues(0)
            .maintenanceChecksEnabled(true)
            .efficiency(1.0)
            .pollutionEmissionFactor(1.0)
            .recipesCompleted(0L)
            .controllerAgeTicks(1000L)
            .lastWorkingTick(400L)
            .recipeCheckResultId("no_recipe")
            .recipeCheckSuccessful(false)
            .recipeCheckPersistsOnShutdown(false)
            .recipeCheckResultText("No valid recipe found")
            .energyStored(0L)
            .energyCapacity(64000L);
    }

    /** Multiblock that is actively running a recipe. */
    static MachineReadings runningMulti() {
        return multi().active(true)
            .hasThingsToDo(true)
            .progressTicks(50)
            .maxProgressTicks(100)
            .recipeCheckResultId("success")
            .recipeCheckSuccessful(true)
            .recipeCheckResultText("Processing")
            .euPerTick(120L);
    }
}
