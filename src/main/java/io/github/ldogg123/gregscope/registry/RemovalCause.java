package io.github.ldogg123.gregscope.registry;

/**
 * Why an entry became a tombstone (design-v0.2 sections 4.1, 4.3). [pure]
 *
 * <p>
 * The codes are pinned for {@code registry.dat} (design-v0.2 section 8.3 {@code cause}), never {@code ordinal()}.
 */
public enum RemovalCause {

    /** Still LIVE or UNLOADED. */
    NONE("none", 0),
    /** The cover was taken off: crowbar, screwdriver, facing-change drop. */
    DETACHED("detached", 1),
    /** The machine was broken and the cover went into its drop NBT. */
    IN_ITEM("in item", 2),
    /** Another sensor heartbeats at the same position and side (or, for a machine sensor, the same block). */
    REPLACED("replaced", 3),
    /** Three validations in a row found no matching cover at the recorded position. */
    TARGET_MISSING("target missing", 4),
    /** An operator ran {@code /gregscope purge}. */
    PURGED("purged", 5);

    private final String label;
    private final int code;

    RemovalCause(String label, int code) {
        this.label = label;
        this.code = code;
    }

    public String label() {
        return label;
    }

    /** The byte written to {@code registry.dat}. */
    public int code() {
        return code;
    }

    /** The cause for a persisted code, or {@link #NONE} if the code is unknown. */
    public static RemovalCause fromCode(int code) {
        for (RemovalCause cause : values()) {
            if (cause.code == code) {
                return cause;
            }
        }
        return NONE;
    }
}
