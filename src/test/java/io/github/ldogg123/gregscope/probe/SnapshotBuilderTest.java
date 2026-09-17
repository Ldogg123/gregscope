package io.github.ldogg123.gregscope.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.MachineState;

class SnapshotBuilderTest {

    private static final List<String> BASIC_ONLY = Arrays
        .asList("steamPerTick", "steamStored", "steamCapacity", "outputBlockedTicks", "stuttering");
    private static final List<String> MULTI_ONLY = Arrays.asList(
        "formed",
        "maintenanceIssues",
        "maintenanceChecksEnabled",
        "efficiency",
        "pollutionEmissionFactor",
        "recipesCompleted",
        "controllerAgeTicks",
        "ticksSinceLastWork",
        "recipeCheckResultId",
        "recipeCheckSuccessful",
        "recipeCheckResultText");

    private static Map<String, Object> map(MachineReadings r) {
        return SnapshotBuilder.build(r)
            .toMap();
    }

    private static double progressOf(int progress, int max) {
        return (Double) map(
            TestReadings.basic()
                .progressTicks(progress)
                .maxProgressTicks(max)).get("progress");
    }

    @Test
    void progressIsClamped() {
        assertEquals(0.25, progressOf(25, 100));
        assertEquals(0.0, progressOf(-100, 100));
        assertEquals(1.0, progressOf(150, 100));
        assertEquals(0.0, progressOf(10, 0));
        assertEquals(0.0, progressOf(10, -5));
        assertEquals(0.0, progressOf(-10, -5));
    }

    @Test
    void rawProgressIsPreserved() {
        Map<String, Object> m = map(
            TestReadings.basic()
                .progressTicks(-100)
                .maxProgressTicks(200));
        assertEquals(-100, m.get("progressTicks"));
        assertEquals(200, m.get("maxProgressTicks"));
    }

    @Test
    void perTickRatesOmittedWhenInactive() {
        Map<String, Object> electric = map(
            TestReadings.basic()
                .euPerTick(30L));
        assertFalse(electric.containsKey("euPerTick"));
        Map<String, Object> steam = map(
            TestReadings.steamBasic()
                .steamPerTick(20L));
        assertFalse(steam.containsKey("steamPerTick"));
        Map<String, Object> multi = map(
            TestReadings.multi()
                .euPerTick(-4096L));
        assertFalse(multi.containsKey("euPerTick"));
    }

    @Test
    void perTickRatesPresentWhenActive() {
        assertEquals(
            30L,
            map(
                TestReadings.basic()
                    .active(true)
                    .euPerTick(30L)).get("euPerTick"));
        assertEquals(
            0L,
            map(
                TestReadings.basic()
                    .active(true)
                    .euPerTick(0L)).get("euPerTick"));
        assertEquals(
            -4096L,
            map(
                TestReadings.runningMulti()
                    .euPerTick(-4096L)).get("euPerTick"));
        assertEquals(
            16L,
            map(
                TestReadings.steamBasic()
                    .active(true)
                    .steamPerTick(16L)).get("steamPerTick"));
    }

    @Test
    void activeWithoutReadingOmitsEuPerTick() {
        assertFalse(
            map(
                TestReadings.runningMulti()
                    .euPerTick(null)).containsKey("euPerTick"));
    }

    @Test
    void ticksSinceLastWorkGating() {
        assertEquals(600L, map(TestReadings.multi()).get("ticksSinceLastWork"));
        assertFalse(map(TestReadings.runningMulti()).containsKey("ticksSinceLastWork"));
        assertFalse(
            map(
                TestReadings.multi()
                    .lastWorkingTick(null)).containsKey("ticksSinceLastWork"));
        assertFalse(
            map(
                TestReadings.multi()
                    .controllerAgeTicks(null)).containsKey("ticksSinceLastWork"));
        assertEquals(
            0L,
            map(
                TestReadings.multi()
                    .controllerAgeTicks(100L)
                    .lastWorkingTick(500L)).get("ticksSinceLastWork"));
    }

    @Test
    void warnings() {
        assertEquals(Collections.emptyList(), map(TestReadings.multi()).get("warnings"));
        assertEquals(
            Collections.singletonList("maintenance"),
            map(
                TestReadings.multi()
                    .maintenanceIssues(2)).get("warnings"));
        assertEquals(
            Collections.singletonList("work_disabled"),
            map(
                TestReadings.runningMulti()
                    .allowedToWork(false)).get("warnings"));
        assertEquals(
            Arrays.asList("maintenance", "work_disabled"),
            map(
                TestReadings.runningMulti()
                    .maintenanceIssues(1)
                    .allowedToWork(false)).get("warnings"));
        // Disabled but not running: no work_disabled warning.
        assertEquals(
            Collections.emptyList(),
            map(
                TestReadings.multi()
                    .allowedToWork(false)).get("warnings"));
        // maintenanceIssues on a basic machine never produces the maintenance warning.
        assertEquals(
            Collections.emptyList(),
            map(
                TestReadings.basic()
                    .maintenanceIssues(3)).get("warnings"));
    }

    @Test
    void warningsListIsUnmodifiable() {
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) map(
            TestReadings.multi()
                .maintenanceIssues(1)).get("warnings");
        assertThrows(UnsupportedOperationException.class, () -> warnings.add("x"));
    }

    @Test
    void statusTextUsesGtRecipeCheckText() {
        Map<String, Object> m = map(
            TestReadings.multi()
                .recipeCheckResultId("gtnhlanth.noaccel")
                .recipeCheckResultText("Not accelerating"));
        assertEquals("waiting", m.get("state"));
        assertEquals("Not accelerating", m.get("statusText"));
    }

    @Test
    void statusTextUsesGtShutdownText() {
        Map<String, Object> m = map(
            TestReadings.multi()
                .wasShutdown(true)
                .shutdownReasonId("power_loss")
                .shutdownCritical(true)
                .shutdownReasonText("Power Loss"));
        assertEquals("Power Loss", m.get("statusText"));
        assertEquals("power_loss", m.get("shutdownReasonId"));
        assertEquals(true, m.get("shutdownCritical"));
    }

    @Test
    void emptyGtTextFallsBackToStateText() {
        Map<String, Object> waiting = map(
            TestReadings.multi()
                .recipeCheckResultId("gtnhlanth.noaccel")
                .recipeCheckResultText(""));
        assertEquals("Waiting", waiting.get("statusText"));
        assertFalse(waiting.containsKey("recipeCheckResultText"));
        Map<String, Object> shutdown = map(
            TestReadings.basic()
                .wasShutdown(true)
                .shutdownReasonId("no_repair")
                .shutdownReasonText(null));
        assertEquals("Shut down", shutdown.get("statusText"));
        // IDs that also have GregScope texts must not use them for GT-sourced rules.
        assertEquals(
            "Shut down",
            map(
                TestReadings.basic()
                    .wasShutdown(true)
                    .shutdownCritical(true)
                    .shutdownReasonId(null)
                    .shutdownReasonText(null)).get("statusText"));
        assertEquals(
            "Idle",
            map(
                TestReadings.multi()
                    .recipeCheckResultId("no_recipe")
                    .recipeCheckResultText("")).get("statusText"));
        assertEquals(
            "Output blocked",
            map(
                TestReadings.multi()
                    .recipeCheckResultId("item_output_full")
                    .recipeCheckResultText("")).get("statusText"));
    }

    @Test
    void gregScopeOwnedTexts() {
        assertEquals(
            "Structure incomplete",
            map(
                TestReadings.multi()
                    .formed(false)).get("statusText"));
        assertEquals(
            "Not enough steam",
            map(
                TestReadings.steamBasic()
                    .stuttering(true)
                    .maxProgressTicks(10)).get("statusText"));
        assertEquals("Idle", map(TestReadings.basic()).get("statusText"));
    }

    @Test
    void shutdownKeysOnlyWhenShutdown() {
        Map<String, Object> m = map(
            TestReadings.basic()
                .shutdownReasonId("power_loss")
                .shutdownCritical(true));
        assertFalse(m.containsKey("shutdownReasonId"));
        assertFalse(m.containsKey("shutdownCritical"));
        Map<String, Object> nullReason = map(
            TestReadings.basic()
                .wasShutdown(true));
        assertEquals("none", nullReason.get("shutdownReasonId"));
        assertEquals(false, nullReason.get("shutdownCritical"));
    }

    @Test
    void kindSpecificKeysDoNotLeak() {
        MachineReadings basicWithMultiValues = TestReadings.basic()
            .active(true)
            .formed(true)
            .maintenanceIssues(1)
            .maintenanceChecksEnabled(true)
            .efficiency(1.0)
            .pollutionEmissionFactor(1.0)
            .recipesCompleted(5L)
            .controllerAgeTicks(10L)
            .lastWorkingTick(1L)
            .recipeCheckResultId("success")
            .recipeCheckSuccessful(true)
            .recipeCheckResultText("ok");
        Map<String, Object> basic = map(basicWithMultiValues.active(false));
        for (String key : MULTI_ONLY) {
            assertFalse(basic.containsKey(key), "basic snapshot leaked " + key);
        }

        MachineReadings multiWithBasicValues = TestReadings.runningMulti()
            .steamPerTick(5L)
            .steamStored(5L)
            .steamCapacity(5L)
            .outputBlockedTicks(5)
            .stuttering(true);
        Map<String, Object> multi = map(multiWithBasicValues);
        for (String key : BASIC_ONLY) {
            assertFalse(multi.containsKey(key), "multi snapshot leaked " + key);
        }
        for (String key : Arrays.asList("formed", "efficiency", "recipeCheckResultId", "controllerAgeTicks")) {
            assertTrue(multi.containsKey(key), "multi snapshot missing " + key);
        }
        for (String key : Arrays.asList("stuttering", "outputBlockedTicks", "energyStored")) {
            assertTrue(map(TestReadings.basic()).containsKey(key), "basic snapshot missing " + key);
        }
    }

    @Test
    void schemaVersionAndIdentity() {
        MachineSnapshot s = SnapshotBuilder.build(TestReadings.multi());
        Map<String, Object> m = s.toMap();
        assertEquals(1, m.get("schemaVersion"));
        assertEquals("multiblock", m.get("kind"));
        assertEquals("singleblock", map(TestReadings.basic()).get("kind"));
        assertEquals(MachineState.IDLE, s.state());
        assertEquals("no_recipe", s.statusId());
        assertEquals(64, m.get("y"));
    }

    @Test
    void copyThroughValues() {
        Map<String, Object> multi = map(
            TestReadings.multi()
                .efficiency(1.25)
                .energyStored(10L)
                .energyCapacity(20L)
                .x(1)
                .y(2)
                .z(3)
                .dimension(-1)
                .metaId(77)
                .pollutionEmissionFactor(0.5)
                .recipesCompleted(9L)
                .maintenanceIssues(2)
                .controllerAgeTicks(1000L)
                .maintenanceChecksEnabled(false));
        assertEquals(1.25, multi.get("efficiency"));
        assertEquals(10L, multi.get("energyStored"));
        assertEquals(20L, multi.get("energyCapacity"));
        assertEquals(1, multi.get("x"));
        assertEquals(2, multi.get("y"));
        assertEquals(3, multi.get("z"));
        assertEquals(-1, multi.get("dimension"));
        assertEquals(77, multi.get("metaId"));
        assertEquals(0.5, multi.get("pollutionEmissionFactor"));
        assertEquals(9L, multi.get("recipesCompleted"));
        assertEquals(2, multi.get("maintenanceIssues"));
        assertEquals(1000L, multi.get("controllerAgeTicks"));
        assertEquals(false, multi.get("maintenanceChecksEnabled"));
        assertEquals("Test Machine", multi.get("name"));
        assertEquals("test.machine", multi.get("metaName"));
        assertEquals("test.TestMachine", multi.get("machineClass"));

        Map<String, Object> steam = map(
            TestReadings.steamBasic()
                .steamStored(5L)
                .steamCapacity(6L)
                .outputBlockedTicks(7));
        assertEquals(5L, steam.get("steamStored"));
        assertEquals(6L, steam.get("steamCapacity"));
        assertEquals(7, steam.get("outputBlockedTicks"));
    }

    @Test
    void missingRequiredReadingFails() {
        assertThrows(
            IllegalStateException.class,
            () -> SnapshotBuilder.build(
                TestReadings.basic()
                    .name(null)));
    }

    @Test
    void noNullsAndOnlyAllowedValueTypes() {
        List<MachineReadings> samples = Arrays.asList(
            TestReadings.basic(),
            TestReadings.steamBasic()
                .active(true)
                .steamPerTick(4L),
            TestReadings.multi(),
            TestReadings.runningMulti()
                .maintenanceIssues(2)
                .allowedToWork(false),
            TestReadings.multi()
                .wasShutdown(true)
                .shutdownReasonId("power_loss")
                .shutdownCritical(true),
            TestReadings.multi()
                .formed(false)
                .recipeCheckResultText(null));
        for (MachineReadings r : samples) {
            for (Map.Entry<String, Object> e : map(r).entrySet()) {
                Object v = e.getValue();
                assertNotNull(v, e.getKey());
                if (v instanceof List) {
                    for (Object element : (List<?>) v) {
                        assertTrue(element instanceof String, e.getKey() + " element type");
                    }
                } else if (!(v instanceof Integer || v instanceof Long
                    || v instanceof Double
                    || v instanceof Boolean
                    || v instanceof String)) {
                        fail(e.getKey() + " has disallowed type " + v.getClass());
                    }
            }
        }
    }
}
