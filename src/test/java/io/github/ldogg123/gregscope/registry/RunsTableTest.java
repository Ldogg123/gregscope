package io.github.ldogg123.gregscope.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.history.GapRanges;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.MinuteSource;

/** GS-109: the {@code runs} table of design-v0.2 section 8.3, including the load step GS-110 must not skip. */
class RunsTableTest {

    private static List<long[]> stored(long... startStop) {
        List<long[]> rows = new ArrayList<>();
        for (int i = 0; i < startStop.length; i += 2) {
            rows.add(new long[] { startStop[i], startStop[i + 1] });
        }
        return rows;
    }

    @Test
    void anEmptyTableClaimsNothing() {
        RunsTable table = new RunsTable();
        assertEquals(0, table.size());
        assertNull(table.currentRun());
        assertTrue(
            table.resolve(1000L)
                .isEmpty());
        assertTrue(
            table.toStored()
                .isEmpty());
        // Stopping without starting is not an error; nothing changes.
        table.stopRun(500L);
        assertEquals(0, table.size());
    }

    /** Section 8.3: an unclean run is closed at the loaded {@code saved}, on load, before anything else happens. */
    @Test
    void loadingClosesUncleanRunsAtTheSavedTimestamp() {
        RunsTable table = RunsTable.loaded(stored(100L, 200L, 300L, 0L), 450L);
        assertEquals(2, table.size());
        assertEquals(
            200L,
            table.rows()
                .get(0)
                .stop());
        assertEquals(
            450L,
            table.rows()
                .get(1)
                .stop(),
            "the unclean run must end at the loaded saved");
        assertFalse(
            table.rows()
                .get(1)
                .isOpen());
        assertNull(table.currentRun(), "nothing is current until startRun");
        // And it is never closed before it started, however stale `saved` is.
        RunsTable early = RunsTable.loaded(stored(1000L, 0L), 10L);
        assertEquals(
            1000L,
            early.rows()
                .get(0)
                .stop());
    }

    @Test
    void theNewRunIsAppendedAfterTheLoadAndClosedOnStop() {
        RunsTable table = RunsTable.loaded(stored(100L, 200L, 300L, 0L), 450L);
        RunsTable.Row current = table.startRun(500L);
        assertEquals(3, table.size());
        assertSameRow(current, table.currentRun());
        assertTrue(current.isOpen());
        assertEquals(
            RunsTable.OPEN,
            table.toStored()
                .get(2)[1],
            "the current run is stored open, which is what makes a crash recognisable");
        assertThrows(IllegalStateException.class, () -> table.startRun(600L));

        table.stopRun(900L);
        assertEquals(900L, current.stop());
        assertFalse(current.isOpen());
        table.stopRun(1000L);
        assertEquals(900L, current.stop(), "a second stop keeps the first one");
    }

    @Test
    void aStopBeforeTheStartIsClampedToTheStart() {
        RunsTable table = new RunsTable();
        RunsTable.Row current = table.startRun(500L);
        table.stopRun(100L);
        assertEquals(500L, current.stop());
    }

    @Test
    void onlyTheNewestThirtyTwoRunsSurvive() {
        List<long[]> rows = new ArrayList<>();
        for (int i = 0; i < RunsTable.MAX_RUNS + 5; i++) {
            rows.add(new long[] { i * 1000L, i * 1000L + 500L });
        }
        RunsTable table = RunsTable.loaded(rows, 0L);
        assertEquals(RunsTable.MAX_RUNS, table.size());
        assertEquals(
            5 * 1000L,
            table.rows()
                .get(0)
                .start(),
            "the oldest rows are dropped, not the newest");

        RunsTable.Row current = table.startRun(999_000L);
        assertEquals(RunsTable.MAX_RUNS, table.size());
        assertSameRow(current, table.currentRun());
        table.stopRun(999_500L);
        assertEquals(
            999_500L,
            table.rows()
                .get(RunsTable.MAX_RUNS - 1)
                .stop());
    }

    @Test
    void resolvedRunsEndTheCurrentOneAtNowAndSkipRowsThatWereNeverClosed() {
        RunsTable table = RunsTable.loaded(stored(100L, 200L), 250L);
        table.startRun(300L);
        List<GapRanges.Run> runs = table.resolve(360L);
        assertEquals(2, runs.size());
        assertEquals(200L, runs.get(0).stop);
        assertEquals(360L, runs.get(1).stop, "the current run ends at now");

        // A row whose load step found no usable `saved` claims no coverage at all, rather than inventing some.
        List<GapRanges.Run> raw = RunsTable.loaded(stored(100L, 0L), 0L)
            .resolve(500L);
        assertEquals(1, raw.size());
        assertEquals(100L, raw.get(0).start);
        assertEquals(100L, raw.get(0).stop, "an unclean run closed at saved=0 covers nothing");
    }

    /**
     * The point of the whole table: minutes outside every run read as {@code server_offline}, and the downtime of a
     * crashed run stays offline once a later run has saved (design-v0.2 review fix F2).
     */
    @Test
    void downtimeBetweenRunsIsServerOffline() {
        // Run 1 crashed at minute 10 (saved 600 s); run 2 starts at minute 20 and is current.
        RunsTable table = RunsTable.loaded(stored(0L, 0L), 600L);
        table.startRun(1200L);
        GapRanges.Context context = GapRanges.Context.of(table.resolve(1800L));
        MinuteSource nothing = minute -> null;
        List<GapRanges.Range> ranges = GapRanges.minutes(nothing, 0, 30, context);

        assertEquals(
            Arrays.asList(
                new GapRanges.Range(0L, 600L, GapReason.UNKNOWN),
                new GapRanges.Range(600L, 1200L, GapReason.SERVER_OFFLINE),
                new GapRanges.Range(1200L, 1800L, GapReason.UNKNOWN)),
            ranges);
    }

    private static void assertSameRow(RunsTable.Row expected, RunsTable.Row actual) {
        assertTrue(expected == actual, "expected the same row object, got " + actual);
    }
}
