package io.github.ldogg123.gregscope.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.model.StateCodes;

/** design-v0.2 §7.3, §7.4, §7.8 and §8.2 size constants. */
class LayoutSizesTest {

    @Test
    void secondSample() {
        assertEquals(28, SecondRing.BYTES_PER_SAMPLE);
        // i32 + u8 x4 + u16 x2 + i64 x2
        assertEquals(4 + 4 * 1 + 2 * 2 + 2 * 8, SecondRing.BYTES_PER_SAMPLE);
        assertEquals(300, SecondRing.CAPACITY);
        assertEquals(8_400, SecondRing.BYTES);
    }

    @Test
    void minuteSlot() {
        assertEquals(64, MinuteSlot.SIZE);
        // i32 + u8 x4 + u8 x10 + u8 x2 + u16 + u8 x2 + i64 x4 + i32 + u16 + u16 crc
        assertEquals(4 + 4 + StateCodes.COUNT + 2 + 2 + 2 + 4 * 8 + 4 + 2 + 2, MinuteSlot.SIZE);
        assertEquals(62, MinuteSlot.CRC_COVERED);
        assertEquals(1_440, MinuteRing.SLOTS);
        assertEquals(92_160, MinuteRing.BYTES);
    }

    @Test
    void historyFile() {
        assertEquals(64, SizeCeilings.HISTORY_FILE_HEADER_BYTES);
        assertEquals(92_224, SizeCeilings.HISTORY_FILE_BYTES);
    }

    @Test
    void perSensorCeilings() {
        assertEquals(103_560, SizeCeilings.RAM_BYTES_PER_SENSOR); // "≈103.6 KB"
        assertEquals(64, SizeCeilings.DISK_WRITE_BYTES_PER_SENSOR_MINUTE);
        assertEquals(300, SizeCeilings.RAM_BYTES_PER_TOMBSTONE);
    }

    /** The §7.8 table at the default cap (256) and the maximum (1024); MB and KB as printed there. */
    @Test
    void tableAt256And1024() {
        assertEquals(2.15, mb(256L * SecondRing.BYTES), 0.005);
        assertEquals(8.6, mb(1024L * SecondRing.BYTES), 0.05);
        assertEquals(23.6, mb(256L * MinuteRing.BYTES), 0.05);
        assertEquals(94.4, mb(1024L * MinuteRing.BYTES), 0.05);
        assertEquals(0.77, mb(256L * SizeCeilings.ENTRY_OVERHEAD_BYTES), 0.005);
        assertEquals(3.1, mb(1024L * SizeCeilings.ENTRY_OVERHEAD_BYTES), 0.05);
        assertEquals(26.5, mb(SizeCeilings.ramBytes(256)), 0.05);
        assertEquals(106.0, mb(SizeCeilings.ramBytes(1024)), 0.05);
        assertEquals(23.6, mb(SizeCeilings.historyDiskBytes(256)), 0.05);
        assertEquals(94.4, mb(SizeCeilings.historyDiskBytes(1024)), 0.05);
        assertTrue(SizeCeilings.registryRawBytes(256) <= 64_000);
        assertTrue(SizeCeilings.registryRawBytes(1024) <= 256_000);
        assertEquals(16_384, SizeCeilings.diskWriteBytesPerMinute(256)); // ≈16 KB/min
        assertEquals(65_536, SizeCeilings.diskWriteBytesPerMinute(1024)); // ≈64 KB/min
    }

    @Test
    void startupLine() {
        assertEquals(
            "history ceilings for limits.maxSensors=256: RAM 26.5 MB (+300 B per tombstone), history files 23.6 MB"
                + " (+ tombstone files within the retention time), registry 64 KB raw, writes 16.4 KB/min",
            SizeCeilings.describe(256));
    }

    private static double mb(long bytes) {
        return bytes / 1e6;
    }
}
