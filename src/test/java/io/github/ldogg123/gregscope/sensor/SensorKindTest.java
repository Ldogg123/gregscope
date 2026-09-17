package io.github.ldogg123.gregscope.sensor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Kind codes and labels are persisted and exported, so they are pinned (design-v0.2 §8.2, design-v0.3 §5.1/§6.4). */
class SensorKindTest {

    @Test
    void codesAndLabelsArePinned() {
        assertEquals(0, SensorKind.MACHINE);
        assertEquals(1, SensorKind.ITEM_FLOW);
        assertEquals(2, SensorKind.FLUID_FLOW);
        assertEquals("machine", SensorKind.label(0));
        assertEquals("item_flow", SensorKind.label(1));
        assertEquals("fluid_flow", SensorKind.label(2));
        assertEquals("unknown", SensorKind.label(3));
        assertEquals("unknown", SensorKind.label(-1));
        assertEquals("unknown", SensorKind.label(255));
    }

    @Test
    void v02SupportsOnlyMachineSensors() {
        assertTrue(SensorKind.isSupported(SensorKind.MACHINE));
        assertFalse(SensorKind.isSupported(SensorKind.ITEM_FLOW));
        assertFalse(SensorKind.isSupported(SensorKind.FLUID_FLOW));
        assertFalse(SensorKind.isSupported(7));
    }
}
