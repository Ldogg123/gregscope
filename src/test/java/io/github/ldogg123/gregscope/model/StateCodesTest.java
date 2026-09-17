package io.github.ldogg123.gregscope.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class StateCodesTest {

    /** design-v0.2 §7.1, pinned by state id so that renaming or reordering the enum cannot move a code. */
    private static final Map<String, Integer> PINNED = new LinkedHashMap<>();

    static {
        PINNED.put("unavailable", 0);
        PINNED.put("starting", 1);
        PINNED.put("unformed", 2);
        PINNED.put("shutdown", 3);
        PINNED.put("power_starved", 4);
        PINNED.put("running", 5);
        PINNED.put("disabled", 6);
        PINNED.put("output_blocked", 7);
        PINNED.put("waiting", 8);
        PINNED.put("idle", 9);
    }

    @Test
    void everyStateHasItsPinnedCode() {
        assertEquals(PINNED.size(), MachineState.values().length, "a new state needs a new slotLayout");
        assertEquals(10, StateCodes.COUNT);
        for (MachineState state : MachineState.values()) {
            Integer expected = PINNED.get(state.id());
            assertTrue(expected != null, "unpinned state " + state.id());
            assertEquals(expected.intValue(), StateCodes.code(state), state.id());
        }
    }

    @Test
    void codesRoundTripAndAreDistinct() {
        Set<Integer> seen = new HashSet<>();
        for (MachineState state : MachineState.values()) {
            int code = StateCodes.code(state);
            assertTrue(seen.add(code), "duplicate code " + code);
            assertEquals(state, StateCodes.state(code));
            assertEquals(state.id(), StateCodes.id(code));
            assertTrue(StateCodes.isKnown(code));
        }
        assertEquals(StateCodes.COUNT, seen.size());
    }

    @Test
    void namedConstantsMatchTheTable() {
        assertEquals(0, StateCodes.UNAVAILABLE);
        assertEquals(1, StateCodes.STARTING);
        assertEquals(2, StateCodes.UNFORMED);
        assertEquals(3, StateCodes.SHUTDOWN);
        assertEquals(4, StateCodes.POWER_STARVED);
        assertEquals(5, StateCodes.RUNNING);
        assertEquals(6, StateCodes.DISABLED);
        assertEquals(7, StateCodes.OUTPUT_BLOCKED);
        assertEquals(8, StateCodes.WAITING);
        assertEquals(9, StateCodes.IDLE);
    }

    @Test
    void unknownCodesAndNull() {
        assertNull(StateCodes.state(-1));
        assertNull(StateCodes.state(10));
        assertNull(StateCodes.state(255));
        assertNull(StateCodes.id(10));
        assertFalse(StateCodes.isKnown(10));
        assertFalse(StateCodes.isKnown(-1));
        assertEquals(StateCodes.UNAVAILABLE, StateCodes.code(null));
    }
}
