package io.github.ldogg123.gregscope.hub;

import java.util.UUID;

/**
 * One list row of the Telemetry Hub (design-v0.2 section 9.2), as it travels to the client through
 * {@code gs_rows}. Immutable. [pure]
 *
 * <p>
 * A row carries codes and numbers only (section 9.3): the client formats them with {@code gregscope.state.*} and the
 * other lang keys, so a script or a differently localised client sees the same data. The only text is the display
 * name, which is a player's label or a machine name and is capped at {@link HubCodecs#MAX_DISPLAY_NAME}.
 *
 * <p>
 * A page always has {@link HubViewModel#ROWS_PER_PAGE} rows; the trailing ones are {@link #EMPTY}, whose
 * {@link #id()} is null. That is what section 9.3's "always 8 entries (padded)" means, and it keeps the client's row
 * widgets from having to appear and disappear.
 *
 * <p>
 * <b>design-v0.3 GS-201 hook.</b> {@link #kind()} is carried even though v0.2 only ever publishes kind 0: the v0.3
 * Hub sorts and filters by it (design-v0.3 section 6.2) and the client already shows the kind label. The flow columns
 * that section adds ({@code unit}, {@code acceptedPerMin}, {@code attemptedPerMin}, {@code flowState},
 * {@code resourceKey}) are deliberately <em>not</em> here: v0.2 has nothing to put in them, and
 * {@link HubCodecs#FORMAT} is what a v0.3 wire layout bumps.
 */
public final class HubRow {

    /** The padding row of a short page: no sensor, no state, no age. */
    public static final HubRow EMPTY = new HubRow(
        null,
        0,
        HubCodecs.AVAILABILITY_NONE,
        0,
        false,
        false,
        HubCodecs.NONE,
        HubCodecs.NO_AGE,
        "");

    private final UUID id;
    private final byte kind;
    private final byte availability;
    private final byte stateCode;
    private final boolean problem;
    private final boolean warning;
    private final long euPerTick;
    private final int ageSeconds;
    private final String displayName;

    public HubRow(UUID id, int kind, int availability, int stateCode, boolean problem, boolean warning, long euPerTick,
        int ageSeconds, String displayName) {
        this.id = id;
        this.kind = (byte) kind;
        this.availability = (byte) availability;
        this.stateCode = (byte) stateCode;
        this.problem = problem;
        this.warning = warning;
        this.euPerTick = euPerTick;
        this.ageSeconds = ageSeconds < 0 ? HubCodecs.NO_AGE : ageSeconds;
        this.displayName = HubCodecs.cap(displayName, HubCodecs.MAX_DISPLAY_NAME);
    }

    /** The sensor's UUID, or null for a padding row. */
    public UUID id() {
        return id;
    }

    /** True for a padding row. */
    public boolean isEmpty() {
        return id == null;
    }

    /** A {@code SensorKind} code; v0.2 only publishes 0 (machine). */
    public byte kind() {
        return kind;
    }

    /** One of the {@code HubCodecs.AVAILABILITY_*} codes (the section 10.1 availability ids, pinned). */
    public byte availability() {
        return availability;
    }

    /** A {@code StateCodes} value; 0 ({@code unavailable}) when there is no readable machine state. */
    public byte stateCode() {
        return stateCode;
    }

    /** The section 9.2 Problems filter decided this row in. */
    public boolean problem() {
        return problem;
    }

    /** The last snapshot carried at least one warning. */
    public boolean warning() {
        return warning;
    }

    /** The last sampled EU/t, or {@link HubCodecs#NONE} when the machine reports none. */
    public long euPerTick() {
        return euPerTick;
    }

    /**
     * Seconds since the row's data was current: since the last sample for a LIVE sensor, since it was last seen
     * otherwise. {@link HubCodecs#NO_AGE} when there is no such timestamp at all.
     */
    public int ageSeconds() {
        return ageSeconds;
    }

    /** Never null, capped at {@link HubCodecs#MAX_DISPLAY_NAME}; empty for a padding row. */
    public String displayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HubRow)) {
            return false;
        }
        HubRow r = (HubRow) o;
        return kind == r.kind && availability == r.availability
            && stateCode == r.stateCode
            && problem == r.problem
            && warning == r.warning
            && euPerTick == r.euPerTick
            && ageSeconds == r.ageSeconds
            && (id == null ? r.id == null : id.equals(r.id))
            && displayName.equals(r.displayName);
    }

    @Override
    public int hashCode() {
        int h = id == null ? 0 : id.hashCode();
        h = h * 31 + displayName.hashCode();
        h = h * 31 + kind;
        h = h * 31 + availability;
        h = h * 31 + stateCode;
        h = h * 31 + (problem ? 2 : 0) + (warning ? 1 : 0);
        h = h * 31 + (int) (euPerTick ^ (euPerTick >>> 32));
        return h * 31 + ageSeconds;
    }

    @Override
    public String toString() {
        return "HubRow{" + (id == null ? "-"
            : id.toString()
                .substring(0, 8))
            + " '"
            + displayName
            + "' availability "
            + availability
            + " state "
            + stateCode
            + (problem ? " problem" : "")
            + "}";
    }
}
