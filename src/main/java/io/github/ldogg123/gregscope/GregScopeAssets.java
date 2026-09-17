package io.github.ldogg123.gregscope;

/**
 * Names of GregScope's registry entries, icons and lang keys (design-v0.2 §3.2, §12.2). [pure]
 *
 * <p>
 * The unit test {@code AssetsExistTest} checks these constants by naming convention, so a new constant is covered
 * automatically: every {@code BLOCK_ICON_*} must be a 16x16 RGBA PNG at {@code textures/blocks/<name>.png}, every
 * {@code ITEM_ICON_*} one at {@code textures/items/<name>.png}, and every {@code LANG_*} key (except the
 * {@code *_PREFIX} constants) must exist in {@code lang/en_US.lang}. The state and gap keys are derived from the stable
 * ids of {@code MachineState} and {@code GapReason}.
 */
public final class GregScopeAssets {

    /** Resource domain and mod id. */
    public static final String DOMAIN = "gregscope";

    /**
     * Machine Sensor cover overlay, a GT custom block icon. GT appends the key straight after {@code textures/blocks/},
     * so the name includes {@code iconsets/} (errata E1). The name is unique because GT keys its custom icon containers
     * by name only.
     */
    public static final String BLOCK_ICON_SENSOR_OVERLAY = "iconsets/GREGSCOPE_SENSOR_OVERLAY";

    /** Machine Sensor item icon, under {@code textures/items/}. */
    public static final String ITEM_ICON_MACHINE_SENSOR = "machine_sensor";

    /** Item registry name ({@code gregscope:machine_sensor}). */
    public static final String REGISTRY_MACHINE_SENSOR = "machine_sensor";

    /** Unlocalized name set on the item; Minecraft prefixes {@code item.} and appends {@code .name}. */
    public static final String UNLOCALIZED_MACHINE_SENSOR = DOMAIN + ".machine_sensor";

    public static final String LANG_ITEM_GROUP = "itemGroup." + DOMAIN;
    public static final String LANG_ITEM_MACHINE_SENSOR = "item." + UNLOCALIZED_MACHINE_SENSOR + ".name";
    public static final String LANG_TOOLTIP_SENSOR_1 = DOMAIN + ".tooltip.sensor.1";
    public static final String LANG_TOOLTIP_SENSOR_2 = DOMAIN + ".tooltip.sensor.2";
    public static final String LANG_TOOLTIP_SENSOR_3 = DOMAIN + ".tooltip.sensor.3";
    /** Sent to the player who attached a sensor the caps refused (design-v0.2 section 4.3 OVER_CAP row). */
    public static final String LANG_CHAT_SENSOR_OVER_CAP = DOMAIN + ".chat.sensor.over_cap";

    /** {@code gregscope.state.<MachineState id>}, one per state. */
    public static final String LANG_STATE_PREFIX = DOMAIN + ".state.";
    /** {@code gregscope.gap.<GapReason id>}, one per reason, derived ones included. */
    public static final String LANG_GAP_PREFIX = DOMAIN + ".gap.";

    private GregScopeAssets() {}

    /** The tooltip lines of the Machine Sensor item, in order. */
    public static String[] sensorTooltipKeys() {
        return new String[] { LANG_TOOLTIP_SENSOR_1, LANG_TOOLTIP_SENSOR_2, LANG_TOOLTIP_SENSOR_3 };
    }

    /** {@code domain:name}, the form Minecraft's icon register expects. */
    public static String iconName(String name) {
        return DOMAIN + ":" + name;
    }

    public static String stateKey(String stateId) {
        return LANG_STATE_PREFIX + stateId;
    }

    public static String gapKey(String gapId) {
        return LANG_GAP_PREFIX + gapId;
    }
}
