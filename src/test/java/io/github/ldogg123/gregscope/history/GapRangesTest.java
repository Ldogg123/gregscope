package io.github.ldogg123.gregscope.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.history.GapRanges.Context;
import io.github.ldogg123.gregscope.history.GapRanges.Range;
import io.github.ldogg123.gregscope.history.GapRanges.Run;
import io.github.ldogg123.gregscope.model.StateCodes;

class GapRangesTest {

    private static final int M = 29_318_460;

    private static long sec(int minute) {
        return minute * 60L;
    }

    private static final class Slots implements MinuteSource {

        final Map<Integer, MinuteSlot> map = new HashMap<>();

        Slots observed(int minute) {
            map.put(
                minute,
                MinuteSlot.builder(minute)
                    .samples(60)
                    .expectedSamples(60)
                    .lastStateCode(StateCodes.RUNNING)
                    .stateSamples(StateCodes.RUNNING, 60)
                    .build());
            return this;
        }

        Slots stored(MinuteSlot slot) {
            map.put(slot.epochMinute(), slot);
            return this;
        }

        @Override
        public MinuteSlot slot(int epochMinute) {
            return map.get(epochMinute);
        }
    }

    private static List<Run> runs(Run... runs) {
        return Arrays.asList(runs);
    }

    private static Range range(int fromMinute, int toMinute, GapReason reason) {
        return new Range(sec(fromMinute), sec(toMinute), reason);
    }

    @Test
    void fullyObservedWindowHasNoGaps() {
        Slots slots = new Slots();
        for (int m = M; m < M + 10; m++) {
            slots.observed(m);
        }
        assertEquals(
            Collections.emptyList(),
            GapRanges.minutes(slots, M, M + 10, Context.of(runs(new Run(sec(M), sec(M + 10))))));
        assertEquals(Collections.emptyList(), GapRanges.minutes(slots, M, M, Context.of(runs())));
    }

    @Test
    void missingMinutesOutsideEveryRunAreServerOffline() {
        // Run 1: minutes M..M+2, offline M+3..M+5, run 2 from M+6.
        Slots slots = new Slots().observed(M)
            .observed(M + 1)
            .observed(M + 2)
            .observed(M + 6);
        Context context = Context.of(runs(new Run(sec(M), sec(M + 3)), new Run(sec(M + 6), sec(M + 8))));
        assertEquals(
            Arrays.asList(range(M + 3, M + 6, GapReason.SERVER_OFFLINE), range(M + 7, M + 8, GapReason.UNKNOWN)),
            GapRanges.minutes(slots, M, M + 8, context));
    }

    @Test
    void noRunsAtAllMeansOffline() {
        assertEquals(
            Arrays.asList(range(M, M + 3, GapReason.SERVER_OFFLINE)),
            GapRanges.minutes(new Slots(), M, M + 3, Context.of(runs())));
    }

    @Test
    void aMinutePartlyInsideARunIsNotOffline() {
        // The server started 30 s into M+1 and stopped 10 s into M+3.
        Context context = Context.of(runs(new Run(sec(M + 1) + 30, sec(M + 3) + 10)));
        assertEquals(
            Arrays.asList(
                range(M, M + 1, GapReason.SERVER_OFFLINE),
                range(M + 1, M + 4, GapReason.UNKNOWN),
                range(M + 4, M + 5, GapReason.SERVER_OFFLINE)),
            GapRanges.minutes(new Slots(), M, M + 5, context));
    }

    @Test
    void missingWhileUnloadedUsesTheRegistryReason() {
        Slots slots = new Slots().observed(M)
            .observed(M + 1);
        Run run = new Run(sec(M), sec(M + 10));
        // UNLOADED since 20 s into M+2: that minute counts (the since-minute is <= it), later ones too.
        Context chunk = Context.unloaded(runs(run), sec(M + 2) + 20, GapReason.CHUNK_UNLOADED);
        assertEquals(
            Arrays.asList(range(M + 2, M + 6, GapReason.CHUNK_UNLOADED)),
            GapRanges.minutes(slots, M, M + 6, chunk));

        Context dim = Context.unloaded(runs(run), sec(M + 4), GapReason.DIMENSION_UNLOADED);
        assertEquals(
            Arrays.asList(range(M + 2, M + 4, GapReason.UNKNOWN), range(M + 4, M + 6, GapReason.DIMENSION_UNLOADED)),
            GapRanges.minutes(slots, M, M + 6, dim));

        assertThrows(IllegalArgumentException.class, () -> Context.unloaded(runs(run), 0, GapReason.TARGET_MISSING));
    }

    @Test
    void offlineWinsOverUnloaded() {
        Context context = Context.unloaded(runs(new Run(sec(M), sec(M + 2))), sec(M), GapReason.CHUNK_UNLOADED);
        assertEquals(
            Arrays.asList(range(M, M + 2, GapReason.CHUNK_UNLOADED), range(M + 2, M + 4, GapReason.SERVER_OFFLINE)),
            GapRanges.minutes(new Slots(), M, M + 4, context));
    }

    @Test
    void storedGapSlotsUseTheirStoredReasons() {
        Slots slots = new Slots().stored(
            MinuteSlot.builder(M)
                .expectedSamples(60)
                .gapMask(GapReason.TARGET_MISSING.mask())
                .build())
            .stored(
                MinuteSlot.builder(M + 1)
                    .expectedSamples(60)
                    .gapMask(GapReason.TARGET_MISSING.mask() | GapReason.PROBE_ERROR.mask())
                    .build())
            .stored(
                MinuteSlot.builder(M + 2)
                    .expectedSamples(60)
                    .gapMask(GapReason.PROBE_ERROR.mask())
                    .build())
            // samples == 0 without reasons: unknown, merged with the adjacent missing unknown minute
            .stored(
                MinuteSlot.builder(M + 3)
                    .expectedSamples(60)
                    .serverTicks(1200)
                    .build());
        Context context = Context.of(runs(new Run(sec(M), sec(M + 100))));
        assertEquals(
            Arrays.asList(
                range(M, M + 2, GapReason.TARGET_MISSING),
                range(M + 1, M + 3, GapReason.PROBE_ERROR),
                range(M + 3, M + 5, GapReason.UNKNOWN)),
            GapRanges.minutes(slots, M, M + 5, context));
    }

    @Test
    void storedSlotsAreNotReattributedByRuns() {
        // A stored gap slot keeps its stored reason even if the runs table does not cover it.
        Slots slots = new Slots().stored(
            MinuteSlot.builder(M)
                .gapMask(GapReason.SENSOR_REMOVED.mask())
                .build());
        assertEquals(
            Arrays.asList(range(M, M + 1, GapReason.SENSOR_REMOVED)),
            GapRanges.minutes(slots, M, M + 1, Context.of(runs())));
    }

    @Test
    void stateZeroWithSamplesIsAGapButPartialMinutesAreNot() {
        Slots slots = new Slots().stored(
            MinuteSlot.builder(M)
                .samples(10)
                .expectedSamples(60)
                .lastStateCode(StateCodes.UNAVAILABLE)
                .stateSamples(StateCodes.UNAVAILABLE, 10)
                .gapMask(GapReason.PROBE_ERROR.mask())
                .build())
            .stored(
                MinuteSlot.builder(M + 1)
                    .samples(20)
                    .expectedSamples(60)
                    .lastStateCode(StateCodes.RUNNING)
                    .stateSamples(StateCodes.RUNNING, 20)
                    .gapMask(GapReason.CHUNK_UNLOADED.mask())
                    .flags(MinuteSlot.FLAG_PARTIAL_MINUTE)
                    .build());
        List<Range> gaps = GapRanges.minutes(slots, M, M + 2, Context.of(runs(new Run(sec(M), sec(M + 2)))));
        assertEquals(Arrays.asList(range(M, M + 1, GapReason.PROBE_ERROR)), gaps);
    }

    @Test
    void runResolution() {
        // Clean run: the load step keeps its stop.
        assertEquals(500, Run.stopOnLoad(100, 500, 999));
        Run clean = Run.resolve(100, 500, false, 2000);
        assertEquals(100, clean.start);
        assertEquals(500, clean.stop);
        // Unclean run (stop 0) is closed on load at the loaded registry's saved time.
        assertEquals(450, Run.stopOnLoad(100, 0, 450));
        assertEquals(450, Run.resolve(100, Run.stopOnLoad(100, 0, 450), false, 2000).stop);
        // saved before the run's own start (never saved during it): an empty run at its start.
        assertEquals(100, Run.stopOnLoad(100, 0, 50));
        // The current process's run is ongoing until now.
        assertEquals(2000, Run.resolve(100, 0, true, 2000).stop);
        // A non-ongoing stop 0 that skipped the load step claims no coverage.
        assertEquals(100, Run.resolve(100, 0, false, 2000).stop);
        assertThrows(IllegalArgumentException.class, () -> new Run(10, 5));

        // An unclean run's minutes after `saved` are offline.
        Context context = Context.of(runs(Run.resolve(sec(M), Run.stopOnLoad(sec(M), 0, sec(M + 2)), false, 0)));
        assertEquals(
            Arrays.asList(range(M, M + 2, GapReason.UNKNOWN), range(M + 2, M + 4, GapReason.SERVER_OFFLINE)),
            GapRanges.minutes(new Slots(), M, M + 4, context));
    }

    /**
     * Run 1 crashes (last registry save at M+2), the server is down until M+10, run 2 starts and later saves (saved
     * moves to M+12). The outage must stay server_offline: run 1 was closed at the saved time loaded at M+10, not at
     * the registry's current saved time.
     */
    @Test
    void uncleanRunFollowedByALaterRunKeepsTheOutageOffline() {
        long savedAtLoad = sec(M + 2);
        long run1Stop = Run.stopOnLoad(sec(M), 0, savedAtLoad); // load step at run 2's start
        long savedLater = sec(M + 12); // run 2's autosave; must not move run 1's end
        assertTrue(savedLater > run1Stop);
        long now = sec(M + 13);
        Context context = Context
            .of(runs(Run.resolve(sec(M), run1Stop, false, now), Run.resolve(sec(M + 10), 0, true, now)));
        assertEquals(
            Arrays.asList(
                range(M, M + 2, GapReason.UNKNOWN),
                range(M + 2, M + 10, GapReason.SERVER_OFFLINE),
                range(M + 10, M + 13, GapReason.UNKNOWN)),
            GapRanges.minutes(new Slots(), M, M + 13, context));
    }

    @Test
    void builderMergesAdjacentAndOverlappingSameReasonOnly() {
        List<Range> merged = new GapRanges.Builder().add(100, 160, GapReason.UNKNOWN)
            .add(160, 220, GapReason.UNKNOWN) // adjacent
            .add(200, 300, GapReason.UNKNOWN) // overlapping
            .add(400, 460, GapReason.UNKNOWN) // separate
            .add(0, 60, GapReason.SERVER_OFFLINE)
            .add(60, 100, GapReason.SERVER_OFFLINE)
            .add(150, 170, GapReason.CHUNK_UNLOADED) // different reason, overlaps: not merged
            .build();
        assertEquals(
            Arrays.asList(
                new Range(0, 100, GapReason.SERVER_OFFLINE),
                new Range(100, 300, GapReason.UNKNOWN),
                new Range(150, 170, GapReason.CHUNK_UNLOADED),
                new Range(400, 460, GapReason.UNKNOWN)),
            merged);
        assertThrows(UnsupportedOperationException.class, () -> merged.add(new Range(1, 2, GapReason.UNKNOWN)));
        assertThrows(IllegalArgumentException.class, () -> new Range(5, 5, GapReason.UNKNOWN));
        assertTrue(
            new GapRanges.Builder().build()
                .isEmpty());
    }

    @Test
    void realisticDay() {
        // Observed M..M+59, unloaded (chunk) from M+60, server stopped at M+90, restarted at M+120 and the chunk loaded
        // again at M+125 (observed from there).
        Slots slots = new Slots();
        for (int m = M; m < M + 60; m++) {
            slots.observed(m);
        }
        slots.stored(
            MinuteSlot.builder(M + 60)
                .samples(30)
                .expectedSamples(60)
                .lastStateCode(StateCodes.RUNNING)
                .stateSamples(StateCodes.RUNNING, 30)
                .gapMask(GapReason.CHUNK_UNLOADED.mask())
                .flags(MinuteSlot.FLAG_PARTIAL_MINUTE)
                .build());
        for (int m = M + 125; m < M + 130; m++) {
            slots.observed(m);
        }
        // The registry no longer says UNLOADED (the sensor is LIVE again), so the unloaded stretch reads unknown: the
        // reader contract only knows the current registry state.
        Context now = Context.of(runs(new Run(sec(M) - 3600, sec(M + 90)), new Run(sec(M + 120), sec(M + 130))));
        assertEquals(
            Arrays.asList(
                range(M + 61, M + 90, GapReason.UNKNOWN),
                range(M + 90, M + 120, GapReason.SERVER_OFFLINE),
                range(M + 120, M + 125, GapReason.UNKNOWN)),
            GapRanges.minutes(slots, M, M + 130, now));
    }
}
