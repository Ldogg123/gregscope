package io.github.ldogg123.gregscope.hub;

import java.util.List;

import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.sync.GenericListSyncHandler;
import com.cleanroommc.modularui.value.sync.GenericSyncValue;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import io.github.ldogg123.gregscope.GregScopeAssets;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.sensor.Labels;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * The Telemetry Hub's ModularUI2 panel: design-v0.2 sections 9.2 (layout) and 9.3 (sync handlers).
 *
 * <p>
 * <b>It does not depend on the host.</b> Everything it reads and writes goes through one {@link HubSession} and the
 * seven handlers registered by name below, so the same code builds the panel on the dedicated server (where the
 * session owns a {@link HubViewModel}) and on the client (where the session is only the received copy). Nothing here
 * touches a {@code World}, a tile entity or the registry.
 *
 * <p>
 * <b>No synced actions.</b> Section 9.3 forbids {@code registerSyncedAction}, so a button never runs server code: it
 * writes a number into one of the four C2S values, and the server's setter validates it
 * ({@link HubSession#setPage}, {@link HubSession#setFilter}, {@link HubSession#setSelectedRow},
 * {@link HubSession#setLabel}). The three S2C values carry the DTOs and accept nothing from a client.
 *
 * <p>
 * <b>Drawing.</b> The widgets carry {@link IKey#dynamic} suppliers, which ModularUI2 only calls while drawing, i.e.
 * on the client; the server builds the same widget tree and never runs them. Every string they build is ASCII and
 * formatted from lang keys, as section 9.2's "text strip" note requires. GS-121 checks the rendering by hand.
 */
public final class HubPanel {

    /** The panel name both sides register their sync handlers under. */
    public static final String PANEL_NAME = "gregscope_hub";

    /** Section 9.3, C2S. */
    public static final String SYNC_PAGE = "gs_page";
    /** Section 9.3, C2S. */
    public static final String SYNC_FILTER = "gs_filter";
    /** Section 9.3, C2S. */
    public static final String SYNC_SELECT = "gs_select";
    /** Section 9.3, C2S. */
    public static final String SYNC_LABEL = "gs_label";
    /** Section 9.3, S2C only. */
    public static final String SYNC_ROWS = "gs_rows";
    /** Section 9.3, S2C only. */
    public static final String SYNC_HEADER = "gs_header";
    /** Section 9.3, S2C only. */
    public static final String SYNC_DETAIL = "gs_detail";

    /** Section 9.2: the panel is about 260x210 and carries no player inventory. */
    public static final int WIDTH = 260;
    public static final int HEIGHT = 210;

    private static final int ROW_HEIGHT = 11;
    private static final int LINE_HEIGHT = 9;

    private HubPanel() {}

    /**
     * Registers the seven named handlers of section 9.3 and builds the section 9.2 layout.
     *
     * @param session the per-viewer session both sides read through
     */
    public static ModularPanel build(HubSession session, PanelSyncManager syncManager) {
        IntSyncValue page = new IntSyncValue(session::page, session::setPage);
        page.allowC2S();
        IntSyncValue filter = new IntSyncValue(session::filter, session::setFilter);
        filter.allowC2S();
        IntSyncValue select = new IntSyncValue(session::selectedRow, session::setSelectedRow);
        select.allowC2S();
        StringSyncValue label = new StringSyncValue(session::label, text -> session.setLabel(text));
        label.allowC2S();

        GenericSyncValue<HubHeader, ?> header = GenericSyncValue.builder(HubHeader.class)
            .getter(session::header)
            .setter(session::setHeader)
            .serializer(HubPacketIo.HEADER_OUT)
            .deserializer(HubPacketIo.HEADER_IN)
            .copyImmutable()
            .build();
        GenericSyncValue<HubDetail, ?> detail = GenericSyncValue.builder(HubDetail.class)
            .getter(session::detail)
            .setter(session::setDetail)
            .serializer(HubPacketIo.DETAIL_OUT)
            .deserializer(HubPacketIo.DETAIL_IN)
            .copyImmutable()
            .build();
        GenericListSyncHandler<HubRow> rows = GenericListSyncHandler.<HubRow>builder()
            .getter(session::rows)
            .setter(session::setRows)
            .serializer(HubPacketIo.ROW_OUT)
            .deserializer(HubPacketIo.ROW_IN)
            .immutableCopy()
            .build();

        syncManager.syncValue(SYNC_PAGE, page);
        syncManager.syncValue(SYNC_FILTER, filter);
        syncManager.syncValue(SYNC_SELECT, select);
        syncManager.syncValue(SYNC_LABEL, label);
        syncManager.syncValue(SYNC_HEADER, header);
        syncManager.syncValue(SYNC_ROWS, rows);
        syncManager.syncValue(SYNC_DETAIL, detail);

        Flow list = Flow.column()
            .fullWidth()
            .coverChildrenHeight()
            .childPadding(1);
        for (int i = 0; i < HubViewModel.ROWS_PER_PAGE; i++) {
            list.child(row(session, select, i));
        }

        return ModularPanel.defaultPanel(PANEL_NAME, WIDTH, HEIGHT)
            .padding(6)
            .child(
                Flow.column()
                    .fullWidth()
                    .coverChildrenHeight()
                    .childPadding(2)
                    .crossAxisAlignment(Alignment.CrossAxis.START)
                    .child(
                        Flow.row()
                            .fullWidth()
                            .height(LINE_HEIGHT)
                            .child(
                                IKey.dynamic(() -> headerLine(session))
                                    .asWidget()
                                    .height(LINE_HEIGHT)
                                    .widthRel(0.8F))
                            .child(filterButton(session, filter)))
                    .child(list)
                    .child(pager(session, page))
                    .child(labelRow(session, label))
                    .child(detailLines(session)));
    }

    // --- widgets ---

    private static ButtonWidget<?> filterButton(HubSession session, IntSyncValue filter) {
        ButtonWidget<?> button = new ButtonWidget<>();
        return button.size(52, LINE_HEIGHT)
            .overlay(IKey.dynamic(() -> filterLabel(session)))
            .onMousePressed(mouseButton -> {
                filter.setIntValue(
                    session.filter() == HubViewModel.FILTER_PROBLEMS ? HubViewModel.FILTER_ALL
                        : HubViewModel.FILTER_PROBLEMS,
                    true,
                    true);
                return true;
            });
    }

    private static ButtonWidget<?> row(HubSession session, IntSyncValue select, int index) {
        ButtonWidget<?> button = new ButtonWidget<>();
        return button.fullWidth()
            .height(ROW_HEIGHT)
            .overlay(
                IKey.dynamic(() -> rowLine(session, index))
                    .alignment(Alignment.CenterLeft))
            .onMousePressed(mouseButton -> {
                select.setIntValue(session.selectedRow() == index ? HubSession.NO_ROW : index, true, true);
                return true;
            });
    }

    private static Flow pager(HubSession session, IntSyncValue page) {
        ButtonWidget<?> previous = new ButtonWidget<>();
        ButtonWidget<?> next = new ButtonWidget<>();
        return Flow.row()
            .fullWidth()
            .height(LINE_HEIGHT + 2)
            .childPadding(2)
            .child(
                previous.size(14, LINE_HEIGHT + 2)
                    .overlay(IKey.str("<"))
                    .onMousePressed(mouseButton -> {
                        page.setIntValue(session.page() - 1, true, true);
                        return true;
                    }))
            .child(
                IKey.dynamic(() -> pageLine(session))
                    .asWidget()
                    .height(LINE_HEIGHT + 2)
                    .width(60))
            .child(
                next.size(14, LINE_HEIGHT + 2)
                    .overlay(IKey.str(">"))
                    .onMousePressed(mouseButton -> {
                        page.setIntValue(session.page() + 1, true, true);
                        return true;
                    }));
    }

    private static Flow labelRow(HubSession session, StringSyncValue label) {
        return Flow.row()
            .fullWidth()
            .height(14)
            .childPadding(3)
            .child(
                IKey.lang(GregScopeAssets.LANG_HUB_LABEL)
                    .asWidget()
                    .height(14)
                    .width(34))
            .child(
                new TextFieldWidget().height(14)
                    .width(150)
                    .setMaxLength(Labels.MAX_INPUT_UNITS)
                    .value(label)
                    .setEnabledIf(
                        widget -> session.detail()
                            .canEdit()));
    }

    private static Flow detailLines(HubSession session) {
        Flow lines = Flow.column()
            .fullWidth()
            .coverChildrenHeight()
            .childPadding(1)
            .crossAxisAlignment(Alignment.CrossAxis.START);
        lines.child(
            IKey.dynamic(() -> detailIdentity(session))
                .asWidget()
                .height(LINE_HEIGHT)
                .fullWidth());
        lines.child(
            IKey.dynamic(() -> detailState(session))
                .asWidget()
                .height(LINE_HEIGHT)
                .fullWidth());
        lines.child(
            IKey.dynamic(() -> detailEnergy(session))
                .asWidget()
                .height(LINE_HEIGHT)
                .fullWidth());
        lines.child(
            IKey.dynamic(() -> windowLine(session, true))
                .asWidget()
                .height(LINE_HEIGHT)
                .fullWidth());
        lines.child(
            IKey.dynamic(() -> windowLine(session, false))
                .asWidget()
                .height(LINE_HEIGHT)
                .fullWidth());
        lines.child(
            IKey.dynamic(() -> stripLine(session))
                .asWidget()
                .height(LINE_HEIGHT)
                .fullWidth());
        lines.child(
            IKey.dynamic(() -> samplerLine(session))
                .asWidget()
                .height(LINE_HEIGHT)
                .fullWidth());
        return lines;
    }

    // --- the text of section 9.2, built from the DTOs and lang keys ---

    static String headerLine(HubSession session) {
        HubHeader header = session.header();
        if (header.unsupported()) {
            return local(GregScopeAssets.LANG_HUB_UNSUPPORTED);
        }
        String owner = header.owner() == null ? local(GregScopeAssets.LANG_HUB_UNOWNED)
            : header.ownerName()
                .isEmpty() ? Labels.shortId(header.owner()) : header.ownerName();
        String line = format(
            GregScopeAssets.LANG_HUB_HEADER,
            owner,
            Integer.valueOf(header.total()),
            Integer.valueOf(header.live()));
        if (header.sensorsAbandoned() > 0) {
            line = line + "  " + format(GregScopeAssets.LANG_HUB_ABANDONED, Integer.valueOf(header.sensorsAbandoned()));
        }
        return line;
    }

    static String filterLabel(HubSession session) {
        return local(
            session.filter() == HubViewModel.FILTER_PROBLEMS ? GregScopeAssets.LANG_HUB_FILTER_PROBLEMS
                : GregScopeAssets.LANG_HUB_FILTER_ALL);
    }

    static String pageLine(HubSession session) {
        HubHeader header = session.header();
        return (header.page() + 1) + " / " + Math.max(1, header.pages());
    }

    /** One list row: a marker, the display name, the machine state and EU/t while LIVE, and the age (section 9.2). */
    static String rowLine(HubSession session, int index) {
        List<HubRow> rows = session.rows();
        if (index < 0 || index >= rows.size()) {
            return "";
        }
        HubRow row = rows.get(index);
        if (row.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append(row.availability() == HubCodecs.AVAILABILITY_LIVE ? '*' : 'o')
            .append(row.problem() ? '!' : ' ')
            .append(' ')
            .append(row.displayName());
        if (row.availability() == HubCodecs.AVAILABILITY_LIVE) {
            out.append("  ")
                .append(local(GregScopeAssets.stateKey(StateCodes.id(row.stateCode()))));
            if (row.euPerTick() != HubCodecs.NONE) {
                out.append("  ")
                    .append(number(row.euPerTick()))
                    .append(" EU/t");
            }
        } else {
            out.append("  ")
                .append(local(GregScopeAssets.hubAvailabilityKey(HubCodecs.availabilityId(row.availability()))));
        }
        out.append("  ")
            .append(age(row.ageSeconds()));
        return out.toString();
    }

    static String detailIdentity(HubSession session) {
        HubDetail detail = session.detail();
        if (!detail.isPresent()) {
            return local(GregScopeAssets.LANG_HUB_NO_SELECTION);
        }
        return format(
            GregScopeAssets.LANG_HUB_DETAIL_WHERE,
            detail.displayName(),
            SensorKind.label(detail.kind()),
            Integer.valueOf(detail.dim()),
            detail.x() + "," + detail.y() + "," + detail.z(),
            Integer.valueOf(detail.side()),
            Labels.shortId(detail.id()));
    }

    static String detailState(HubSession session) {
        HubDetail detail = session.detail();
        if (!detail.isPresent()) {
            return "";
        }
        String progress = detail.progressPermyriad() < 0 ? "-" : (detail.progressPermyriad() / 100) + "%";
        return format(
            GregScopeAssets.LANG_HUB_DETAIL_STATE,
            local(GregScopeAssets.stateKey(StateCodes.id(detail.stateCode()))),
            detail.statusText()
                .isEmpty() ? detail.statusId() : detail.statusText(),
            progress,
            detail.maintenanceIssues() < 0 ? "-" : Integer.toString(detail.maintenanceIssues()),
            age(detail.sampleAgeSeconds()));
    }

    static String detailEnergy(HubSession session) {
        HubDetail detail = session.detail();
        if (!detail.isPresent()) {
            return "";
        }
        return format(
            GregScopeAssets.LANG_HUB_DETAIL_ENERGY,
            detail.euPerTick() == HubCodecs.NONE ? "-" : number(detail.euPerTick()),
            detail.energyStored() == HubCodecs.NONE ? "-" : number(detail.energyStored()),
            detail.energyCapacity() == HubCodecs.NONE ? "-" : number(detail.energyCapacity()));
    }

    static String windowLine(HubSession session, boolean recent) {
        HubDetail detail = session.detail();
        if (!detail.isPresent()) {
            return "";
        }
        if (!detail.hasHistory()) {
            return recent ? local(GregScopeAssets.LANG_HUB_NO_HISTORY) : "";
        }
        if (!detail.historyLoaded()) {
            return recent ? local(GregScopeAssets.LANG_HUB_LOADING) : "";
        }
        HubWindow window = recent ? detail.fiveMinutes() : detail.day();
        return format(
            recent ? GregScopeAssets.LANG_HUB_WINDOW_5M : GregScopeAssets.LANG_HUB_WINDOW_24H,
            percent(window.stateFraction(StateCodes.RUNNING)),
            percent(window.stateFraction(StateCodes.IDLE)),
            percent(window.coverage()),
            window.recipesCompleted() < 0 ? "-" : Long.toString(window.recipesCompleted()));
    }

    static String stripLine(HubSession session) {
        HubDetail detail = session.detail();
        if (!detail.isPresent() || !detail.hasHistory()) {
            return "";
        }
        return "24h  " + detail.strip();
    }

    static String samplerLine(HubSession session) {
        HubHeader header = session.header();
        return format(
            GregScopeAssets.LANG_HUB_SAMPLER,
            micros(header.cycleMicrosP99()),
            Long.valueOf(header.samplingSkippedTotal()),
            local(
                header.samplingEnabled() ? GregScopeAssets.LANG_HUB_SAMPLING_ON
                    : GregScopeAssets.LANG_HUB_SAMPLING_OFF));
    }

    // --- small ASCII formatters (section 9.2: ASCII only, to avoid 1.7.10 glyph risk) ---

    /** {@code 1s}, {@code 12m}, {@code 3h}, {@code 2d}, or {@code -} when there is no timestamp. */
    static String age(int seconds) {
        if (seconds < 0) {
            return "-";
        }
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        if (seconds < 86_400) {
            return (seconds / 3600) + "h";
        }
        return (seconds / 86_400) + "d";
    }

    /** Thousands separated with a comma, so the 1.7.10 font never has to draw a locale glyph. */
    static String number(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder();
        if (value < 0L) {
            out.append('-');
        }
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(',');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    static String percent(double fraction) {
        if (fraction < 0.0 || Double.isNaN(fraction)) {
            return "-";
        }
        return Math.round(fraction * 100.0) + "%";
    }

    /** Microseconds as milliseconds with two decimals, without a locale-dependent formatter. */
    static String micros(long microseconds) {
        if (microseconds < 0L) {
            return "-";
        }
        long hundredths = (microseconds + 5L) / 10L;
        return (hundredths / 100L) + "." + (hundredths % 100L < 10L ? "0" : "") + (hundredths % 100L);
    }

    private static String local(String key) {
        return StatCollector.translateToLocal(key);
    }

    private static String format(String key, Object... args) {
        return StatCollector.translateToLocalFormatted(key, args);
    }
}
