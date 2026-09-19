package io.github.ldogg123.gregscope.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MachineSnapshotTest {

    /** Sets every key, in reverse canonical order. */
    private static MachineSnapshot.Builder fullReversed() {
        return MachineSnapshot.builder()
            // v0.3 buffers, set here because this fixture's contract is "every key, in reverse", and the test
            // asserts the built map iterates in SnapshotKeys.ORDER regardless of the order they were set in.
            .meInputs(2)
            .outputSaturation(0.25D)
            .inputSaturation(0.5D)
            .outputCapacity(32_000L)
            .inputCapacity(128_000L)
            .outputTotal(8000L)
            .inputTotal(4000L)
            .outputs(Arrays.asList("f:lava=8000/32000"))
            .inputs(Arrays.asList("f:water=4000/128000"))
            .stuttering(false)
            .outputBlockedTicks(0)
            .recipeCheckResultText("text")
            .recipeCheckSuccessful(true)
            .recipeCheckResultId("success")
            .ticksSinceLastWork(3L)
            .controllerAgeTicks(10L)
            .recipesCompleted(2L)
            .pollutionEmissionFactor(0.5)
            .efficiency(1.25)
            .maintenanceChecksEnabled(true)
            .maintenanceIssues(0)
            .formed(true)
            .steamCapacity(1L)
            .steamStored(1L)
            .energyCapacity(1L)
            .energyStored(1L)
            .steamPerTick(1L)
            .euPerTick(-1L)
            .shutdownCritical(false)
            .shutdownReasonId("none")
            .warnings(new ArrayList<String>())
            .progress(0.5)
            .maxProgressTicks(10)
            .progressTicks(5)
            .wasShutdown(true)
            .hasThingsToDo(true)
            .allowedToWork(true)
            .active(true)
            .z(3)
            .y(2)
            .x(1)
            .dimension(-1)
            .machineClass("a.B")
            .metaId(7)
            .metaName("meta")
            .name("Name")
            .statusText("Running")
            .statusId("running")
            .state(MachineState.RUNNING)
            .kind(MachineKind.MULTIBLOCK);
    }

    private static MachineSnapshot.Builder minimal() {
        return MachineSnapshot.builder()
            .kind(MachineKind.SINGLEBLOCK)
            .state(MachineState.IDLE)
            .statusId("none")
            .statusText("Idle")
            .name("Name")
            .metaName("meta")
            .metaId(7)
            .machineClass("a.B")
            .dimension(0)
            .x(1)
            .y(2)
            .z(3)
            .active(false)
            .allowedToWork(true)
            .hasThingsToDo(false)
            .wasShutdown(false)
            .progressTicks(0)
            .maxProgressTicks(0)
            .progress(0.0)
            .warnings(new ArrayList<String>());
    }

    @Test
    void keyOrderFollowsCanonicalOrder() {
        Map<String, Object> map = fullReversed().build()
            .toMap();
        assertEquals(SnapshotKeys.ORDER, new ArrayList<>(map.keySet()));
    }

    @Test
    void minimalSnapshotHasOnlyRequiredKeysInOrder() {
        MachineSnapshot s = minimal().build();
        List<String> expected = new ArrayList<>();
        for (String key : SnapshotKeys.ORDER) {
            if (SnapshotKeys.REQUIRED.contains(key)) {
                expected.add(key);
            }
        }
        assertEquals(
            expected,
            new ArrayList<>(
                s.toMap()
                    .keySet()));
        assertEquals(
            1,
            s.toMap()
                .get("schemaVersion"));
        assertEquals(MachineKind.SINGLEBLOCK, s.kind());
        assertEquals(MachineState.IDLE, s.state());
        assertEquals("none", s.statusId());
    }

    @Test
    void optionalKeysAreOmittedAndNullClearsAKey() {
        Map<String, Object> map = minimal().recipeCheckResultText("x")
            .recipeCheckResultText(null)
            .build()
            .toMap();
        assertFalse(map.containsKey("recipeCheckResultText"));
        assertFalse(map.containsKey("euPerTick"));
    }

    @Test
    void missingRequiredKeyThrows() {
        for (String required : Arrays.asList("name", "statusText", "warnings")) {
            MachineSnapshot.Builder b = minimal();
            if ("name".equals(required)) {
                b.name(null);
            } else if ("statusText".equals(required)) {
                b.statusText(null);
            } else {
                b.warnings(null);
            }
            IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
            assertTrue(
                e.getMessage()
                    .contains(required));
        }
        assertThrows(IllegalStateException.class, () -> new MachineSnapshot.Builder().build());
    }

    @Test
    void mapIsUnmodifiable() {
        Map<String, Object> map = minimal().build()
            .toMap();
        assertThrows(UnsupportedOperationException.class, () -> map.put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> map.remove("name"));
    }

    @Test
    void warningsAreDefensivelyCopiedAndUnmodifiable() {
        List<String> source = new ArrayList<>();
        source.add("maintenance");
        MachineSnapshot s = minimal().warnings(source)
            .build();
        source.add("work_disabled");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) s.toMap()
            .get("warnings");
        assertEquals(Arrays.asList("maintenance"), warnings);
        assertThrows(UnsupportedOperationException.class, () -> warnings.add("x"));
    }

    @Test
    void nullWarningElementRejected() {
        assertThrows(IllegalArgumentException.class, () -> minimal().warnings(Arrays.asList("a", null)));
    }

    @Test
    void orderAndRequiredAreConsistent() {
        assertTrue(SnapshotKeys.ORDER.containsAll(SnapshotKeys.REQUIRED));
        assertEquals(
            SnapshotKeys.ORDER.size(),
            SnapshotKeys.ORDER.stream()
                .distinct()
                .count());
        assertThrows(UnsupportedOperationException.class, () -> SnapshotKeys.ORDER.add("x"));
    }
}
