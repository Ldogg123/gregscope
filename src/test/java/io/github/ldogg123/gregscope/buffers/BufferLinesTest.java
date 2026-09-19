package io.github.ldogg123.gregscope.buffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The snapshot encoding of design-v0.3-buffers section 3. These strings go into a schema that other people parse,
 * so the format is pinned here rather than left to whatever the formatter happened to produce.
 */
class BufferLinesTest {

    private static BufferSet set(String... entries) {
        BufferCollector collector = new BufferCollector();
        collector.reset();
        for (String entry : entries) {
            String[] parts = entry.split(",");
            collector.add(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        }
        return collector.build();
    }

    @Test
    void aFluidCarriesItsCapacityAndAnItemDoesNot() {
        List<String> lines = BufferLines.of(set("f:water,4000,128000", "i:minecraft:cobblestone:0,17,0"));
        assertEquals("[f:water=4000/128000, i:minecraft:cobblestone:0=17]", lines.toString());
    }

    /** A missing capacity must be absent, not written as /0: they are different facts. */
    @Test
    void noCapacityIsOmittedRatherThanWrittenAsZero() {
        List<String> lines = BufferLines.of(set("i:x:0,5,0"));
        assertEquals("[i:x:0=5]", lines.toString());
        assertTrue(
            !lines.get(0)
                .contains("/"),
            "a slot has no capacity, so no /0 may appear: " + lines);
    }

    @Test
    void largestFirstAndTheRollupLast() {
        BufferCollector collector = new BufferCollector();
        collector.reset();
        for (int i = 1; i <= 6; i++) {
            collector.add("f:f" + i, i * 100L, 0L);
        }
        List<String> lines = BufferLines.of(collector.build());
        assertEquals("f:f6=600", lines.get(0), "largest first");
        assertEquals("other=300x2", lines.get(lines.size() - 1), "the rollup is last and carries amount x count");
    }

    @Test
    void meBackedInputsGetTheirOwnLine() {
        BufferCollector collector = new BufferCollector();
        collector.reset();
        collector.add("f:water", 500L, 16_000L);
        collector.addMeBacked();
        collector.addMeBacked();
        List<String> lines = BufferLines.of(collector.build());
        assertEquals("[f:water=500/16000, me=2]", lines.toString());
    }

    @Test
    void nothingToSayIsAnEmptyListNotANullOrAPlaceholder() {
        assertEquals(
            "[]",
            BufferLines.of(null)
                .toString());
        BufferCollector collector = new BufferCollector();
        collector.reset();
        assertEquals(
            "[]",
            BufferLines.of(collector.build())
                .toString());
    }

    /** An empty tank with room names no resource, but the saturation beside it is what makes that readable. */
    @Test
    void anEmptyTankWithRoomNamesNothing() {
        BufferSet set = set("f:water,0,16000");
        assertEquals(
            "[]",
            BufferLines.of(set)
                .toString());
        assertEquals(0.0D, set.saturation(), 1e-9, "and the caller still reports 0% of 16,000");
    }

    @Test
    void theListIsUnmodifiable() {
        List<String> lines = BufferLines.of(set("f:water,1,2"));
        assertThrows(UnsupportedOperationException.class, () -> lines.add("nope"));
    }
}
