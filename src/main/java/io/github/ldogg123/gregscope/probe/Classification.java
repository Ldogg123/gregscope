package io.github.ldogg123.gregscope.probe;

import io.github.ldogg123.gregscope.model.MachineState;

/** Result of {@link StateClassifier#classify}: normalized state, status ID and where its display text comes from. */
public final class Classification {

    /** Origin of the human-readable status text. */
    public enum TextSource {
        GREGSCOPE,
        SHUTDOWN_REASON,
        RECIPE_CHECK
    }

    private final MachineState state;
    private final String statusId;
    private final TextSource textSource;

    public Classification(MachineState state, String statusId, TextSource textSource) {
        this.state = state;
        this.statusId = statusId;
        this.textSource = textSource;
    }

    public MachineState state() {
        return state;
    }

    public String statusId() {
        return statusId;
    }

    public TextSource textSource() {
        return textSource;
    }

    @Override
    public String toString() {
        return state.id() + "/" + statusId + "/" + textSource;
    }
}
