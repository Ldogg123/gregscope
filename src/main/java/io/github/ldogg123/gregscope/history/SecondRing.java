package io.github.ldogg123.gregscope.history;

import io.github.ldogg123.gregscope.model.StateCodes;

/**
 * The last 300 one-second samples, RAM only, as columnar primitive arrays (design-v0.2 §7.3). Server thread only.
 * [pure]
 *
 * <p>
 * Entries are placed by a head pointer in append order, never by timestamp, so two samples in the same wall-clock
 * second are both kept. Logical index {@code 0} is the oldest retained entry and {@code size()-1} the newest.
 *
 * <p>
 * The 28-byte big-endian form ({@link #encode}) is:
 *
 * <pre>
 * off type field
 *  0  i32  epochSec
 *  4  u8   stateCode (0 when gap)
 *  5  u8   gapReason (0 = none, else bit+1)
 *  6  u8   flags: b0 active, b1 allowedToWork, b2 formedKnown, b3 formed, b4 wasShutdown, b5 hasEu, b6 hasEnergy,
 *               b7 valid
 *  7  u8   maintenanceIssues (saturating)
 *  8  u16  progress x10000
 * 10  u16  inputSaturation x10000, or 0xFFFF for "no measurable buffer" (v0.3; was reserved)
 * 12  i64  euPerTick (valid if b5)
 * 20  i64  energyStored (valid if b6)
 * </pre>
 */
public final class SecondRing {

    /** {@code inputSaturation} at offset 10 when the machine has no buffer that reports a capacity. */
    public static final int SATURATION_NONE = 0xFFFF;

    public static final int CAPACITY = 300;
    public static final int BYTES_PER_SAMPLE = 28;
    public static final int BYTES = CAPACITY * BYTES_PER_SAMPLE;

    public static final int FLAG_ACTIVE = 1;
    public static final int FLAG_ALLOWED_TO_WORK = 1 << 1;
    public static final int FLAG_FORMED_KNOWN = 1 << 2;
    public static final int FLAG_FORMED = 1 << 3;
    public static final int FLAG_WAS_SHUTDOWN = 1 << 4;
    public static final int FLAG_HAS_EU = 1 << 5;
    public static final int FLAG_HAS_ENERGY = 1 << 6;
    public static final int FLAG_VALID = 1 << 7;

    /** Progress is stored as a fraction x10000. */
    public static final int PROGRESS_SCALE = 10000;

    private final int[] epochSec = new int[CAPACITY];
    private final byte[] stateCode = new byte[CAPACITY];
    private final byte[] gapReason = new byte[CAPACITY];
    private final byte[] flags = new byte[CAPACITY];
    private final byte[] maintenance = new byte[CAPACITY];
    private final short[] progress = new short[CAPACITY];
    private final short[] inputSaturation = new short[CAPACITY];
    private final long[] euPerTick = new long[CAPACITY];
    private final long[] energyStored = new long[CAPACITY];

    /** Physical index of the next write. */
    private int head;
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Appends an observed sample. {@link #FLAG_VALID} is set; {@link #FLAG_HAS_EU} / {@link #FLAG_HAS_ENERGY} decide
     * whether the EU values are kept (they are stored as 0 otherwise).
     *
     * @param flags             b0-b6 as in the layout (b7 is ignored and set)
     * @param maintenanceIssues saturated to 0..255
     * @param progress          fraction 0..1, stored clamped as x10000
     */
    public void appendSample(int epochSec, int stateCode, int flags, int maintenanceIssues, double progress,
        long euPerTick, long energyStored) {
        appendSample(epochSec, stateCode, flags, maintenanceIssues, progress, euPerTick, energyStored, Double.NaN);
    }

    /**
     * @param inputSaturation how full the machine's input buffers are, 0.0-1.0, or {@code NaN} when nothing there
     *                        reports a capacity. Stored in the byte the layout reserved, so the 5-minute trend
     *                        design-v0.3-buffers section 4 needs costs no extra memory and no format change - the
     *                        second ring never reaches disk.
     */
    public void appendSample(int epochSec, int stateCode, int flags, int maintenanceIssues, double progress,
        long euPerTick, long energyStored, double inputSaturation) {
        if (!StateCodes.isKnown(stateCode)) {
            throw new IllegalArgumentException("stateCode " + stateCode);
        }
        int f = flags & 0x7F | FLAG_VALID;
        double clamped = Double.isNaN(progress) ? 0.0 : Math.max(0.0, Math.min(1.0, progress));
        write(
            epochSec,
            stateCode,
            0,
            f,
            Math.max(0, Math.min(LongMath.U8_MAX, maintenanceIssues)),
            (int) Math.round(clamped * PROGRESS_SCALE),
            (f & FLAG_HAS_EU) != 0 ? euPerTick : 0L,
            (f & FLAG_HAS_ENERGY) != 0 ? energyStored : 0L,
            encodeSaturation(inputSaturation));
    }

    /** Appends a gap second: state 0, the reason code {@code bit+1}, no flags, zero values. */
    public void appendGap(int epochSec, GapReason reason) {
        if (reason == null || !reason.isStored()) {
            throw new IllegalArgumentException("not a stored gap reason: " + reason);
        }
        write(epochSec, StateCodes.UNAVAILABLE, reason.secondCode(), 0, 0, 0, 0L, 0L, SATURATION_NONE);
    }

    /**
     * Appends an entry from its 28-byte form (for fixtures and tests). The entry is stored as given, including its
     * flags and its saturation byte.
     */
    public void appendEncoded(byte[] in, int off) {
        if (off < 0 || off > in.length - BYTES_PER_SAMPLE) {
            throw new IndexOutOfBoundsException("offset " + off);
        }
        write(
            LongMath.i32(in, off),
            LongMath.u8(in, off + 4),
            LongMath.u8(in, off + 5),
            LongMath.u8(in, off + 6),
            LongMath.u8(in, off + 7),
            LongMath.u16(in, off + 8),
            LongMath.i64(in, off + 12),
            LongMath.i64(in, off + 20),
            LongMath.u16(in, off + 10));
    }

    private void write(int sec, int state, int gap, int f, int maint, int prog, long eu, long energy, int saturation) {
        epochSec[head] = sec;
        stateCode[head] = (byte) state;
        gapReason[head] = (byte) gap;
        flags[head] = (byte) f;
        maintenance[head] = (byte) maint;
        progress[head] = (short) prog;
        inputSaturation[head] = (short) saturation;
        euPerTick[head] = eu;
        energyStored[head] = energy;
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
    }

    /** {@code NaN} becomes {@link #SATURATION_NONE}; anything real is clamped into 0..10000. */
    private static int encodeSaturation(double ratio) {
        if (Double.isNaN(ratio)) {
            return SATURATION_NONE;
        }
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        return (int) Math.round(clamped * PROGRESS_SCALE);
    }

    /**
     * How full the machine's inputs were at sample {@code i}, or {@code NaN} when nothing reported a capacity.
     * {@code NaN} and {@code 0.0} are different answers: "not measurable" against "empty, with room".
     */
    public double inputSaturation(int i) {
        int raw = inputSaturation[physical(i)] & 0xFFFF;
        return raw == SATURATION_NONE ? Double.NaN : raw / (double) PROGRESS_SCALE;
    }

    /** Physical slot of logical index {@code i} (0 = oldest). */
    private int physical(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + size);
        }
        return (head - size + i + CAPACITY) % CAPACITY;
    }

    public int epochSec(int i) {
        return epochSec[physical(i)];
    }

    public int stateCode(int i) {
        return stateCode[physical(i)] & 0xFF;
    }

    /** 0 = none, else {@link GapReason#secondCode()}. */
    public int gapReasonCode(int i) {
        return gapReason[physical(i)] & 0xFF;
    }

    /** The gap reason, or {@code null} for an observed sample. */
    public GapReason gapReason(int i) {
        return GapReason.fromSecondCode(gapReasonCode(i));
    }

    public int flags(int i) {
        return flags[physical(i)] & 0xFF;
    }

    public boolean hasFlag(int i, int flag) {
        return (flags(i) & flag) != 0;
    }

    /** True for an observed sample ({@link #FLAG_VALID}); false for a gap second. */
    public boolean isValid(int i) {
        return hasFlag(i, FLAG_VALID);
    }

    public int maintenanceIssues(int i) {
        return maintenance[physical(i)] & 0xFF;
    }

    /** Progress x10000. */
    public int progress(int i) {
        return progress[physical(i)] & 0xFFFF;
    }

    public long euPerTick(int i) {
        return euPerTick[physical(i)];
    }

    public long energyStored(int i) {
        return energyStored[physical(i)];
    }

    /** Writes the 28-byte form of logical entry {@code i} at {@code out[off]}. */
    public void encode(int i, byte[] out, int off) {
        int p = physical(i);
        if (off < 0 || off > out.length - BYTES_PER_SAMPLE) {
            throw new IndexOutOfBoundsException("offset " + off);
        }
        LongMath.putI32(out, off, epochSec[p]);
        out[off + 4] = stateCode[p];
        out[off + 5] = gapReason[p];
        out[off + 6] = flags[p];
        out[off + 7] = maintenance[p];
        LongMath.putU16(out, off + 8, progress[p] & 0xFFFF);
        LongMath.putU16(out, off + 10, inputSaturation[p] & 0xFFFF);
        LongMath.putI64(out, off + 12, euPerTick[p]);
        LongMath.putI64(out, off + 20, energyStored[p]);
    }

    /**
     * Up to {@code count} newest entries with {@code epochSec < before}, as logical indices oldest first. Entries are
     * scanned newest to oldest in append order, so an entry written after a backwards clock step is still considered.
     */
    public int[] newest(int count, long before) {
        if (count <= 0) {
            return new int[0];
        }
        int[] picked = new int[Math.min(count, size)];
        int n = 0;
        for (int i = size - 1; i >= 0 && n < picked.length; i--) {
            if (epochSec(i) < before) {
                picked[n++] = i;
            }
        }
        int[] result = new int[n];
        for (int k = 0; k < n; k++) {
            result[k] = picked[n - 1 - k];
        }
        return result;
    }

    /** Logical indices of entries with {@code from <= epochSec < to}, in append order (oldest first). */
    public int[] window(long from, long to) {
        int n = 0;
        int[] tmp = new int[size];
        for (int i = 0; i < size; i++) {
            int sec = epochSec(i);
            if (sec >= from && sec < to) {
                tmp[n++] = i;
            }
        }
        int[] result = new int[n];
        System.arraycopy(tmp, 0, result, 0, n);
        return result;
    }

    public void clear() {
        head = 0;
        size = 0;
    }
}
