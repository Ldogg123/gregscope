package io.github.ldogg123.gregscope.registry;

/**
 * The lifecycle state of a registry entry (design-v0.2 section 4.2). [pure]
 *
 * <p>
 * LIVE and UNLOADED are the two states that count towards the caps; MISSING, IN_ITEM and REMOVED are tombstones kept
 * only until they expire. OVER_CAP is transient: a refused cover gets no entry at all, so it is never persisted and
 * never counted. The persisted codes come from design-v0.2 section 8.3 and are pinned here (never
 * {@code ordinal()}), the same way {@code StateCodes} pins the machine states.
 */
public enum SensorState {

    /** Loaded and validated: sampled, rings allocated, counted. */
    LIVE("live", -1),
    /** Chunk or dimension not loaded, or the server just started: not sampled, minute ring kept, counted. */
    UNLOADED("unloaded", 0),
    /** The cover exists but a cap refused it: no entry, nothing persisted, not counted. */
    OVER_CAP("over cap", -1),
    /** Tombstone: the chunk was loaded but the tile or cover was gone three validations in a row. */
    MISSING("missing", 1),
    /** Tombstone: the machine was picked up with the cover in its drop NBT. */
    IN_ITEM("in item", 2),
    /** Tombstone: detached, replaced or purged. */
    REMOVED("removed", 3);

    private final String label;
    private final int persistedCode;

    SensorState(String label, int persistedCode) {
        this.label = label;
        this.persistedCode = persistedCode;
    }

    /** The word the cover description shows (design-v0.2 section 3.4 "availability"). */
    public String label() {
        return label;
    }

    /** True for MISSING, IN_ITEM and REMOVED. */
    public boolean isTombstone() {
        return this == MISSING || this == IN_ITEM || this == REMOVED;
    }

    /** True for LIVE and UNLOADED: the states that count towards {@code limits.maxSensors}. */
    public boolean countsTowardCaps() {
        return this == LIVE || this == UNLOADED;
    }

    /** True while the entry holds a second ring, a minute ring and counters. */
    public boolean holdsRings() {
        return this == LIVE || this == UNLOADED;
    }

    /**
     * The byte written to {@code registry.dat} (design-v0.2 section 8.3), or -1 for a state that is never persisted.
     * LIVE is never persisted: a loaded non-tombstone entry starts as UNLOADED.
     */
    public int persistedCode() {
        return persistedCode;
    }

    /** The state for a persisted code, or {@code null} if the code is unknown. */
    public static SensorState fromPersistedCode(int code) {
        for (SensorState state : values()) {
            if (state.persistedCode == code && code >= 0) {
                return state;
            }
        }
        return null;
    }
}
