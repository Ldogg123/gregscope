package io.github.ldogg123.gregscope.hub;

import java.util.UUID;

import io.github.ldogg123.gregscope.sensor.KeyValue;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;

/**
 * Reads and writes the Telemetry Hub tile entity's own NBT keys (design-v0.2 section 9.1). [pure]
 *
 * <pre>
 * key       type        meaning
 * gsHub     byte 1      GregScope marker and format version
 * owM, owL  long        owner UUID; absent means unowned
 * owN       string      owner name, at most 16 units
 * </pre>
 *
 * Read rules, deliberately the same shape as {@code SensorNbtCodec} (design-v0.2 section 3.3): a compound without
 * {@code gsHub} is {@link Status#ABSENT}, which is what a freshly placed Hub looks like before anything is stored. A
 * {@code gsHub} above {@link #FORMAT} is {@link Status#UNSUPPORTED}: nothing is parsed, and the tile entity must write
 * its whole compound back unchanged, so a world written by a newer GregScope survives a rollback. {@code gsHub} is
 * read as an unsigned byte, and the owner name is capped exactly as a sensor's is.
 *
 * <p>
 * The Hub stores no telemetry (section 9.1), so these three keys are the whole record.
 */
public final class HubNbtCodec {

    public static final int FORMAT = 1;

    public static final String GS_HUB = "gsHub";
    public static final String OWNER_MSB = "owM";
    public static final String OWNER_LSB = "owL";
    public static final String OWNER_NAME = "owN";

    public enum Status {
        /** A Telemetry Hub record of a supported format. */
        VALID,
        /** No GregScope Hub data at all: a Hub that has never been written, or a foreign blob. */
        ABSENT,
        /** Hub data of a newer format: preserved verbatim, never parsed and never overwritten. */
        UNSUPPORTED
    }

    /** The outcome of {@link #read}. */
    public static final class Result {

        private final Status status;
        private final UUID owner;
        private final String ownerName;
        private final int version;

        private Result(Status status, UUID owner, String ownerName, int version) {
            this.status = status;
            this.owner = owner;
            this.ownerName = ownerName;
            this.version = version;
        }

        public Status status() {
            return status;
        }

        /** The Hub owner, or {@code null} when the Hub is unowned or the data was not parsed. */
        public UUID owner() {
            return owner;
        }

        /** The cached owner name; empty when there is no owner or the name was unknown. Never null. */
        public String ownerName() {
            return ownerName;
        }

        /** The {@code gsHub} value read, 0 if absent. */
        public int version() {
            return version;
        }
    }

    private HubNbtCodec() {}

    /** Reads a Hub record. {@code d} is {@code null} when there is no compound at all. Never modifies {@code d}. */
    public static Result read(KeyValue d) {
        if (d == null || !d.hasByte(GS_HUB)) {
            return new Result(Status.ABSENT, null, "", 0);
        }
        int version = d.getByte(GS_HUB) & 0xFF;
        if (version > FORMAT) {
            return new Result(Status.UNSUPPORTED, null, "", version);
        }
        if (version != FORMAT) {
            // A marker of 0 is not something this project ever wrote; treat it as no record rather than as data.
            return new Result(Status.ABSENT, null, "", version);
        }
        if (!d.hasLong(OWNER_MSB) || !d.hasLong(OWNER_LSB)) {
            return new Result(Status.VALID, null, "", version);
        }
        UUID owner = new UUID(d.getLong(OWNER_MSB), d.getLong(OWNER_LSB));
        String ownerName = d.hasString(OWNER_NAME) ? d.getString(OWNER_NAME) : "";
        return new Result(Status.VALID, owner, SensorIdentity.capOwnerName(ownerName), version);
    }

    /**
     * Writes a Hub record as format 1. An absent owner is written as absent keys, removing any previous value.
     * Callers must not call this for a compound that read as {@link Status#UNSUPPORTED}.
     */
    public static void write(UUID owner, String ownerName, KeyValue d) {
        d.putByte(GS_HUB, (byte) FORMAT);
        if (owner == null) {
            d.remove(OWNER_MSB);
            d.remove(OWNER_LSB);
            d.remove(OWNER_NAME);
            return;
        }
        d.putLong(OWNER_MSB, owner.getMostSignificantBits());
        d.putLong(OWNER_LSB, owner.getLeastSignificantBits());
        d.putString(OWNER_NAME, SensorIdentity.capOwnerName(ownerName));
    }
}
