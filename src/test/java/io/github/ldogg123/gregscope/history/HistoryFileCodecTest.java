package io.github.ldogg123.gregscope.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.Fixtures;

/** GS-109: the {@code .gsh} header and file geometry of design-v0.2 section 8.2, against golden fixtures. */
class HistoryFileCodecTest {

    /** The UUID {@code tools/gen_fixtures.py} wrote into the {@code gsh_header_*} fixtures. */
    private static final UUID GOLDEN_ID = new UUID(0x3FA2C1D0A1B24C3DL, -0x5E6F708192A3B4C5L);
    private static final long GOLDEN_CREATED = 1_759_000_000L;

    @Test
    void crc32CheckValue() {
        assertEquals(0xCBF43926L, Crc32.compute("123456789".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0L, Crc32.compute(new byte[0]));
        byte[] padded = "xx123456789yy".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0xCBF43926L, Crc32.compute(padded, 2, 9));
        assertThrows(IndexOutOfBoundsException.class, () -> Crc32.compute(padded, 10, 9));
    }

    @Test
    void fileSizeIsExactly92224Bytes() {
        assertEquals(64, HistoryFileCodec.HEADER_BYTES);
        assertEquals(64, HistoryFileCodec.SLOT_SIZE);
        assertEquals(1440, HistoryFileCodec.SLOT_COUNT);
        assertEquals(92_224, HistoryFileCodec.FILE_BYTES);
        assertEquals(SizeCeilings.HISTORY_FILE_BYTES, HistoryFileCodec.FILE_BYTES);
        byte[] file = HistoryFileCodec.newFile(GOLDEN_ID, 0, GOLDEN_CREATED);
        assertEquals(92_224, file.length);
        // Body zeroed: epochMinute 0 is "empty" (section 7.4), so a new file is all gaps, not garbage.
        assertArrayEquals(new byte[MinuteRing.BYTES], Arrays.copyOfRange(file, 64, file.length));
        assertEquals(64, HistoryFileCodec.slotOffset(0));
        assertEquals(64 + 1439 * 64, HistoryFileCodec.slotOffset(1439));
        assertThrows(IndexOutOfBoundsException.class, () -> HistoryFileCodec.slotOffset(1440));
        assertThrows(IndexOutOfBoundsException.class, () -> HistoryFileCodec.slotOffset(-1));
    }

    @Test
    void goldenHeaderBytesEncode() {
        byte[] golden = Fixtures.hex("v1/gsh_header_valid.hex");
        assertEquals(HistoryFileCodec.HEADER_BYTES, golden.length);
        byte[] encoded = new byte[HistoryFileCodec.HEADER_BYTES];
        HistoryFileCodec.encodeHeader(HistoryFileCodec.Header.create(GOLDEN_ID, 0, GOLDEN_CREATED), encoded, 0);
        assertArrayEquals(golden, encoded);
        // And the file the codec creates starts with exactly those bytes.
        byte[] file = HistoryFileCodec.newFile(GOLDEN_ID, 0, GOLDEN_CREATED);
        assertArrayEquals(golden, Arrays.copyOf(file, HistoryFileCodec.HEADER_BYTES));
    }

    @Test
    void goldenHeaderBytesDecode() {
        byte[] golden = Fixtures.hex("v1/gsh_header_valid.hex");
        HistoryFileCodec.Header header = HistoryFileCodec.decodeHeader(golden, 0);
        assertEquals(1, header.formatVersion());
        assertEquals(64, header.slotSize());
        assertEquals(1440, header.slotCount());
        assertEquals(0, header.sensorKind());
        assertEquals(SlotLayouts.MACHINE_MINUTE_V1, header.slotLayout());
        assertEquals(GOLDEN_ID, header.id());
        assertEquals(GOLDEN_CREATED, header.createdEpochSec());
        assertTrue(HistoryFileCodec.headerChecksumMatches(golden, 0));
        assertEquals(HistoryFileCodec.Header.create(GOLDEN_ID, 0, GOLDEN_CREATED), header);
    }

    @Test
    void aWholeFileWithTheGoldenHeaderIsOk() {
        assertEquals(
            HistoryFileCodec.Status.OK,
            HistoryFileCodec.inspect(goldenFile("gsh_header_valid.hex"), GOLDEN_ID));
    }

    @Test
    void aBadChecksumIsCorruptAndIsRenamedNotDeleted() {
        byte[] file = goldenFile("gsh_header_bad_crc.hex");
        assertFalse(HistoryFileCodec.headerChecksumMatches(file, 0));
        assertEquals(HistoryFileCodec.Status.CORRUPT, HistoryFileCodec.inspect(file, GOLDEN_ID));
        assertEquals(".corrupt-1700000000000", HistoryFileCodec.Status.CORRUPT.suffix(1, 1_700_000_000_000L));
    }

    @Test
    void badMagicAndAWrongSizeAreCorrupt() {
        byte[] file = goldenFile("gsh_header_valid.hex");
        file[0] = 'X';
        assertEquals(HistoryFileCodec.Status.CORRUPT, HistoryFileCodec.inspect(file, GOLDEN_ID));
        assertEquals(HistoryFileCodec.Status.CORRUPT, HistoryFileCodec.inspect(new byte[0], GOLDEN_ID));
        assertEquals(HistoryFileCodec.Status.CORRUPT, HistoryFileCodec.inspect(null, GOLDEN_ID));
        byte[] truncated = Arrays.copyOf(goldenFile("gsh_header_valid.hex"), HistoryFileCodec.FILE_BYTES - 64);
        assertEquals(HistoryFileCodec.Status.CORRUPT, HistoryFileCodec.inspect(truncated, GOLDEN_ID));
    }

    @Test
    void aNewerFormatVersionIsUnsupportedAndNamesItsVersion() {
        byte[] file = goldenFile("gsh_header_v2.hex");
        assertTrue(HistoryFileCodec.headerChecksumMatches(file, 0));
        assertEquals(HistoryFileCodec.Status.UNSUPPORTED, HistoryFileCodec.inspect(file, GOLDEN_ID));
        int version = HistoryFileCodec.decodeHeader(file, 0)
            .formatVersion();
        assertEquals(2, version);
        assertEquals(".unsupported-v2", HistoryFileCodec.Status.UNSUPPORTED.suffix(version, 0L));
    }

    @Test
    void aReservedFlowLayoutIsUnsupportedNotReinterpreted() {
        byte[] file = goldenFile("gsh_header_flow_layout.hex");
        assertTrue(HistoryFileCodec.headerChecksumMatches(file, 0));
        HistoryFileCodec.Header header = HistoryFileCodec.decodeHeader(file, 0);
        assertEquals(1, header.sensorKind());
        assertEquals(SlotLayouts.ITEM_FLOW_MINUTE_V1, header.slotLayout());
        assertEquals(HistoryFileCodec.Status.UNSUPPORTED, HistoryFileCodec.inspect(file, GOLDEN_ID));
    }

    /** A kind and a layout that do not belong together is a file nobody wrote on purpose: never reinterpret it. */
    @Test
    void aKindLayoutMismatchIsUnsupported() {
        byte[] file = HistoryFileCodec.newFile(GOLDEN_ID, 0, GOLDEN_CREATED);
        file[HistoryFileCodec.OFF_SLOT_LAYOUT] = 2;
        rechecksum(file);
        assertEquals(HistoryFileCodec.Status.UNSUPPORTED, HistoryFileCodec.inspect(file, GOLDEN_ID));

        byte[] geometry = HistoryFileCodec.newFile(GOLDEN_ID, 0, GOLDEN_CREATED);
        geometry[HistoryFileCodec.OFF_SLOT_COUNT + 1] = (byte) 0xA1; // slotCount 0x05A0 (1440) -> 0x05A1 (1441)
        rechecksum(geometry);
        assertEquals(
            1441,
            HistoryFileCodec.decodeHeader(geometry, 0)
                .slotCount());
        assertEquals(HistoryFileCodec.Status.UNSUPPORTED, HistoryFileCodec.inspect(geometry, GOLDEN_ID));
    }

    @Test
    void anotherSensorsFileIsAMismatch() {
        byte[] file = goldenFile("gsh_header_valid.hex");
        UUID other = new UUID(1L, 2L);
        assertEquals(HistoryFileCodec.Status.MISMATCH, HistoryFileCodec.inspect(file, other));
        assertEquals(".mismatch", HistoryFileCodec.Status.MISMATCH.suffix(1, 0L));
        // Without an expected id there is nothing to mismatch (a tool reading a file it found).
        assertEquals(HistoryFileCodec.Status.OK, HistoryFileCodec.inspect(file, null));
        assertThrows(IllegalStateException.class, () -> HistoryFileCodec.Status.OK.suffix(1, 0L));
    }

    @Test
    void slotLayoutsPinTheKindPairing() {
        assertEquals(SlotLayouts.MACHINE_MINUTE_V1, SlotLayouts.forKind(0));
        assertEquals(SlotLayouts.ITEM_FLOW_MINUTE_V1, SlotLayouts.forKind(1));
        assertEquals(SlotLayouts.FLUID_FLOW_MINUTE_V1, SlotLayouts.forKind(2));
        assertEquals(SlotLayouts.NONE, SlotLayouts.forKind(3));
        assertEquals(SlotLayouts.NONE, SlotLayouts.forKind(255));
        assertTrue(SlotLayouts.isSupported(SlotLayouts.MACHINE_MINUTE_V1));
        assertFalse(SlotLayouts.isSupported(SlotLayouts.ITEM_FLOW_MINUTE_V1));
        assertFalse(SlotLayouts.isSupported(SlotLayouts.NONE));
        assertEquals(MinuteSlot.SIZE, SlotLayouts.slotSize(SlotLayouts.MACHINE_MINUTE_V1));
    }

    /** A header the fixture pinned, padded out to a whole file so {@link HistoryFileCodec#inspect} accepts it. */
    private static byte[] goldenFile(String fixture) {
        byte[] header = Fixtures.hex("v1/" + fixture);
        assertEquals(HistoryFileCodec.HEADER_BYTES, header.length);
        byte[] file = new byte[HistoryFileCodec.FILE_BYTES];
        System.arraycopy(header, 0, file, 0, header.length);
        return file;
    }

    private static void rechecksum(byte[] file) {
        LongMath
            .putI32(file, HistoryFileCodec.OFF_CRC, (int) Crc32.compute(file, 0, HistoryFileCodec.HEADER_CRC_COVERED));
    }
}
