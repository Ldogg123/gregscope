package io.github.ldogg123.gregscope.history;

/**
 * The {@code slotLayout} codes of a history file header and which {@code sensorKind} each belongs to (design-v0.2
 * §8.2; design-v0.3 §4.1/§4.2). [pure]
 *
 * <p>
 * v0.2 implements layout 1 only ({@link MinuteSlot}, the machine minute). Layouts 2 and 3 are reserved for the v0.3
 * flow meters and are recognised here so a v0.2 build can call such a file <em>unsupported</em> and rename it aside
 * instead of reinterpreting its bytes (§8.2: "a future change to the slot format must use a new {@code slotLayout},
 * never a reinterpretation").
 *
 * <p>
 * This is the part of the design-v0.3 GS-201 A5 {@code SlotLayout} strategy that GS-109 touches: the header needs the
 * kind/layout pairing to validate a file. The strategy itself ({@code id()}, {@code validate}, {@code merge}, the
 * {@code MinuteHeader} view and {@code MinuteRing(SlotLayout)}) belongs to the history refactor, not here.
 */
public final class SlotLayouts {

    /** No layout; not a value any header may carry. */
    public static final int NONE = 0;
    /** Machine minute v1, design-v0.2 §7.4: {@link MinuteSlot}. */
    public static final int MACHINE_MINUTE_V1 = 1;
    /** Reserved: item flow minute, design-v0.3 §4.2. */
    public static final int ITEM_FLOW_MINUTE_V1 = 2;
    /** Reserved: fluid flow minute, design-v0.3 §4.2. */
    public static final int FLUID_FLOW_MINUTE_V1 = 3;

    private SlotLayouts() {}

    /**
     * The layout a sensor of {@code kind} records, or {@link #NONE} for a kind no version defines.
     *
     * @param kind a {@code SensorKind} code
     */
    public static int forKind(int kind) {
        switch (kind) {
            case 0:
                return MACHINE_MINUTE_V1;
            case 1:
                return ITEM_FLOW_MINUTE_V1;
            case 2:
                return FLUID_FLOW_MINUTE_V1;
            default:
                return NONE;
        }
    }

    /** True if this build can read and write slots of {@code slotLayout} (v0.2: layout 1 only). */
    public static boolean isSupported(int slotLayout) {
        return slotLayout == MACHINE_MINUTE_V1;
    }

    /** Every slot is 64 B in every defined layout (design-v0.2 §7.4, design-v0.3 §4.2). */
    public static int slotSize(int slotLayout) {
        return MinuteSlot.SIZE;
    }
}
