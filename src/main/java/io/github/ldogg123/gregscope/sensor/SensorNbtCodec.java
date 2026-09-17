package io.github.ldogg123.gregscope.sensor;

import java.util.UUID;

/**
 * Reads and writes {@link SensorIdentity} in the cover's {@code d} compound (design-v0.2 §3.3). [pure]
 *
 * <pre>
 * key       type        meaning
 * gs        byte 1      GregScope marker and format version
 * idM, idL  long        sensor UUID
 * lbl       string      sanitized label; absent means empty
 * owM, owL  long        owner UUID; absent means unowned
 * owN       string      owner name, at most 16 units
 * ct        long        creation time, epoch seconds
 * </pre>
 *
 * Read rules: a missing {@code d} compound or {@code gs} marker (a foreign blob) gives {@link Status#INERT}. A
 * {@code gs} above 1 gives {@link Status#UNSUPPORTED}: nothing is changed, and the cover must write its raw compound
 * back unchanged, which protects a rollback. {@code gs} is read as an unsigned byte. The label is sanitized on every
 * read.
 */
public final class SensorNbtCodec {

    public static final int FORMAT = 1;

    public static final String GS = "gs";
    public static final String ID_MSB = "idM";
    public static final String ID_LSB = "idL";
    public static final String LABEL = "lbl";
    public static final String OWNER_MSB = "owM";
    public static final String OWNER_LSB = "owL";
    public static final String OWNER_NAME = "owN";
    public static final String CREATED = "ct";

    public enum Status {
        /** A GregScope sensor identity of a supported format. */
        VALID,
        /** Not GregScope data (or incomplete): the cover never registers. */
        INERT,
        /** GregScope data of a newer format: preserved verbatim, the cover never registers. */
        UNSUPPORTED
    }

    /** The outcome of {@link #read}. */
    public static final class Result {

        private final Status status;
        private final SensorIdentity identity;
        private final int version;

        private Result(Status status, SensorIdentity identity, int version) {
            this.status = status;
            this.identity = identity;
            this.version = version;
        }

        public Status status() {
            return status;
        }

        /** Non-null only for {@link Status#VALID}. */
        public SensorIdentity identity() {
            return identity;
        }

        /** The {@code gs} value read, 0 if absent. */
        public int version() {
            return version;
        }

        /** The availability text for the cover description: {@code null} when valid. */
        public String inertDescription() {
            switch (status) {
                case INERT:
                    return "inactive";
                case UNSUPPORTED:
                    return "unsupported data version";
                default:
                    return null;
            }
        }
    }

    private SensorNbtCodec() {}

    /**
     * Reads an identity. {@code d} is {@code null} when the cover data is missing or not a compound. Never modifies
     * {@code d}.
     */
    public static Result read(KeyValue d) {
        if (d == null || !d.hasByte(GS)) {
            return new Result(Status.INERT, null, 0);
        }
        int version = d.getByte(GS) & 0xFF;
        if (version > FORMAT) {
            return new Result(Status.UNSUPPORTED, null, version);
        }
        if (version != FORMAT || !d.hasLong(ID_MSB) || !d.hasLong(ID_LSB)) {
            return new Result(Status.INERT, null, version);
        }
        UUID id = new UUID(d.getLong(ID_MSB), d.getLong(ID_LSB));
        String label = d.hasString(LABEL) ? d.getString(LABEL) : "";
        UUID owner = null;
        String ownerName = null;
        if (d.hasLong(OWNER_MSB) && d.hasLong(OWNER_LSB)) {
            owner = new UUID(d.getLong(OWNER_MSB), d.getLong(OWNER_LSB));
            ownerName = d.hasString(OWNER_NAME) ? d.getString(OWNER_NAME) : "";
        }
        long created = d.hasLong(CREATED) ? d.getLong(CREATED) : 0L;
        return new Result(Status.VALID, new SensorIdentity(id, label, owner, ownerName, created), version);
    }

    /**
     * Writes {@code identity} as format 1. An empty label and an absent owner are written as absent keys (removing any
     * previous value). Callers must not call this for a compound that read as {@link Status#UNSUPPORTED}.
     */
    public static void write(SensorIdentity identity, KeyValue d) {
        d.putByte(GS, (byte) FORMAT);
        d.putLong(
            ID_MSB,
            identity.id()
                .getMostSignificantBits());
        d.putLong(
            ID_LSB,
            identity.id()
                .getLeastSignificantBits());
        if (identity.label()
            .isEmpty()) {
            d.remove(LABEL);
        } else {
            d.putString(LABEL, identity.label());
        }
        if (identity.isOwned()) {
            d.putLong(
                OWNER_MSB,
                identity.owner()
                    .getMostSignificantBits());
            d.putLong(
                OWNER_LSB,
                identity.owner()
                    .getLeastSignificantBits());
            d.putString(OWNER_NAME, identity.ownerName());
        } else {
            d.remove(OWNER_MSB);
            d.remove(OWNER_LSB);
            d.remove(OWNER_NAME);
        }
        d.putLong(CREATED, identity.createdEpochSec());
    }
}
