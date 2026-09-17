package io.github.ldogg123.gregscope.sensor;

/**
 * Sensor kinds, as stored in the registry ({@code kind}) and in a history file header ({@code sensorKind}), and their
 * stable labels (design-v0.2 §8.2, §10.1; design-v0.3 §5.1 A2/A3, §6.3/§6.4). [pure]
 *
 * <p>
 * v0.2 implements only {@link #MACHINE}. Kinds 1 and 2 are reserved for the v0.3 flow meters; a v0.2 build must keep
 * data of those kinds untouched (A3), so it can recognise them without supporting them.
 */
public final class SensorKind {

    public static final int MACHINE = 0;
    /** Reserved for v0.3 item flow meters. */
    public static final int ITEM_FLOW = 1;
    /** Reserved for v0.3 fluid flow meters. */
    public static final int FLUID_FLOW = 2;

    private SensorKind() {}

    /**
     * The stable label used by OpenComputers ({@code kind="machine"}) and the exporter model
     * ({@code kind=machine|item_flow|fluid_flow}); {@code "unknown"} for any other value.
     */
    public static String label(int kind) {
        switch (kind) {
            case MACHINE:
                return "machine";
            case ITEM_FLOW:
                return "item_flow";
            case FLUID_FLOW:
                return "fluid_flow";
            default:
                return "unknown";
        }
    }

    /** True only for kinds this version registers, samples and counts (v0.2: machine only). */
    public static boolean isSupported(int kind) {
        return kind == MACHINE;
    }
}
