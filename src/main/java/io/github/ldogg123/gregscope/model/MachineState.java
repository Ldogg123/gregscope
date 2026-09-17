package io.github.ldogg123.gregscope.model;

/**
 * Normalized, intentionally coarse machine state. The id is part of the stable snapshot contract. [pure] (v0.1 enum;
 * marked when the bytecode check of {@code PureSourcesTest} found that the [pure] {@code StateCodes} depends on it.)
 */
public enum MachineState {

    UNAVAILABLE("unavailable"),
    STARTING("starting"),
    UNFORMED("unformed"),
    SHUTDOWN("shutdown"),
    POWER_STARVED("power_starved"),
    RUNNING("running"),
    DISABLED("disabled"),
    OUTPUT_BLOCKED("output_blocked"),
    WAITING("waiting"),
    IDLE("idle");

    private final String id;

    MachineState(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
