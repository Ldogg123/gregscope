package io.github.ldogg123.gregscope.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.model.MachineState;
import io.github.ldogg123.gregscope.probe.Classification.TextSource;

class StateClassifierTest {

    private static void assertClass(MachineReadings r, MachineState state, String statusId, TextSource source) {
        Classification c = StateClassifier.classify(r);
        assertEquals(state, c.state(), "state of " + c);
        assertEquals(statusId, c.statusId(), "statusId of " + c);
        assertEquals(source, c.textSource(), "textSource of " + c);
    }

    @Test
    void missingKindIsRejected() {
        assertThrows(IllegalStateException.class, () -> StateClassifier.classify(new MachineReadings()));
    }

    // Individual rules

    @Test
    void r1StartupPending() {
        assertClass(
            TestReadings.multi()
                .startupPending(true),
            MachineState.STARTING,
            "startup_check",
            TextSource.GREGSCOPE);
    }

    @Test
    void r2Unformed() {
        assertClass(
            TestReadings.multi()
                .formed(false),
            MachineState.UNFORMED,
            "structure_incomplete",
            TextSource.GREGSCOPE);
    }

    @Test
    void r2NullFormedIsUnformed() {
        assertClass(
            TestReadings.multi()
                .formed(null),
            MachineState.UNFORMED,
            "structure_incomplete",
            TextSource.GREGSCOPE);
    }

    @Test
    void r3ShutdownWithReason() {
        assertClass(
            TestReadings.multi()
                .wasShutdown(true)
                .shutdownReasonId("power_loss")
                .shutdownCritical(true),
            MachineState.SHUTDOWN,
            "power_loss",
            TextSource.SHUTDOWN_REASON);
    }

    @Test
    void r3NonCriticalNamedReasonOnBasic() {
        assertClass(
            TestReadings.basic()
                .wasShutdown(true)
                .shutdownReasonId("no_repair")
                .shutdownCritical(false),
            MachineState.SHUTDOWN,
            "no_repair",
            TextSource.SHUTDOWN_REASON);
    }

    @Test
    void r3CriticalNoneIsShutdown() {
        assertClass(
            TestReadings.multi()
                .wasShutdown(true)
                .shutdownReasonId("none")
                .shutdownCritical(true),
            MachineState.SHUTDOWN,
            "none",
            TextSource.SHUTDOWN_REASON);
    }

    @Test
    void r3NonCriticalNoneFallsThroughToDisabled() {
        assertClass(
            TestReadings.multi()
                .wasShutdown(true)
                .shutdownReasonId("none")
                .shutdownCritical(false)
                .allowedToWork(false),
            MachineState.DISABLED,
            "disabled",
            TextSource.GREGSCOPE);
    }

    @Test
    void r3NullReasonIdTreatedAsNone() {
        assertClass(
            TestReadings.multi()
                .wasShutdown(true)
                .shutdownReasonId(null)
                .shutdownCritical(true),
            MachineState.SHUTDOWN,
            "none",
            TextSource.SHUTDOWN_REASON);
    }

    @Test
    void r4CrashPersistsOnShutdown() {
        assertClass(
            TestReadings.multi()
                .allowedToWork(false)
                .recipeCheckResultId("crash")
                .recipeCheckSuccessful(false)
                .recipeCheckPersistsOnShutdown(true),
            MachineState.SHUTDOWN,
            "crash",
            TextSource.RECIPE_CHECK);
    }

    @Test
    void r4RequiresWorkDisabled() {
        assertClass(
            TestReadings.multi()
                .recipeCheckResultId("crash")
                .recipeCheckSuccessful(false)
                .recipeCheckPersistsOnShutdown(true),
            MachineState.WAITING,
            "crash",
            TextSource.RECIPE_CHECK);
    }

    @Test
    void r5Stuttering() {
        assertClass(
            TestReadings.basic()
                .stuttering(true)
                .maxProgressTicks(200)
                .progressTicks(10),
            MachineState.POWER_STARVED,
            "power_starved",
            TextSource.GREGSCOPE);
    }

    @Test
    void r5NegativeProgressWithoutStutterFlag() {
        assertClass(
            TestReadings.basic()
                .stuttering(false)
                .maxProgressTicks(200)
                .progressTicks(-100),
            MachineState.POWER_STARVED,
            "power_starved",
            TextSource.GREGSCOPE);
    }

    @Test
    void r5SteamStarved() {
        assertClass(
            TestReadings.steamBasic()
                .stuttering(true)
                .maxProgressTicks(200)
                .progressTicks(-100),
            MachineState.POWER_STARVED,
            "steam_starved",
            TextSource.GREGSCOPE);
    }

    @Test
    void r6Running() {
        assertClass(TestReadings.runningMulti(), MachineState.RUNNING, "running", TextSource.GREGSCOPE);
    }

    @Test
    void r7Disabled() {
        assertClass(
            TestReadings.basic()
                .allowedToWork(false),
            MachineState.DISABLED,
            "disabled",
            TextSource.GREGSCOPE);
    }

    @Test
    void r8SteamVentBlocked() {
        assertClass(
            TestReadings.steamBasic()
                .steamVentBlocked(true),
            MachineState.OUTPUT_BLOCKED,
            "steam_vent_blocked",
            TextSource.GREGSCOPE);
    }

    @Test
    void r9OutputBlockedWithoutRecipe() {
        assertClass(
            TestReadings.basic()
                .outputBlockedTicks(5),
            MachineState.OUTPUT_BLOCKED,
            "item_output_full",
            TextSource.GREGSCOPE);
    }

    @Test
    void r9RequiresNoHeldRecipe() {
        assertClass(
            TestReadings.basic()
                .outputBlockedTicks(5)
                .maxProgressTicks(100),
            MachineState.IDLE,
            "none",
            TextSource.GREGSCOPE);
    }

    @Test
    void r10ItemOutputFull() {
        assertClass(
            TestReadings.multi()
                .recipeCheckResultId("item_output_full"),
            MachineState.OUTPUT_BLOCKED,
            "item_output_full",
            TextSource.RECIPE_CHECK);
    }

    @Test
    void r10FluidOutputFull() {
        assertClass(
            TestReadings.multi()
                .recipeCheckResultId("fluid_output_full"),
            MachineState.OUTPUT_BLOCKED,
            "fluid_output_full",
            TextSource.RECIPE_CHECK);
    }

    @Test
    void r11UnknownFutureIdIsWaiting() {
        assertClass(
            TestReadings.multi()
                .recipeCheckResultId("some_future_mod.new_condition")
                .recipeCheckSuccessful(false),
            MachineState.WAITING,
            "some_future_mod.new_condition",
            TextSource.RECIPE_CHECK);
    }

    @Test
    void r11OddIdsKeptVerbatim() {
        assertClass(
            TestReadings.multi()
                .recipeCheckResultId("gtnhlanth.noaccel")
                .recipeCheckSuccessful(false),
            MachineState.WAITING,
            "gtnhlanth.noaccel",
            TextSource.RECIPE_CHECK);
        assertClass(
            TestReadings.multi()
                .recipeCheckResultId("EEC_nospawner")
                .recipeCheckSuccessful(false),
            MachineState.WAITING,
            "EEC_nospawner",
            TextSource.RECIPE_CHECK);
    }

    @Test
    void r11NullSuccessfulUnknownIdIsWaiting() {
        assertClass(
            TestReadings.multi()
                .recipeCheckResultId("some.unknown")
                .recipeCheckSuccessful(null),
            MachineState.WAITING,
            "some.unknown",
            TextSource.RECIPE_CHECK);
    }

    @Test
    void r5bActiveBasicWithMachineErrorsIsWaiting() {
        assertClass(
            TestReadings.basic()
                .active(true)
                .maxProgressTicks(100)
                .machineErrors(true),
            MachineState.WAITING,
            "machine_error",
            TextSource.GREGSCOPE);
    }

    @Test
    void r5bRequiresActive() {
        assertClass(
            TestReadings.basic()
                .machineErrors(true),
            MachineState.IDLE,
            "none",
            TextSource.GREGSCOPE);
    }

    @Test
    void r5BeatsR5b() {
        assertClass(
            TestReadings.basic()
                .active(true)
                .maxProgressTicks(100)
                .stuttering(true)
                .machineErrors(true),
            MachineState.POWER_STARVED,
            "power_starved",
            TextSource.GREGSCOPE);
    }

    @Test
    void r12NoRecipeIsIdle() {
        assertClass(TestReadings.multi(), MachineState.IDLE, "no_recipe", TextSource.RECIPE_CHECK);
    }

    @Test
    void r12NoneIsIdle() {
        assertClass(
            TestReadings.multi()
                .recipeCheckResultId("none"),
            MachineState.IDLE,
            "none",
            TextSource.RECIPE_CHECK);
        assertClass(
            TestReadings.multi()
                .recipeCheckResultId(null)
                .recipeCheckSuccessful(null),
            MachineState.IDLE,
            "none",
            TextSource.RECIPE_CHECK);
    }

    @Test
    void r12SuccessTypedAdHocIdIsIdle() {
        assertClass(
            TestReadings.multi()
                .recipeCheckResultId("no_scrap")
                .recipeCheckSuccessful(true),
            MachineState.IDLE,
            "no_scrap",
            TextSource.RECIPE_CHECK);
    }

    @Test
    void r13BasicIdle() {
        assertClass(TestReadings.basic(), MachineState.IDLE, "none", TextSource.GREGSCOPE);
    }

    // Precedence

    @Test
    void r1BeatsR2() {
        assertClass(
            TestReadings.multi()
                .startupPending(true)
                .formed(false),
            MachineState.STARTING,
            "startup_check",
            TextSource.GREGSCOPE);
    }

    @Test
    void r1BeatsR3() {
        assertClass(
            TestReadings.multi()
                .startupPending(true)
                .wasShutdown(true)
                .shutdownReasonId("power_loss")
                .shutdownCritical(true),
            MachineState.STARTING,
            "startup_check",
            TextSource.GREGSCOPE);
    }

    @Test
    void r2BeatsR3() {
        assertClass(
            TestReadings.multi()
                .formed(false)
                .wasShutdown(true)
                .shutdownReasonId("structure_incomplete")
                .shutdownCritical(false),
            MachineState.UNFORMED,
            "structure_incomplete",
            TextSource.GREGSCOPE);
    }

    @Test
    void r3BeatsR4() {
        assertClass(
            TestReadings.multi()
                .allowedToWork(false)
                .wasShutdown(true)
                .shutdownReasonId("power_loss")
                .shutdownCritical(true)
                .recipeCheckResultId("crash")
                .recipeCheckPersistsOnShutdown(true),
            MachineState.SHUTDOWN,
            "power_loss",
            TextSource.SHUTDOWN_REASON);
    }

    @Test
    void r5BeatsR6WhenActiveAndStuttering() {
        assertClass(
            TestReadings.basic()
                .active(true)
                .stuttering(true)
                .maxProgressTicks(200)
                .progressTicks(20),
            MachineState.POWER_STARVED,
            "power_starved",
            TextSource.GREGSCOPE);
    }

    @Test
    void r5RequiresPositiveMaxProgress() {
        assertClass(
            TestReadings.steamBasic()
                .stuttering(true)
                .maxProgressTicks(0),
            MachineState.IDLE,
            "none",
            TextSource.GREGSCOPE);
    }

    @Test
    void r5RequiresAllowedToWork() {
        assertClass(
            TestReadings.basic()
                .allowedToWork(false)
                .stuttering(true)
                .maxProgressTicks(200)
                .progressTicks(-100),
            MachineState.DISABLED,
            "disabled",
            TextSource.GREGSCOPE);
    }

    @Test
    void r6BeatsR7() {
        assertClass(
            TestReadings.runningMulti()
                .allowedToWork(false),
            MachineState.RUNNING,
            "running",
            TextSource.GREGSCOPE);
    }

    @Test
    void r6BeatsR9StaleOutputBlocked() {
        assertClass(
            TestReadings.basic()
                .active(true)
                .outputBlockedTicks(40)
                .maxProgressTicks(0),
            MachineState.RUNNING,
            "running",
            TextSource.GREGSCOPE);
    }

    @Test
    void staleOutputFullWithWorkDisabledIsDisabled() {
        assertClass(
            TestReadings.multi()
                .allowedToWork(false)
                .recipeCheckResultId("item_output_full"),
            MachineState.DISABLED,
            "disabled",
            TextSource.GREGSCOPE);
    }

    @Test
    void r7BeatsR8() {
        assertClass(
            TestReadings.steamBasic()
                .allowedToWork(false)
                .steamVentBlocked(true),
            MachineState.DISABLED,
            "disabled",
            TextSource.GREGSCOPE);
    }

    @Test
    void r7BeatsR9() {
        assertClass(
            TestReadings.basic()
                .allowedToWork(false)
                .outputBlockedTicks(5)
                .maxProgressTicks(0),
            MachineState.DISABLED,
            "disabled",
            TextSource.GREGSCOPE);
    }
}
