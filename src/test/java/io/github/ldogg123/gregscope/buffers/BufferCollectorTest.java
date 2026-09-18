package io.github.ldogg123.gregscope.buffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** GS-301: the bounded top-K rules of design-v0.3-buffers section 3, and the honesty rules around them. */
class BufferCollectorTest {

    private static BufferCollector collector() {
        BufferCollector collector = new BufferCollector();
        collector.reset();
        return collector;
    }

    @Test
    void theBiggestHoldingsAreReportedLargestFirst() {
        BufferCollector collector = collector();
        collector.add("f:water", 300L, 16_000L);
        collector.add("f:lava", 900L, 16_000L);
        collector.add("f:oxygen", 600L, 16_000L);
        BufferSet set = collector.build();

        assertEquals(
            3,
            set.top()
                .size());
        assertEquals(
            "f:lava",
            set.top()
                .get(0)
                .key(),
            "largest first");
        assertEquals(
            "f:oxygen",
            set.top()
                .get(1)
                .key());
        assertEquals(
            "f:water",
            set.top()
                .get(2)
                .key());
        assertEquals(0, set.otherCount(), "nothing was displaced");
        assertEquals(1800L, set.totalAmount());
    }

    @Test
    void pastTheTopKTheRestBecomeOtherAndTheTotalsStayExact() {
        BufferCollector collector = collector();
        for (int i = 0; i < 10; i++) {
            collector.add("f:fluid" + i, (i + 1) * 100L, 16_000L);
        }
        BufferSet set = collector.build();

        assertEquals(
            BufferCollector.TOP_K,
            set.top()
                .size(),
            "only the top K are named");
        assertEquals(
            "f:fluid9",
            set.top()
                .get(0)
                .key(),
            "the largest is kept");
        assertEquals(6, set.otherCount(), "the other six are rolled up");
        assertEquals(100L + 200L + 300L + 400L + 500L + 600L, set.otherAmount(), "and summed exactly");
        assertEquals(5500L, set.totalAmount(), "the total covers everything, named or not");
    }

    /** The displaced entry must go to "other", not vanish: totals are the thing saturation is computed from. */
    @Test
    void aDisplacedEntryIsRolledUpRatherThanLost() {
        BufferCollector collector = collector();
        collector.add("f:a", 10L, 0L);
        collector.add("f:b", 20L, 0L);
        collector.add("f:c", 30L, 0L);
        collector.add("f:d", 40L, 0L);
        collector.add("f:big", 999L, 0L);
        BufferSet set = collector.build();

        assertEquals(1, set.otherCount(), "the smallest was displaced");
        assertEquals(10L, set.otherAmount(), "and it was the 10, not something else");
        assertEquals(1099L, set.totalAmount());
        assertTrue(
            set.top()
                .stream()
                .anyMatch(r -> "f:big".equals(r.key())),
            "the big one made the cut");
        assertFalse(
            set.top()
                .stream()
                .anyMatch(r -> "f:a".equals(r.key())),
            "the small one did not");
    }

    /** An empty tank with room in it is exactly the state worth showing, so its capacity must still count. */
    @Test
    void anEmptyTankStillContributesItsCapacity() {
        BufferCollector collector = collector();
        collector.add("f:water", 0L, 16_000L);
        BufferSet set = collector.build();

        assertEquals(0L, set.totalAmount());
        assertEquals(16_000L, set.totalCapacity(), "the room is real even though the tank is empty");
        assertEquals(0.0D, set.saturation(), "an empty tank with room is 0% full, not unmeasurable");
        assertTrue(
            set.top()
                .isEmpty(),
            "nothing is held, so nothing is named");
        assertFalse(set.isEmpty(), "but the machine does have a buffer, which is worth reporting");
    }

    @Test
    void saturationIsNaNWhenNothingReportsACapacity() {
        BufferCollector collector = collector();
        collector.add("i:minecraft:cobblestone:0", 64L, BufferReading.UNKNOWN_CAPACITY);
        BufferSet set = collector.build();

        assertTrue(
            Double.isNaN(set.saturation()),
            "a bus slot reports no capacity, and 'unmeasurable' must not render as 'empty'");
        assertEquals(64L, set.totalAmount(), "the amount is still exact");
    }

    @Test
    void saturationIsClampedAndExact() {
        BufferCollector collector = collector();
        collector.add("f:water", 8000L, 16_000L);
        assertEquals(
            0.5D,
            collector.build()
                .saturation(),
            1e-9);

        BufferCollector over = collector();
        over.add("f:water", 20_000L, 16_000L);
        assertEquals(
            1.0D,
            over.build()
                .saturation(),
            1e-9,
            "a handler over its own cap reads as full, not 125%");
    }

    /** The ME rule: counted as present, never as an amount. */
    @Test
    void anMeBackedInputIsFlaggedAndContributesNoAmount() {
        BufferCollector collector = collector();
        collector.add("f:water", 500L, 16_000L);
        collector.addMeBacked();
        collector.addMeBacked();
        BufferSet set = collector.build();

        assertEquals(2, set.meBacked());
        assertTrue(set.hasMeBacked());
        assertEquals(500L, set.totalAmount(), "an ME hatch's local buffer must not be counted as supply");
        assertEquals(16_000L, set.totalCapacity(), "nor its capacity");
        assertEquals(
            1,
            set.top()
                .size(),
            "and it is not named as a resource");
    }

    @Test
    void anMeOnlyMachineIsNotEmptyButHasNothingToReport() {
        BufferCollector collector = collector();
        collector.addMeBacked();
        BufferSet set = collector.build();

        assertFalse(set.isEmpty(), "there is something true to say: the input is ME-backed");
        assertTrue(
            set.top()
                .isEmpty());
        assertTrue(Double.isNaN(set.saturation()), "nothing measurable, so not 0%");
    }

    @Test
    void aMachineWithNoBuffersAtAllIsEmpty() {
        assertTrue(
            collector().build()
                .isEmpty());
        assertSameEmpty(collector().build());
    }

    private static void assertSameEmpty(BufferSet set) {
        assertEquals(0L, set.totalAmount());
        assertEquals(0L, set.totalCapacity());
        assertTrue(Double.isNaN(set.saturation()));
        assertTrue(
            set.top()
                .isEmpty());
    }

    @Test
    void blankKeysAndNegativeAmountsAreRefusedRatherThanReportedOddly() {
        BufferCollector collector = collector();
        collector.add(null, 100L, 0L);
        collector.add("", 100L, 0L);
        collector.add("f:water", -50L, 0L);
        BufferSet set = collector.build();

        assertEquals(0L, set.totalAmount(), "none of those is a holding");
        assertTrue(
            set.top()
                .isEmpty());
        assertNull(BufferReading.of(null, 1L, 1L));
        assertNull(BufferReading.of("", 1L, 1L));
        assertEquals(
            0L,
            BufferReading.of("f:water", -1L, -1L)
                .amount(),
            "a negative amount clamps to zero");
    }

    @Test
    void theTopListIsUnmodifiable() {
        BufferCollector collector = collector();
        collector.add("f:water", 100L, 0L);
        List<BufferReading> top = collector.build()
            .top();
        assertThrows(UnsupportedOperationException.class, () -> top.add(BufferReading.of("f:lava", 1L, 1L)));
    }

    /** The collector is reused across machines, so a stale entry would attribute one machine's fluid to another. */
    @Test
    void resetLeavesNothingFromTheMachineBefore() {
        BufferCollector collector = collector();
        collector.add("f:water", 500L, 16_000L);
        collector.addMeBacked();
        collector.build();

        collector.reset();
        BufferSet set = collector.build();
        assertTrue(set.isEmpty(), "a reused collector must not carry the previous machine's contents");
        assertEquals(0, set.meBacked());
        assertEquals(0L, set.totalCapacity());
    }

    @Test
    void aSingleBufferFillRatioIsItsOwn() {
        BufferReading reading = BufferReading.of("f:water", 4000L, 16_000L);
        assertEquals(0.25D, reading.fill(), 1e-9);
        assertTrue(
            Double.isNaN(
                BufferReading.of("i:x:0", 5L, 0L)
                    .fill()),
            "no capacity means unmeasurable");
        assertEquals(
            1.0D,
            BufferReading.of("f:water", 99L, 10L)
                .fill(),
            1e-9,
            "clamped");
    }
}
