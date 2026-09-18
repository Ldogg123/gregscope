package io.github.ldogg123.gregscope.hub;

import java.util.UUID;

import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.SensorState;

/**
 * The wire format of the Hub DTOs (design-v0.2 sections 9.2 and 9.3), over the {@link ByteSink} / {@link ByteSource}
 * seam. [pure]
 *
 * <p>
 * <b>What the caps are for.</b> Section 9.3 pins displayName 40, statusId 64 and statusText 128, and says the codecs
 * reject longer strings. Encoding caps (a machine name arrives from GT and is nobody's fault); decoding
 * <em>rejects</em>, because a decoder is reading bytes someone else sent. The same asymmetry is why every layout
 * starts with {@link #FORMAT}: a packet that is not this format is refused rather than misread.
 *
 * <p>
 * <b>Shape.</b> Every layout is fixed except for the strings, so nothing has to be guessed while decoding. Booleans
 * travel packed in a flags byte; an absent number is a sentinel ({@link #NONE} for a long, -1 for a progress or
 * maintenance reading, {@link #NO_AGE} for an age), never 0, because 0 is a real reading. A null UUID is written as
 * two zero longs: {@code 00000000-0000-0000-0000-000000000000} is not a value {@code UUID.randomUUID} produces, and
 * a sensor whose id it was could not be told apart from "no sensor" anyway.
 *
 * <p>
 * <b>Availability codes.</b> Pinned here, in the order of the section 10.1 availability ids
 * ({@code live, unloaded, over_cap, missing, in_item, removed}), never {@code SensorState.ordinal()}: the same reason
 * {@code StateCodes} exists (erratum E6). {@code SensorState.persistedCode()} could not be reused because it has no
 * code for LIVE, which is the code the Hub needs most.
 */
public final class HubCodecs {

    /** The wire format of every DTO in this package. A decoder refuses anything else. */
    public static final byte FORMAT = 1;

    /** An absent {@code long} reading (EU/t, stored energy, a window average). */
    public static final long NONE = Long.MIN_VALUE;
    /** An absent age in seconds: there is no such timestamp. */
    public static final int NO_AGE = -1;

    /** Section 9.3. */
    public static final int MAX_DISPLAY_NAME = 40;
    /** Section 9.3. */
    public static final int MAX_STATUS_ID = 64;
    /** Section 9.3. */
    public static final int MAX_STATUS_TEXT = 128;
    /** A sanitized label is at most 32 code points, so at most 64 UTF-16 units. */
    public static final int MAX_LABEL = 64;
    /** Machine and meta names; the same 64 the registry entry caps them at. */
    public static final int MAX_NAME = 64;
    /** The cached owner name, as the sensor and Hub NBT records cap it. */
    public static final int MAX_OWNER_NAME = 16;
    /** The largest string any layout carries; a {@link ByteSink} may refuse anything longer outright. */
    public static final int MAX_STRING = MAX_STATUS_TEXT;

    /** No sensor: a padding row, or a detail with nothing selected. */
    public static final int AVAILABILITY_NONE = -1;
    public static final int AVAILABILITY_LIVE = 0;
    public static final int AVAILABILITY_UNLOADED = 1;
    public static final int AVAILABILITY_OVER_CAP = 2;
    public static final int AVAILABILITY_MISSING = 3;
    public static final int AVAILABILITY_IN_ITEM = 4;
    public static final int AVAILABILITY_REMOVED = 5;

    private HubCodecs() {}

    // --- shared helpers ---

    /**
     * {@code text} truncated to {@code maxUnits} UTF-16 units, never splitting a surrogate pair (a lone surrogate is
     * not a character and 1.7.10's string writer would encode it as a replacement). A null becomes "".
     */
    public static String cap(String text, int maxUnits) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxUnits) {
            return text;
        }
        int end = maxUnits;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** The pinned availability code of a lifecycle state; {@link #AVAILABILITY_NONE} for null. */
    public static int availabilityCode(SensorState state) {
        if (state == null) {
            return AVAILABILITY_NONE;
        }
        switch (state) {
            case LIVE:
                return AVAILABILITY_LIVE;
            case UNLOADED:
                return AVAILABILITY_UNLOADED;
            case OVER_CAP:
                return AVAILABILITY_OVER_CAP;
            case MISSING:
                return AVAILABILITY_MISSING;
            case IN_ITEM:
                return AVAILABILITY_IN_ITEM;
            case REMOVED:
                return AVAILABILITY_REMOVED;
            default:
                throw new IllegalArgumentException("no pinned availability code for " + state);
        }
    }

    /** The state a pinned availability code means, or null for {@link #AVAILABILITY_NONE} or an unknown code. */
    public static SensorState availabilityState(int code) {
        switch (code) {
            case AVAILABILITY_LIVE:
                return SensorState.LIVE;
            case AVAILABILITY_UNLOADED:
                return SensorState.UNLOADED;
            case AVAILABILITY_OVER_CAP:
                return SensorState.OVER_CAP;
            case AVAILABILITY_MISSING:
                return SensorState.MISSING;
            case AVAILABILITY_IN_ITEM:
                return SensorState.IN_ITEM;
            case AVAILABILITY_REMOVED:
                return SensorState.REMOVED;
            default:
                return null;
        }
    }

    /** The stable section 10.1 availability id, or "" for {@link #AVAILABILITY_NONE}. */
    public static String availabilityId(int code) {
        switch (code) {
            case AVAILABILITY_LIVE:
                return "live";
            case AVAILABILITY_UNLOADED:
                return "unloaded";
            case AVAILABILITY_OVER_CAP:
                return "over_cap";
            case AVAILABILITY_MISSING:
                return "missing";
            case AVAILABILITY_IN_ITEM:
                return "in_item";
            case AVAILABILITY_REMOVED:
                return "removed";
            default:
                return "";
        }
    }

    static void writeUuid(ByteSink sink, UUID id) {
        sink.writeLong(id == null ? 0L : id.getMostSignificantBits());
        sink.writeLong(id == null ? 0L : id.getLeastSignificantBits());
    }

    static UUID readUuid(ByteSource source) {
        long msb = source.readLong();
        long lsb = source.readLong();
        return msb == 0L && lsb == 0L ? null : new UUID(msb, lsb);
    }

    private static void writeString(ByteSink sink, String value, int maxUnits) {
        sink.writeString(cap(value, maxUnits));
    }

    private static String readString(ByteSource source, int maxUnits) {
        String value = source.readString(maxUnits);
        if (value == null) {
            throw new IllegalArgumentException("null string in a Hub DTO");
        }
        if (value.length() > maxUnits) {
            throw new IllegalArgumentException("string of " + value.length() + " units exceeds the cap " + maxUnits);
        }
        return value;
    }

    private static void readFormat(ByteSource source) {
        byte format = source.readByte();
        if (format != FORMAT) {
            throw new IllegalArgumentException("unsupported Hub DTO format " + format);
        }
    }

    private static int checkAvailability(int code) {
        if (code != AVAILABILITY_NONE && availabilityState(code) == null) {
            throw new IllegalArgumentException("unknown availability code " + code);
        }
        return code;
    }

    private static int checkStateCode(int code) {
        if (!StateCodes.isKnown(code)) {
            throw new IllegalArgumentException("unknown state code " + code);
        }
        return code;
    }

    // --- HubRow ---

    /** {@code format, id, kind, availability, stateCode, flags, euPerTick, ageSeconds, displayName}. */
    public static void encode(ByteSink sink, HubRow row) {
        sink.writeByte(FORMAT);
        writeUuid(sink, row.id());
        sink.writeByte(row.kind());
        sink.writeByte(row.availability());
        sink.writeByte(row.stateCode());
        sink.writeByte((row.problem() ? 1 : 0) | (row.warning() ? 2 : 0));
        sink.writeLong(row.euPerTick());
        sink.writeInt(row.ageSeconds());
        writeString(sink, row.displayName(), MAX_DISPLAY_NAME);
    }

    /**
     * @throws IllegalArgumentException on a wrong format, an unknown code or an oversize string
     */
    public static HubRow decodeRow(ByteSource source) {
        readFormat(source);
        UUID id = readUuid(source);
        int kind = source.readByte();
        int availability = checkAvailability(source.readByte());
        int stateCode = checkStateCode(source.readByte());
        int flags = source.readByte();
        long euPerTick = source.readLong();
        int age = source.readInt();
        String displayName = readString(source, MAX_DISPLAY_NAME);
        return new HubRow(
            id,
            kind,
            availability,
            stateCode,
            (flags & 1) != 0,
            (flags & 2) != 0,
            euPerTick,
            age,
            displayName);
    }

    // --- HubHeader ---

    /**
     * {@code format, owner, ownerName, total, live, shown, filter, page, pages, flags, p99, skipped, abandoned}.
     */
    public static void encode(ByteSink sink, HubHeader header) {
        sink.writeByte(FORMAT);
        writeUuid(sink, header.owner());
        writeString(sink, header.ownerName(), MAX_OWNER_NAME);
        sink.writeInt(header.total());
        sink.writeInt(header.live());
        sink.writeInt(header.shown());
        sink.writeByte(header.filter());
        sink.writeInt(header.page());
        sink.writeInt(header.pages());
        sink.writeByte((header.unsupported() ? 1 : 0) | (header.samplingEnabled() ? 2 : 0));
        sink.writeLong(header.cycleMicrosP99());
        sink.writeLong(header.samplingSkippedTotal());
        sink.writeInt(header.sensorsAbandoned());
    }

    /**
     * @throws IllegalArgumentException on a wrong format or an oversize owner name
     */
    public static HubHeader decodeHeader(ByteSource source) {
        readFormat(source);
        UUID owner = readUuid(source);
        String ownerName = readString(source, MAX_OWNER_NAME);
        int total = source.readInt();
        int live = source.readInt();
        int shown = source.readInt();
        int filter = source.readByte();
        int page = source.readInt();
        int pages = source.readInt();
        int flags = source.readByte();
        long p99 = source.readLong();
        long skipped = source.readLong();
        int abandoned = source.readInt();
        return new HubHeader(
            owner,
            ownerName,
            total,
            live,
            shown,
            HubViewModel.clampFilter(filter),
            page,
            pages,
            (flags & 1) != 0,
            (flags & 2) != 0,
            p99,
            skipped,
            abandoned);
    }

    // --- HubWindow ---

    /** {@code format, stateSamples[10], samples, expected, observedMinutes, eu..., recipes, flags, maint, gaps}. */
    public static void encode(ByteSink sink, HubWindow window) {
        sink.writeByte(FORMAT);
        for (int i = 0; i < StateCodes.COUNT; i++) {
            sink.writeLong(window.stateSamples(i));
        }
        sink.writeLong(window.samples());
        sink.writeLong(window.expectedSamples());
        sink.writeInt(window.observedMinutes());
        sink.writeLong(window.euSamples());
        sink.writeLong(window.euPerTickAvg());
        sink.writeLong(window.euPerTickMin());
        sink.writeLong(window.euPerTickMax());
        sink.writeLong(window.recipesCompleted());
        sink.writeByte(window.recipesCounterReset() ? 1 : 0);
        sink.writeInt(window.maintenanceMax());
        sink.writeInt(window.gapMask());
    }

    /**
     * @throws IllegalArgumentException on a wrong format
     */
    public static HubWindow decodeWindow(ByteSource source) {
        readFormat(source);
        long[] states = new long[StateCodes.COUNT];
        for (int i = 0; i < states.length; i++) {
            states[i] = source.readLong();
        }
        long samples = source.readLong();
        long expected = source.readLong();
        int observedMinutes = source.readInt();
        long euSamples = source.readLong();
        long euAvg = source.readLong();
        long euMin = source.readLong();
        long euMax = source.readLong();
        long recipes = source.readLong();
        boolean reset = source.readByte() != 0;
        int maintenance = source.readInt();
        int gapMask = source.readInt();
        return new HubWindow(
            states,
            samples,
            expected,
            observedMinutes,
            euSamples,
            euAvg,
            euMin,
            euMax,
            recipes,
            reset,
            maintenance,
            gapMask);
    }

    // --- HubDetail ---

    /**
     * {@code format, id}; with a null id that is the whole message, because a detail with nothing selected has
     * nothing else to say.
     */
    public static void encode(ByteSink sink, HubDetail detail) {
        sink.writeByte(FORMAT);
        writeUuid(sink, detail.id());
        if (!detail.isPresent()) {
            return;
        }
        sink.writeByte(detail.kind());
        sink.writeByte(detail.availability());
        sink.writeByte(detail.stateCode());
        writeString(sink, detail.displayName(), MAX_DISPLAY_NAME);
        writeString(sink, detail.label(), MAX_LABEL);
        writeString(sink, detail.machineName(), MAX_NAME);
        writeString(sink, detail.metaName(), MAX_NAME);
        writeString(sink, detail.statusId(), MAX_STATUS_ID);
        writeString(sink, detail.statusText(), MAX_STATUS_TEXT);
        sink.writeInt(detail.metaId());
        sink.writeInt(detail.dim());
        sink.writeInt(detail.x());
        sink.writeInt(detail.y());
        sink.writeInt(detail.z());
        sink.writeByte(detail.side());
        sink.writeInt(detail.progressPermyriad());
        sink.writeInt(detail.maintenanceIssues());
        sink.writeLong(detail.euPerTick());
        sink.writeLong(detail.energyStored());
        sink.writeLong(detail.energyCapacity());
        sink.writeInt(detail.sampleAgeSeconds());
        sink.writeInt(detail.seenAgeSeconds());
        sink.writeInt(detail.stateAgeSeconds());
        sink.writeByte((detail.canEdit() ? 1 : 0) | (detail.historyLoaded() ? 2 : 0) | (detail.hasHistory() ? 4 : 0));
        encode(sink, detail.fiveMinutes());
        encode(sink, detail.day());
        for (int i = 0; i < HubDetail.HOURS; i++) {
            sink.writeByte(detail.hour(i));
        }
    }

    /**
     * @throws IllegalArgumentException on a wrong format, an unknown code or an oversize string
     */
    public static HubDetail decodeDetail(ByteSource source) {
        readFormat(source);
        UUID id = readUuid(source);
        if (id == null) {
            return HubDetail.NONE;
        }
        HubDetail.Builder builder = HubDetail.builder()
            .id(id)
            .kind(source.readByte())
            .availability(checkAvailability(source.readByte()))
            .stateCode(checkStateCode(source.readByte()))
            .displayName(readString(source, MAX_DISPLAY_NAME))
            .label(readString(source, MAX_LABEL))
            .machineName(readString(source, MAX_NAME))
            .metaName(readString(source, MAX_NAME))
            .statusId(readString(source, MAX_STATUS_ID))
            .statusText(readString(source, MAX_STATUS_TEXT));
        builder.metaId(source.readInt());
        int dim = source.readInt();
        int x = source.readInt();
        int y = source.readInt();
        int z = source.readInt();
        builder.position(dim, x, y, z, source.readByte());
        builder.progressPermyriad(source.readInt());
        builder.maintenanceIssues(source.readInt());
        builder.euPerTick(source.readLong());
        builder.energyStored(source.readLong());
        builder.energyCapacity(source.readLong());
        int sampleAge = source.readInt();
        int seenAge = source.readInt();
        builder.ages(sampleAge, seenAge, source.readInt());
        int flags = source.readByte();
        builder.canEdit((flags & 1) != 0)
            .historyLoaded((flags & 2) != 0)
            .hasHistory((flags & 4) != 0);
        builder.fiveMinutes(decodeWindow(source));
        builder.day(decodeWindow(source));
        byte[] hourly = new byte[HubDetail.HOURS];
        for (int i = 0; i < hourly.length; i++) {
            hourly[i] = source.readByte();
        }
        return builder.hourly(hourly)
            .build();
    }
}
