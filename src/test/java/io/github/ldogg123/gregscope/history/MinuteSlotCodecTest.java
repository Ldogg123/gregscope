package io.github.ldogg123.gregscope.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.Fixtures;
import io.github.ldogg123.gregscope.model.StateCodes;

class MinuteSlotCodecTest {

    private static final int M = 29_318_460;

    /** The values the independent generator wrote into {@code fixtures/v1/minute_slot_valid.hex}. */
    private static MinuteSlot goldenSlot() {
        return MinuteSlot.builder(M)
            .samples(58)
            .expectedSamples(60)
            .gapMask(GapReason.CHUNK_UNLOADED.mask() | GapReason.TARGET_MISSING.mask())
            .lastStateCode(StateCodes.RUNNING)
            .stateSamples(StateCodes.POWER_STARVED, 2)
            .stateSamples(StateCodes.RUNNING, 50)
            .stateSamples(StateCodes.OUTPUT_BLOCKED, 3)
            .stateSamples(StateCodes.IDLE, 3)
            .maintenanceMax(1)
            .flags(MinuteSlot.FLAG_RECIPES_COUNTER_RESET | MinuteSlot.FLAG_SERVER_START_MINUTE)
            .serverTicks(1180)
            .euSamples(50)
            .euPerTickAvg(1850)
            .euPerTickMin(-64)
            .euPerTickMax(2133)
            .energyStoredLast(1_600_000)
            .recipesCompletedDelta(12)
            .build();
    }

    @Test
    void crcCheckValue() {
        assertEquals(0x29B1, Crc16Ccitt.compute("123456789".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0xFFFF, Crc16Ccitt.compute(new byte[0]));
        byte[] padded = "xx123456789yy".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x29B1, Crc16Ccitt.compute(padded, 2, 9));
        assertThrows(IndexOutOfBoundsException.class, () -> Crc16Ccitt.compute(padded, 10, 9));
    }

    @Test
    void goldenBytesEncode() {
        byte[] golden = Fixtures.hex("v1/minute_slot_valid.hex");
        assertEquals(MinuteSlot.SIZE, golden.length);
        assertArrayEquals(golden, goldenSlot().toBytes());
        assertEquals(0x9F84, Crc16Ccitt.compute(golden, 0, MinuteSlot.CRC_COVERED));
    }

    @Test
    void goldenBytesDecode() {
        MinuteSlot slot = MinuteSlot.decode(Fixtures.hex("v1/minute_slot_valid.hex"), 0);
        assertNotNull(slot);
        assertEquals(goldenSlot(), slot);
        assertEquals(M, slot.epochMinute());
        assertEquals(M * 60L, slot.epochSecond());
        assertEquals(58, slot.samples());
        assertEquals(GapReason.fromMask(0x05), Arrays.asList(GapReason.CHUNK_UNLOADED, GapReason.TARGET_MISSING));
        assertEquals(-64, slot.euPerTickMin());
        assertEquals(1180, slot.serverTicks());
        assertEquals(12, slot.recipesCompletedDelta());
        assertFalse(slot.isGap());
    }

    @Test
    void encodeAtOffsetAndDecodeAtOffset() {
        byte[] buffer = new byte[3 * MinuteSlot.SIZE];
        Arrays.fill(buffer, (byte) 0x5A);
        goldenSlot().encode(buffer, MinuteSlot.SIZE);
        assertEquals(0x5A, buffer[MinuteSlot.SIZE - 1]);
        assertEquals(0x5A, buffer[2 * MinuteSlot.SIZE]);
        assertEquals(goldenSlot(), MinuteSlot.decode(buffer, MinuteSlot.SIZE));
        assertThrows(IndexOutOfBoundsException.class, () -> goldenSlot().encode(buffer, 2 * MinuteSlot.SIZE + 1));
        assertThrows(IndexOutOfBoundsException.class, () -> MinuteSlot.decode(buffer, -1));
    }

    @Test
    void tornSlotReadsAsGap() {
        byte[] torn = Fixtures.hex("v1/minute_slot_torn.hex");
        assertNull(MinuteSlot.decode(torn, 0));
        // Every single-byte corruption of the valid slot is detected.
        byte[] golden = Fixtures.hex("v1/minute_slot_valid.hex");
        for (int i = 0; i < MinuteSlot.SIZE; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] copy = golden.clone();
                copy[i] ^= (byte) (1 << bit);
                assertNull(MinuteSlot.decode(copy, 0), "byte " + i + " bit " + bit);
            }
        }
        // A torn slot inside the ring is a gap for readers.
        MinuteRing ring = new MinuteRing();
        byte[] body = new byte[MinuteRing.BYTES];
        System.arraycopy(torn, 0, body, MinuteRing.index(M) * MinuteSlot.SIZE, MinuteSlot.SIZE);
        assertEquals(0, ring.mergeLoaded(body, 0));
        assertNull(ring.slot(M));
        List<GapRanges.Range> gaps = GapRanges
            .minutes(ring, M, M + 1, GapRanges.Context.of(Arrays.asList(new GapRanges.Run(0, Long.MAX_VALUE))));
        assertEquals(Arrays.asList(new GapRanges.Range(M * 60L, M * 60L + 60, GapReason.UNKNOWN)), gaps);
    }

    @Test
    void emptySlotIsNotValid() {
        assertNull(MinuteSlot.decode(new byte[MinuteSlot.SIZE], 0));
        assertThrows(IllegalArgumentException.class, () -> MinuteSlot.builder(0));
    }

    @Test
    void crcValidButImpossibleContentIsRejected() {
        byte[] golden = Fixtures.hex("v1/minute_slot_valid.hex");
        assertNull(MinuteSlot.decode(recrc(golden, 7, 10), 0), "state code 10");
        assertNull(MinuteSlot.decode(recrc(golden, 6, 0x40), 0), "derived gap bit 6 stored");
        assertNull(MinuteSlot.decode(recrc(golden, 19, 0x08), 0), "undefined flag bit");
        byte[] delta = golden.clone();
        delta[56] = (byte) 0xFF;
        delta[57] = (byte) 0xFF;
        delta[58] = (byte) 0xFF;
        delta[59] = (byte) 0xFE; // -2
        assertNull(MinuteSlot.decode(recrc(delta, 59, 0xFE), 0), "recipes delta -2");
        byte[] reserved = recrc(golden, 23, 0x01);
        assertNotNull(MinuteSlot.decode(reserved, 0), "reserved bytes are not checked");
    }

    private static byte[] recrc(byte[] src, int offset, int value) {
        byte[] copy = src.clone();
        copy[offset] = (byte) value;
        int crc = Crc16Ccitt.compute(copy, 0, MinuteSlot.CRC_COVERED);
        copy[62] = (byte) (crc >>> 8);
        copy[63] = (byte) crc;
        return copy;
    }

    @Test
    void staleEpochIsIgnored() {
        byte[] valid = Fixtures.hex("v1/minute_slot_valid.hex");
        byte[] stale = Fixtures.hex("v1/minute_slot_stale.hex");
        MinuteSlot staleSlot = MinuteSlot.decode(stale, 0);
        assertNotNull(staleSlot, "the stale fixture has a valid CRC");
        assertEquals(M - 1440, staleSlot.epochMinute());
        assertEquals(MinuteRing.index(M), MinuteRing.index(M - 1440));

        assertTrue(MinuteSlot.isWithinWindow(M - 1439, M));
        assertFalse(MinuteSlot.isWithinWindow(M - 1440, M));
        assertFalse(MinuteSlot.isWithinWindow(M + 1, M));

        // A loaded file body holding the stale slot (a day old, CRC valid) and a newer slot M+5: the stale slot is
        // loaded into its index but reads as missing, because it is outside the newest minute's window.
        MinuteRing ring = new MinuteRing();
        byte[] body = new byte[MinuteRing.BYTES];
        System.arraycopy(stale, 0, body, MinuteRing.index(M - 1440) * MinuteSlot.SIZE, MinuteSlot.SIZE);
        byte[] newer = MinuteSlot.builder(M + 5)
            .samples(1)
            .lastStateCode(StateCodes.IDLE)
            .build()
            .toBytes();
        System.arraycopy(newer, 0, body, MinuteRing.index(M + 5) * MinuteSlot.SIZE, MinuteSlot.SIZE);
        assertEquals(2, ring.mergeLoaded(body, 0));
        assertEquals(M + 5, ring.newestEpochMinute());
        assertNull(ring.slot(M - 1440), "outside newest-1439..newest");
        assertNotNull(ring.slot(M + 5));

        // Put refuses a minute that is already stale.
        assertNull(ring.put(MinuteSlot.decode(stale, 0)));
        // A valid slot for M replaces the stale bytes at the shared index.
        assertNotNull(ring.put(MinuteSlot.decode(valid, 0)));
        assertEquals(goldenSlot(), ring.slot(M));
        assertNull(ring.slot(M - 1440));
    }

    @Test
    void ringIndexIsFloorMod() {
        assertEquals(M % 1440, MinuteRing.index(M));
        assertEquals(0, MinuteRing.index(1440));
        assertEquals(1439, MinuteRing.index(-1));
        assertEquals(1, MinuteRing.index(1441));
    }

    @Test
    void ringPutMergesSameMinuteAndStoresBytes() {
        MinuteRing ring = new MinuteRing();
        assertEquals(0, ring.newestEpochMinute());
        assertNull(ring.slot(M));
        MinuteSlot a = MinuteSlot.builder(M)
            .samples(10)
            .expectedSamples(60)
            .lastStateCode(StateCodes.RUNNING)
            .stateSamples(StateCodes.RUNNING, 10)
            .build();
        MinuteSlot b = MinuteSlot.builder(M)
            .samples(5)
            .expectedSamples(60)
            .lastStateCode(StateCodes.IDLE)
            .stateSamples(StateCodes.IDLE, 5)
            .build();
        assertEquals(a, ring.put(a));
        MinuteSlot merged = ring.put(b);
        assertEquals(15, merged.samples());
        assertEquals(merged, ring.slot(M));
        byte[] raw = new byte[MinuteSlot.SIZE];
        ring.copyRaw(MinuteRing.index(M), raw, 0);
        assertArrayEquals(merged.toBytes(), raw, "the ring stores exactly the bytes to write");
        assertEquals(M, ring.newestEpochMinute());
        assertNull(ring.slot(M + 1));
        assertNull(ring.slot(M - 1));
    }

    @Test
    void loadedSlotsNeverOverwriteRam() {
        MinuteRing ring = new MinuteRing();
        MinuteSlot ram = MinuteSlot.builder(M)
            .samples(3)
            .lastStateCode(StateCodes.RUNNING)
            .build();
        ring.put(ram);
        byte[] body = new byte[MinuteRing.BYTES];
        MinuteSlot diskSame = MinuteSlot.builder(M)
            .samples(60)
            .lastStateCode(StateCodes.IDLE)
            .build();
        MinuteSlot diskOther = MinuteSlot.builder(M - 1)
            .samples(60)
            .lastStateCode(StateCodes.IDLE)
            .build();
        diskSame.encode(body, MinuteRing.index(M) * MinuteSlot.SIZE);
        diskOther.encode(body, MinuteRing.index(M - 1) * MinuteSlot.SIZE);
        // A valid slot stored at the wrong index is ignored.
        MinuteSlot misplaced = MinuteSlot.builder(M - 2)
            .samples(1)
            .lastStateCode(StateCodes.IDLE)
            .build();
        misplaced.encode(body, MinuteRing.index(M - 3) * MinuteSlot.SIZE);

        assertEquals(1, ring.mergeLoaded(body, 0));
        assertEquals(ram, ring.slot(M));
        assertEquals(diskOther, ring.slot(M - 1));
        assertNull(ring.slot(M - 2));
        assertNull(ring.slot(M - 3));
    }

    @Test
    void gapReasonIdsAndBitsAreStable() {
        String[] ids = { "chunk_unloaded", "dimension_unloaded", "target_missing", "sampling_skipped", "probe_error",
            "sensor_removed", "server_offline", "unknown" };
        assertEquals(ids.length, GapReason.values().length);
        for (int bit = 0; bit < ids.length; bit++) {
            GapReason reason = GapReason.fromBit(bit);
            assertEquals(ids[bit], reason.id());
            assertEquals(bit, reason.bit());
            assertEquals(1 << bit, reason.mask());
            assertEquals(bit + 1, reason.secondCode());
            assertEquals(reason, GapReason.fromSecondCode(bit + 1));
            assertEquals(reason, GapReason.fromId(ids[bit]));
            assertEquals(bit < 6, reason.isStored());
        }
        assertEquals(0x3F, GapReason.STORED_MASK);
        assertNull(GapReason.fromSecondCode(0));
        assertNull(GapReason.fromBit(8));
        assertNull(GapReason.fromId("lag"));
    }
}
