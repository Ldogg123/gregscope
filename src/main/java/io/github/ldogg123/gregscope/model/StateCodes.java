package io.github.ldogg123.gregscope.model;

/**
 * Pinned byte codes for {@link MachineState} in stored history (design-v0.2 §7.1). [pure]
 *
 * <p>
 * The mapping is an explicit table, never {@code ordinal()} (erratum E6): reordering the enum must not change what
 * stored history means. Code {@code 0} ({@code unavailable}) doubles as "no state" in gap entries. A new state needs a
 * new {@code slotLayout}, because {@code stateSamples} has exactly {@link #COUNT} columns.
 */
public final class StateCodes {

    /** Number of pinned codes; codes are {@code 0..COUNT-1}. */
    public static final int COUNT = 10;

    public static final int UNAVAILABLE = 0;
    public static final int STARTING = 1;
    public static final int UNFORMED = 2;
    public static final int SHUTDOWN = 3;
    public static final int POWER_STARVED = 4;
    public static final int RUNNING = 5;
    public static final int DISABLED = 6;
    public static final int OUTPUT_BLOCKED = 7;
    public static final int WAITING = 8;
    public static final int IDLE = 9;

    private StateCodes() {}

    /** The pinned code of {@code state}; {@code null} maps to {@link #UNAVAILABLE}. */
    public static int code(MachineState state) {
        if (state == null) {
            return UNAVAILABLE;
        }
        switch (state) {
            case UNAVAILABLE:
                return UNAVAILABLE;
            case STARTING:
                return STARTING;
            case UNFORMED:
                return UNFORMED;
            case SHUTDOWN:
                return SHUTDOWN;
            case POWER_STARVED:
                return POWER_STARVED;
            case RUNNING:
                return RUNNING;
            case DISABLED:
                return DISABLED;
            case OUTPUT_BLOCKED:
                return OUTPUT_BLOCKED;
            case WAITING:
                return WAITING;
            case IDLE:
                return IDLE;
            default:
                throw new IllegalArgumentException("no pinned code for " + state);
        }
    }

    /** The state stored as {@code code}, or {@code null} for a code outside {@code 0..COUNT-1}. */
    public static MachineState state(int code) {
        switch (code) {
            case UNAVAILABLE:
                return MachineState.UNAVAILABLE;
            case STARTING:
                return MachineState.STARTING;
            case UNFORMED:
                return MachineState.UNFORMED;
            case SHUTDOWN:
                return MachineState.SHUTDOWN;
            case POWER_STARVED:
                return MachineState.POWER_STARVED;
            case RUNNING:
                return MachineState.RUNNING;
            case DISABLED:
                return MachineState.DISABLED;
            case OUTPUT_BLOCKED:
                return MachineState.OUTPUT_BLOCKED;
            case WAITING:
                return MachineState.WAITING;
            case IDLE:
                return MachineState.IDLE;
            default:
                return null;
        }
    }

    public static boolean isKnown(int code) {
        return code >= 0 && code < COUNT;
    }

    /** The stable state id ({@link MachineState#id()}) for {@code code}, or {@code null} if unknown. */
    public static String id(int code) {
        MachineState state = state(code);
        return state == null ? null : state.id();
    }
}
