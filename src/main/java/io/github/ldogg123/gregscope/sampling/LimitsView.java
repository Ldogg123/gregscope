package io.github.ldogg123.gregscope.sampling;

import io.github.ldogg123.gregscope.config.Settings;

/**
 * The limits a {@link TelemetryFrame} carries (design-v0.2 section 7.7), copied from {@link Settings} when the frame
 * is built so a reader never has to reach back into the config. Immutable. [pure]
 *
 * <p>
 * Not named in design-v0.2 section 2; section 7.7 puts a {@code LimitsView} in the frame.
 */
public final class LimitsView {

    private final int maxSensors;
    private final int maxSensorsPerTeam;
    private final int maxOpenHubViews;
    private final int tickBudgetMicros;
    private final boolean samplingEnabled;

    public LimitsView(Settings settings) {
        this.maxSensors = settings.maxSensors();
        this.maxSensorsPerTeam = settings.maxSensorsPerTeam();
        this.maxOpenHubViews = settings.maxOpenHubViews();
        this.tickBudgetMicros = settings.tickBudgetMicros();
        this.samplingEnabled = settings.samplingEnabled();
    }

    public int maxSensors() {
        return maxSensors;
    }

    /** 0 means no per-team limit. */
    public int maxSensorsPerTeam() {
        return maxSensorsPerTeam;
    }

    public int maxOpenHubViews() {
        return maxOpenHubViews;
    }

    public int tickBudgetMicros() {
        return tickBudgetMicros;
    }

    public boolean samplingEnabled() {
        return samplingEnabled;
    }

    @Override
    public String toString() {
        return "LimitsView{maxSensors=" + maxSensors
            + ", maxSensorsPerTeam="
            + maxSensorsPerTeam
            + ", maxOpenHubViews="
            + maxOpenHubViews
            + ", tickBudgetMicros="
            + tickBudgetMicros
            + ", samplingEnabled="
            + samplingEnabled
            + "}";
    }
}
