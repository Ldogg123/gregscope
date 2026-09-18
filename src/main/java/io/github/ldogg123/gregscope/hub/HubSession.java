package io.github.ldogg123.gregscope.hub;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;

import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.access.AccessPolicy;
import io.github.ldogg123.gregscope.access.GtnhlibTeamResolver;
import io.github.ldogg123.gregscope.access.Viewer;
import io.github.ldogg123.gregscope.history.GapRanges;
import io.github.ldogg123.gregscope.history.MinuteSource;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.sensor.Labels;

/**
 * One viewer's Telemetry Hub view on the server, and the same viewer's received copy on the client: the object whose
 * fields design-v0.2 section 9.3 says the sync getters return.
 *
 * <p>
 * <b>Two sides, one shape.</b> A session is built on both sides, because {@code buildUI} runs on both. On the server
 * it owns a {@link HubViewModel} and every getter answers from a rebuild; on the client there is no registry, so
 * {@link #isClient()} is true, the model is null, {@link #refresh()} does nothing, and the setters the sync handlers
 * call are what fills the same fields. That is why every getter below is safe to call on either side.
 *
 * <p>
 * <b>Stable references (section 14 acceptance criterion).</b> {@link #header()}, {@link #rows()} and
 * {@link #detail()} return the very same objects until a rebuild really happens, because
 * {@link HubViewModel#rebuild} is throttled on {@code frame.sequence()} plus the session inputs and this class only
 * copies its outputs when the rebuild reports work. ModularUI2 compares a synced value with {@code Objects.equals},
 * so an unchanged reference is what keeps an idle Hub from sending three DTOs per tick.
 *
 * <p>
 * <b>Validation (section 9.3).</b> Every setter is the server-side entry point of a C2S packet, so each one clamps or
 * refuses rather than trusting the number: the page into {@code [0, pages-1]}, the filter into {0,1}, the selection
 * into a row index on the viewer's current page, and the label through {@link Labels#acceptsInput},
 * {@link AccessPolicy#canRename} and {@link SensorRegistry#writeLabel} (which owns the "LIVE only", the live cover
 * lookup and the rename cooldown). A refused value is simply not stored, so the getter still returns the accepted one
 * and ModularUI2's next {@code detectAndSendChanges} pushes it back to the client.
 *
 * <p>
 * <b>No rebuild on packet arrival (section 6.3).</b> None of the four C2S setters calls {@link #refresh()}: each one
 * only clamps its value and marks the view model dirty, which costs a comparison. The rebuild happens in the getters,
 * and the getters are driven by ModularUI2's {@code detectAndSendChanges}, so a client that sends a hundred
 * {@code gs_select} packets in one tick pays for <em>one</em> rebuild, at the next container update, instead of a
 * hundred. Letting a packet rebuild was a tick-time denial of service: a rebuild scans and sorts the whole scope and,
 * with a row selected, reads about 2,885 minute slots.
 *
 * <p>
 * <b>Denied sessions.</b> ModularUI2's {@code OpenGuiPacket} is a client-to-server packet carrying client-chosen
 * coordinates, so {@code buildUI} - not the right-click - is the real entry point of the section 5 access check and
 * the section 9.1 view cap. A session built for a viewer who fails either is {@link #isDenied()}: it owns no view
 * model, every setter is a no-op, every getter answers the empty DTOs, and
 * {@code TileTelemetryHub.canInteractWith} refuses at once, so the container closes without a single row ever
 * reaching the client.
 */
public final class HubSession {

    /** Section 9.3 {@code gs_select}: no row selected. */
    public static final int NO_ROW = -1;

    /**
     * Section 9.3 {@code canInteractWith}: the range check runs every tick, the access re-check every 100 ticks. The
     * counter is the number of interaction checks, which Minecraft makes once per player tick
     * ({@code EntityPlayer.onUpdate}), so this is 5 seconds.
     */
    public static final int ACCESS_RECHECK_TICKS = 100;

    private final boolean client;
    private final boolean denied;
    private final UUID viewer;
    private final Viewer access;
    private final UUID hubOwner;
    private final HubViewModel model;
    private final RegistryHistory history;

    private HubHeader header = HubHeader.EMPTY;
    private List<HubRow> rows = HubViewModel.emptyPage();
    private HubDetail detail = HubDetail.NONE;
    private int page;
    private int filter = HubViewModel.FILTER_ALL;
    private int selectedRow = NO_ROW;

    private String label = "";
    private UUID labelOwner;

    private int interactionChecks;
    private boolean accessOk = true;

    /**
     * @param tile   the Hub being looked at; its owner scopes every row (section 5)
     * @param player the viewer, from {@code PosGuiData}
     * @param client true when this session belongs to the client copy of the panel
     */
    public HubSession(TileTelemetryHub tile, EntityPlayer player, boolean client) {
        this(tile, player, client, false);
    }

    /**
     * @param tile   the Hub being looked at; its owner scopes every row (section 5)
     * @param player the viewer, from {@code PosGuiData}
     * @param client true when this session belongs to the client copy of the panel
     * @param denied true when the server refused this viewer the section 5 access check or the section 9.1 view cap;
     *               such a session carries no data at all and accepts nothing
     */
    public HubSession(TileTelemetryHub tile, EntityPlayer player, boolean client, boolean denied) {
        this.client = client;
        this.denied = denied && !client;
        this.viewer = player == null ? null : player.getUniqueID();
        this.hubOwner = tile == null ? null : tile.owner();
        if (client || this.denied) {
            this.access = null;
            this.model = null;
            this.history = null;
            return;
        }
        final EntityPlayer viewing = player;
        this.access = player == null ? null
            : Viewer.player(player.getUniqueID(), level -> viewing.canCommandSenderUseCommand(level, GregScope.MODID));
        this.model = new HubViewModel(
            hubOwner,
            tile == null ? "" : tile.ownerName(),
            tile != null && tile.unsupported());
        this.history = new RegistryHistory();
    }

    public boolean isClient() {
        return client;
    }

    /**
     * True when the server refused this viewer the Hub: the panel exists, but it carries nothing, accepts nothing and
     * is closed by {@code canInteractWith} on the first player tick.
     */
    public boolean isDenied() {
        return denied;
    }

    /** The player this view belongs to; null only for a session built without a player. */
    public UUID viewer() {
        return viewer;
    }

    /** The Hub's owner, or null when it is unowned or its record is of an unsupported version. */
    public UUID hubOwner() {
        return hubOwner;
    }

    /** How many times the view model really rebuilt; 0 on the client. The rebuild throttle's evidence. */
    public int rebuilds() {
        return model == null ? 0 : model.rebuilds();
    }

    // --- the section 9.3 outputs ---

    public HubHeader header() {
        refresh();
        return header;
    }

    /** Exactly {@link HubViewModel#ROWS_PER_PAGE} rows, padded with {@link HubRow#EMPTY}; unmodifiable. */
    public List<HubRow> rows() {
        refresh();
        return rows;
    }

    public HubDetail detail() {
        refresh();
        return detail;
    }

    public void setHeader(HubHeader value) {
        if (denied) {
            return;
        }
        header = value == null ? HubHeader.EMPTY : value;
    }

    public void setRows(List<HubRow> value) {
        if (denied) {
            return;
        }
        rows = value == null ? HubViewModel.emptyPage() : Collections.unmodifiableList(value);
    }

    public void setDetail(HubDetail value) {
        if (denied) {
            return;
        }
        detail = value == null ? HubDetail.NONE : value;
    }

    // --- the section 9.3 inputs ---

    public int page() {
        refresh();
        return page;
    }

    /**
     * Section 9.3 {@code gs_page}: clamped into {@code [0, pages-1]}, so -5 becomes 0 and 999 the last page.
     *
     * <p>
     * The clamp is against the <em>last built</em> page count, which is what a value arriving from a client can be
     * checked against; {@link HubViewModel#rebuild} clamps again against the new count, which is what carries a viewer
     * back when the list shrinks under them. Deliberately no {@link #refresh()}: see the class javadoc.
     */
    public void setPage(int value) {
        if (denied) {
            return;
        }
        if (model != null) {
            model.setPage(value);
            page = model.page();
            return;
        }
        page = clamp(value, 0, Math.max(1, header.pages()) - 1);
    }

    public int filter() {
        refresh();
        return filter;
    }

    /** Section 9.3 {@code gs_filter}: anything outside {0,1} becomes {@link HubViewModel#FILTER_ALL}. */
    public void setFilter(int value) {
        if (denied) {
            return;
        }
        if (model != null) {
            model.setFilter(value);
            filter = model.filter();
            page = model.page();
            return;
        }
        filter = HubViewModel.clampFilter(value);
        page = 0;
    }

    /** The row index of the selected sensor on the viewer's current page, or {@link #NO_ROW}. */
    public int selectedRow() {
        refresh();
        return selectedRow;
    }

    /**
     * Section 9.3 {@code gs_select}: a row index {@code 0..7} resolves to that row's <b>UUID</b>; any other index, or
     * an empty row, selects nothing. The selection is a UUID, so it survives resorting and paging.
     */
    public void setSelectedRow(int value) {
        if (denied) {
            return;
        }
        if (model != null) {
            model.selectRow(value);
            selectedRow = rowOf(model.selected());
            return;
        }
        selectedRow = value >= 0 && value < HubViewModel.ROWS_PER_PAGE ? value : NO_ROW;
    }

    /** The label field's current text: what the selected sensor is called, or the last value the server accepted. */
    public String label() {
        refresh();
        return label;
    }

    /**
     * Section 9.3 {@code gs_label}. Refuses, without storing anything, when: the text is longer than
     * {@link Labels#MAX_INPUT_UNITS} units before sanitizing; nothing is selected; the viewer may not rename the
     * selected sensor; or {@link SensorRegistry#writeLabel} refuses it (not LIVE, no reachable cover, or the rename
     * cooldown). On a refusal the accepted value stays in place, and ModularUI2's next {@code detectAndSendChanges}
     * pushes it back to the client, which is what section 9.3 means by "on rejection the value resets".
     *
     * <p>
     * The permission is asked of {@link AccessPolicy#canRename} here and now, against the selected sensor's current
     * owner, rather than read off the last built {@link HubDetail#canEdit()}: {@code canEdit} is the <em>client's</em>
     * hint for greying the text field out, it is up to one sampling interval old, and reading it would have meant
     * calling {@link #refresh()} on every {@code gs_label} packet - the rebuild amplification the class javadoc
     * describes. {@code writeLabel} still owns the LIVE check, the live cover lookup and the rename cooldown, which is
     * the other half of {@code canEdit}.
     *
     * @return true if the label was written
     */
    public boolean setLabel(String value) {
        if (denied) {
            return false;
        }
        if (model == null) {
            label = value == null ? "" : value;
            return true;
        }
        if (value == null || !Labels.acceptsInput(value)) {
            return false;
        }
        UUID selected = model.selected();
        if (selected == null) {
            return false;
        }
        SensorRegistry registry = GregScope.registry();
        if (registry == null) {
            return false;
        }
        SensorEntry target = registry.core()
            .entry(selected);
        if (target == null || !canRename(target.owner())) {
            return false;
        }
        if (registry.writeLabel(selected, value, viewer) != SensorRegistry.LabelOutcome.WRITTEN) {
            return false;
        }
        label = Labels.sanitize(value);
        labelOwner = selected;
        return true;
    }

    /** Section 5's rename rule for this viewer, asked against the sensor's current owner. */
    private boolean canRename(UUID sensorOwner) {
        return new AccessPolicy(GregScope.settings())
            .canRename(access == null ? Viewer.CONSOLE : access, sensorOwner, new GtnhlibTeamResolver());
    }

    // --- canInteractWith (section 9.3) ---

    /**
     * The access half of section 9.3's {@code canInteractWith}: the section 5 check the right-click already made,
     * re-asked every {@link #ACCESS_RECHECK_TICKS} calls. Losing access closes the GUI, because ModularUI2 asks this
     * every tick and Minecraft closes a container that answers false.
     */
    public boolean stillAllowed(TileTelemetryHub tile, EntityPlayer player) {
        if (denied || tile == null || player == null) {
            return false;
        }
        if (interactionChecks++ % ACCESS_RECHECK_TICKS == 0) {
            accessOk = tile.canOpen(player);
        }
        return accessOk;
    }

    // --- the rebuild ---

    /**
     * Brings the three DTOs up to date. Cheap when nothing changed: {@link HubViewModel#rebuild} compares the frame
     * sequence and the session inputs and answers false, and then nothing here is touched, which is what keeps the
     * getters returning the same references.
     */
    public void refresh() {
        if (model == null) {
            return;
        }
        long now = GregScope.clock()
            .epochSec();
        model.setSensorsAbandoned(abandoned());
        if (!model.rebuild(
            GregScope.frame(),
            new AccessPolicy(GregScope.settings()),
            access == null ? Viewer.CONSOLE : access,
            new GtnhlibTeamResolver(),
            history,
            now)) {
            return;
        }
        header = model.header();
        rows = model.rows();
        detail = model.detail();
        page = model.page();
        filter = model.filter();
        selectedRow = rowOf(model.selected());
        UUID selected = detail.id();
        if (selected == null) {
            labelOwner = null;
            label = "";
        } else if (!selected.equals(labelOwner)) {
            labelOwner = selected;
            label = detail.label();
        }
    }

    /**
     * The {@code HistoryPersistence.sensorsAbandoned()} counter the header shows (design-v0.2 section 8.4): how many
     * sensors stopped being written after {@code MAX_FILE_FAILURES} failures. It is 0 on a healthy server, and the
     * carry-over GS-113 left open is exactly this wiring.
     */
    private static int abandoned() {
        if (GregScope.history() == null) {
            return 0;
        }
        long value = GregScope.history()
            .sensorsAbandoned();
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private int rowOf(UUID id) {
        if (id == null) {
            return NO_ROW;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (id.equals(
                rows.get(i)
                    .id())) {
                return i;
            }
        }
        return NO_ROW;
    }

    private static int clamp(int value, int low, int high) {
        if (value < low) {
            return low;
        }
        return value > high ? high : value;
    }

    /** {@link HubViewModel.History} over the live registry: the rings, the load flag and the section 7.5 runs. */
    private static final class RegistryHistory implements HubViewModel.History {

        @Override
        public MinuteSource minutes(UUID id) {
            SensorEntry entry = entry(id);
            return entry == null ? null : entry.minutes();
        }

        @Override
        public boolean loaded(UUID id) {
            SensorEntry entry = entry(id);
            return entry != null && entry.historyLoaded();
        }

        @Override
        public GapRanges.Context gapContext(UUID id, long nowEpochSec) {
            SensorRegistry registry = GregScope.registry();
            if (registry == null) {
                return GapRanges.Context.of(Collections.<GapRanges.Run>emptyList());
            }
            return registry.core()
                .gapContext(id, nowEpochSec);
        }

        private static SensorEntry entry(UUID id) {
            SensorRegistry registry = GregScope.registry();
            return registry == null ? null
                : registry.core()
                    .entry(id);
        }
    }
}
