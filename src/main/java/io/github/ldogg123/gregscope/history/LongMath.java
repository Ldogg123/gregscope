package io.github.ldogg123.gregscope.history;

import java.math.BigInteger;

/** Saturating and rounding arithmetic plus big-endian byte access for the history layouts. [pure] */
final class LongMath {

    static final int U8_MAX = 0xFF;
    static final int U16_MAX = 0xFFFF;

    private LongMath() {}

    static int addU8(int a, int b) {
        return (int) Math.min(U8_MAX, (long) a + b);
    }

    static int addU16(int a, int b) {
        return (int) Math.min(U16_MAX, (long) a + b);
    }

    static int addI32(int a, int b) {
        long sum = (long) a + b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : sum < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) sum;
    }

    static long addSat(long a, long b) {
        long sum = a + b;
        // Overflow iff both operands have the sign opposite to the result.
        if (((a ^ sum) & (b ^ sum)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return sum;
    }

    /** {@code sum / count} rounded half away from zero; {@code count > 0}. The result always fits a long. */
    static long roundedDivide(BigInteger sum, long count) {
        BigInteger[] qr = sum.divideAndRemainder(BigInteger.valueOf(count));
        long quotient = qr[0].longValue();
        if (qr[1].abs()
            .shiftLeft(1)
            .compareTo(BigInteger.valueOf(count)) >= 0) {
            quotient += sum.signum();
        }
        return quotient;
    }

    static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    static void putI32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static void putI64(byte[] b, int off, long v) {
        putI32(b, off, (int) (v >>> 32));
        putI32(b, off + 4, (int) v);
    }

    static int u8(byte[] b, int off) {
        return b[off] & 0xFF;
    }

    static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | b[off + 1] & 0xFF;
    }

    static int i32(byte[] b, int off) {
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16 | (b[off + 2] & 0xFF) << 8 | b[off + 3] & 0xFF;
    }

    static long i64(byte[] b, int off) {
        return (long) i32(b, off) << 32 | i32(b, off + 4) & 0xFFFFFFFFL;
    }
}
