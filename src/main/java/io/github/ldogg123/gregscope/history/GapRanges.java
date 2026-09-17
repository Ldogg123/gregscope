package io.github.ldogg123.gregscope.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The §7.5 reader contract for gaps in minute history (design-v0.2). [pure]
 *
 * <ol>
 * <li>Aggregates use observed samples only; gaps are never zero-filled.</li>
 * <li>A stored slot with state 0, or {@code samples == 0}, is a gap with its stored reasons ({@code unknown} if its
 * mask is empty).</li>
 * <li>A missing slot is {@code server_offline} if its minute overlaps no recorded server run; otherwise
 * {@code chunk_unloaded}/{@code dimension_unloaded} if the registry says UNLOADED since a time at or before that
 * minute; otherwise {@code unknown}.</li>
 * <li>Gaps are returned as merged ranges {@code {from, to, reason}}: epoch seconds, {@code from} inclusive, {@code to}
 * exclusive, sorted by {@code from} then reason bit. Adjacent or overlapping ranges with the same reason merge.</li>
 * </ol>
 *
 * Callers choose the window: it should not start before the sensor was created and should end before the minute that is
 * still open.
 */
public final class GapRanges {

    private GapRanges() {}

    /** A server run in epoch seconds, {@code [start, stop)}. Build with {@link #resolve} from the runs table. */
    public static final class Run {

        public final long start;
        public final long stop;

        public Run(long start, long stop) {
            if (stop < start) {
                throw new IllegalArgumentException("run stops before it starts: " + start + ".." + stop);
            }
            this.start = start;
            this.stop = stop;
        }

        /**
         * The §8.3 load step for one stored run: the {@code stop} to keep in memory (and save) when the registry is
         * loaded at server start, before the new run is appended and before anything is saved. An unclean run
         * ({@code stop == 0}) is closed at the loaded registry's {@code saved} time, never before its own start; a
         * clean run keeps its stop.
         *
         * <p>
         * {@code saved} is registry-wide and only describes the run that was current when it was written. Once the new
         * process saves, {@code saved} moves past the downtime, so a reader that resolved an older unclean run with
         * the current {@code saved} would count the downtime as inside that run and lose {@code server_offline}.
         * Closing unclean runs on load keeps their approximate stop time; readers never need {@code saved}.
         */
        public static long stopOnLoad(long start, long stop, long saved) {
            return stop != 0 ? stop : Math.max(start, saved);
        }

        /**
         * Resolves a run from the in-memory runs table (§8.3). The {@code ongoing} run (the current process) ends at
         * {@code now}. Every other run was closed by {@link #stopOnLoad}; a stored {@code stop == 0} that is not
         * ongoing (the load step skipped, or damaged data) resolves to an empty run at its start, so its minutes read
         * as {@code server_offline} rather than claiming coverage nobody recorded.
         */
        public static Run resolve(long start, long stop, boolean ongoing, long now) {
            if (ongoing) {
                return new Run(start, Math.max(start, now));
            }
            return new Run(start, Math.max(start, stop));
        }

        boolean overlaps(long from, long to) {
            return start < to && from < stop;
        }
    }

    /** What the registry knows about the sensor, needed for rule 3. */
    public static final class Context {

        final List<Run> runs;
        final long unloadedSinceEpochSec;
        final GapReason unloadedReason;

        private Context(List<Run> runs, long unloadedSinceEpochSec, GapReason unloadedReason) {
            this.runs = runs;
            this.unloadedSinceEpochSec = unloadedSinceEpochSec;
            this.unloadedReason = unloadedReason;
        }

        /** The sensor is not UNLOADED. */
        public static Context of(List<Run> runs) {
            return new Context(copy(runs), -1, null);
        }

        /**
         * The registry has the sensor UNLOADED since {@code sinceEpochSec} with {@code reason}
         * ({@link GapReason#CHUNK_UNLOADED} or {@link GapReason#DIMENSION_UNLOADED}).
         */
        public static Context unloaded(List<Run> runs, long sinceEpochSec, GapReason reason) {
            if (reason != GapReason.CHUNK_UNLOADED && reason != GapReason.DIMENSION_UNLOADED) {
                throw new IllegalArgumentException("unloaded reason " + reason);
            }
            return new Context(copy(runs), sinceEpochSec, reason);
        }

        private static List<Run> copy(List<Run> runs) {
            return Collections.unmodifiableList(new ArrayList<>(runs));
        }
    }

    /** One merged gap range. */
    public static final class Range {

        public final long from;
        public final long to;
        public final GapReason reason;

        public Range(long from, long to, GapReason reason) {
            if (to <= from || reason == null) {
                throw new IllegalArgumentException("range " + from + ".." + to + " " + reason);
            }
            this.from = from;
            this.to = to;
            this.reason = reason;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Range)) {
                return false;
            }
            Range r = (Range) o;
            return from == r.from && to == r.to && reason == r.reason;
        }

        @Override
        public int hashCode() {
            return (int) (from * 31 + to) * 31 + reason.hashCode();
        }

        @Override
        public String toString() {
            return "{" + from + ".." + to + " " + reason.id() + "}";
        }
    }

    /**
     * Gap ranges for the minutes {@code [fromMinute, toMinute)}. Partially observed minutes (samples &gt; 0 with a
     * non-zero state) are not gaps; their {@code gapMask} belongs to the row.
     */
    public static List<Range> minutes(MinuteSource source, int fromMinute, int toMinute, Context context) {
        Builder ranges = new Builder();
        for (long m = fromMinute; m < toMinute; m++) {
            int minute = (int) m;
            long from = m * 60L;
            long to = from + 60L;
            MinuteSlot slot = minute == 0 ? null : source.slot(minute);
            if (slot != null) {
                if (!slot.isGap()) {
                    continue;
                }
                int mask = slot.gapMask();
                if (mask == 0) {
                    ranges.add(from, to, GapReason.UNKNOWN);
                }
                for (GapReason reason : GapReason.fromMask(mask)) {
                    ranges.add(from, to, reason);
                }
            } else {
                ranges.add(from, to, missingReason(from, to, context));
            }
        }
        return ranges.build();
    }

    /** Rule 3 for a missing minute {@code [from, from+60)}. */
    public static GapReason missingReason(long from, long to, Context context) {
        boolean inRun = false;
        for (Run run : context.runs) {
            if (run.overlaps(from, to)) {
                inRun = true;
                break;
            }
        }
        if (!inRun) {
            return GapReason.SERVER_OFFLINE;
        }
        if (context.unloadedReason != null && Math.floorDiv(context.unloadedSinceEpochSec, 60L) * 60L <= from) {
            return context.unloadedReason;
        }
        return GapReason.UNKNOWN;
    }

    /** Collects ranges and merges adjacent or overlapping ones with the same reason. */
    public static final class Builder {

        private final List<Range> ranges = new ArrayList<>();

        public Builder add(long from, long to, GapReason reason) {
            ranges.add(new Range(from, to, reason));
            return this;
        }

        public List<Range> build() {
            List<Range> sorted = new ArrayList<>(ranges);
            Collections.sort(sorted, (a, b) -> {
                int c = Integer.compare(a.reason.bit(), b.reason.bit());
                return c != 0 ? c : Long.compare(a.from, b.from);
            });
            List<Range> merged = new ArrayList<>();
            Range current = null;
            for (Range r : sorted) {
                if (current != null && current.reason == r.reason && r.from <= current.to) {
                    current = new Range(current.from, Math.max(current.to, r.to), r.reason);
                } else {
                    if (current != null) {
                        merged.add(current);
                    }
                    current = r;
                }
            }
            if (current != null) {
                merged.add(current);
            }
            Collections.sort(merged, (a, b) -> {
                int c = Long.compare(a.from, b.from);
                return c != 0 ? c : Integer.compare(a.reason.bit(), b.reason.bit());
            });
            return Collections.unmodifiableList(merged);
        }
    }
}
