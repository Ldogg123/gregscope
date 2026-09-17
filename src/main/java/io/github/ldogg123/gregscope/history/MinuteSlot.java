package io.github.ldogg123.gregscope.history;

import java.math.BigInteger;
import java.util.Arrays;

import io.github.ldogg123.gregscope.model.StateCodes;

/**
 * One minute of machine history: the 64-byte, big-endian slot of design-v0.2 §7.4 ({@code slotLayout} 1). Immutable.
 * [pure]
 *
 * <pre>
 * off type  field
 *  0  i32   epochMinute (0 = empty)
 *  4  u8    samples
 *  5  u8    expectedSamples (1200 / intervalTicks)
 *  6  u8    gapMask (stored bits 0-5)
 *  7  u8    lastStateCode
 *  8  u8x10 stateSamples by StateCode
 * 18  u8    maintenanceMax
 * 19  u8    flags: b0 recipesCounterReset, b1 partialMinute, b2 serverStartMinute
 * 20  u16   serverTicks
 * 22  u8    euSamples
 * 23  u8    reserved 0
 * 24  i64   euPerTickAvg (Long.MIN_VALUE = none)
 * 32  i64   euPerTickMin
 * 40  i64   euPerTickMax
 * 48  i64   energyStoredLast (Long.MIN_VALUE = absent)
 * 56  i32   recipesCompletedDelta (-1 = not a multiblock)
 * 60  u16   reserved 0
 * 62  u16   CRC-16/CCITT-FALSE over bytes 0..61
 * </pre>
 */
public final class MinuteSlot {

    public static final int SIZE = 64;
    /** Bytes covered by the CRC. */
    public static final int CRC_COVERED = 62;
    /** {@code euPerTickAvg/Min/Max} and {@code energyStoredLast} when there is no value. */
    public static final long NONE = Long.MIN_VALUE;
    /** {@code recipesCompletedDelta} of a machine that is not a multiblock. */
    public static final int RECIPES_NONE = -1;

    public static final int FLAG_RECIPES_COUNTER_RESET = 1;
    public static final int FLAG_PARTIAL_MINUTE = 1 << 1;
    public static final int FLAG_SERVER_START_MINUTE = 1 << 2;
    /** Flag bits defined by slotLayout 1. */
    public static final int FLAGS_DEFINED = 0x07;

    /** Samples per minute for each allowed {@code intervalTicks}: {@code 1200 / intervalTicks}. */
    public static int expectedSamples(int intervalTicks) {
        if (intervalTicks <= 0 || 1200 % intervalTicks != 0 || 1200 / intervalTicks > LongMath.U8_MAX) {
            throw new IllegalArgumentException("intervalTicks " + intervalTicks);
        }
        return 1200 / intervalTicks;
    }

    /** The §7.4 window rule: {@code newest-1439 <= epochMinute <= newest}. */
    public static boolean isWithinWindow(int epochMinute, int newestEpochMinute) {
        return epochMinute <= newestEpochMinute
            && (long) epochMinute >= (long) newestEpochMinute - (MinuteRing.SLOTS - 1);
    }

    private final int epochMinute;
    private final int samples;
    private final int expectedSamples;
    private final int gapMask;
    private final int lastStateCode;
    private final int[] stateSamples;
    private final int maintenanceMax;
    private final int flags;
    private final int serverTicks;
    private final int euSamples;
    private final long euPerTickAvg;
    private final long euPerTickMin;
    private final long euPerTickMax;
    private final long energyStoredLast;
    private final int recipesCompletedDelta;

    private MinuteSlot(Builder b) {
        epochMinute = b.epochMinute;
        samples = b.samples;
        expectedSamples = b.expectedSamples;
        gapMask = b.gapMask;
        lastStateCode = b.lastStateCode;
        stateSamples = b.stateSamples.clone();
        maintenanceMax = b.maintenanceMax;
        flags = b.flags;
        serverTicks = b.serverTicks;
        euSamples = b.euSamples;
        euPerTickAvg = b.euPerTickAvg;
        euPerTickMin = b.euPerTickMin;
        euPerTickMax = b.euPerTickMax;
        energyStoredLast = b.energyStoredLast;
        recipesCompletedDelta = b.recipesCompletedDelta;
    }

    public int epochMinute() {
        return epochMinute;
    }

    public long epochSecond() {
        return epochMinute * 60L;
    }

    public int samples() {
        return samples;
    }

    public int expectedSamples() {
        return expectedSamples;
    }

    public int gapMask() {
        return gapMask;
    }

    public int lastStateCode() {
        return lastStateCode;
    }

    public int stateSamples(int stateCode) {
        return stateSamples[stateCode];
    }

    public int[] stateSamples() {
        return stateSamples.clone();
    }

    public int maintenanceMax() {
        return maintenanceMax;
    }

    public int flags() {
        return flags;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public int serverTicks() {
        return serverTicks;
    }

    public int euSamples() {
        return euSamples;
    }

    public long euPerTickAvg() {
        return euPerTickAvg;
    }

    public long euPerTickMin() {
        return euPerTickMin;
    }

    public long euPerTickMax() {
        return euPerTickMax;
    }

    public long energyStoredLast() {
        return energyStoredLast;
    }

    public int recipesCompletedDelta() {
        return recipesCompletedDelta;
    }

    /**
     * §7.5 rule 2: a stored minute with no valid samples, or whose last state is code 0, is a gap with its stored
     * reasons.
     */
    public boolean isGap() {
        return samples == 0 || lastStateCode == StateCodes.UNAVAILABLE;
    }

    /** Coverage {@code samples / expectedSamples}, capped at 1. */
    public double coverage() {
        return expectedSamples == 0 ? 0.0 : Math.min(1.0, samples / (double) expectedSamples);
    }

    public byte[] toBytes() {
        byte[] out = new byte[SIZE];
        encode(out, 0);
        return out;
    }

    /** Writes the 64 bytes, including the CRC, at {@code out[off]}. */
    public void encode(byte[] out, int off) {
        if (off < 0 || off > out.length - SIZE) {
            throw new IndexOutOfBoundsException("offset " + off);
        }
        LongMath.putI32(out, off, epochMinute);
        out[off + 4] = (byte) samples;
        out[off + 5] = (byte) expectedSamples;
        out[off + 6] = (byte) gapMask;
        out[off + 7] = (byte) lastStateCode;
        for (int i = 0; i < StateCodes.COUNT; i++) {
            out[off + 8 + i] = (byte) stateSamples[i];
        }
        out[off + 18] = (byte) maintenanceMax;
        out[off + 19] = (byte) flags;
        LongMath.putU16(out, off + 20, serverTicks);
        out[off + 22] = (byte) euSamples;
        out[off + 23] = 0;
        LongMath.putI64(out, off + 24, euPerTickAvg);
        LongMath.putI64(out, off + 32, euPerTickMin);
        LongMath.putI64(out, off + 40, euPerTickMax);
        LongMath.putI64(out, off + 48, energyStoredLast);
        LongMath.putI32(out, off + 56, recipesCompletedDelta);
        LongMath.putU16(out, off + 60, 0);
        LongMath.putU16(out, off + 62, Crc16Ccitt.compute(out, off, CRC_COVERED));
    }

    /**
     * Reads the slot at {@code in[off]}. Returns {@code null} (a gap to readers) for an empty slot
     * ({@code epochMinute == 0}), a CRC mismatch (a torn write), or a CRC-valid slot whose contents slotLayout 1 cannot
     * hold: a state code of 10 or more, gap bits 6-7, undefined flag bits or a recipes delta below -1. The window rule
     * is separate
     * ({@link #isWithinWindow}); reserved bytes are not checked.
     */
    public static MinuteSlot decode(byte[] in, int off) {
        if (off < 0 || off > in.length - SIZE) {
            throw new IndexOutOfBoundsException("offset " + off);
        }
        int epochMinute = LongMath.i32(in, off);
        if (epochMinute == 0) {
            return null;
        }
        if (Crc16Ccitt.compute(in, off, CRC_COVERED) != LongMath.u16(in, off + 62)) {
            return null;
        }
        int gapMask = LongMath.u8(in, off + 6);
        int lastState = LongMath.u8(in, off + 7);
        int flags = LongMath.u8(in, off + 19);
        if ((gapMask & ~GapReason.STORED_MASK) != 0 || !StateCodes.isKnown(lastState)
            || (flags & ~FLAGS_DEFINED) != 0
            || LongMath.i32(in, off + 56) < RECIPES_NONE) {
            return null;
        }
        Builder b = builder(epochMinute).samples(LongMath.u8(in, off + 4))
            .expectedSamples(LongMath.u8(in, off + 5))
            .gapMask(gapMask)
            .lastStateCode(lastState)
            .maintenanceMax(LongMath.u8(in, off + 18))
            .flags(flags)
            .serverTicks(LongMath.u16(in, off + 20))
            .euSamples(LongMath.u8(in, off + 22))
            .euPerTickAvg(LongMath.i64(in, off + 24))
            .euPerTickMin(LongMath.i64(in, off + 32))
            .euPerTickMax(LongMath.i64(in, off + 40))
            .energyStoredLast(LongMath.i64(in, off + 48))
            .recipesCompletedDelta(LongMath.i32(in, off + 56));
        for (int i = 0; i < StateCodes.COUNT; i++) {
            b.stateSamples(i, LongMath.u8(in, off + 8 + i));
        }
        return b.build();
    }

    /**
     * The §7.4 merge rule for the same minute reopened: counts are added with saturation, {@code gapMask} and flags
     * OR-ed, the EU average weighted by {@code euSamples}, min and max combined, {@code recipesCompletedDelta} summed
     * when both are {@code >= 0}. From {@code newer}: {@code lastStateCode} if it has samples and
     * {@code energyStoredLast} if present, else the older value. Not named by the design and chosen here:
     * {@code expectedSamples} and {@code maintenanceMax} take the larger value, and a delta of {@code -1} on one side
     * yields the other side's delta.
     */
    public static MinuteSlot merge(MinuteSlot older, MinuteSlot newer) {
        if (older.epochMinute != newer.epochMinute) {
            throw new IllegalArgumentException("minutes differ: " + older.epochMinute + " vs " + newer.epochMinute);
        }
        Builder b = builder(older.epochMinute).samples(LongMath.addU8(older.samples, newer.samples))
            .expectedSamples(Math.max(older.expectedSamples, newer.expectedSamples))
            .gapMask(older.gapMask | newer.gapMask)
            .lastStateCode(newer.samples > 0 ? newer.lastStateCode : older.lastStateCode)
            .maintenanceMax(Math.max(older.maintenanceMax, newer.maintenanceMax))
            .flags(older.flags | newer.flags)
            .serverTicks(LongMath.addU16(older.serverTicks, newer.serverTicks))
            .euSamples(LongMath.addU8(older.euSamples, newer.euSamples))
            .energyStoredLast(newer.energyStoredLast != NONE ? newer.energyStoredLast : older.energyStoredLast);
        for (int i = 0; i < StateCodes.COUNT; i++) {
            b.stateSamples(i, LongMath.addU8(older.stateSamples[i], newer.stateSamples[i]));
        }
        if (older.euSamples == 0) {
            b.euPerTickAvg(newer.euPerTickAvg)
                .euPerTickMin(newer.euPerTickMin)
                .euPerTickMax(newer.euPerTickMax);
        } else if (newer.euSamples == 0) {
            b.euPerTickAvg(older.euPerTickAvg)
                .euPerTickMin(older.euPerTickMin)
                .euPerTickMax(older.euPerTickMax);
        } else {
            BigInteger sum = BigInteger.valueOf(older.euPerTickAvg)
                .multiply(BigInteger.valueOf(older.euSamples))
                .add(
                    BigInteger.valueOf(newer.euPerTickAvg)
                        .multiply(BigInteger.valueOf(newer.euSamples)));
            b.euPerTickAvg(LongMath.roundedDivide(sum, (long) older.euSamples + newer.euSamples))
                .euPerTickMin(Math.min(older.euPerTickMin, newer.euPerTickMin))
                .euPerTickMax(Math.max(older.euPerTickMax, newer.euPerTickMax));
        }
        if (older.recipesCompletedDelta >= 0 && newer.recipesCompletedDelta >= 0) {
            b.recipesCompletedDelta(LongMath.addI32(older.recipesCompletedDelta, newer.recipesCompletedDelta));
        } else {
            b.recipesCompletedDelta(Math.max(older.recipesCompletedDelta, newer.recipesCompletedDelta));
        }
        return b.build();
    }

    public static Builder builder(int epochMinute) {
        return new Builder(epochMinute);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MinuteSlot)) {
            return false;
        }
        return Arrays.equals(toBytes(), ((MinuteSlot) o).toBytes());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(toBytes());
    }

    @Override
    public String toString() {
        return "MinuteSlot{minute=" + epochMinute
            + ", samples="
            + samples
            + "/"
            + expectedSamples
            + ", gapMask="
            + gapMask
            + ", last="
            + lastStateCode
            + ", states="
            + Arrays.toString(stateSamples)
            + ", maint="
            + maintenanceMax
            + ", flags="
            + flags
            + ", serverTicks="
            + serverTicks
            + ", eu="
            + euSamples
            + "x avg "
            + euPerTickAvg
            + " ["
            + euPerTickMin
            + ", "
            + euPerTickMax
            + "], energy="
            + energyStoredLast
            + ", recipes="
            + recipesCompletedDelta
            + "}";
    }

    /** Builds a slot; every field is range-checked against its stored width. Defaults describe an empty minute. */
    public static final class Builder {

        private final int epochMinute;
        private int samples;
        private int expectedSamples;
        private int gapMask;
        private int lastStateCode;
        private final int[] stateSamples = new int[StateCodes.COUNT];
        private int maintenanceMax;
        private int flags;
        private int serverTicks;
        private int euSamples;
        private long euPerTickAvg = NONE;
        private long euPerTickMin = NONE;
        private long euPerTickMax = NONE;
        private long energyStoredLast = NONE;
        private int recipesCompletedDelta = RECIPES_NONE;

        private Builder(int epochMinute) {
            if (epochMinute == 0) {
                throw new IllegalArgumentException("epochMinute 0 marks an empty slot");
            }
            this.epochMinute = epochMinute;
        }

        private static int u8(String name, int value) {
            if (value < 0 || value > LongMath.U8_MAX) {
                throw new IllegalArgumentException(name + " " + value);
            }
            return value;
        }

        public Builder samples(int v) {
            samples = u8("samples", v);
            return this;
        }

        public Builder expectedSamples(int v) {
            expectedSamples = u8("expectedSamples", v);
            return this;
        }

        public Builder gapMask(int v) {
            if ((v & ~GapReason.STORED_MASK) != 0) {
                throw new IllegalArgumentException("gapMask " + v);
            }
            gapMask = v;
            return this;
        }

        public Builder lastStateCode(int v) {
            if (!StateCodes.isKnown(v)) {
                throw new IllegalArgumentException("lastStateCode " + v);
            }
            lastStateCode = v;
            return this;
        }

        public Builder stateSamples(int stateCode, int v) {
            if (!StateCodes.isKnown(stateCode)) {
                throw new IllegalArgumentException("stateCode " + stateCode);
            }
            stateSamples[stateCode] = u8("stateSamples", v);
            return this;
        }

        public Builder maintenanceMax(int v) {
            maintenanceMax = u8("maintenanceMax", v);
            return this;
        }

        public Builder flags(int v) {
            if ((v & ~FLAGS_DEFINED) != 0) {
                throw new IllegalArgumentException("flags " + v);
            }
            flags = v;
            return this;
        }

        public Builder serverTicks(int v) {
            if (v < 0 || v > LongMath.U16_MAX) {
                throw new IllegalArgumentException("serverTicks " + v);
            }
            serverTicks = v;
            return this;
        }

        public Builder euSamples(int v) {
            euSamples = u8("euSamples", v);
            return this;
        }

        public Builder euPerTickAvg(long v) {
            euPerTickAvg = v;
            return this;
        }

        public Builder euPerTickMin(long v) {
            euPerTickMin = v;
            return this;
        }

        public Builder euPerTickMax(long v) {
            euPerTickMax = v;
            return this;
        }

        public Builder energyStoredLast(long v) {
            energyStoredLast = v;
            return this;
        }

        public Builder recipesCompletedDelta(int v) {
            if (v < RECIPES_NONE) {
                throw new IllegalArgumentException("recipesCompletedDelta " + v);
            }
            recipesCompletedDelta = v;
            return this;
        }

        public MinuteSlot build() {
            return new MinuteSlot(this);
        }
    }
}
