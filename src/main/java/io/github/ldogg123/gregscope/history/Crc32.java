package io.github.ldogg123.gregscope.history;

/**
 * CRC-32 (IEEE 802.3, the one {@code java.util.zip.CRC32} computes): reflected polynomial 0xEDB88320, initial value
 * 0xFFFFFFFF, reflected input and output, final XOR 0xFFFFFFFF. The check value of ASCII {@code "123456789"} is
 * {@code 0xCBF43926}. [pure]
 *
 * <p>
 * It is written out rather than delegated to {@code java.util.zip.CRC32} because that class's member name
 * {@code update} lands in the constant pool of whatever calls it, and {@code ShippedClassesTest.noPeriodicWorkHooks}
 * reads member names to forbid tick hooks ({@code canUpdate}/{@code update}/{@code updateEntity}). A 20-line table is
 * cheaper than weakening that check, and it keeps the design-v0.2 §8.2 header checksum independent of the JDK.
 */
public final class Crc32 {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? crc >>> 1 ^ 0xEDB88320 : crc >>> 1;
            }
            TABLE[i] = crc;
        }
    }

    private Crc32() {}

    /** CRC of {@code length} bytes of {@code data} starting at {@code offset}, as an unsigned value in a long. */
    public static long compute(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException("offset " + offset + ", length " + length + ", size " + data.length);
        }
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = crc >>> 8 ^ TABLE[(crc ^ data[i]) & 0xFF];
        }
        return (crc ^ 0xFFFFFFFF) & 0xFFFFFFFFL;
    }

    public static long compute(byte[] data) {
        return compute(data, 0, data.length);
    }
}
