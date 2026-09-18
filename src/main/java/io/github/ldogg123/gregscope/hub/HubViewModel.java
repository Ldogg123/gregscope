package io.github.ldogg123.gregscope.hub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.ldogg123.gregscope.access.AccessPolicy;
import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.access.Viewer;
import io.github.ldogg123.gregscope.history.GapRanges;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.history.MinuteSource;
import io.github.ldogg123.gregscope.history.Summaries;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.SnapshotKeys;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.SensorView;
import io.github.ldogg123.gregscope.sampling.TelemetryFrame;
import io.github.ldogg123.gregscope.sensor.Labels;

/**
 * One viewer's Telemetry Hub view: the data half of design-v0.2 section 9.2. [pure]
 *
 * <p>
 * It turns a {@link TelemetryFrame} plus minute-history accessors into the three DTOs section 9.3 syncs
 * ({@link #header()}, {@link #rows()}, {@link #detail()}), and it owns the session inputs the client can change
 * ({@link #setFilter(int)}, {@link #setPage(int)}, {@link #selectRow(int)}). There is one of these per open view, on
 * the server thread; GS-114's panel holds one and does nothing but read its getters and forward the named handlers.
 *
 * <p>
 * <b>No world, by construction.</b> Everything it reads arrives as an argument: the frame (immutable, published by
 * the sampler), an {@link AccessPolicy} with a {@link TeamResolver}, and a {@link History} the host implements over
 * the registry's rings. Nothing here can load a chunk, and the class is plain-JVM unit tested.
 *
 * <p>
 * <b>Rebuild throttle (section 9.3).</b> {@link #rebuild} does the work only when {@code frame.sequence()} changed or
 * a session input changed since the last one. {@code nowEpochSec} is deliberately <em>not</em> an input: the sampler
 * publishes a frame every {@code sampling.intervalTicks} (one second by default), so the ages a rebuild computed can
 * never be more than one interval stale, and a view that nobody touches while the server is idle costs one comparison
 * per GUI tick instead of a full sort.
 *
 * <p>
 * <b>Stale machine data is not shown as current.</b> A row carries a machine state, an EU/t reading and a warning
 * flag only while the sensor is LIVE; for an UNLOADED sensor or a tombstone they are {@code unavailable} /
 * {@link HubCodecs#NONE} / false, which is exactly how section 9.2 draws its {@code UNLOADED} row. The detail pane
 * does show the last known snapshot whatever the availability, because it also shows how old that sample is.
 */
public final class HubViewModel {

    /** Section 9.2: the list shows eight rows, padded when the page is short. */
    public static final int ROWS_PER_PAGE = 8;

    /** Section 9.3 {@code gs_filter}: everything in scope. */
    public static final int FILTER_ALL = 0;
    /** Section 9.3 {@code gs_filter}: only the rows {@link HubRow#problem()} is set on. */
    public static final int FILTER_PROBLEMS = 1;
    /**
     * How many filters v0.2 has. design-v0.3 section 6.2 adds 2 (Machines) and 3 (Flow); until it does,
     * {@link #clampFilter(int)} maps them to {@link #FILTER_ALL} like any other unknown value, which is what section
     * 9.3 requires of a v0.2 server reading a value it does not know.
     */
    public static final int FILTER_COUNT = 2;

    /** Minutes in the 24-hour window and in the hourly strip; both cover the same window on purpose. */
    public static final int DAY_MINUTES = HubDetail.HOURS * 60;
    /** Minutes in the "last 5 min" window (section 9.2). */
    public static final int RECENT_MINUTES = 5;

    /** Severity ranks of section 9.2's sort order; lower sorts first. */
    private static final int RANK_MISSING = 6;
    private static final int RANK_IN_ITEM = 7;
    private static final int RANK_REMOVED = 8;
    private static final int RANK_UNLOADED = 9;
    private static final int RANK_STARTING = 10;
    private static final int RANK_RUNNING = 11;
    private static final int RANK_IDLE = 12;
    /** A LIVE sensor whose machine could not be read: not a Problem (section 9.2 does not list it), but last. */
    private static final int RANK_UNAVAILABLE = 13;

    /**
     * Minute history for one sensor, as the host reads it off the registry. Called only for the selected sensor, on
     * the server thread, and only inside a rebuild.
     */
    public interface History {

        /** The sensor's minute ring, or null when it holds none (a tombstone, or an unknown id). */
        MinuteSource minutes(UUID id);

        /** Whether the sensor's history file has been read (section 8.3); false means the window is still filling. */
        boolean loaded(UUID id);

        /** The section 7.5 rule-3 context: the server runs, and whether the registry has the sensor UNLOADED. */
        GapRanges.Context gapContext(UUID id, long nowEpochSec);

        /** A host with no history at all: every sensor reads as "no ring". */
        History NONE = new History() {

            @Override
            public MinuteSource minutes(UUID id) {
                return null;
            }

            @Override
            public boolean loaded(UUID id) {
                return false;
            }

            @Override
            public GapRanges.Context gapContext(UUID id, long nowEpochSec) {
                return GapRanges.Context.of(Collections.<GapRanges.Run>emptyList());
            }
        };
    }

    private final UUID hubOwner;
    private final String hubOwnerName;
    private final boolean unsupported;

    private int filter = FILTER_ALL;
    private int page;
    private UUID selected;
    private int sensorsAbandoned;

    private boolean dirty = true;
    private long lastSequence = -1L;
    private int rebuilds;

    private HubHeader header = HubHeader.EMPTY;
    private List<HubRow> rows = emptyRows();
    private HubDetail detail = HubDetail.NONE;

    /**
     * @param hubOwner     the Hub's owner, or null for an unowned or unsupported Hub (nothing is ever in its scope,
     *                     per section 5)
     * @param hubOwnerName the cached owner name for the header, or null
     * @param unsupported  the tile entity's {@code gsHub} record is newer than this build (section 9.1)
     */
    public HubViewModel(UUID hubOwner, String hubOwnerName, boolean unsupported) {
        this.hubOwner = hubOwner;
        this.hubOwnerName = hubOwnerName == null ? "" : hubOwnerName;
        this.unsupported = unsupported;
    }

    // --- session inputs (section 9.3) ---

    /** Section 9.3: a value outside {@code {0, 1}} becomes {@link #FILTER_ALL}. */
    public static int clampFilter(int value) {
        return value == FILTER_PROBLEMS ? FILTER_PROBLEMS : FILTER_ALL;
    }

    /** Sets {@code gs_filter}, clamped. Changing it resets the page, because page 3 of a shorter list is nothing. */
    public void setFilter(int value) {
        int clamped = clampFilter(value);
        if (clamped != filter) {
            filter = clamped;
            page = 0;
            dirty = true;
        }
    }

    public int filter() {
        return filter;
    }

    /**
     * Sets {@code gs_page}, 0-based, clamped into {@code [0, pages-1]} of the <em>last built</em> page count. The
     * next {@link #rebuild} clamps again against the new count, which is what makes a page survive the list
     * shrinking under it.
     */
    public void setPage(int value) {
        int clamped = clamp(value, 0, header.pages() - 1);
        if (clamped != page) {
            page = clamped;
            dirty = true;
        }
    }

    public int page() {
        return page;
    }

    /**
     * Section 9.3 {@code gs_select}: a row index {@code 0..7} on the viewer's current page resolves to that row's
     * <b>UUID</b>; any other index, or an empty row, selects nothing. Because the selection is a UUID and not an
     * index, it survives resorting, paging and the list growing.
     */
    public void selectRow(int rowIndex) {
        UUID id = rowIndex >= 0 && rowIndex < rows.size() ? rows.get(rowIndex)
            .id() : null;
        select(id);
    }

    /** Selects by UUID directly; null clears the selection. */
    public void select(UUID id) {
        if (id == null ? selected != null : !id.equals(selected)) {
            selected = id;
            dirty = true;
        }
    }

    /** The selected sensor, or null. */
    public UUID selected() {
        return selected;
    }

    /** The {@code HistoryPersistence.sensorsAbandoned()} counter the header shows; the host sets it per rebuild. */
    public void setSensorsAbandoned(int value) {
        if (value != sensorsAbandoned) {
            sensorsAbandoned = value;
            dirty = true;
        }
    }

    // --- outputs ---

    /** Never null; the same reference until a rebuild replaces it. */
    public HubHeader header() {
        return header;
    }

    /** Exactly {@link #ROWS_PER_PAGE} rows, padded with {@link HubRow#EMPTY}; unmodifiable. */
    public List<HubRow> rows() {
        return rows;
    }

    /** The selected sensor's detail, or {@link HubDetail#NONE}. */
    public HubDetail detail() {
        return detail;
    }

    /** How many times {@link #rebuild} did the work; the throttle's evidence. */
    public int rebuilds() {
        return rebuilds;
    }

    // --- the rebuild ---

    /**
     * Rebuilds the three DTOs if anything changed.
     *
     * @param viewer the player looking at this Hub; only {@link HubDetail#canEdit()} depends on them, because
     *               section 5 scopes the rows to the <em>Hub owner</em> and an op gains no extra visibility
     * @return true if the work was done, false if the throttle answered from the last build
     */
    public <T> boolean rebuild(TelemetryFrame frame, AccessPolicy policy, Viewer viewer, TeamResolver<T> teams,
        History history, long nowEpochSec) {
        if (frame == null || policy == null || viewer == null || teams == null || history == null) {
            throw new IllegalArgumentException("frame, policy, viewer, teams and history are required");
        }
        if (!dirty && lastSequence == frame.sequence()) {
            return false;
        }
        rebuilds++;
        dirty = false;
        lastSequence = frame.sequence();

        List<Candidate> scoped = new ArrayList<>();
        int live = 0;
        SensorView selectedView = null;
        for (SensorView view : frame.sensors()) {
            if (!policy.inHubScope(hubOwner, view.owner(), teams)) {
                continue;
            }
            if (view.state() == SensorState.LIVE) {
                live++;
            }
            if (view.id()
                .equals(selected)) {
                selectedView = view;
            }
            scoped.add(new Candidate(row(view, nowEpochSec)));
        }
        int total = scoped.size();

        List<Candidate> shown = new ArrayList<>(scoped.size());
        for (Candidate candidate : scoped) {
            if (filter != FILTER_PROBLEMS || candidate.row.problem()) {
                shown.add(candidate);
            }
        }
        Collections.sort(shown, BY_SEVERITY_THEN_NAME);

        int pages = pageCount(shown.size());
        page = clamp(page, 0, pages - 1);
        rows = page(shown, page);

        // A selection the scope no longer holds is dropped; one the filter hides is kept, so switching to Problems
        // and back does not lose the machine the player was reading.
        if (selected != null && selectedView == null) {
            selected = null;
        }
        detail = selectedView == null ? HubDetail.NONE
            : detail(selectedView, policy, viewer, teams, history, frame.intervalTicks(), nowEpochSec);

        header = new HubHeader(
            hubOwner,
            hubOwnerName,
            total,
            live,
            shown.size(),
            filter,
            page,
            pages,
            unsupported,
            frame.limits()
                .samplingEnabled(),
            frame.stats()
                .cycleMicrosP99(),
            frame.stats()
                .samplingSkippedTotal(),
            sensorsAbandoned);
        return true;
    }

    // --- rows ---

    private static HubRow row(SensorView view, long nowEpochSec) {
        boolean liveNow = view.state() == SensorState.LIVE;
        MachineSnapshot snapshot = liveNow ? view.lastSnapshot() : null;
        int stateCode = snapshot == null ? StateCodes.UNAVAILABLE : StateCodes.code(snapshot.state());
        boolean warning = snapshot != null && hasWarnings(snapshot);
        return new HubRow(
            view.id(),
            view.kind(),
            HubCodecs.availabilityCode(view.state()),
            stateCode,
            isProblem(view.state(), stateCode, warning),
            warning,
            snapshot == null ? HubCodecs.NONE : longOrNone(snapshot, SnapshotKeys.EU_PER_TICK),
            age(liveNow ? view.lastSampleEpochSec() : view.lastSeenEpochSec(), nowEpochSec),
            Labels.displayName(view.label(), machineName(view), view.metaName(), view.id()));
    }

    /**
     * Section 9.2's Problems filter: LIVE with a state in {shutdown, power_starved, output_blocked, waiting,
     * unformed, disabled}, or MISSING, or any warning.
     *
     * <p>
     * "Any warning" is read as "any warning on a current reading". A sensor that is not LIVE has no current reading,
     * and its last snapshot can be days old - a maintenance warning from before a chunk unloaded would otherwise pin
     * a machine nobody can see into the Problems list for ever, which is the opposite of what the filter is for.
     * MISSING is a problem on its own, so nothing is lost.
     */
    private static boolean isProblem(SensorState state, int stateCode, boolean warning) {
        if (state == SensorState.MISSING) {
            return true;
        }
        if (state != SensorState.LIVE) {
            return false;
        }
        if (warning) {
            return true;
        }
        return stateCode == StateCodes.SHUTDOWN || stateCode == StateCodes.POWER_STARVED
            || stateCode == StateCodes.OUTPUT_BLOCKED
            || stateCode == StateCodes.WAITING
            || stateCode == StateCodes.UNFORMED
            || stateCode == StateCodes.DISABLED;
    }

    /** Section 9.2's severity order, as one rank per row; lower is more severe. */
    static int severity(HubRow row) {
        switch (row.availability()) {
            case HubCodecs.AVAILABILITY_MISSING:
                return RANK_MISSING;
            case HubCodecs.AVAILABILITY_IN_ITEM:
                return RANK_IN_ITEM;
            case HubCodecs.AVAILABILITY_REMOVED:
                return RANK_REMOVED;
            case HubCodecs.AVAILABILITY_UNLOADED:
            case HubCodecs.AVAILABILITY_OVER_CAP:
                return RANK_UNLOADED;
            default:
                break;
        }
        switch (row.stateCode()) {
            case StateCodes.SHUTDOWN:
                return 0;
            case StateCodes.POWER_STARVED:
                return 1;
            case StateCodes.OUTPUT_BLOCKED:
                return 2;
            case StateCodes.WAITING:
                return 3;
            case StateCodes.UNFORMED:
                return 4;
            case StateCodes.DISABLED:
                return 5;
            case StateCodes.STARTING:
                return RANK_STARTING;
            case StateCodes.RUNNING:
                return RANK_RUNNING;
            case StateCodes.IDLE:
                return RANK_IDLE;
            default:
                return RANK_UNAVAILABLE;
        }
    }

    /** Section 9.2: severity, then display name ignoring case, then id. */
    private static final Comparator<Candidate> BY_SEVERITY_THEN_NAME = new Comparator<Candidate>() {

        @Override
        public int compare(Candidate a, Candidate b) {
            int c = Integer.compare(severity(a.row), severity(b.row));
            if (c != 0) {
                return c;
            }
            c = a.row.displayName()
                .compareToIgnoreCase(b.row.displayName());
            if (c != 0) {
                return c;
            }
            return a.row.id()
                .compareTo(b.row.id());
        }
    };

    private static List<HubRow> page(List<Candidate> shown, int page) {
        List<HubRow> out = new ArrayList<>(ROWS_PER_PAGE);
        int first = page * ROWS_PER_PAGE;
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            int index = first + i;
            out.add(index < shown.size() ? shown.get(index).row : HubRow.EMPTY);
        }
        return Collections.unmodifiableList(out);
    }

    /** At least 1, so an empty Hub still reads "1 / 1". */
    static int pageCount(int shown) {
        return shown <= 0 ? 1 : (shown + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE;
    }

    // --- detail ---

    private <T> HubDetail detail(SensorView view, AccessPolicy policy, Viewer viewer, TeamResolver<T> teams,
        History history, int intervalTicks, long nowEpochSec) {
        MachineSnapshot snapshot = view.lastSnapshot();
        Map<String, Object> map = snapshot == null ? Collections.<String, Object>emptyMap() : snapshot.toMap();
        HubDetail.Builder builder = HubDetail.builder()
            .id(view.id())
            .kind(view.kind())
            .availability(HubCodecs.availabilityCode(view.state()))
            .stateCode(snapshot == null ? StateCodes.UNAVAILABLE : StateCodes.code(snapshot.state()))
            .displayName(Labels.displayName(view.label(), machineName(view), view.metaName(), view.id()))
            .label(view.label())
            .machineName(view.machineName())
            .metaName(view.metaName())
            .statusId(view.lastStatusId())
            .statusText(string(map, SnapshotKeys.STATUS_TEXT))
            .metaId(view.metaId())
            .position(view.dim(), view.x(), view.y(), view.z(), view.side())
            .progressPermyriad(permyriad(map))
            .maintenanceIssues(intOrAbsent(map, SnapshotKeys.MAINTENANCE_ISSUES))
            .euPerTick(snapshot == null ? HubCodecs.NONE : longOrNone(snapshot, SnapshotKeys.EU_PER_TICK))
            .energyStored(snapshot == null ? HubCodecs.NONE : longOrNone(snapshot, SnapshotKeys.ENERGY_STORED))
            .energyCapacity(snapshot == null ? HubCodecs.NONE : longOrNone(snapshot, SnapshotKeys.ENERGY_CAPACITY))
            .ages(
                age(view.lastSampleEpochSec(), nowEpochSec),
                age(view.lastSeenEpochSec(), nowEpochSec),
                age(view.stateSinceEpochSec(), nowEpochSec))
            .canEdit(view.state() == SensorState.LIVE && policy.canRename(viewer, view.owner(), teams));

        MinuteSource minutes = history.minutes(view.id());
        builder.hasHistory(minutes != null)
            .historyLoaded(history.loaded(view.id()));
        if (minutes == null) {
            return builder.build();
        }
        int nowMinute = (int) Math.floorDiv(nowEpochSec, 60L);
        int expected = MinuteSlot.expectedSamples(intervalTicks);
        return builder
            .fiveMinutes(HubWindow.of(Summaries.minutes(minutes, nowMinute - RECENT_MINUTES, nowMinute, expected)))
            .day(HubWindow.of(Summaries.minutes(minutes, nowMinute - DAY_MINUTES, nowMinute, expected)))
            .hourly(hourly(minutes, nowMinute, expected))
            .build();
    }

    /**
     * Section 9.2's 24-hour strip: one running fraction per hour, oldest first, over the same window as the 24-hour
     * summary. An hour with no observed sample is {@link HubDetail#HOUR_GAP} and renders as {@code ?}; that is the
     * section 7.5 rule that a gap is never zero-filled, applied to a picture.
     */
    static byte[] hourly(MinuteSource minutes, int nowMinute, int expectedSamplesPerMinute) {
        byte[] out = new byte[HubDetail.HOURS];
        for (int hour = 0; hour < HubDetail.HOURS; hour++) {
            int from = nowMinute - DAY_MINUTES + hour * 60;
            Summaries.Summary summary = Summaries.minutes(minutes, from, from + 60, expectedSamplesPerMinute);
            if (!summary.hasSamples()) {
                out[hour] = HubDetail.HOUR_GAP;
                continue;
            }
            out[hour] = (byte) Math.round(summary.stateFraction(StateCodes.RUNNING) * 100.0);
        }
        return out;
    }

    // --- small readers ---

    private static String machineName(SensorView view) {
        MachineSnapshot snapshot = view.lastSnapshot();
        if (snapshot == null) {
            return view.machineName();
        }
        String name = string(snapshot.toMap(), SnapshotKeys.NAME);
        return name.isEmpty() ? view.machineName() : name;
    }

    private static boolean hasWarnings(MachineSnapshot snapshot) {
        Object value = snapshot.toMap()
            .get(SnapshotKeys.WARNINGS);
        return value instanceof List && !((List<?>) value).isEmpty();
    }

    private static long longOrNone(MachineSnapshot snapshot, String key) {
        Object value = snapshot.toMap()
            .get(key);
        return value instanceof Number ? ((Number) value).longValue() : HubCodecs.NONE;
    }

    private static int intOrAbsent(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : "";
    }

    /** Recipe progress as ten-thousandths, clamped into {@code 0..10000}; -1 when the machine reports none. */
    private static int permyriad(Map<String, Object> map) {
        Object value = map.get(SnapshotKeys.PROGRESS);
        if (!(value instanceof Number)) {
            return -1;
        }
        double fraction = ((Number) value).doubleValue();
        if (Double.isNaN(fraction)) {
            return -1;
        }
        return clamp((int) Math.round(fraction * 10_000.0), 0, 10_000);
    }

    /** Seconds since {@code then}, or {@link HubCodecs#NO_AGE} without a timestamp; never negative. */
    private static int age(long thenEpochSec, long nowEpochSec) {
        if (thenEpochSec <= 0L) {
            return HubCodecs.NO_AGE;
        }
        long seconds = nowEpochSec - thenEpochSec;
        if (seconds <= 0L) {
            return 0;
        }
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    private static int clamp(int value, int low, int high) {
        if (value < low) {
            return low;
        }
        return value > high ? high : value;
    }

    /**
     * A page of {@link #ROWS_PER_PAGE} {@link HubRow#EMPTY} rows, unmodifiable and shared: what a view shows before
     * its first rebuild, and what GS-114's client-side session starts from before the first {@code gs_rows} packet.
     */
    public static List<HubRow> emptyPage() {
        return EMPTY_PAGE;
    }

    private static final List<HubRow> EMPTY_PAGE = emptyRows();

    private static List<HubRow> emptyRows() {
        List<HubRow> out = new ArrayList<>(ROWS_PER_PAGE);
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            out.add(HubRow.EMPTY);
        }
        return Collections.unmodifiableList(out);
    }

    /** A scoped sensor's row, wrapped so the sort keeps working on one object per sensor. */
    private static final class Candidate {

        final HubRow row;

        Candidate(HubRow row) {
            this.row = row;
        }
    }
}
