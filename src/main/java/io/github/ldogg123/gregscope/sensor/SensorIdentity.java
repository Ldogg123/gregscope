package io.github.ldogg123.gregscope.sensor;

import java.util.UUID;

/**
 * The identity a Machine Sensor cover carries in its NBT (design-v0.2 §3.3): sensor UUID, label, owner and creation
 * time. Immutable. [pure]
 *
 * <p>
 * The label is always sanitized ({@link Labels#sanitize}). An unowned sensor has neither owner UUID nor owner name; an
 * owner name is capped at {@link #MAX_OWNER_NAME} UTF-16 units without splitting a surrogate pair.
 */
public final class SensorIdentity {

    /** Longest cached owner name (Minecraft names are at most 16 characters). */
    public static final int MAX_OWNER_NAME = 16;

    private final UUID id;
    private final String label;
    private final UUID owner;
    private final String ownerName;
    private final long createdEpochSec;

    public SensorIdentity(UUID id, String label, UUID owner, String ownerName, long createdEpochSec) {
        if (id == null) {
            throw new IllegalArgumentException("id");
        }
        this.id = id;
        this.label = Labels.sanitize(label);
        this.owner = owner;
        this.ownerName = owner == null ? null : capOwnerName(ownerName);
        this.createdEpochSec = createdEpochSec;
    }

    /**
     * Caps a cached owner name at {@link #MAX_OWNER_NAME} UTF-16 units without splitting a surrogate pair. Public
     * because the Telemetry Hub caches an owner name the same way (design-v0.2 section 9.1), and one rule with one
     * test is better than two.
     */
    public static String capOwnerName(String name) {
        if (name == null) {
            return "";
        }
        if (name.length() <= MAX_OWNER_NAME) {
            return name;
        }
        int end = MAX_OWNER_NAME;
        if (Character.isHighSurrogate(name.charAt(end - 1)) && Character.isLowSurrogate(name.charAt(end))) {
            end--;
        }
        return name.substring(0, end);
    }

    /**
     * The identity a cover gets when it is attached (design-v0.2 §3.4 {@code onPlayerAttach}).
     *
     * <p>
     * The owner is the attaching player. A cover attached by nobody or by a FakePlayer (another mod's automation, a
     * gametest) has no attributable player, so the caller passes {@code playerUuid == null} and the machine's own GT
     * owner is used; if the machine is unowned too, the sensor is unowned. The label is the stack's display name, so
     * an anvil rename before attaching becomes the label; it is sanitized by the constructor.
     *
     * @param playerUuid  the attaching player's UUID, or {@code null} for no player or a FakePlayer
     * @param holderOwner the machine's GT owner UUID, or {@code null} if the machine is unowned
     */
    public static SensorIdentity forAttach(UUID id, String stackDisplayName, UUID playerUuid, String playerName,
        UUID holderOwner, String holderOwnerName, long createdEpochSec) {
        UUID owner = playerUuid != null ? playerUuid : holderOwner;
        String ownerName = playerUuid != null ? playerName : holderOwnerName;
        return new SensorIdentity(id, stackDisplayName, owner, ownerName, createdEpochSec);
    }

    public UUID id() {
        return id;
    }

    /** Sanitized; empty if there is no label. */
    public String label() {
        return label;
    }

    /** Owner UUID, or {@code null} if unowned. */
    public UUID owner() {
        return owner;
    }

    public boolean isOwned() {
        return owner != null;
    }

    /** Cached owner name; {@code null} if unowned, possibly empty if the name was unknown. */
    public String ownerName() {
        return ownerName;
    }

    public long createdEpochSec() {
        return createdEpochSec;
    }

    public String shortId() {
        return Labels.shortId(id);
    }

    public SensorIdentity withLabel(String newLabel) {
        return new SensorIdentity(id, newLabel, owner, ownerName, createdEpochSec);
    }

    /** The same sensor data under a fresh UUID (a duplicate re-keyed, §4.3). */
    public SensorIdentity withId(UUID newId) {
        return new SensorIdentity(newId, label, owner, ownerName, createdEpochSec);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SensorIdentity)) {
            return false;
        }
        SensorIdentity other = (SensorIdentity) o;
        return id.equals(other.id) && label.equals(other.label)
            && (owner == null ? other.owner == null : owner.equals(other.owner))
            && (ownerName == null ? other.ownerName == null : ownerName.equals(other.ownerName))
            && createdEpochSec == other.createdEpochSec;
    }

    @Override
    public int hashCode() {
        int h = id.hashCode();
        h = 31 * h + label.hashCode();
        h = 31 * h + (owner == null ? 0 : owner.hashCode());
        h = 31 * h + (ownerName == null ? 0 : ownerName.hashCode());
        return 31 * h + Long.hashCode(createdEpochSec);
    }

    @Override
    public String toString() {
        return "SensorIdentity{" + id
            + ", label='"
            + label
            + "', owner="
            + owner
            + " ("
            + ownerName
            + "), ct="
            + createdEpochSec
            + "}";
    }
}
