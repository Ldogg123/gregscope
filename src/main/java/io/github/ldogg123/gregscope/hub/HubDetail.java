package io.github.ldogg123.gregscope.hub;

import java.util.Arrays;
import java.util.UUID;

/**
 * The Telemetry Hub's detail pane for the selected sensor (design-v0.2 section 9.2), as it travels through
 * {@code gs_detail}. Immutable. [pure]
 *
 * <p>
 * Everything below the row list is here: the identity line, the machine line, the EU and energy numbers, the 5-minute
 * and 24-hour {@link HubWindow}s, the 24-hour hourly strip, and the {@code canEdit} flag section 9.3 requires (the
 * label field is only enabled when the viewer may rename this sensor and it is LIVE). {@link #NONE} is what a viewer
 * with nothing selected gets.
 *
 * <p>
 * Absent numbers are sentinels, never zero: {@link HubCodecs#NONE} for a machine that reports no EU or no energy
 * buffer, -1 for an absent progress or maintenance reading. Zero is a real reading and must not be confused with
 * "the machine does not have this".
 */
public final class HubDetail {

    /** How many hours {@link #hourly()} covers (design-v0.2 section 9.2's "24h" strip). */
    public static final int HOURS = 24;
    /** {@link #hourly()} entry for an hour with no observed sample: the strip's {@code ?}. */
    public static final byte HOUR_GAP = -1;

    /** Nothing selected. */
    /** {@link #inputSaturationPermyriad()} when the machine has no buffer that reports a capacity. */
    public static final int SATURATION_NONE = 0xFFFF;

    public static final HubDetail NONE = builder().build();

    private final UUID id;
    private final byte kind;
    private final byte availability;
    private final byte stateCode;
    private final String displayName;
    private final String label;
    private final String machineName;
    private final String metaName;
    private final String statusId;
    private final String statusText;
    private final int metaId;
    private final int dim;
    private final int x;
    private final int y;
    private final int z;
    private final byte side;
    private final int progressPermyriad;
    private final int maintenanceIssues;
    private final long euPerTick;
    private final long energyStored;
    private final long energyCapacity;
    private final int sampleAgeSeconds;
    private final int seenAgeSeconds;
    private final int stateAgeSeconds;
    /** v0.3: how full the inputs are, x10000, or {@link #SATURATION_NONE} when nothing reports a capacity. */
    private final int inputSaturationPermyriad;
    /** v0.3: the {@code BufferTrend.Direction} ordinal, so the DTO stays primitives only. */
    private final byte trend;
    private final boolean canEdit;
    private final boolean historyLoaded;
    private final boolean hasHistory;
    private final HubWindow fiveMinutes;
    private final HubWindow day;
    private final byte[] hourly;

    private HubDetail(Builder b) {
        this.id = b.id;
        this.kind = (byte) b.kind;
        this.availability = (byte) b.availability;
        this.stateCode = (byte) b.stateCode;
        this.displayName = HubCodecs.cap(b.displayName, HubCodecs.MAX_DISPLAY_NAME);
        this.label = HubCodecs.cap(b.label, HubCodecs.MAX_LABEL);
        this.machineName = HubCodecs.cap(b.machineName, HubCodecs.MAX_NAME);
        this.metaName = HubCodecs.cap(b.metaName, HubCodecs.MAX_NAME);
        this.statusId = HubCodecs.cap(b.statusId, HubCodecs.MAX_STATUS_ID);
        this.statusText = HubCodecs.cap(b.statusText, HubCodecs.MAX_STATUS_TEXT);
        this.metaId = b.metaId;
        this.dim = b.dim;
        this.x = b.x;
        this.y = b.y;
        this.z = b.z;
        this.side = (byte) b.side;
        this.progressPermyriad = b.progressPermyriad;
        this.maintenanceIssues = b.maintenanceIssues;
        this.euPerTick = b.euPerTick;
        this.energyStored = b.energyStored;
        this.energyCapacity = b.energyCapacity;
        this.sampleAgeSeconds = b.sampleAgeSeconds;
        this.seenAgeSeconds = b.seenAgeSeconds;
        this.stateAgeSeconds = b.stateAgeSeconds;
        this.inputSaturationPermyriad = b.inputSaturationPermyriad;
        this.trend = (byte) b.trend;
        this.canEdit = b.canEdit;
        this.historyLoaded = b.historyLoaded;
        this.hasHistory = b.hasHistory;
        this.fiveMinutes = b.fiveMinutes;
        this.day = b.day;
        this.hourly = b.hourly.clone();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The selected sensor, or null when nothing is selected. */
    public UUID id() {
        return id;
    }

    public boolean isPresent() {
        return id != null;
    }

    public byte kind() {
        return kind;
    }

    /** One of the {@code HubCodecs.AVAILABILITY_*} codes. */
    public byte availability() {
        return availability;
    }

    /** A {@code StateCodes} value; 0 when there is no readable machine state. */
    public byte stateCode() {
        return stateCode;
    }

    public String displayName() {
        return displayName;
    }

    /** The sanitized label, or empty. This is what the label field shows. */
    public String label() {
        return label;
    }

    public String machineName() {
        return machineName;
    }

    public String metaName() {
        return metaName;
    }

    /** The stable status id of the last snapshot (section 9.3: the client renders {@code gregscope.status.*}). */
    public String statusId() {
        return statusId;
    }

    /** GT's own status text, passed through and capped at {@link HubCodecs#MAX_STATUS_TEXT}. */
    public String statusText() {
        return statusText;
    }

    public int metaId() {
        return metaId;
    }

    public int dim() {
        return dim;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    /** The {@code ForgeDirection} ordinal of the covered face. */
    public byte side() {
        return side;
    }

    /** Recipe progress in ten-thousandths (0..10000), or -1 when the machine reports none. */
    public int progressPermyriad() {
        return progressPermyriad;
    }

    /** Maintenance issues, or -1 when the machine has no maintenance. */
    public int maintenanceIssues() {
        return maintenanceIssues;
    }

    /** The last sampled EU/t, or {@link HubCodecs#NONE}. */
    public long euPerTick() {
        return euPerTick;
    }

    public long energyStored() {
        return energyStored;
    }

    public long energyCapacity() {
        return energyCapacity;
    }

    /** Seconds since the last observed sample, or {@link HubCodecs#NO_AGE}. */
    public int sampleAgeSeconds() {
        return sampleAgeSeconds;
    }

    /** Seconds since the sensor was last seen, or {@link HubCodecs#NO_AGE}. */
    public int seenAgeSeconds() {
        return seenAgeSeconds;
    }

    /** Seconds in the current availability, or {@link HubCodecs#NO_AGE}. */
    public int stateAgeSeconds() {
        return stateAgeSeconds;
    }

    /**
     * How full the machine's inputs are, x10000, or {@link #SATURATION_NONE} for "nothing here reports a capacity".
     * A <b>level</b>, never a rate: design-v0.3-buffers section 1 has the reasoning.
     */
    public int inputSaturationPermyriad() {
        return inputSaturationPermyriad;
    }

    /** The {@code BufferTrend.Direction} ordinal of the input buffers over the last five minutes. */
    public int trend() {
        return trend & 0xFF;
    }

    /** The viewer may write this sensor's label right now (section 9.3's {@code gs_label} preconditions). */
    public boolean canEdit() {
        return canEdit;
    }

    /** The sensor's history file has been read; false means the 24-hour numbers are still filling in. */
    public boolean historyLoaded() {
        return historyLoaded;
    }

    /** The sensor still holds a minute ring at all; false for a tombstone, whose windows are empty. */
    public boolean hasHistory() {
        return hasHistory;
    }

    public HubWindow fiveMinutes() {
        return fiveMinutes;
    }

    public HubWindow day() {
        return day;
    }

    /**
     * {@link #HOURS} running-fraction percentages, oldest hour first, each 0..100 or {@link #HOUR_GAP} for an hour
     * with no observed sample. A copy: the array is never shared.
     */
    public byte[] hourly() {
        return hourly.clone();
    }

    byte hour(int index) {
        return hourly[index];
    }

    /**
     * The section 9.2 strip, ASCII only (the 1.7.10 font has no guaranteed block glyphs): {@code #} for 75% or more
     * of the hour running, {@code =} for 50%, {@code -} for 25%, {@code .} below that, and {@code ?} for an hour
     * with no observed sample. The thresholds are pinned here rather than on the client, so every surface that shows
     * a strip shows the same one.
     */
    public String strip() {
        StringBuilder out = new StringBuilder(hourly.length);
        for (byte value : hourly) {
            out.append(symbol(value));
        }
        return out.toString();
    }

    /** The strip symbol for one hourly value; see {@link #strip()}. */
    public static char symbol(int runningPercent) {
        if (runningPercent < 0) {
            return '?';
        }
        if (runningPercent >= 75) {
            return '#';
        }
        if (runningPercent >= 50) {
            return '=';
        }
        if (runningPercent >= 25) {
            return '-';
        }
        return '.';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HubDetail)) {
            return false;
        }
        HubDetail d = (HubDetail) o;
        return kind == d.kind && availability == d.availability
            && stateCode == d.stateCode
            && metaId == d.metaId
            && dim == d.dim
            && x == d.x
            && y == d.y
            && z == d.z
            && side == d.side
            && progressPermyriad == d.progressPermyriad
            && maintenanceIssues == d.maintenanceIssues
            && euPerTick == d.euPerTick
            && energyStored == d.energyStored
            && energyCapacity == d.energyCapacity
            && sampleAgeSeconds == d.sampleAgeSeconds
            && seenAgeSeconds == d.seenAgeSeconds
            && stateAgeSeconds == d.stateAgeSeconds
            && canEdit == d.canEdit
            && historyLoaded == d.historyLoaded
            && hasHistory == d.hasHistory
            && (id == null ? d.id == null : id.equals(d.id))
            && displayName.equals(d.displayName)
            && label.equals(d.label)
            && machineName.equals(d.machineName)
            && metaName.equals(d.metaName)
            && statusId.equals(d.statusId)
            && statusText.equals(d.statusText)
            && fiveMinutes.equals(d.fiveMinutes)
            && day.equals(d.day)
            && Arrays.equals(hourly, d.hourly);
    }

    @Override
    public int hashCode() {
        int h = id == null ? 0 : id.hashCode();
        h = h * 31 + displayName.hashCode();
        h = h * 31 + label.hashCode();
        h = h * 31 + statusId.hashCode();
        h = h * 31 + statusText.hashCode();
        h = h * 31 + kind + availability * 17 + stateCode * 257;
        h = h * 31 + progressPermyriad;
        h = h * 31 + maintenanceIssues;
        h = h * 31 + (int) (euPerTick ^ (euPerTick >>> 32));
        h = h * 31 + sampleAgeSeconds;
        h = h * 31 + (canEdit ? 4 : 0) + (historyLoaded ? 2 : 0) + (hasHistory ? 1 : 0);
        h = h * 31 + fiveMinutes.hashCode();
        h = h * 31 + day.hashCode();
        return h * 31 + Arrays.hashCode(hourly);
    }

    @Override
    public String toString() {
        return "HubDetail{" + (id == null ? "none"
            : id.toString()
                .substring(0, 8))
            + " '"
            + displayName
            + "' "
            + strip()
            + "}";
    }

    /** Collects the fields; unset ones keep the "absent" defaults that {@link #NONE} is made of. */
    public static final class Builder {

        private UUID id;
        private int kind;
        private int availability = HubCodecs.AVAILABILITY_NONE;
        private int stateCode;
        private String displayName = "";
        private String label = "";
        private String machineName = "";
        private String metaName = "";
        private String statusId = "";
        private String statusText = "";
        private int metaId;
        private int dim;
        private int x;
        private int y;
        private int z;
        private int side;
        private int progressPermyriad = -1;
        private int maintenanceIssues = -1;
        private long euPerTick = HubCodecs.NONE;
        private long energyStored = HubCodecs.NONE;
        private long energyCapacity = HubCodecs.NONE;
        private int sampleAgeSeconds = HubCodecs.NO_AGE;
        private int seenAgeSeconds = HubCodecs.NO_AGE;
        private int stateAgeSeconds = HubCodecs.NO_AGE;
        private int inputSaturationPermyriad = SATURATION_NONE;
        private int trend;

        private boolean canEdit;
        private boolean historyLoaded;
        private boolean hasHistory;
        private HubWindow fiveMinutes = HubWindow.EMPTY;
        private HubWindow day = HubWindow.EMPTY;
        private byte[] hourly = emptyHours();

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder kind(int value) {
            this.kind = value;
            return this;
        }

        public Builder availability(int value) {
            this.availability = value;
            return this;
        }

        public Builder stateCode(int value) {
            this.stateCode = value;
            return this;
        }

        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Builder label(String value) {
            this.label = value;
            return this;
        }

        public Builder machineName(String value) {
            this.machineName = value;
            return this;
        }

        public Builder metaName(String value) {
            this.metaName = value;
            return this;
        }

        public Builder statusId(String value) {
            this.statusId = value;
            return this;
        }

        public Builder statusText(String value) {
            this.statusText = value;
            return this;
        }

        public Builder metaId(int value) {
            this.metaId = value;
            return this;
        }

        public Builder position(int dim, int x, int y, int z, int side) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
            return this;
        }

        public Builder progressPermyriad(int value) {
            this.progressPermyriad = value;
            return this;
        }

        public Builder maintenanceIssues(int value) {
            this.maintenanceIssues = value;
            return this;
        }

        public Builder euPerTick(long value) {
            this.euPerTick = value;
            return this;
        }

        public Builder energyStored(long value) {
            this.energyStored = value;
            return this;
        }

        public Builder energyCapacity(long value) {
            this.energyCapacity = value;
            return this;
        }

        public Builder ages(int sampleAgeSeconds, int seenAgeSeconds, int stateAgeSeconds) {
            this.sampleAgeSeconds = sampleAgeSeconds;
            this.seenAgeSeconds = seenAgeSeconds;
            this.stateAgeSeconds = stateAgeSeconds;
            return this;
        }

        public Builder inputSaturationPermyriad(int value) {
            this.inputSaturationPermyriad = value < 0 ? SATURATION_NONE : value;
            return this;
        }

        public Builder trend(int ordinal) {
            this.trend = ordinal;
            return this;
        }

        public Builder canEdit(boolean value) {
            this.canEdit = value;
            return this;
        }

        public Builder historyLoaded(boolean value) {
            this.historyLoaded = value;
            return this;
        }

        public Builder hasHistory(boolean value) {
            this.hasHistory = value;
            return this;
        }

        public Builder fiveMinutes(HubWindow value) {
            this.fiveMinutes = value == null ? HubWindow.EMPTY : value;
            return this;
        }

        public Builder day(HubWindow value) {
            this.day = value == null ? HubWindow.EMPTY : value;
            return this;
        }

        /** {@link #HOURS} values, oldest first; anything outside -1..100 is clamped. */
        public Builder hourly(byte[] value) {
            if (value == null || value.length != HOURS) {
                throw new IllegalArgumentException("hourly needs " + HOURS + " values");
            }
            byte[] copy = new byte[HOURS];
            for (int i = 0; i < HOURS; i++) {
                byte v = value[i];
                copy[i] = v < HOUR_GAP ? HOUR_GAP : (v > 100 ? 100 : v);
            }
            this.hourly = copy;
            return this;
        }

        public HubDetail build() {
            return new HubDetail(this);
        }
    }

    /** {@link #HOURS} gap hours: what a sensor with no history shows. */
    static byte[] emptyHours() {
        byte[] hours = new byte[HOURS];
        for (int i = 0; i < HOURS; i++) {
            hours[i] = HOUR_GAP;
        }
        return hours;
    }
}
