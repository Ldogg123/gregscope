package io.github.ldogg123.gregscope.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;

import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeAssets;
import io.github.ldogg123.gregscope.access.AccessPolicy;
import io.github.ldogg123.gregscope.access.GtnhlibTeamResolver;
import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.access.Viewer;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapRanges;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.HistoryIo;
import io.github.ldogg123.gregscope.history.MinuteRing;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.history.SizeCeilings;
import io.github.ldogg123.gregscope.history.Summaries;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.SnapshotKeys;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorRegistryCore;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.SamplerStatsView;
import io.github.ldogg123.gregscope.sampling.TelemetryFrame;
import io.github.ldogg123.gregscope.sensor.Labels;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * {@code /gregscope} (design-v0.2 section 11): the Minecraft adapter around the pure {@link CommandArgs}.
 *
 * <p>
 * <b>Permissions.</b> Section 11 registers the command at level 0 and lets every subcommand check its own rule
 * through {@link AccessPolicy}. Level 0 alone is not enough in 1.7.10: {@code EntityPlayerMP} answers
 * {@code canCommandSenderUseCommand} with false for every player who is not on the ops list, <em>at any level,
 * level 0 included</em> ({@code EntityPlayerMP.java:1077-1098}), so {@code CommandBase}'s default gate would hide the
 * command from ordinary players. {@link #canCommandSenderUseCommand(ICommandSender)} therefore returns true and the
 * real decisions are made per subcommand: {@code list}, {@code info} and {@code stats} are filtered by
 * {@code canView}, {@code label} needs {@code canRename} and {@code purge} is op only. The viewer's own op check is
 * exactly the vanilla one, asked with {@code permissions.opLevel}.
 *
 * <p>
 * <b>Output.</b> Every line is a {@link ChatComponentTranslation} whose key is a {@code gregscope.cmd.*} constant of
 * {@link GregScopeAssets}, sent to the sender only, and <b>no line is logged</b> (section 11: "No INFO log line per
 * call"). The arguments inside a line are data, not translated text: state names, gap ids and sensor kinds travel as
 * their stable ids, the same ones OpenComputers and the exporter model use, so a script that reads chat sees the same
 * words on every client.
 *
 * <p>
 * <b>Never loads a chunk.</b> Everything is answered from the registry and the history rings, which are RAM. The one
 * write, {@code label}, goes through {@code SensorRegistry.writeLabel}, whose cover lookup is
 * {@code DimensionManager.getWorld} plus {@code World.blockExists} - a hash lookup that refuses an unloaded sensor
 * rather than loading it (section 3.5).
 */
public final class GregScopeCommand extends CommandBase {

    /** The command name, and the permission node handed to {@code canCommandSenderUseCommand}. */
    public static final String NAME = "gregscope";

    private static final String[] SUBCOMMANDS = { "stats", "list", "info", "label", "purge" };

    /** How many gap reasons the 24 h line names before it stops. */

    /**
     * Design-v0.3-buffers: what the machine is holding, one line per direction, omitted entirely when there is
     * nothing to say. The percentage is a <b>level</b>, not a rate - GregScope cannot measure throughput - so the
     * line deliberately reads "Input: 45%" and never anything per second.
     */
    private static void sendBuffers(ICommandSender sender, MachineSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        Map<String, Object> map = snapshot.toMap();
        sendDirection(sender, map, "Input", SnapshotKeys.INPUTS, SnapshotKeys.INPUT_SATURATION);
        sendDirection(sender, map, "Output", SnapshotKeys.OUTPUTS, SnapshotKeys.OUTPUT_SATURATION);
    }

    private static void sendDirection(ICommandSender sender, Map<String, Object> map, String label, String linesKey,
        String saturationKey) {
        Object raw = map.get(linesKey);
        if (!(raw instanceof List)) {
            return;
        }
        List<?> lines = (List<?>) raw;
        Object saturation = map.get(saturationKey);
        String fill = "-";
        if (saturation instanceof Number) {
            double ratio = ((Number) saturation).doubleValue();
            // NaN means "nothing here reports a capacity", which is not the same as empty and must not read as 0%.
            fill = Double.isNaN(ratio) ? "n/a" : Math.round(ratio * 100.0D) + "%";
        }
        StringBuilder held = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                held.append(", ");
            }
            held.append(lines.get(i));
        }
        send(
            sender,
            GregScopeAssets.LANG_CMD_INFO_BUFFERS,
            label,
            fill,
            held.length() == 0 ? "empty" : held.toString());
    }

    private static final int MAX_GAP_REASONS = 4;

    @Override
    public String getCommandName() {
        return NAME;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return GregScopeAssets.LANG_CMD_USAGE;
    }

    /** Section 11: the command itself is level 0; the subcommands carry the rules. */
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    /**
     * True for everyone. See the class comment: vanilla's own check is false for a non-op player even at level 0, so
     * deferring to it would make section 11's "any player" rows unreachable.
     */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    /** Completes subcommand names and {@code list} filters only; never sensor ids, which are scoped per viewer. */
    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        }
        if (args.length == 2 && "list".equals(CommandArgs.lower(args[0]))) {
            List<String> filters = new ArrayList<>();
            for (CommandArgs.Filter filter : CommandArgs.Filter.values()) {
                filters.add(filter.id());
            }
            return getListOfStringsMatchingLastWord(args, filters.toArray(new String[0]));
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        SensorRegistry registry = GregScope.registry();
        if (registry == null) {
            send(sender, GregScopeAssets.LANG_CMD_NO_SERVER);
            return;
        }
        CommandArgs.Parsed parsed = CommandArgs.parse(args);
        if (!parsed.ok()) {
            sendParseError(sender, parsed);
            return;
        }
        Settings settings = GregScope.settings();
        AccessPolicy policy = new AccessPolicy(settings);
        TeamResolver<?> teams = new GtnhlibTeamResolver();
        Viewer viewer = viewerOf(sender);
        long now = GregScope.clock()
            .epochSec();
        switch (parsed.sub()) {
            case STATS:
                stats(sender, registry, policy, viewer, teams, settings, now);
                return;
            case LIST:
                list(sender, registry, policy, viewer, teams, parsed, now);
                return;
            case INFO:
                info(sender, registry, policy, viewer, teams, parsed, settings, now);
                return;
            case LABEL:
                label(sender, registry, policy, viewer, teams, parsed);
                return;
            case PURGE:
                purge(sender, registry, policy, viewer, teams, parsed, now);
                return;
            default:
                send(sender, GregScopeAssets.LANG_CMD_USAGE);
                return;
        }
    }

    // --- subcommands ---

    /** Section 6.3: the documented budget numbers, the histogram quantiles, the I/O queue and the size estimates. */
    private void stats(ICommandSender sender, SensorRegistry registry, AccessPolicy policy, Viewer viewer,
        TeamResolver<?> teams, Settings settings, long now) {
        SensorRegistryCore core = registry.core();
        Map<SensorState, Integer> visible = new EnumMap<>(SensorState.class);
        for (SensorState state : SensorState.values()) {
            visible.put(state, Integer.valueOf(0));
        }
        for (SensorEntry entry : core.entries()) {
            if (policy.canView(viewer, entry.owner(), teams)) {
                visible.put(
                    entry.state(),
                    Integer.valueOf(
                        visible.get(entry.state())
                            .intValue() + 1));
            }
        }
        send(
            sender,
            GregScopeAssets.LANG_CMD_STATS_SENSORS,
            visible.get(SensorState.LIVE),
            visible.get(SensorState.UNLOADED),
            visible.get(SensorState.MISSING),
            visible.get(SensorState.IN_ITEM),
            visible.get(SensorState.REMOVED),
            Integer.valueOf(settings.maxSensors()));
        TelemetryFrame frame = GregScope.frame();
        SamplerStatsView stats = frame.stats();
        send(
            sender,
            GregScopeAssets.LANG_CMD_STATS_TIMING,
            Long.valueOf(stats.cycleMicrosP50()),
            Long.valueOf(stats.cycleMicrosP99()),
            Long.valueOf(stats.cycleMicrosMax()),
            Long.valueOf(stats.windowTicks()),
            Integer.valueOf(settings.tickBudgetMicros()));
        send(
            sender,
            GregScopeAssets.LANG_CMD_STATS_WORK,
            Long.valueOf(stats.cyclesTotal()),
            Long.valueOf(stats.samplesTotal()),
            Long.valueOf(stats.budgetExceededTicksTotal()),
            Long.valueOf(stats.samplingSkippedTotal()),
            Long.valueOf(stats.probeErrorsTotal()));
        send(
            sender,
            GregScopeAssets.LANG_CMD_STATS_REGISTRY,
            Long.valueOf(stats.duplicatesRekeyedTotal()),
            Long.valueOf(stats.quotaRefusedTotal()),
            Long.valueOf(stats.clockSkewRefusedTotal()));
        HistoryIo io = GregScope.historyIo();
        send(
            sender,
            GregScopeAssets.LANG_CMD_STATS_IO,
            Integer.valueOf(io == null ? 0 : io.queued()),
            Integer.valueOf(io == null ? 0 : io.queueCapacity()),
            Long.valueOf(stats.ioQueuedTotal()),
            Long.valueOf(stats.ioDroppedTotal()),
            Long.valueOf(stats.ioErrorsTotal()));
        int entries = core.size();
        int withRings = core.countedSensors();
        send(
            sender,
            GregScopeAssets.LANG_CMD_STATS_MEMORY,
            CommandArgs.bytes(
                SizeCeilings.ramBytes(withRings) + (long) (entries - withRings) * SizeCeilings.RAM_BYTES_PER_TOMBSTONE),
            CommandArgs.bytes(SizeCeilings.historyDiskBytes(entries)),
            settings.historyPersist() ? "on" : "off");
        send(
            sender,
            GregScopeAssets.LANG_CMD_STATS_FRAME,
            Long.valueOf(frame.sequence()),
            frame.sequence() == 0L ? "never" : CommandArgs.age(frame.publishedEpochMillis() / 1000L, now),
            Integer.valueOf(frame.intervalTicks()),
            settings.samplingEnabled() ? "on" : "off");
    }

    /** Section 11: 10 per page, filtered by {@code canView}, ordered by sensor id so paging is stable. */
    private void list(ICommandSender sender, SensorRegistry registry, AccessPolicy policy, Viewer viewer,
        TeamResolver<?> teams, CommandArgs.Parsed parsed, long now) {
        List<SensorEntry> rows = new ArrayList<>();
        for (SensorEntry entry : registry.core()
            .entries()) {
            if (policy.canView(viewer, entry.owner(), teams) && matches(entry, parsed.filter(), now)) {
                rows.add(entry);
            }
        }
        Collections.sort(rows, BY_ID);
        if (rows.isEmpty()) {
            send(
                sender,
                GregScopeAssets.LANG_CMD_LIST_EMPTY,
                parsed.filter()
                    .id());
            return;
        }
        int total = rows.size();
        int page = CommandArgs.clampPage(parsed.page(), total);
        int first = CommandArgs.firstIndex(page, total);
        int last = Math.min(total, first + CommandArgs.PAGE_SIZE);
        send(
            sender,
            GregScopeAssets.LANG_CMD_LIST_HEADER,
            parsed.filter()
                .id(),
            Integer.valueOf(page),
            Integer.valueOf(CommandArgs.pageCount(total)),
            Integer.valueOf(total));
        for (int i = first; i < last; i++) {
            SensorEntry entry = rows.get(i);
            send(
                sender,
                GregScopeAssets.LANG_CMD_LIST_ROW,
                Labels.shortId(entry.id()),
                availability(entry, now),
                displayName(entry),
                Integer.valueOf(entry.dim()),
                Integer.valueOf(entry.x()) + "," + entry.y() + "," + entry.z(),
                CommandArgs.age(entry.lastSeenEpochSec(), now));
        }
    }

    /** Section 11: the full record plus the 5-minute and 24-hour summaries of section 7.5. */
    private void info(ICommandSender sender, SensorRegistry registry, AccessPolicy policy, Viewer viewer,
        TeamResolver<?> teams, CommandArgs.Parsed parsed, Settings settings, long now) {
        SensorEntry entry = resolve(sender, registry, policy, viewer, teams, parsed.idPrefix(), now);
        if (entry == null) {
            return;
        }
        send(
            sender,
            GregScopeAssets.LANG_CMD_INFO_HEADER,
            Labels.shortId(entry.id()),
            SensorKind.label(entry.kind()),
            displayName(entry));
        send(
            sender,
            GregScopeAssets.LANG_CMD_INFO_OWNER,
            entry.owner() == null ? "unowned"
                : (entry.ownerName() == null || entry.ownerName()
                    .isEmpty()) ? entry.owner()
                        .toString() : entry.ownerName(),
            entry.label()
                .isEmpty() ? "-" : entry.label());
        send(
            sender,
            GregScopeAssets.LANG_CMD_INFO_STATE,
            availability(entry, now),
            CommandArgs.age(entry.stateSinceEpochSec(), now),
            CommandArgs.age(entry.lastSeenEpochSec(), now),
            CommandArgs.age(entry.lastSampleEpochSec(), now));
        send(
            sender,
            GregScopeAssets.LANG_CMD_INFO_WHERE,
            Integer.valueOf(entry.dim()),
            Integer.valueOf(entry.x()) + "," + entry.y() + "," + entry.z(),
            Integer.valueOf(entry.side()));
        send(
            sender,
            GregScopeAssets.LANG_CMD_INFO_MACHINE,
            entry.machineName()
                .isEmpty() ? "-" : entry.machineName(),
            entry.metaName()
                .isEmpty() ? "-" : entry.metaName(),
            Integer.valueOf(entry.metaId()),
            entry.lastStatusId()
                .isEmpty() ? "-" : entry.lastStatusId());
        sendBuffers(sender, entry.lastSnapshot());
        MinuteRing ring = entry.minutes();
        if (ring == null) {
            send(
                sender,
                GregScopeAssets.LANG_CMD_INFO_NO_HISTORY,
                entry.removalCause()
                    .label());
            return;
        }
        if (!entry.historyLoaded()) {
            send(sender, GregScopeAssets.LANG_CMD_INFO_LOADING);
        }
        int nowMinute = (int) (now / 60L);
        int expected = MinuteSlot.expectedSamples(settings.intervalTicks());
        window(sender, "5 min", Summaries.minutes(ring, nowMinute - 5, nowMinute, expected));
        window(sender, "24 h", Summaries.minutes(ring, nowMinute - MinuteRing.SLOTS, nowMinute, expected));
        gaps(
            sender,
            GapRanges.minutes(
                ring,
                nowMinute - MinuteRing.SLOTS,
                nowMinute,
                registry.core()
                    .gapContext(entry.id(), now)));
    }

    private void window(ICommandSender sender, String name, Summaries.Summary summary) {
        send(
            sender,
            GregScopeAssets.LANG_CMD_INFO_WINDOW,
            name,
            CommandArgs.percent(summary.coverage()),
            Long.valueOf(summary.samples()),
            CommandArgs.percent(summary.stateFraction(StateCodes.RUNNING)),
            summary.euSamples() == 0 ? "-" : Long.toString(summary.euPerTickAvg()),
            summary.recipesCompleted() < 0 ? "-" : Long.toString(summary.recipesCompleted()));
    }

    /** One line per {@code info}: the 24 h gap minutes per reason, biggest first. */
    private void gaps(ICommandSender sender, List<GapRanges.Range> ranges) {
        if (ranges.isEmpty()) {
            send(sender, GregScopeAssets.LANG_CMD_INFO_GAPS_NONE);
            return;
        }
        Map<GapReason, Long> minutes = new EnumMap<>(GapReason.class);
        for (GapRanges.Range range : ranges) {
            Long previous = minutes.get(range.reason);
            minutes.put(
                range.reason,
                Long.valueOf((previous == null ? 0L : previous.longValue()) + (range.to - range.from) / 60L));
        }
        List<Map.Entry<GapReason, Long>> sorted = new ArrayList<>(minutes.entrySet());
        Collections.sort(sorted, BY_MINUTES);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < sorted.size() && i < MAX_GAP_REASONS; i++) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(
                sorted.get(i)
                    .getKey()
                    .id())
                .append(' ')
                .append(
                    sorted.get(i)
                        .getValue())
                .append('m');
        }
        if (sorted.size() > MAX_GAP_REASONS) {
            text.append(", +")
                .append(sorted.size() - MAX_GAP_REASONS);
        }
        send(sender, GregScopeAssets.LANG_CMD_INFO_GAPS, text.toString());
    }

    /** Section 3.5: {@code canRename}, LIVE, the cover is the source of truth, and never a chunk load. */
    private void label(ICommandSender sender, SensorRegistry registry, AccessPolicy policy, Viewer viewer,
        TeamResolver<?> teams, CommandArgs.Parsed parsed) {
        long now = GregScope.clock()
            .epochSec();
        SensorEntry entry = resolve(sender, registry, policy, viewer, teams, parsed.idPrefix(), now);
        if (entry == null) {
            return;
        }
        if (!policy.canRename(viewer, entry.owner(), teams)) {
            send(sender, GregScopeAssets.LANG_CMD_DENIED);
            return;
        }
        String shortId = Labels.shortId(entry.id());
        SensorRegistry.LabelOutcome outcome = registry.writeLabel(entry.id(), parsed.label(), viewer.uuid());
        switch (outcome) {
            case WRITTEN:
                if (parsed.label()
                    .trim()
                    .isEmpty()) {
                    send(sender, GregScopeAssets.LANG_CMD_LABEL_CLEARED, shortId);
                } else {
                    send(sender, GregScopeAssets.LANG_CMD_LABEL_SET, shortId, entry.label());
                }
                return;
            case TOO_LONG:
                send(sender, GregScopeAssets.LANG_CMD_LABEL_TOO_LONG, Integer.valueOf(Labels.MAX_CODE_POINTS));
                return;
            case COOLDOWN:
                send(
                    sender,
                    GregScopeAssets.LANG_CMD_LABEL_COOLDOWN,
                    Integer.valueOf(registry.renameCooldownRemaining(viewer.uuid())));
                return;
            default:
                send(sender, GregScopeAssets.LANG_CMD_LABEL_NOT_LOADED, shortId);
                return;
        }
    }

    /** Section 11: op only, for one sensor or for every tombstone or stale entry. */
    private void purge(ICommandSender sender, SensorRegistry registry, AccessPolicy policy, Viewer viewer,
        TeamResolver<?> teams, CommandArgs.Parsed parsed, long now) {
        if (!policy.canPurge(viewer)) {
            send(sender, GregScopeAssets.LANG_CMD_DENIED);
            return;
        }
        SensorRegistryCore core = registry.core();
        if (parsed.purgeTarget() == CommandArgs.PurgeTarget.ONE) {
            // An op sees every sensor, so the prefix is resolved over the whole registry; canView is still asked, so
            // the "not found" and "ambiguous" answers stay the ones the policy allows.
            SensorEntry entry = resolve(sender, registry, policy, viewer, teams, parsed.idPrefix(), now);
            if (entry == null) {
                return;
            }
            String shortId = Labels.shortId(entry.id());
            send(
                sender,
                registry.purge(entry.id()) ? GregScopeAssets.LANG_CMD_PURGE_ONE : GregScopeAssets.LANG_CMD_PURGE_NONE,
                shortId);
            return;
        }
        boolean tombstones = parsed.purgeTarget() == CommandArgs.PurgeTarget.TOMBSTONES;
        List<UUID> doomed = new ArrayList<>();
        for (SensorEntry entry : core.entries()) {
            boolean hit = tombstones ? entry.state()
                .isTombstone()
                : CommandArgs.isStale(entry.state() == SensorState.UNLOADED, entry.lastSeenEpochSec(), now);
            if (hit) {
                doomed.add(entry.id());
            }
        }
        int purged = 0;
        for (UUID id : doomed) {
            if (registry.purge(id)) {
                purged++;
            }
        }
        String what = tombstones ? "tombstones" : "stale";
        if (purged == 0) {
            send(sender, GregScopeAssets.LANG_CMD_PURGE_NONE, what);
        } else {
            send(sender, GregScopeAssets.LANG_CMD_PURGE_BULK, Integer.valueOf(purged), what);
        }
    }

    // --- shared helpers ---

    /**
     * Resolves an id prefix over the sensors the viewer may see, and answers the section 11 ambiguity rule itself.
     *
     * @return the one matching entry, or null after a "not found" or "ambiguous" message was already sent
     */
    private SensorEntry resolve(ICommandSender sender, SensorRegistry registry, AccessPolicy policy, Viewer viewer,
        TeamResolver<?> teams, String prefix, long now) {
        // Every match is collected rather than the first six: the message says how many there are, and a number
        // that stopped at the listing limit would be a lie. The registry holds at most limits.maxSensors entries
        // plus tombstones, so this stays a bounded scan of RAM.
        List<SensorEntry> found = new ArrayList<>();
        for (SensorEntry entry : registry.core()
            .entries()) {
            if (CommandArgs.matchesPrefix(entry.id(), prefix) && policy.canView(viewer, entry.owner(), teams)) {
                found.add(entry);
            }
        }
        if (found.isEmpty()) {
            send(sender, GregScopeAssets.LANG_CMD_NOT_FOUND, prefix);
            return null;
        }
        if (found.size() == 1) {
            return found.get(0);
        }
        Collections.sort(found, BY_ID);
        send(sender, GregScopeAssets.LANG_CMD_AMBIGUOUS, prefix, Integer.valueOf(found.size()));
        for (int i = 0; i < found.size() && i < CommandArgs.MAX_MATCHES; i++) {
            SensorEntry entry = found.get(i);
            send(
                sender,
                GregScopeAssets.LANG_CMD_AMBIGUOUS_ROW,
                Labels.shortId(entry.id()),
                availability(entry, now),
                displayName(entry));
        }
        return null;
    }

    private static boolean matches(SensorEntry entry, CommandArgs.Filter filter, long now) {
        switch (filter) {
            case LIVE:
                return entry.state() == SensorState.LIVE;
            case UNLOADED:
                return entry.state() == SensorState.UNLOADED;
            case MISSING:
                return entry.state() == SensorState.MISSING;
            case TOMBSTONES:
                return entry.state()
                    .isTombstone();
            case STALE:
                return CommandArgs.isStale(entry.state() == SensorState.UNLOADED, entry.lastSeenEpochSec(), now);
            default:
                return true;
        }
    }

    /** Section 3.5: the label, else the last snapshot name, else the meta name, then {@code #shortId}. */
    private static String displayName(SensorEntry entry) {
        return Labels.displayName(entry.label(), entry.machineName(), entry.metaName(), entry.id());
    }

    /** The word {@code list} and {@code info} show: the state label, with {@code stale} for an old UNLOADED entry. */
    private static String availability(SensorEntry entry, long now) {
        if (CommandArgs.isStale(entry.state() == SensorState.UNLOADED, entry.lastSeenEpochSec(), now)) {
            return "stale";
        }
        return entry.state()
            .label();
    }

    /**
     * Section 5: a player asks with the game's own permission check, scoped to this command; any other sender (the
     * console, RCON, a command block) is the console, which counts as op.
     */
    static Viewer viewerOf(ICommandSender sender) {
        if (sender instanceof EntityPlayer) {
            final EntityPlayer player = (EntityPlayer) sender;
            return Viewer.player(player.getUniqueID(), level -> player.canCommandSenderUseCommand(level, NAME));
        }
        return Viewer.CONSOLE;
    }

    private void sendParseError(ICommandSender sender, CommandArgs.Parsed parsed) {
        switch (parsed.error()) {
            case UNKNOWN_SUBCOMMAND:
                send(sender, GregScopeAssets.LANG_CMD_UNKNOWN, parsed.offending());
                break;
            case MISSING_ID:
                send(sender, GregScopeAssets.LANG_CMD_MISSING_ID);
                break;
            case PREFIX_TOO_SHORT:
                send(sender, GregScopeAssets.LANG_CMD_PREFIX_SHORT, Integer.valueOf(CommandArgs.MIN_PREFIX));
                break;
            case BAD_PAGE:
                send(sender, GregScopeAssets.LANG_CMD_BAD_PAGE, parsed.offending());
                break;
            case BAD_FILTER:
                send(sender, GregScopeAssets.LANG_CMD_BAD_FILTER, parsed.offending());
                break;
            case LABEL_TOO_LONG:
                send(sender, GregScopeAssets.LANG_CMD_LABEL_TOO_LONG, Integer.valueOf(Labels.MAX_CODE_POINTS));
                break;
            default:
                break;
        }
        for (String key : GregScopeAssets.commandUsageKeys()) {
            send(sender, key);
        }
    }

    /** Section 11: to the sender only, and never to the log. */
    private static void send(ICommandSender sender, String key, Object... args) {
        sender.addChatMessage(new ChatComponentTranslation(key, args));
    }

    private static final Comparator<SensorEntry> BY_ID = new Comparator<SensorEntry>() {

        @Override
        public int compare(SensorEntry a, SensorEntry b) {
            return a.id()
                .compareTo(b.id());
        }
    };

    private static final Comparator<Map.Entry<GapReason, Long>> BY_MINUTES = new Comparator<Map.Entry<GapReason, Long>>() {

        @Override
        public int compare(Map.Entry<GapReason, Long> a, Map.Entry<GapReason, Long> b) {
            int byMinutes = b.getValue()
                .compareTo(a.getValue());
            return byMinutes != 0 ? byMinutes
                : a.getKey()
                    .id()
                    .compareTo(
                        b.getKey()
                            .id());
        }
    };

    /** The subcommand names, for the usage lines and tab completion. */
    public static List<String> subcommands() {
        return Collections.unmodifiableList(Arrays.asList(SUBCOMMANDS));
    }
}
