package io.github.ldogg123.gregscope.integration.opencomputers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.ldogg123.gregscope.history.GapRanges;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.MinuteRing;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.history.MinuteSource;
import io.github.ldogg123.gregscope.history.SecondRing;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.SnapshotKeys;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.model.StatusIds;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.SensorView;
import io.github.ldogg123.gregscope.sampling.TelemetryFrame;
import io.github.ldogg123.gregscope.sensor.Labels;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * Builders for the OpenComputers tables of design-v0.2 sections 10.1, 10.3 and 10.4: the sensor record
 * ({@code sensorRecordVersion=1}), the two Hub component tables ({@code getInfo} and {@code listSensors}) and the
 * history table ({@code historyVersion=1}). [pure]
 *
 * <p>
 * Everything here is plain {@code java.util} structure. OpenComputers converts a {@link Map} into a Lua table and a
 * {@link List} into a Lua array table on the way out ({@code oc/li/cil/oc/server/driver/Registry.scala:167-250} and
 * {@code oc/li/cil/oc/util/ExtendedLuaState.scala:27-104}), and only {@code Boolean}, {@code Integer}, {@code Long},
 * {@code Double}, {@code String}, {@code List} and {@code Map} values are produced, exactly as schema v1 already
 * requires of a snapshot map.
 *
 * <p>
 * <b>Absent values are missing keys</b> (section 10.4, the schema v1 rule). No builder here ever stores a
 * {@code null}, so a Lua table never has a key whose value is {@code nil}, and {@code ipairs} over {@code rows} or
 * {@code gaps} never stops early.
 *
 * <p>
 * <b>The section 7.5 reader contract.</b> Rows are observed data only: a minute that is a gap, and a second the ring
 * recorded as a gap, are left out of {@code rows} and appear in {@code gaps} instead. Nothing is interpolated or
 * zero-filled, and a minute or second that was never recorded at all gets its reason from
 * {@link GapRanges#missingReason}, so a caller can tell "the server was down" from "the chunk was unloaded".
 */
public final class LuaTables {

    /** Section 10.1: the version of the sensor record layout. */
    public static final int SENSOR_RECORD_VERSION = 1;
    /** Section 10.4: the version of the history table layout. */
    public static final int HISTORY_VERSION = 1;
    /** Section 10.3 {@code getInfo}: v0.1 published API version 1, v0.2 publishes 2. */
    public static final int API_VERSION = 2;
    /** Section 10.3: the snapshot schema {@code getSnapshot} and {@code getLatest} return, unchanged since v0.1. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Section 10.3 {@code secondsCapacity} and {@code minutesCapacity}: how much history a sensor can hold at all.
     * Taken from the rings themselves rather than written out again, so the numbers a computer reads are the ones the
     * server really keeps.
     */
    public static final int SECONDS_CAPACITY = SecondRing.CAPACITY;
    public static final int MINUTES_CAPACITY = MinuteRing.SLOTS;

    /** Section 10.4 resolution ids. */
    public static final String RESOLUTION_SECOND = "second";
    public static final String RESOLUTION_MINUTE = "minute";

    /** Seconds per row at each resolution. */
    public static final int SECOND_STEP = 1;
    public static final int MINUTE_STEP = 60;

    /** Section 10.4: {@code count} defaults to 60 at both resolutions. */
    public static final int DEFAULT_COUNT = 60;
    /** Section 10.4: {@code count} is clamped to 1..300 for seconds and 1..240 for minutes. */
    public static final int MAX_SECOND_COUNT = 300;
    public static final int MAX_MINUTE_COUNT = 240;

    /** Section 10.2: the machine component has no sensor on any of its six faces. */
    public static final String ERROR_NO_SENSOR = "no sensor";
    /** Section 10.3: the id given to a Hub callback matches no sensor in scope. */
    public static final String ERROR_SENSOR_NOT_FOUND = "sensor not found";
    /** Section 10.3: the id prefix given to a Hub callback matches more than one sensor. */
    public static final String ERROR_AMBIGUOUS_ID = "ambiguous id";
    /** Section 10.4: the resolution is neither {@code "second"} nor {@code "minute"}. */
    public static final String ERROR_BAD_RESOLUTION = "bad resolution";
    /** Section 10.4: minute history was not read back from disk yet. */
    public static final String ERROR_HISTORY_LOADING = "history loading";

    // --- sensor record keys (section 10.1) ---

    public static final String KEY_SENSOR_RECORD_VERSION = "sensorRecordVersion";
    public static final String KEY_ID = "id";
    public static final String KEY_SHORT_ID = "shortId";
    public static final String KEY_LABEL = "label";
    public static final String KEY_DISPLAY_NAME = "displayName";
    public static final String KEY_OWNER = "owner";
    public static final String KEY_OWNER_NAME = "ownerName";
    public static final String KEY_AVAILABILITY = "availability";
    public static final String KEY_AVAILABILITY_SINCE = "availabilitySince";
    public static final String KEY_DIMENSION = "dimension";
    public static final String KEY_X = "x";
    public static final String KEY_Y = "y";
    public static final String KEY_Z = "z";
    public static final String KEY_SIDE = "side";
    public static final String KEY_KIND = "kind";
    public static final String KEY_META_NAME = "metaName";
    public static final String KEY_META_ID = "metaId";
    public static final String KEY_MACHINE_NAME = "machineName";
    public static final String KEY_STATE = "state";
    public static final String KEY_STATUS_ID = "statusId";
    public static final String KEY_LAST_SEEN = "lastSeen";
    public static final String KEY_LAST_SAMPLE = "lastSample";
    public static final String KEY_AGE_SECONDS = "ageSeconds";
    public static final String KEY_HISTORY_LOADED = "historyLoaded";

    // --- Hub component keys (section 10.3) ---

    public static final String KEY_API_VERSION = "apiVersion";
    public static final String KEY_SCHEMA_VERSION = "schemaVersion";
    public static final String KEY_GREGSCOPE_VERSION = "gregscopeVersion";
    public static final String KEY_VISIBLE = "visible";
    public static final String KEY_LIVE = "live";
    public static final String KEY_MAX_SENSORS = "maxSensors";
    public static final String KEY_INTERVAL_TICKS = "intervalTicks";
    public static final String KEY_SECONDS_CAPACITY = "secondsCapacity";
    public static final String KEY_MINUTES_CAPACITY = "minutesCapacity";
    public static final String KEY_FRAME_SEQUENCE = "frameSequence";
    public static final String KEY_FRAME_AGE_SECONDS = "frameAgeSeconds";

    public static final String KEY_TOTAL = "total";
    public static final String KEY_OFFSET = "offset";
    public static final String KEY_SENSORS = "sensors";

    // --- history table keys (section 10.4) ---

    public static final String KEY_HISTORY_VERSION = "historyVersion";
    public static final String KEY_RESOLUTION = "resolution";
    public static final String KEY_STEP = "step";
    public static final String KEY_FROM = "from";
    public static final String KEY_TO = "to";
    public static final String KEY_ROWS = "rows";
    public static final String KEY_GAPS = "gaps";
    public static final String KEY_REASON = "reason";

    public static final String KEY_T = "t";
    public static final String KEY_SAMPLES = "samples";
    public static final String KEY_EXPECTED = "expected";
    public static final String KEY_COVERAGE = "coverage";
    public static final String KEY_LAST_STATE = "lastState";
    public static final String KEY_STATE_SECONDS = "stateSeconds";
    public static final String KEY_MAINTENANCE_MAX = "maintenanceMax";
    public static final String KEY_SERVER_TICKS = "serverTicks";
    public static final String KEY_EU_PER_TICK_AVG = "euPerTickAvg";
    public static final String KEY_EU_PER_TICK_MIN = "euPerTickMin";
    public static final String KEY_EU_PER_TICK_MAX = "euPerTickMax";
    public static final String KEY_ENERGY_STORED = "energyStored";
    public static final String KEY_RECIPES_COMPLETED = "recipesCompleted";

    public static final String KEY_ACTIVE = "active";
    public static final String KEY_ALLOWED_TO_WORK = "allowedToWork";
    public static final String KEY_FORMED = "formed";
    public static final String KEY_WAS_SHUTDOWN = "wasShutdown";
    public static final String KEY_PROGRESS = "progress";
    public static final String KEY_EU_PER_TICK = "euPerTick";
    public static final String KEY_MAINTENANCE_ISSUES = "maintenanceIssues";

    /**
     * The section 10.1 {@code availability} ids, indexed by the design-v0.2 section 8.3 lifecycle states in the order
     * section 10.1 lists them. Pinned here rather than taken from {@code SensorState.label()}, which spells two of
     * them with a space ("over cap", "in item") because it feeds the cover tooltip, and never from
     * {@code ordinal()} (erratum E6).
     */
    private static final String AVAILABILITY_LIVE = "live";
    private static final String AVAILABILITY_UNLOADED = "unloaded";
    private static final String AVAILABILITY_OVER_CAP = "over_cap";
    private static final String AVAILABILITY_MISSING = "missing";
    private static final String AVAILABILITY_IN_ITEM = "in_item";
    private static final String AVAILABILITY_REMOVED = "removed";

    /**
     * {@code ForgeDirection} names in ordinal order, lower case, as section 10.1's {@code side="east"} spells them.
     * The table is pinned because a [pure] class may not name {@code ForgeDirection};
     * {@code OcMachineSensorTests.sideNamesMatchForgeDirection} checks every entry against the real enum on the
     * running server, so a pinned name can never drift from what the cover was placed on.
     */
    private static final String[] SIDE_NAMES = { "down", "up", "north", "south", "west", "east" };
    /** What section 10.1 shows for a side outside {@link #SIDE_NAMES} ({@code ForgeDirection.UNKNOWN} and beyond). */
    public static final String SIDE_UNKNOWN = "unknown";

    private LuaTables() {}

    // --- small pinned tables ---

    /** The lower-case {@code ForgeDirection} name of a cover side, or {@link #SIDE_UNKNOWN}. */
    public static String sideName(int side) {
        return side >= 0 && side < SIDE_NAMES.length ? SIDE_NAMES[side] : SIDE_UNKNOWN;
    }

    /** The section 10.1 availability id of a lifecycle state; {@code ""} for null. */
    public static String availability(SensorState state) {
        if (state == null) {
            return "";
        }
        switch (state) {
            case LIVE:
                return AVAILABILITY_LIVE;
            case UNLOADED:
                return AVAILABILITY_UNLOADED;
            case OVER_CAP:
                return AVAILABILITY_OVER_CAP;
            case MISSING:
                return AVAILABILITY_MISSING;
            case IN_ITEM:
                return AVAILABILITY_IN_ITEM;
            case REMOVED:
                return AVAILABILITY_REMOVED;
            default:
                throw new IllegalArgumentException("no pinned availability id for " + state);
        }
    }

    /** True for a resolution section 10.4 defines. */
    public static boolean isResolution(String resolution) {
        return RESOLUTION_SECOND.equals(resolution) || RESOLUTION_MINUTE.equals(resolution);
    }

    /** Seconds one row covers: 1 or 60. Throws for an unknown resolution, which callers reject first. */
    public static int step(String resolution) {
        if (RESOLUTION_SECOND.equals(resolution)) {
            return SECOND_STEP;
        }
        if (RESOLUTION_MINUTE.equals(resolution)) {
            return MINUTE_STEP;
        }
        throw new IllegalArgumentException("resolution " + resolution);
    }

    /** The largest {@code count} the resolution accepts: 300 seconds or 240 minutes. */
    public static int maxCount(String resolution) {
        return RESOLUTION_SECOND.equals(resolution) ? MAX_SECOND_COUNT : MAX_MINUTE_COUNT;
    }

    /** Section 10.4: {@code count} is clamped into {@code 1..maxCount}, never refused. */
    public static int clampCount(String resolution, int count) {
        int max = maxCount(resolution);
        if (count < 1) {
            return 1;
        }
        return count > max ? max : count;
    }

    /**
     * The largest {@code before} a window may use: the last epoch second a minute ring can index, because
     * {@code epochMinute} is an {@code i32} in the section 7.4 slot layout.
     */
    public static final long MAX_BEFORE = Integer.MAX_VALUE * 60L;

    /**
     * Section 10.4 {@code before} is an epoch second chosen by the caller, so it is clamped into
     * {@code [0, MAX_BEFORE]} rather than trusted: a Lua number can hold values no slot layout can index, and an
     * out-of-range window would only ever be empty anyway.
     */
    public static long clampBefore(long before) {
        if (before < 0L) {
            return 0L;
        }
        return before > MAX_BEFORE ? MAX_BEFORE : before;
    }

    // --- section 10.1 sensor record ---

    /**
     * The sensor record of section 10.1.
     *
     * <p>
     * {@code state} and {@code statusId} come from the last snapshot the sampler took, at any availability, with
     * {@code ageSeconds} as the qualifier - the same rule GS-113's {@code HubDetail} follows, so the Hub and a
     * computer never disagree about a machine. Only a sensor that has <em>no</em> snapshot at all reads the
     * v1-reserved {@code unavailable} / {@code machine_unavailable}.
     *
     * @param view          the sensor, as the registry or a {@code TelemetryFrame} sees it
     * @param historyLoaded whether the minute ring has been read back from disk
     * @param nowEpochSec   the clock reading {@code ageSeconds} is measured against
     */
    public static Map<String, Object> sensorRecord(SensorView view, boolean historyLoaded, long nowEpochSec) {
        if (view == null) {
            throw new IllegalArgumentException("view is required");
        }
        MachineSnapshot snapshot = view.lastSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY_SENSOR_RECORD_VERSION, SENSOR_RECORD_VERSION);
        out.put(
            KEY_ID,
            view.id()
                .toString());
        out.put(KEY_SHORT_ID, Labels.shortId(view.id()));
        out.put(KEY_LABEL, view.label());
        out.put(KEY_DISPLAY_NAME, Labels.displayName(view.label(), machineName(view), view.metaName(), view.id()));
        if (view.owner() != null) {
            out.put(
                KEY_OWNER,
                view.owner()
                    .toString());
        }
        if (view.ownerName() != null && !view.ownerName()
            .isEmpty()) {
            out.put(KEY_OWNER_NAME, view.ownerName());
        }
        out.put(KEY_AVAILABILITY, availability(view.state()));
        out.put(KEY_AVAILABILITY_SINCE, view.stateSinceEpochSec());
        out.put(KEY_DIMENSION, view.dim());
        out.put(KEY_X, view.x());
        out.put(KEY_Y, view.y());
        out.put(KEY_Z, view.z());
        out.put(KEY_SIDE, sideName(view.side()));
        out.put(KEY_KIND, SensorKind.label(view.kind()));
        out.put(KEY_META_NAME, view.metaName());
        out.put(KEY_META_ID, view.metaId());
        out.put(KEY_MACHINE_NAME, view.machineName());
        out.put(
            KEY_STATE,
            snapshot == null || snapshot.state() == null ? StateCodes.id(StateCodes.UNAVAILABLE)
                : snapshot.state()
                    .id());
        out.put(KEY_STATUS_ID, snapshot == null ? StatusIds.MACHINE_UNAVAILABLE : snapshot.statusId());
        if (view.lastSeenEpochSec() > 0L) {
            out.put(KEY_LAST_SEEN, view.lastSeenEpochSec());
        }
        if (view.lastSampleEpochSec() > 0L) {
            out.put(KEY_LAST_SAMPLE, view.lastSampleEpochSec());
            out.put(KEY_AGE_SECONDS, Math.max(0L, nowEpochSec - view.lastSampleEpochSec()));
        }
        out.put(KEY_HISTORY_LOADED, historyLoaded);
        return out;
    }

    /** The machine's own name when the last snapshot carries one, else the registry's cached one (section 3.5). */
    private static String machineName(SensorView view) {
        MachineSnapshot snapshot = view.lastSnapshot();
        if (snapshot == null) {
            return view.machineName();
        }
        Object value = snapshot.toMap()
            .get(SnapshotKeys.NAME);
        String name = value instanceof String ? (String) value : "";
        return name.isEmpty() ? view.machineName() : name;
    }

    // --- section 10.3 Hub component tables ---

    /**
     * The {@code getInfo} table of section 10.3: what this Hub is, how much it can see and how fresh the data is.
     *
     * <p>
     * {@code owner} and {@code ownerName} are absent for an unowned Hub, following the missing-key rule, and
     * {@code visible} is then 0: an unowned Hub has an empty scope (section 5).
     *
     * @param configuredIntervalTicks the sampling interval to report while no frame has been published yet, where
     *                                {@link TelemetryFrame#EMPTY} carries none of its own
     * @param nowEpochSec             the clock reading {@code frameAgeSeconds} is measured against
     */
    public static Map<String, Object> hubInfo(HubScope scope, String gregscopeVersion, int configuredIntervalTicks,
        long nowEpochSec) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        TelemetryFrame frame = scope.frame();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY_API_VERSION, API_VERSION);
        out.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION);
        out.put(KEY_HISTORY_VERSION, HISTORY_VERSION);
        out.put(KEY_SENSOR_RECORD_VERSION, SENSOR_RECORD_VERSION);
        out.put(KEY_GREGSCOPE_VERSION, gregscopeVersion == null ? "" : gregscopeVersion);
        if (scope.owner() != null) {
            out.put(
                KEY_OWNER,
                scope.owner()
                    .toString());
        }
        if (!scope.ownerName()
            .isEmpty()) {
            out.put(KEY_OWNER_NAME, scope.ownerName());
        }
        out.put(KEY_VISIBLE, scope.total());
        out.put(KEY_LIVE, scope.live());
        out.put(
            KEY_MAX_SENSORS,
            frame.limits()
                .maxSensors());
        out.put(KEY_INTERVAL_TICKS, frame.intervalTicks() > 0 ? frame.intervalTicks() : configuredIntervalTicks);
        out.put(KEY_SECONDS_CAPACITY, SECONDS_CAPACITY);
        out.put(KEY_MINUTES_CAPACITY, MINUTES_CAPACITY);
        out.put(KEY_FRAME_SEQUENCE, frame.sequence());
        out.put(KEY_FRAME_AGE_SECONDS, frameAgeSeconds(frame, nowEpochSec));
        return out;
    }

    /**
     * How old the frame is in whole seconds, never negative, and 0 while no frame has been published (
     * {@link TelemetryFrame#EMPTY} has no publication time). A backwards clock reads 0 rather than a negative age.
     */
    private static long frameAgeSeconds(TelemetryFrame frame, long nowEpochSec) {
        long published = frame.publishedEpochMillis();
        if (published <= 0L) {
            return 0L;
        }
        return Math.max(0L, nowEpochSec - published / 1000L);
    }

    /**
     * The {@code listSensors} table of section 10.3: {@code {total, offset, sensors={record...}}}, where
     * {@code total} counts every sensor in scope and {@code offset} is the clamped offset the page really starts at.
     * The order is the frame's (sensor UUID), so paging is stable.
     *
     * @param offset first row to return; negative means 0, past the end means an empty page
     * @param limit  page size, clamped into {@link HubScope#MIN_LIST_LIMIT}..{@link HubScope#MAX_LIST_LIMIT}
     */
    public static Map<String, Object> sensorList(HubScope scope, int offset, int limit, long nowEpochSec) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        int from = HubScope.clampOffset(offset);
        List<Object> sensors = new ArrayList<>();
        for (HubScope.Entry entry : scope.page(from, limit)) {
            sensors.add(sensorRecord(entry.view(), entry.historyLoaded(), nowEpochSec));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY_TOTAL, scope.total());
        out.put(KEY_OFFSET, from);
        out.put(KEY_SENSORS, sensors);
        return out;
    }

    // --- section 10.4 history ---

    /**
     * The minute history table of section 10.4.
     *
     * <p>
     * The window is whole minutes: {@code before} is floored to a minute boundary, so {@code to} is the start of the
     * minute that is still open and {@code from} is {@code count} minutes before it. Passing the returned
     * {@code from} back as {@code before} pages one window further back with no overlap and no hole.
     *
     * @param minutes                  the sensor's minute ring
     * @param count                    already clamped by {@link #clampCount}
     * @param before                   exclusive upper bound in epoch seconds
     * @param expectedSamplesPerMinute {@code 1200 / intervalTicks} of the current configuration, used for a minute
     *                                 that stored no expectation of its own
     * @param gapContext               what the registry knows for section 7.5 rule 3
     */
    public static Map<String, Object> minuteHistory(UUID id, MinuteSource minutes, int count, long before,
        int expectedSamplesPerMinute, GapRanges.Context gapContext) {
        if (minutes == null || gapContext == null || count < 1 || expectedSamplesPerMinute < 1) {
            throw new IllegalArgumentException("minutes, gapContext, count >= 1 and expectedSamplesPerMinute >= 1");
        }
        int toMinute = (int) Math.floorDiv(clampBefore(before), 60L);
        int fromMinute = toMinute - count;
        List<Object> rows = new ArrayList<>();
        for (int m = fromMinute; m < toMinute; m++) {
            MinuteSlot slot = m == 0 ? null : minutes.slot(m);
            if (slot == null || slot.isGap()) {
                continue;
            }
            rows.add(minuteRow(slot, expectedSamplesPerMinute));
        }
        Map<String, Object> out = header(id, RESOLUTION_MINUTE, MINUTE_STEP, fromMinute * 60L, toMinute * 60L);
        out.put(KEY_ROWS, rows);
        out.put(KEY_GAPS, gapList(GapRanges.minutes(minutes, fromMinute, toMinute, gapContext)));
        return out;
    }

    /** One observed or partially observed minute (section 10.4). */
    private static Map<String, Object> minuteRow(MinuteSlot slot, int expectedSamplesPerMinute) {
        int expected = slot.expectedSamples() > 0 ? slot.expectedSamples() : expectedSamplesPerMinute;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(KEY_T, slot.epochSecond());
        row.put(KEY_SAMPLES, slot.samples());
        row.put(KEY_EXPECTED, expected);
        row.put(KEY_COVERAGE, expected == 0 ? 0.0 : Math.min(1.0, slot.samples() / (double) expected));
        row.put(KEY_LAST_STATE, StateCodes.id(slot.lastStateCode()));
        Map<String, Object> states = new LinkedHashMap<>();
        for (int code = 0; code < StateCodes.COUNT; code++) {
            int samples = slot.stateSamples(code);
            if (samples > 0) {
                states.put(StateCodes.id(code), samples);
            }
        }
        row.put(KEY_STATE_SECONDS, states);
        row.put(KEY_MAINTENANCE_MAX, slot.maintenanceMax());
        row.put(KEY_SERVER_TICKS, slot.serverTicks());
        if (slot.euSamples() > 0) {
            row.put(KEY_EU_PER_TICK_AVG, slot.euPerTickAvg());
            row.put(KEY_EU_PER_TICK_MIN, slot.euPerTickMin());
            row.put(KEY_EU_PER_TICK_MAX, slot.euPerTickMax());
        }
        if (slot.energyStoredLast() != MinuteSlot.NONE) {
            row.put(KEY_ENERGY_STORED, slot.energyStoredLast());
        }
        if (slot.recipesCompletedDelta() != MinuteSlot.RECIPES_NONE) {
            row.put(KEY_RECIPES_COMPLETED, slot.recipesCompletedDelta());
        }
        List<Object> gaps = new ArrayList<>();
        for (GapReason reason : GapReason.fromMask(slot.gapMask())) {
            gaps.add(reason.id());
        }
        if (!gaps.isEmpty()) {
            row.put(KEY_GAPS, gaps);
        }
        return row;
    }

    /**
     * The second history table of section 10.4. The window is {@code [before - count, before)}; a second the ring
     * recorded as a gap, and a second it never recorded at all, are left out of {@code rows} and merged into
     * {@code gaps}.
     *
     * @param seconds    the sensor's second ring, or null when it holds none (only a LIVE sensor does)
     * @param count      already clamped by {@link #clampCount}
     * @param gapContext what the registry knows for section 7.5 rule 3
     */
    public static Map<String, Object> secondHistory(UUID id, SecondRing seconds, int count, long before,
        GapRanges.Context gapContext) {
        if (gapContext == null || count < 1) {
            throw new IllegalArgumentException("gapContext and count >= 1 are required");
        }
        long to = clampBefore(before);
        long from = to - count;
        List<Object> rows = new ArrayList<>();
        // A second may hold both an observed sample and a gap entry (two writes in one wall-clock second). The
        // observed one wins, so the second is not also reported as a gap.
        boolean[] observed = new boolean[count];
        GapReason[] recorded = new GapReason[count];
        if (seconds != null) {
            for (int index : seconds.window(from, to)) {
                int offset = (int) (seconds.epochSec(index) - from);
                if (offset < 0 || offset >= count) {
                    continue;
                }
                if (seconds.isValid(index)) {
                    observed[offset] = true;
                    rows.add(secondRow(seconds, index));
                } else if (recorded[offset] == null) {
                    recorded[offset] = seconds.gapReason(index);
                }
            }
        }
        GapRanges.Builder gaps = new GapRanges.Builder();
        for (int offset = 0; offset < count; offset++) {
            if (observed[offset]) {
                continue;
            }
            long t = from + offset;
            GapReason reason = recorded[offset];
            gaps.add(t, t + 1L, reason != null ? reason : GapRanges.missingReason(t, t + 1L, gapContext));
        }
        Map<String, Object> out = header(id, RESOLUTION_SECOND, SECOND_STEP, from, to);
        out.put(KEY_ROWS, rows);
        out.put(KEY_GAPS, gapList(gaps.build()));
        return out;
    }

    /** One observed second (section 10.4). */
    private static Map<String, Object> secondRow(SecondRing seconds, int index) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(KEY_T, (long) seconds.epochSec(index));
        row.put(KEY_STATE, StateCodes.id(seconds.stateCode(index)));
        row.put(KEY_ACTIVE, seconds.hasFlag(index, SecondRing.FLAG_ACTIVE));
        row.put(KEY_ALLOWED_TO_WORK, seconds.hasFlag(index, SecondRing.FLAG_ALLOWED_TO_WORK));
        if (seconds.hasFlag(index, SecondRing.FLAG_FORMED_KNOWN)) {
            row.put(KEY_FORMED, seconds.hasFlag(index, SecondRing.FLAG_FORMED));
        }
        row.put(KEY_WAS_SHUTDOWN, seconds.hasFlag(index, SecondRing.FLAG_WAS_SHUTDOWN));
        row.put(KEY_PROGRESS, seconds.progress(index) / (double) SecondRing.PROGRESS_SCALE);
        if (seconds.hasFlag(index, SecondRing.FLAG_HAS_EU)) {
            row.put(KEY_EU_PER_TICK, seconds.euPerTick(index));
        }
        if (seconds.hasFlag(index, SecondRing.FLAG_HAS_ENERGY)) {
            row.put(KEY_ENERGY_STORED, seconds.energyStored(index));
        }
        row.put(KEY_MAINTENANCE_ISSUES, seconds.maintenanceIssues(index));
        return row;
    }

    private static Map<String, Object> header(UUID id, String resolution, int step, long from, long to) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY_HISTORY_VERSION, HISTORY_VERSION);
        if (id != null) {
            out.put(KEY_ID, id.toString());
        }
        out.put(KEY_RESOLUTION, resolution);
        out.put(KEY_STEP, step);
        out.put(KEY_FROM, from);
        out.put(KEY_TO, to);
        return out;
    }

    /** The merged {@code {from, to, reason}} ranges of section 7.5 rule 4 as Lua tables. */
    private static List<Object> gapList(List<GapRanges.Range> ranges) {
        List<Object> out = new ArrayList<>(ranges.size());
        for (GapRanges.Range range : ranges) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(KEY_FROM, range.from);
            entry.put(KEY_TO, range.to);
            entry.put(KEY_REASON, range.reason.id());
            out.add(entry);
        }
        return Collections.unmodifiableList(out);
    }
}
