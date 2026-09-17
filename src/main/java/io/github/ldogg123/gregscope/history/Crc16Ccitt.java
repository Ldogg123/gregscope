package io.github.ldogg123.gregscope.history;

/**
 * CRC-16/CCITT-FALSE: polynomial 0x1021, initial value 0xFFFF, no reflection, no final XOR. The check value of ASCII
 * {@code "123456789"} is {@code 0x29B1} (design-v0.2 §7.4). [pure]
 */
public final class Crc16Ccitt {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
            TABLE[i] = crc & 0xFFFF;
        }
    }

    private Crc16Ccitt() {}

    /** CRC of {@code length} bytes of {@code data} starting at {@code offset}, as an unsigned value 0..0xFFFF. */
    public static int compute(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException("offset " + offset + ", length " + length + ", size " + data.length);
        }
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = ((crc << 8) ^ TABLE[((crc >>> 8) ^ data[i]) & 0xFF]) & 0xFFFF;
        }
        return crc;
    }

    public static int compute(byte[] data) {
        return compute(data, 0, data.length);
    }
}
