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

    /**
     * Telemetry Hub block icons, under {@code textures/blocks/} (design-v0.2 §9.1, §12.2). GS-112 ships all three and
     * asks Minecraft for {@link #BLOCK_ICON_HUB_SIDE} through {@code Block.setBlockTextureName}; the per-side icons
     * need the client-only {@code registerBlockIcons}/{@code getIcon}, so GS-114 wires the front and top ones.
     */
    public static final String BLOCK_ICON_HUB_FRONT = "telemetry_hub_front";
    public static final String BLOCK_ICON_HUB_SIDE = "telemetry_hub_side";
    public static final String BLOCK_ICON_HUB_TOP = "telemetry_hub_top";

    /** Machine Sensor item icon, under {@code textures/items/}. */
    public static final String ITEM_ICON_MACHINE_SENSOR = "machine_sensor";

    /** Item registry name ({@code gregscope:machine_sensor}). */
    public static final String REGISTRY_MACHINE_SENSOR = "machine_sensor";

    /** Block registry name ({@code gregscope:telemetry_hub}); frozen before the first public jar (§17). */
    public static final String REGISTRY_TELEMETRY_HUB = "telemetry_hub";

    /** Tile entity registry name, frozen with the block (§9.1, §17). */
    public static final String TILE_ENTITY_TELEMETRY_HUB = DOMAIN + ":telemetry_hub";

    /** Unlocalized name set on the item; Minecraft prefixes {@code item.} and appends {@code .name}. */
    public static final String UNLOCALIZED_MACHINE_SENSOR = DOMAIN + ".machine_sensor";

    /** Unlocalized name set on the block; Minecraft prefixes {@code tile.} and appends {@code .name}. */
    public static final String UNLOCALIZED_TELEMETRY_HUB = DOMAIN + ".telemetry_hub";

    public static final String LANG_ITEM_GROUP = "itemGroup." + DOMAIN;
    public static final String LANG_ITEM_MACHINE_SENSOR = "item." + UNLOCALIZED_MACHINE_SENSOR + ".name";
    public static final String LANG_TILE_TELEMETRY_HUB = "tile." + UNLOCALIZED_TELEMETRY_HUB + ".name";
    public static final String LANG_TOOLTIP_SENSOR_1 = DOMAIN + ".tooltip.sensor.1";
    public static final String LANG_TOOLTIP_SENSOR_2 = DOMAIN + ".tooltip.sensor.2";
    public static final String LANG_TOOLTIP_SENSOR_3 = DOMAIN + ".tooltip.sensor.3";
    /** Sent to the player who attached a sensor the caps refused (design-v0.2 section 4.3 OVER_CAP row). */
    public static final String LANG_CHAT_SENSOR_OVER_CAP = DOMAIN + ".chat.sensor.over_cap";

    // --- Telemetry Hub (design-v0.2 section 9.1, GS-112). Sent to the player whose right-click opened nothing.

    /** Section 5 refused this viewer: not the owner, not in the owner's team, not an operator. */
    public static final String LANG_HUB_DENIED = DOMAIN + ".hub.denied";
    /** Section 9.1: {@code limits.maxOpenHubViews} views are already open, server-wide. */
    public static final String LANG_HUB_BUSY = DOMAIN + ".hub.busy";

    // --- /gregscope (design-v0.2 section 11, GS-111). Every line is a ChatComponentTranslation with one of these
    // keys; the values inside a line are data (stable ids and numbers), never translated text.

    /** The one-line usage {@code CommandBase.getCommandUsage} returns. */
    public static final String LANG_CMD_USAGE = DOMAIN + ".cmd.usage";
    public static final String LANG_CMD_USAGE_STATS = DOMAIN + ".cmd.usage.stats";
    public static final String LANG_CMD_USAGE_LIST = DOMAIN + ".cmd.usage.list";
    public static final String LANG_CMD_USAGE_INFO = DOMAIN + ".cmd.usage.info";
    public static final String LANG_CMD_USAGE_LABEL = DOMAIN + ".cmd.usage.label";
    public static final String LANG_CMD_USAGE_PURGE = DOMAIN + ".cmd.usage.purge";
    /** No server, i.e. the registry of this run does not exist (a command typed between world loads). */
    public static final String LANG_CMD_NO_SERVER = DOMAIN + ".cmd.no_server";
    public static final String LANG_CMD_UNKNOWN = DOMAIN + ".cmd.unknown";
    public static final String LANG_CMD_MISSING_ID = DOMAIN + ".cmd.missing_id";
    public static final String LANG_CMD_PREFIX_SHORT = DOMAIN + ".cmd.prefix_short";
    public static final String LANG_CMD_BAD_PAGE = DOMAIN + ".cmd.bad_page";
    public static final String LANG_CMD_BAD_FILTER = DOMAIN + ".cmd.bad_filter";
    /** Section 5: the viewer may not do this (rename without {@code canRename}, purge without op). */
    public static final String LANG_CMD_DENIED = DOMAIN + ".cmd.denied";
    /** No sensor the viewer may see matches the prefix. */
    public static final String LANG_CMD_NOT_FOUND = DOMAIN + ".cmd.not_found";
    /** Section 11: an ambiguous prefix, followed by up to five {@link #LANG_CMD_AMBIGUOUS_ROW} lines. */
    public static final String LANG_CMD_AMBIGUOUS = DOMAIN + ".cmd.ambiguous";
    public static final String LANG_CMD_AMBIGUOUS_ROW = DOMAIN + ".cmd.ambiguous.row";
    public static final String LANG_CMD_LIST_HEADER = DOMAIN + ".cmd.list.header";
    public static final String LANG_CMD_LIST_ROW = DOMAIN + ".cmd.list.row";
    public static final String LANG_CMD_LIST_EMPTY = DOMAIN + ".cmd.list.empty";
    public static final String LANG_CMD_INFO_HEADER = DOMAIN + ".cmd.info.header";
    public static final String LANG_CMD_INFO_OWNER = DOMAIN + ".cmd.info.owner";
    public static final String LANG_CMD_INFO_STATE = DOMAIN + ".cmd.info.state";
    public static final String LANG_CMD_INFO_WHERE = DOMAIN + ".cmd.info.where";
    public static final String LANG_CMD_INFO_MACHINE = DOMAIN + ".cmd.info.machine";
    /** One per window: the 5-minute and the 24-hour summary of section 7.5. */
    public static final String LANG_CMD_INFO_WINDOW = DOMAIN + ".cmd.info.window";
    public static final String LANG_CMD_INFO_GAPS = DOMAIN + ".cmd.info.gaps";
    public static final String LANG_CMD_INFO_GAPS_NONE = DOMAIN + ".cmd.info.gaps.none";
    /** The section 8.4 "loading history..." gate: the rings are not merged with the file yet. */
    public static final String LANG_CMD_INFO_LOADING = DOMAIN + ".cmd.info.loading";
    /** A tombstone has no rings at all (section 4.2), so it has no summaries either. */
    public static final String LANG_CMD_INFO_NO_HISTORY = DOMAIN + ".cmd.info.no_history";
    public static final String LANG_CMD_LABEL_SET = DOMAIN + ".cmd.label.set";
    public static final String LANG_CMD_LABEL_CLEARED = DOMAIN + ".cmd.label.cleared";
    public static final String LANG_CMD_LABEL_TOO_LONG = DOMAIN + ".cmd.label.too_long";
    /** Section 3.5: a label write never loads a chunk, so an unloaded sensor is refused. */
    public static final String LANG_CMD_LABEL_NOT_LOADED = DOMAIN + ".cmd.label.not_loaded";
    public static final String LANG_CMD_LABEL_COOLDOWN = DOMAIN + ".cmd.label.cooldown";
    public static final String LANG_CMD_PURGE_ONE = DOMAIN + ".cmd.purge.one";
    public static final String LANG_CMD_PURGE_BULK = DOMAIN + ".cmd.purge.bulk";
    public static final String LANG_CMD_PURGE_NONE = DOMAIN + ".cmd.purge.none";
    public static final String LANG_CMD_STATS_SENSORS = DOMAIN + ".cmd.stats.sensors";
    /** Section 6.3: the 1,024-bucket log histogram's quantiles over the last 60-second window. */
    public static final String LANG_CMD_STATS_TIMING = DOMAIN + ".cmd.stats.timing";
    public static final String LANG_CMD_STATS_WORK = DOMAIN + ".cmd.stats.work";
    public static final String LANG_CMD_STATS_REGISTRY = DOMAIN + ".cmd.stats.registry";
    public static final String LANG_CMD_STATS_IO = DOMAIN + ".cmd.stats.io";
    public static final String LANG_CMD_STATS_MEMORY = DOMAIN + ".cmd.stats.memory";
    public static final String LANG_CMD_STATS_FRAME = DOMAIN + ".cmd.stats.frame";

    /** {@code gregscope.state.<MachineState id>}, one per state. */
    public static final String LANG_STATE_PREFIX = DOMAIN + ".state.";
    /** {@code gregscope.gap.<GapReason id>}, one per reason, derived ones included. */
    public static final String LANG_GAP_PREFIX = DOMAIN + ".gap.";

    private GregScopeAssets() {}

    /** The tooltip lines of the Machine Sensor item, in order. */
    public static String[] sensorTooltipKeys() {
        return new String[] { LANG_TOOLTIP_SENSOR_1, LANG_TOOLTIP_SENSOR_2, LANG_TOOLTIP_SENSOR_3 };
    }

    /** The {@code /gregscope} usage block (design-v0.2 section 11), header first, one line per subcommand. */
    public static String[] commandUsageKeys() {
        return new String[] { LANG_CMD_USAGE, LANG_CMD_USAGE_STATS, LANG_CMD_USAGE_LIST, LANG_CMD_USAGE_INFO,
            LANG_CMD_USAGE_LABEL, LANG_CMD_USAGE_PURGE };
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
