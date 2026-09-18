package io.github.ldogg123.gregscope.integration.opencomputers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import io.github.ldogg123.gregscope.access.AccessPolicy;
import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.command.CommandArgs;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.SensorView;
import io.github.ldogg123.gregscope.sampling.TelemetryFrame;

/**
 * What one Telemetry Hub lets a computer see: a {@link TelemetryFrame} filtered to the Hub owner's scope
 * (design-v0.2 section 5), with the paging and id resolution the section 10.3 callbacks need. Immutable. [pure]
 *
 * <p>
 * <b>Scope is the Hub's, not the caller's.</b> {@link AccessPolicy#inHubScope} takes no viewer: a computer wired to
 * an Adapter next to a Hub sees exactly what a player opening that Hub would see, and an unowned Hub shows nothing at
 * all. Section 5 spells out the rest: out-of-scope ids answer "sensor not found" and never reveal that they exist,
 * which is why {@link #lookup} searches {@link #entries()} and not the frame.
 *
 * <p>
 * <b>Order.</b> {@link TelemetryFrame#sensors()} is sorted by sensor UUID and filtering keeps that order, so
 * {@code listSensors} pages stably: a sensor that appears or disappears moves the rows after it and none before it.
 *
 * <p>
 * <b>Built on the server thread.</b> The team lookups behind {@code inHubScope} and the {@code historyLoaded} flag
 * are server-thread state, so the whole object is built there and then only read. That is why every callback of
 * {@code HubEnvironment} is a server-thread (non-direct) callback; the reasoning is in that class.
 */
public final class HubScope {

    /** Section 10.3 {@code listSensors}: the default page size. */
    public static final int DEFAULT_LIST_LIMIT = 32;
    /** Section 10.3: the limit is clamped into 1..64, never refused. */
    public static final int MIN_LIST_LIMIT = 1;
    public static final int MAX_LIST_LIMIT = 64;

    /**
     * Section 10.3 writes {@code getLatest(idOrPrefix>=8)}: a prefix shorter than eight characters is not an id this
     * component accepts. It answers {@link LuaTables#ERROR_SENSOR_NOT_FOUND} rather than "ambiguous id", because a
     * short prefix that happens to match one sensor is still not an id, and section 5 asks this component never to
     * reveal what a caller may not see.
     */
    public static final int MIN_ID_PREFIX = 8;

    /** What a Hub with no owner, or a server with no frame yet, lets a computer see: nothing. */
    public static final HubScope EMPTY = new HubScope(
        TelemetryFrame.EMPTY,
        null,
        "",
        Collections.<Entry>emptyList(),
        0);

    private final TelemetryFrame frame;
    private final UUID owner;
    private final String ownerName;
    private final List<Entry> entries;
    private final int live;

    private HubScope(TelemetryFrame frame, UUID owner, String ownerName, List<Entry> entries, int live) {
        this.frame = frame;
        this.owner = owner;
        this.ownerName = ownerName;
        this.entries = Collections.unmodifiableList(entries);
        this.live = live;
    }

    /**
     * Filters {@code frame} to the sensors this Hub's owner may see.
     *
     * @param hubOwner      the Hub's owner, or null for an unowned Hub (then the scope is empty)
     * @param hubOwnerName  the cached owner name; never null on the way out
     * @param historyLoaded whether a sensor's minute ring has been read back from disk, asked once per sensor
     */
    public static <T> HubScope of(TelemetryFrame frame, UUID hubOwner, String hubOwnerName, AccessPolicy policy,
        TeamResolver<T> teams, Predicate<UUID> historyLoaded) {
        if (frame == null || policy == null || teams == null || historyLoaded == null) {
            throw new IllegalArgumentException("frame, policy, teams and historyLoaded are required");
        }
        List<Entry> rows = new ArrayList<>();
        int liveRows = 0;
        for (SensorView view : frame.sensors()) {
            if (!policy.inHubScope(hubOwner, view.owner(), teams)) {
                continue;
            }
            rows.add(new Entry(view, historyLoaded.test(view.id())));
            if (view.state() == SensorState.LIVE) {
                liveRows++;
            }
        }
        return new HubScope(frame, hubOwner, hubOwnerName == null ? "" : hubOwnerName, rows, liveRows);
    }

    /** The frame this scope was built from; never null. */
    public TelemetryFrame frame() {
        return frame;
    }

    /** The Hub's owner, or null when it is unowned or its record is of an unsupported version. */
    public UUID owner() {
        return owner;
    }

    /** The cached owner name; empty when there is no owner. Never null. */
    public String ownerName() {
        return ownerName;
    }

    /** Sensors in scope, in frame order (sensor UUID). Unmodifiable. */
    public List<Entry> entries() {
        return entries;
    }

    /** How many sensors are in scope. */
    public int total() {
        return entries.size();
    }

    /** How many of them are LIVE. */
    public int live() {
        return live;
    }

    /** Section 10.3: {@code limit} is clamped into {@code 1..64}. */
    public static int clampLimit(int limit) {
        if (limit < MIN_LIST_LIMIT) {
            return MIN_LIST_LIMIT;
        }
        return limit > MAX_LIST_LIMIT ? MAX_LIST_LIMIT : limit;
    }

    /** A negative offset is the first page; an offset past the end is an empty page, not an error. */
    public static int clampOffset(int offset) {
        return offset < 0 ? 0 : offset;
    }

    /** At most {@code limit} entries from {@code offset}; empty past the end. Both arguments are clamped here too. */
    public List<Entry> page(int offset, int limit) {
        int from = clampOffset(offset);
        if (from >= entries.size()) {
            return Collections.emptyList();
        }
        int to = Math.min(entries.size(), from + clampLimit(limit));
        return entries.subList(from, to);
    }

    /**
     * The one sensor in scope whose UUID starts with {@code idOrPrefix}, matched the way {@code /gregscope} matches
     * an id prefix: dashes removed, case-insensitively ({@link CommandArgs#matchesPrefix}). A prefix shorter than
     * {@link #MIN_ID_PREFIX} characters, one that matches nothing, and one that matches a sensor outside this Hub's
     * scope are the same answer.
     */
    public Lookup lookup(String idOrPrefix) {
        if (CommandArgs.normalize(idOrPrefix)
            .length() < MIN_ID_PREFIX) {
            return Lookup.NOT_FOUND;
        }
        Entry found = null;
        for (Entry entry : entries) {
            if (!CommandArgs.matchesPrefix(
                entry.view()
                    .id(),
                idOrPrefix)) {
                continue;
            }
            if (found != null) {
                return Lookup.AMBIGUOUS;
            }
            found = entry;
        }
        return found == null ? Lookup.NOT_FOUND : new Lookup(found, null);
    }

    @Override
    public String toString() {
        return "HubScope{owner=" + owner
            + ", "
            + entries.size()
            + " sensors ("
            + live
            + " live), frame #"
            + frame.sequence()
            + "}";
    }

    /** One sensor in scope: its frame row plus the one fact a frame does not carry. */
    public static final class Entry {

        private final SensorView view;
        private final boolean historyLoaded;

        Entry(SensorView view, boolean historyLoaded) {
            this.view = view;
            this.historyLoaded = historyLoaded;
        }

        public SensorView view() {
            return view;
        }

        /** Design-v0.2 section 8.4: false until the async minute-history load merged. */
        public boolean historyLoaded() {
            return historyLoaded;
        }

        @Override
        public String toString() {
            return "Entry{" + view.id() + ", historyLoaded=" + historyLoaded + "}";
        }
    }

    /** The result of {@link HubScope#lookup}: one entry, or one of the two section 10.3/10.4 error messages. */
    public static final class Lookup {

        static final Lookup NOT_FOUND = new Lookup(null, LuaTables.ERROR_SENSOR_NOT_FOUND);
        static final Lookup AMBIGUOUS = new Lookup(null, LuaTables.ERROR_AMBIGUOUS_ID);

        private final Entry entry;
        private final String error;

        private Lookup(Entry entry, String error) {
            this.entry = entry;
            this.error = error;
        }

        /** The resolved sensor, or null when {@link #error()} says why there is none. */
        public Entry entry() {
            return entry;
        }

        /** The soft-error message, or null when {@link #entry()} resolved. */
        public String error() {
            return error;
        }

        @Override
        public String toString() {
            return entry == null ? "Lookup{" + error + "}" : "Lookup{" + entry + "}";
        }
    }
}
