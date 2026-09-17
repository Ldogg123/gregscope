package io.github.ldogg123.gregscope.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SnapshotParsingTest {

    @Test
    void parseLong() {
        assertNull(Numbers.parseLong(null));
        assertNull(Numbers.parseLong(""));
        assertNull(Numbers.parseLong("  "));
        assertEquals(Long.valueOf(12), Numbers.parseLong("12"));
        assertEquals(Long.valueOf(-5), Numbers.parseLong("-5"));
        assertEquals(Long.valueOf(7), Numbers.parseLong("+7"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), Numbers.parseLong("9223372036854775807"));
        assertNull(Numbers.parseLong("1.5"));
        assertNull(Numbers.parseLong("9223372036854775808"));
        assertNull(Numbers.parseLong("abc"));
    }

    @Test
    void parseFiniteDouble() {
        assertNull(Numbers.parseFiniteDouble(null));
        assertNull(Numbers.parseFiniteDouble(""));
        assertNull(Numbers.parseFiniteDouble("  "));
        assertNull(Numbers.parseFiniteDouble("NaN"));
        assertNull(Numbers.parseFiniteDouble("Infinity"));
        assertNull(Numbers.parseFiniteDouble("-Infinity"));
        assertNull(Numbers.parseFiniteDouble("garbage"));
        assertNull(Numbers.parseFiniteDouble("1e999"));
        assertEquals(Double.valueOf(0.949999988079071), Numbers.parseFiniteDouble("0.949999988079071"));
        assertEquals(Double.valueOf(1000.0), Numbers.parseFiniteDouble("1e3"));
        assertEquals(Double.valueOf(-2.5), Numbers.parseFiniteDouble("-2.5"));
        assertEquals(Double.valueOf(12.0), Numbers.parseFiniteDouble("12"));
    }

    @Test
    void mapDefaults() {
        Map<String, String> info = new HashMap<>();
        info.put("energyUsage", "4096");
        info.put("pollution", "0.75");
        info.put("bad", "x");
        assertEquals(4096L, Numbers.longOrDefault(info, "energyUsage", -1L));
        assertEquals(-1L, Numbers.longOrDefault(info, "missing", -1L));
        assertEquals(-1L, Numbers.longOrDefault(info, "bad", -1L));
        assertEquals(-1L, Numbers.longOrDefault(null, "energyUsage", -1L));
        assertEquals(0.75, Numbers.doubleOrDefault(info, "pollution", 9.0));
        assertEquals(9.0, Numbers.doubleOrDefault(info, "missing", 9.0));
        assertEquals(9.0, Numbers.doubleOrDefault(null, "pollution", 9.0));
    }

    @Test
    void saturatingAdd() {
        assertEquals(5L, Numbers.saturatingAdd(2L, 3L));
        assertEquals(-1L, Numbers.saturatingAdd(2L, -3L));
        assertEquals(Long.MAX_VALUE, Numbers.saturatingAdd(Long.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, Numbers.saturatingAdd(Long.MAX_VALUE - 1, Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, Numbers.saturatingAdd(Long.MIN_VALUE, -1L));
        assertEquals(Long.MIN_VALUE, Numbers.saturatingAdd(Long.MIN_VALUE + 1, Long.MIN_VALUE));
        assertEquals(-1L, Numbers.saturatingAdd(Long.MAX_VALUE, Long.MIN_VALUE));
    }

    @Test
    void generatorEuPerTick() {
        assertEquals(-1000L, Numbers.generatorEuPerTick(1000L, 10000, 1L));
        assertEquals(-500L, Numbers.generatorEuPerTick(1000L, 5000, 1L));
        assertEquals(-1250L, Numbers.generatorEuPerTick(1000L, 12500, 1L));
        assertEquals(-4000L, Numbers.generatorEuPerTick(1000L, 10000, 4L));
        assertEquals(-1000L, Numbers.generatorEuPerTick(1000L, 10000, 0L));
        assertEquals(0L, Numbers.generatorEuPerTick(1000L, 0, 1L));
        // eut * efficiency overflows: falls back to dividing first, without wrapping.
        assertEquals(-(Long.MAX_VALUE / 10000L * 10000L), Numbers.generatorEuPerTick(Long.MAX_VALUE, 10000, 1L));
        // Fallback itself overflows (efficiency > 10000): saturates.
        assertEquals(-Long.MAX_VALUE, Numbers.generatorEuPerTick(Long.MAX_VALUE, 20000, 1L));
        // Amperes overflow: saturates.
        assertEquals(-Long.MAX_VALUE, Numbers.generatorEuPerTick(Long.MAX_VALUE / 2, 10000, 4L));
    }

    @Test
    void normalizeReasonIds() {
        assertEquals("structure_incomplete", ReasonIds.normalize("structure_incomplete", "simple_result"));
        assertEquals("gtnhlanth.noaccel", ReasonIds.normalize("gtnhlanth.noaccel", "whatever"));
        assertEquals("missing_item", ReasonIds.normalize("", "missing_item"));
        assertEquals("missing_item", ReasonIds.normalize(null, "missing_item"));
        assertEquals("none", ReasonIds.normalize(null, "simple_result"));
        assertEquals("none", ReasonIds.normalize("", ""));
        assertEquals("none", ReasonIds.normalize(null, null));
        assertEquals("EEC_nospawner", ReasonIds.normalize("EEC_nospawner", null));
        assertEquals(" Padded ", ReasonIds.normalize(" Padded ", null));
    }

    @Test
    void reasonIdPredicates() {
        assertTrue(ReasonIds.isOutputFull("item_output_full"));
        assertTrue(ReasonIds.isOutputFull("fluid_output_full"));
        assertFalse(ReasonIds.isOutputFull("Item_Output_Full"));
        assertFalse(ReasonIds.isOutputFull(null));
        assertTrue(ReasonIds.isBenignWhenIdle("none", false));
        assertTrue(ReasonIds.isBenignWhenIdle("no_recipe", false));
        assertTrue(ReasonIds.isBenignWhenIdle("no_scrap", true));
        assertFalse(ReasonIds.isBenignWhenIdle("no_scrap", false));
        assertFalse(ReasonIds.isBenignWhenIdle("gtnhlanth.noaccel", false));
    }

    @Test
    void stripFormatting() {
        assertNull(Text.stripFormatting(null));
        assertEquals("", Text.stripFormatting(""));
        assertEquals("Electric Blast Furnace", Text.stripFormatting("§aElectric §lBlast§r Furnace"));
        assertEquals("Upper Case", Text.stripFormatting("§AUpper §KCase§R"));
        assertEquals("keep §z", Text.stripFormatting("keep §z"));
        assertEquals("trailing §", Text.stripFormatting("trailing §"));
        assertEquals("plain", Text.stripFormatting("plain"));
    }

    @Test
    void firstNonEmpty() {
        assertNull(Text.firstNonEmpty());
        assertNull(Text.firstNonEmpty((String[]) null));
        assertNull(Text.firstNonEmpty(null, ""));
        assertEquals("b", Text.firstNonEmpty(null, "", "b", "c"));
        assertEquals(" ", Text.firstNonEmpty(" ", "b"));
    }
}
