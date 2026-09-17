package io.github.ldogg123.gregscope.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.Fixtures;
import io.github.ldogg123.gregscope.model.StateCodes;

class SecondRingTest {

    private static final int T0 = 1_759_107_600;

    private static void running(SecondRing ring, int sec, long eu) {
        ring.appendSample(sec, StateCodes.RUNNING, SecondRing.FLAG_ACTIVE | SecondRing.FLAG_HAS_EU, 0, 0.5, eu, 0);
    }

    @Test
    void emptyRing() {
        SecondRing ring = new SecondRing();
        assertEquals(0, ring.size());
        assertTrue(ring.isEmpty());
        assertEquals(0, ring.newest(10, Long.MAX_VALUE).length);
        assertEquals(0, ring.window(0, Long.MAX_VALUE).length);
        assertThrows(IndexOutOfBoundsException.class, () -> ring.epochSec(0));
    }

    @Test
    void appendKeepsHeadOrderOldestFirst() {
        SecondRing ring = new SecondRing();
        for (int i = 0; i < 5; i++) {
            running(ring, T0 + i, 100 + i);
        }
        assertEquals(5, ring.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(T0 + i, ring.epochSec(i));
            assertEquals(100 + i, ring.euPerTick(i));
        }
    }

    @Test
    void wrapDropsOldestAndKeepsCapacity() {
        SecondRing ring = new SecondRing();
        int total = SecondRing.CAPACITY * 2 + 37;
        for (int i = 0; i < total; i++) {
            running(ring, T0 + i, i);
        }
        assertEquals(SecondRing.CAPACITY, ring.size());
        int firstKept = total - SecondRing.CAPACITY;
        for (int i = 0; i < SecondRing.CAPACITY; i++) {
            assertEquals(T0 + firstKept + i, ring.epochSec(i), "index " + i);
            assertEquals(firstKept + i, ring.euPerTick(i));
        }
        assertEquals(T0 + total - 1, ring.epochSec(ring.size() - 1));
    }

    @Test
    void sameSecondDuplicatesAreBothKept() {
        SecondRing ring = new SecondRing();
        running(ring, T0, 1);
        running(ring, T0, 2);
        ring.appendGap(T0, GapReason.PROBE_ERROR);
        assertEquals(3, ring.size());
        assertEquals(T0, ring.epochSec(0));
        assertEquals(T0, ring.epochSec(1));
        assertEquals(1, ring.euPerTick(0));
        assertEquals(2, ring.euPerTick(1));
        assertEquals(GapReason.PROBE_ERROR, ring.gapReason(2));
        assertArrayEquals(new int[] { 0, 1, 2 }, ring.window(T0, T0 + 1));
    }

    @Test
    void clockGoingBackwardsStaysInAppendOrder() {
        SecondRing ring = new SecondRing();
        running(ring, T0 + 10, 1);
        running(ring, T0 + 5, 2);
        running(ring, T0 + 11, 3);
        assertEquals(T0 + 5, ring.epochSec(1));
        assertArrayEquals(new int[] { 0, 1, 2 }, ring.newest(5, T0 + 12));
        assertArrayEquals(new int[] { 1 }, ring.newest(5, T0 + 10));
    }

    @Test
    void newestNBeforeQueries() {
        SecondRing ring = new SecondRing();
        for (int i = 0; i < 400; i++) {
            running(ring, T0 + i, i);
        }
        // The ring holds seconds T0+100 .. T0+399 at logical 0..299.
        assertArrayEquals(new int[] { 297, 298, 299 }, ring.newest(3, Long.MAX_VALUE));
        // before is exclusive.
        int[] beforeSix = ring.newest(3, T0 + 300);
        assertArrayEquals(new int[] { 197, 198, 199 }, beforeSix);
        assertEquals(T0 + 299, ring.epochSec(beforeSix[2]));
        // More requested than exist before the bound: all of them, oldest first.
        int[] early = ring.newest(60, T0 + 103);
        assertArrayEquals(new int[] { 0, 1, 2 }, early);
        assertEquals(0, ring.newest(10, T0 + 100).length);
        assertEquals(0, ring.newest(0, Long.MAX_VALUE).length);
        assertEquals(300, ring.newest(1000, Long.MAX_VALUE).length);
        // Window [from, to).
        int[] window = ring.window(T0 + 390, T0 + 395);
        assertEquals(5, window.length);
        assertEquals(T0 + 390, ring.epochSec(window[0]));
        assertEquals(T0 + 394, ring.epochSec(window[4]));
    }

    @Test
    void sampleValuesAndClamping() {
        SecondRing ring = new SecondRing();
        ring.appendSample(T0, StateCodes.IDLE, 0xFF, 999, 1.7, 5, 6);
        assertEquals(0xFF, ring.flags(0));
        assertTrue(ring.isValid(0));
        assertEquals(255, ring.maintenanceIssues(0));
        assertEquals(10000, ring.progress(0));
        assertNull(ring.gapReason(0));

        ring.appendSample(T0 + 1, StateCodes.IDLE, 0, -3, -0.5, 5, 6);
        assertEquals(SecondRing.FLAG_VALID, ring.flags(1), "b7 is always set for a sample");
        assertEquals(0, ring.maintenanceIssues(1));
        assertEquals(0, ring.progress(1));
        assertEquals(0L, ring.euPerTick(1), "EU without hasEu is not stored");
        assertEquals(0L, ring.energyStored(1), "energy without hasEnergy is not stored");

        ring.appendSample(T0 + 2, StateCodes.RUNNING, 0, 0, 0.42, 0, 0);
        assertEquals(4200, ring.progress(2));
        ring.appendSample(T0 + 3, StateCodes.RUNNING, 0, 0, Double.NaN, 0, 0);
        assertEquals(0, ring.progress(3));

        assertThrows(IllegalArgumentException.class, () -> ring.appendSample(T0, 10, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ring.appendGap(T0, GapReason.SERVER_OFFLINE));
        assertThrows(IllegalArgumentException.class, () -> ring.appendGap(T0, null));
    }

    @Test
    void gapEntries() {
        SecondRing ring = new SecondRing();
        for (GapReason reason : GapReason.values()) {
            if (reason.isStored()) {
                ring.appendGap(T0, reason);
            }
        }
        assertEquals(GapReason.STORED_COUNT, ring.size());
        for (int i = 0; i < ring.size(); i++) {
            assertEquals(StateCodes.UNAVAILABLE, ring.stateCode(i));
            assertEquals(i + 1, ring.gapReasonCode(i));
            assertEquals(GapReason.fromBit(i), ring.gapReason(i));
            assertFalse(ring.isValid(i));
            assertEquals(0, ring.flags(i));
        }
    }

    @Test
    void goldenSampleBytes() {
        byte[] golden = Fixtures.hex("v1/second_sample.hex");
        assertEquals(SecondRing.BYTES_PER_SAMPLE, golden.length);
        SecondRing ring = new SecondRing();
        ring.appendSample(
            1_759_107_600,
            StateCodes.RUNNING,
            SecondRing.FLAG_ACTIVE | SecondRing.FLAG_ALLOWED_TO_WORK
                | SecondRing.FLAG_FORMED_KNOWN
                | SecondRing.FLAG_FORMED
                | SecondRing.FLAG_HAS_EU
                | SecondRing.FLAG_HAS_ENERGY,
            2,
            0.42,
            -1920,
            1_200_000);
        byte[] out = new byte[SecondRing.BYTES_PER_SAMPLE];
        ring.encode(0, out, 0);
        assertArrayEquals(golden, out);

        SecondRing decoded = new SecondRing();
        decoded.appendEncoded(golden, 0);
        assertEquals(1_759_107_600, decoded.epochSec(0));
        assertEquals(StateCodes.RUNNING, decoded.stateCode(0));
        assertEquals(0xEF, decoded.flags(0));
        assertFalse(decoded.hasFlag(0, SecondRing.FLAG_WAS_SHUTDOWN));
        assertEquals(4200, decoded.progress(0));
        assertEquals(-1920, decoded.euPerTick(0));
        assertEquals(1_200_000, decoded.energyStored(0));
    }

    @Test
    void goldenGapBytes() {
        byte[] golden = Fixtures.hex("v1/second_gap.hex");
        SecondRing ring = new SecondRing();
        ring.appendGap(1_759_107_601, GapReason.TARGET_MISSING);
        byte[] out = new byte[SecondRing.BYTES_PER_SAMPLE];
        ring.encode(0, out, 0);
        assertArrayEquals(golden, out);
    }

    @Test
    void clearEmpties() {
        SecondRing ring = new SecondRing();
        running(ring, T0, 1);
        ring.clear();
        assertEquals(0, ring.size());
        running(ring, T0 + 1, 2);
        assertEquals(T0 + 1, ring.epochSec(0));
    }
}
