package io.github.ldogg123.gregscope.history;

import java.util.UUID;

/**
 * The {@code .gsh} history file of design-v0.2 §8.2, format 1: a fixed {@value #FILE_BYTES} B file made of a 64-byte
 * header and 1,440 {@link MinuteSlot}s. Big-endian throughout. [pure]
 *
 * <pre>
 * off type    field
 *  0  4 B     magic "GSH1"
 *  4  u16     formatVersion = 1
 *  6  u16     slotSize = 64
 *  8  u16     slotCount = 1440
 * 10  u8      sensorKind (0 machine; 1 item flow and 2 fluid flow reserved for v0.3)
 * 11  u8      slotLayout (1 = machine minute v1)
 * 12  i64+i64 sensor UUID, most significant bits then least significant
 * 28  i64     createdEpochSec
 * 36  24 B    reserved 0
 * 60  u32     CRC-32 of bytes 0..59
 * </pre>
 *
 * <p>
 * The header is written once, when the file is created. {@link #inspect} classifies a file that was read back; the
 * caller renames a file that is not {@link Status#OK} aside with {@link Status#suffix} and starts a new one. Nothing
 * here deletes or writes a file: that is {@code FileStore}'s job.
 */
public final class HistoryFileCodec {

    /** {@code "GSH1"} in ASCII. */
    public static final byte[] MAGIC = { 'G', 'S', 'H', '1' };
    public static final int HEADER_BYTES = 64;
    /** Bytes of the header the CRC covers. */
    public static final int HEADER_CRC_COVERED = 60;
    public static final int FORMAT_VERSION = 1;
    public static final int SLOT_SIZE = MinuteSlot.SIZE;
    public static final int SLOT_COUNT = MinuteRing.SLOTS;
    /** 64 + 1,440 x 64 = 92,224 B (design-v0.2 §7.8). */
    public static final int FILE_BYTES = HEADER_BYTES + MinuteRing.BYTES;

    static final int OFF_FORMAT_VERSION = 4;
    static final int OFF_SLOT_SIZE = 6;
    static final int OFF_SLOT_COUNT = 8;
    static final int OFF_SENSOR_KIND = 10;
    static final int OFF_SLOT_LAYOUT = 11;
    static final int OFF_UUID_MSB = 12;
    static final int OFF_UUID_LSB = 20;
    static final int OFF_CREATED = 28;
    static final int OFF_RESERVED = 36;
    static final int RESERVED_BYTES = 24;
    static final int OFF_CRC = 60;

    private HistoryFileCodec() {}

    /** The byte offset of slot {@code index} in the file. */
    public static int slotOffset(int index) {
        if (index < 0 || index >= SLOT_COUNT) {
            throw new IndexOutOfBoundsException("slot index " + index);
        }
        return HEADER_BYTES + index * SLOT_SIZE;
    }

    /** What a file read back from disk turned out to be (design-v0.2 §8.2 "header rules"). */
    public enum Status {

        /** A format-1 header for the expected sensor: the body can be merged. */
        OK(null),
        /** Wrong size, wrong magic, or a header checksum that does not match: rename and start a new file. */
        CORRUPT("corrupt"),
        /** A format version, slot layout, sensor kind or slot geometry this build cannot read. */
        UNSUPPORTED("unsupported"),
        /** A readable header that belongs to another sensor. */
        MISMATCH("mismatch");

        private final String base;

        Status(String base) {
            this.base = base;
        }

        /**
         * The suffix appended to the file name when it is renamed aside; such files are never deleted automatically
         * (design-v0.2 §8.1).
         *
         * @param version     the header's {@code formatVersion} (only used by {@link #UNSUPPORTED})
         * @param epochMillis wall-clock time (only used by {@link #CORRUPT}, so several bad files can coexist)
         */
        public String suffix(int version, long epochMillis) {
            switch (this) {
                case CORRUPT:
                    return "." + base + "-" + epochMillis;
                case UNSUPPORTED:
                    return "." + base + "-v" + version;
                case MISMATCH:
                    return "." + base;
                default:
                    throw new IllegalStateException("OK files are not renamed");
            }
        }
    }

    /** A decoded header. Immutable. */
    public static final class Header {

        private final int formatVersion;
        private final int slotSize;
        private final int slotCount;
        private final int sensorKind;
        private final int slotLayout;
        private final UUID id;
        private final long createdEpochSec;

        public Header(int formatVersion, int slotSize, int slotCount, int sensorKind, int slotLayout, UUID id,
            long createdEpochSec) {
            if (id == null) {
                throw new IllegalArgumentException("id");
            }
            this.formatVersion = formatVersion;
            this.slotSize = slotSize;
            this.slotCount = slotCount;
            this.sensorKind = sensorKind;
            this.slotLayout = slotLayout;
            this.id = id;
            this.createdEpochSec = createdEpochSec;
        }

        /** The header a new file of this build gets: format 1, 64 x 1,440, the layout the kind records. */
        public static Header create(UUID id, int sensorKind, long createdEpochSec) {
            return new Header(
                FORMAT_VERSION,
                SLOT_SIZE,
                SLOT_COUNT,
                sensorKind,
                SlotLayouts.forKind(sensorKind),
                id,
                createdEpochSec);
        }

        public int formatVersion() {
            return formatVersion;
        }

        public int slotSize() {
            return slotSize;
        }

        public int slotCount() {
            return slotCount;
        }

        public int sensorKind() {
            return sensorKind;
        }

        public int slotLayout() {
            return slotLayout;
        }

        public UUID id() {
            return id;
        }

        public long createdEpochSec() {
            return createdEpochSec;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Header)) {
                return false;
            }
            Header h = (Header) o;
            return formatVersion == h.formatVersion && slotSize == h.slotSize
                && slotCount == h.slotCount
                && sensorKind == h.sensorKind
                && slotLayout == h.slotLayout
                && id.equals(h.id)
                && createdEpochSec == h.createdEpochSec;
        }

        @Override
        public int hashCode() {
            int h = formatVersion;
            h = 31 * h + slotSize;
            h = 31 * h + slotCount;
            h = 31 * h + sensorKind;
            h = 31 * h + slotLayout;
            h = 31 * h + id.hashCode();
            return 31 * h + Long.hashCode(createdEpochSec);
        }

        @Override
        public String toString() {
            return "GSH" + formatVersion
                + "{"
                + id
                + " kind "
                + sensorKind
                + " layout "
                + slotLayout
                + " "
                + slotCount
                + "x"
                + slotSize
                + " created "
                + createdEpochSec
                + "}";
        }
    }

    /** Writes the 64-byte header, checksum included, at {@code offset}. */
    public static void encodeHeader(Header header, byte[] out, int offset) {
        if (offset < 0 || offset > out.length - HEADER_BYTES) {
            throw new IndexOutOfBoundsException("offset " + offset);
        }
        System.arraycopy(MAGIC, 0, out, offset, MAGIC.length);
        LongMath.putU16(out, offset + OFF_FORMAT_VERSION, header.formatVersion);
        LongMath.putU16(out, offset + OFF_SLOT_SIZE, header.slotSize);
        LongMath.putU16(out, offset + OFF_SLOT_COUNT, header.slotCount);
        out[offset + OFF_SENSOR_KIND] = (byte) header.sensorKind;
        out[offset + OFF_SLOT_LAYOUT] = (byte) header.slotLayout;
        LongMath.putI64(out, offset + OFF_UUID_MSB, header.id.getMostSignificantBits());
        LongMath.putI64(out, offset + OFF_UUID_LSB, header.id.getLeastSignificantBits());
        LongMath.putI64(out, offset + OFF_CREATED, header.createdEpochSec);
        for (int i = 0; i < RESERVED_BYTES; i++) {
            out[offset + OFF_RESERVED + i] = 0;
        }
        LongMath.putI32(out, offset + OFF_CRC, (int) Crc32.compute(out, offset, HEADER_CRC_COVERED));
    }

    /**
     * A whole new file: the header followed by 1,440 zeroed slots (an {@code epochMinute} of 0 is "empty", §7.4).
     * Design-v0.2 §8.2 creates a file by writing this image to {@code .tmp} and moving it into place.
     */
    public static byte[] newFile(UUID id, int sensorKind, long createdEpochSec) {
        byte[] file = new byte[FILE_BYTES];
        encodeHeader(Header.create(id, sensorKind, createdEpochSec), file, 0);
        return file;
    }

    /** Reads a header without checking it; use {@link #inspect} to decide whether it may be trusted. */
    public static Header decodeHeader(byte[] file, int offset) {
        if (offset < 0 || offset > file.length - HEADER_BYTES) {
            throw new IndexOutOfBoundsException("offset " + offset);
        }
        return new Header(
            LongMath.u16(file, offset + OFF_FORMAT_VERSION),
            LongMath.u16(file, offset + OFF_SLOT_SIZE),
            LongMath.u16(file, offset + OFF_SLOT_COUNT),
            LongMath.u8(file, offset + OFF_SENSOR_KIND),
            LongMath.u8(file, offset + OFF_SLOT_LAYOUT),
            new UUID(LongMath.i64(file, offset + OFF_UUID_MSB), LongMath.i64(file, offset + OFF_UUID_LSB)),
            LongMath.i64(file, offset + OFF_CREATED));
    }

    /** True if the header's stored checksum matches its first 60 bytes. */
    public static boolean headerChecksumMatches(byte[] file, int offset) {
        long stored = LongMath.i32(file, offset + OFF_CRC) & 0xFFFFFFFFL;
        return stored == Crc32.compute(file, offset, HEADER_CRC_COVERED);
    }

    /**
     * Classifies a file read back from disk (design-v0.2 §8.2 "header rules"), in the order the design states them:
     * the size, magic and checksum decide {@link Status#CORRUPT}; then a format version, slot layout, sensor kind or
     * geometry this build cannot read decides {@link Status#UNSUPPORTED}; then a foreign UUID decides
     * {@link Status#MISMATCH}.
     *
     * @param file       the whole file as read, which need not be {@value #FILE_BYTES} B
     * @param expectedId the sensor the caller asked for
     */
    public static Status inspect(byte[] file, UUID expectedId) {
        if (file == null || file.length != FILE_BYTES) {
            return Status.CORRUPT;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (file[i] != MAGIC[i]) {
                return Status.CORRUPT;
            }
        }
        if (!headerChecksumMatches(file, 0)) {
            return Status.CORRUPT;
        }
        Header header = decodeHeader(file, 0);
        // Design-v0.2 §8.2 names "formatVersion > 1". Version 0 is no version this project ever wrote either, and the
        // conservative action for anything unreadable is to rename rather than to overwrite, so != 1 is unsupported.
        if (header.formatVersion != FORMAT_VERSION || header.slotSize != SLOT_SIZE
            || header.slotCount != SLOT_COUNT
            || !SlotLayouts.isSupported(header.slotLayout)
            || SlotLayouts.forKind(header.sensorKind) != header.slotLayout) {
            return Status.UNSUPPORTED;
        }
        if (expectedId != null && !expectedId.equals(header.id)) {
            return Status.MISMATCH;
        }
        return Status.OK;
    }
}
