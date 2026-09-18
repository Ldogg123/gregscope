package io.github.ldogg123.gregscope.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.ldogg123.gregscope.history.GapRanges;

/**
 * The {@code runs} table of {@code registry.dat} (design-v0.2 §8.3): when each server run of this world started and
 * stopped, in epoch seconds, at most {@value #MAX_RUNS} of them with the oldest dropped. [pure]
 *
 * <p>
 * It is what turns a minute nobody recorded into {@code server_offline} rather than {@code unknown} (§7.5 rule 3), so
 * the order of operations at startup matters and is enforced here:
 *
 * <ol>
 * <li>{@link #loaded(List, long)} reads the stored rows and closes every unclean run ({@code stop == 0}) at the
 * {@code saved} time that was loaded with them, through {@link GapRanges.Run#stopOnLoad}. This happens while decoding,
 * so it is impossible to append the new run or to save before it has been done (the GS-110 follow-up recorded in the
 * GS-107 implementation notes).</li>
 * <li>{@link #startRun(long)} appends the new run with {@code stop = 0}.</li>
 * <li>{@link #stopRun(long)} closes it when the server stops.</li>
 * </ol>
 *
 * <p>
 * The current run keeps {@code stop == 0} on disk while the server is up, which is exactly what makes a crash
 * recognisable: the next start finds it open and closes it at the last {@code saved}.
 */
public final class RunsTable {

    /** Design-v0.2 §8.3: at most 32 runs, oldest dropped. */
    public static final int MAX_RUNS = 32;
    /** A run that has not stopped yet, or that stopped uncleanly. */
    public static final long OPEN = 0L;

    /** One stored row. Mutable only through {@link RunsTable}. */
    public static final class Row {

        private final long start;
        private long stop;

        Row(long start, long stop) {
            this.start = start;
            this.stop = stop;
        }

        public long start() {
            return start;
        }

        /** The stop time, or {@link #OPEN} while the run is the current one. */
        public long stop() {
            return stop;
        }

        public boolean isOpen() {
            return stop == OPEN;
        }

        @Override
        public String toString() {
            return "{" + start + ".." + (stop == OPEN ? "open" : Long.toString(stop)) + "}";
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private int currentIndex = -1;

    /** An empty table: a world that has no {@code registry.dat}, or one that could not be read. */
    public RunsTable() {}

    /**
     * The stored rows with every unclean run closed at {@code saved} (design-v0.2 §8.3, review fix F2). Rows are
     * kept in the order they were stored; only the newest {@value #MAX_RUNS} survive.
     *
     * @param stored each entry {@code {start, stop}}, {@code stop == 0} meaning unclean
     * @param saved  the {@code saved} field loaded from the same file
     */
    public static RunsTable loaded(List<long[]> stored, long saved) {
        RunsTable table = new RunsTable();
        for (long[] row : stored) {
            if (row == null || row.length < 2) {
                continue;
            }
            table.rows.add(new Row(row[0], GapRanges.Run.stopOnLoad(row[0], row[1], saved)));
        }
        table.trim();
        return table;
    }

    /** Appends this process's run. Call it after {@link #loaded} and before anything is saved. */
    public Row startRun(long nowEpochSec) {
        if (currentIndex >= 0) {
            throw new IllegalStateException("a run is already open");
        }
        Row row = new Row(nowEpochSec, OPEN);
        rows.add(row);
        trim();
        currentIndex = rows.size() - 1;
        return row;
    }

    /** Closes this process's run, never before it started. Calling it twice keeps the first stop. */
    public void stopRun(long nowEpochSec) {
        Row current = currentRun();
        if (current == null || !current.isOpen()) {
            return;
        }
        current.stop = Math.max(current.start, nowEpochSec);
    }

    /** This process's run, or null before {@link #startRun} or after it was trimmed away. */
    public Row currentRun() {
        return currentIndex >= 0 && currentIndex < rows.size() ? rows.get(currentIndex) : null;
    }

    /** Every row, oldest first. */
    public List<Row> rows() {
        return Collections.unmodifiableList(rows);
    }

    public int size() {
        return rows.size();
    }

    /**
     * The runs as the §7.5 reader contract wants them: this process's run ends at {@code now}, every other row at its
     * stored stop, and a row that is open although it is not the current one claims no coverage at all
     * ({@link GapRanges.Run#resolve}).
     */
    public List<GapRanges.Run> resolve(long nowEpochSec) {
        List<GapRanges.Run> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            out.add(GapRanges.Run.resolve(row.start, row.stop, i == currentIndex, nowEpochSec));
        }
        return Collections.unmodifiableList(out);
    }

    /** The rows as {@code {start, stop}} pairs, for the NBT codec. */
    public List<long[]> toStored() {
        List<long[]> out = new ArrayList<>(rows.size());
        for (Row row : rows) {
            out.add(new long[] { row.start, row.stop });
        }
        return out;
    }

    private void trim() {
        while (rows.size() > MAX_RUNS) {
            rows.remove(0);
            if (currentIndex >= 0) {
                currentIndex--;
            }
        }
    }

    @Override
    public String toString() {
        return "RunsTable" + rows;
    }
}
