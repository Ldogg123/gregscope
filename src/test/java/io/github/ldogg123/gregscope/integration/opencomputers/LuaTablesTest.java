package io.github.ldogg123.gregscope.integration.opencomputers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.history.GapRanges;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.MinuteRing;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.history.SecondRing;
import io.github.ldogg123.gregscope.model.MachineKind;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.MachineState;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.RemovalCause;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.SensorView;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * The OpenComputers table builders of design-v0.2 sections 10.1 and 10.4 (GS-115): record keys, the absent-key rule,
 * count clamps, {@code before} paging and gap ranges.
 */
class LuaTablesTest {

    /** 2026-01-01T00:00:00Z, a whole minute, so windows and slot minutes line up in the assertions. */
    private static final long T0 = 1_767_225_600L;
    private static final int NOW_MINUTE = (int) (T0 / 60L);
    private static final int EXPECTED_PER_MINUTE = 60;
    private static final UUID ID = UUID.fromString("3fa2c1d0-1111-4000-8000-000000000001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    // --- section 10.1 record ---

    @Test
    void recordCarriesEverySectionTenOneKey() {
        Map<String, Object> record = LuaTables.sensorRecord(live().view(), true, T0);
        Set<String> expected = new LinkedHashSet<>(
            Arrays.asList(
                "sensorRecordVersion",
                "id",
                "shortId",
                "label",
                "displayName",
                "owner",
                "ownerName",
                "availability",
                "availabilitySince",
                "dimension",
                "x",
                "y",
                "z",
                "side",
                "kind",
                "metaName",
                "metaId",
                "machineName",
                "state",
                "statusId",
                "lastSeen",
                "lastSample",
                "ageSeconds",
                "historyLoaded"));
        assertEquals(expected, record.keySet(), "record keys");
        assertEquals(1, record.get("sensorRecordVersion"));
        assertEquals(ID.toString(), record.get("id"));
        assertEquals("3fa2c1d0", record.get("shortId"));
        assertEquals("EBF North", record.get("label"));
        assertEquals("EBF North", record.get("displayName"));
        assertEquals(OWNER.toString(), record.get("owner"));
        assertEquals("Steve", record.get("ownerName"));
        assertEquals("live", record.get("availability"));
        assertEquals(T0 - 600L, record.get("availabilitySince"));
        assertEquals(0, record.get("dimension"));
        assertEquals(120, record.get("x"));
        assertEquals(64, record.get("y"));
        assertEquals(-30, record.get("z"));
        assertEquals("west", record.get("side"));
        assertEquals("machine", record.get("kind"));
        assertEquals("machine.ebf", record.get("metaName"));
        assertEquals(1001, record.get("metaId"));
        assertEquals("Electric Blast Furnace", record.get("machineName"));
        assertEquals("running", record.get("state"));
        assertEquals("running", record.get("statusId"));
        assertEquals(T0 - 5L, record.get("lastSeen"));
        assertEquals(T0 - 1L, record.get("lastSample"));
        assertEquals(1L, record.get("ageSeconds"));
        assertEquals(Boolean.TRUE, record.get("historyLoaded"));
    }

    @Test
    void displayNameFallsBackToTheMachineNameAndShortId() {
        Sensor sensor = live();
        sensor.entry.setIdentity(new SensorIdentity(ID, "", OWNER, "Steve", T0 - 86_400L));
        Map<String, Object> record = LuaTables.sensorRecord(sensor.view(), true, T0);
        assertEquals("", record.get("label"));
        assertEquals("Electric Blast Furnace #3fa2c1d0", record.get("displayName"));
    }

    @Test
    void absentValuesAreMissingKeysNeverNil() {
        Sensor sensor = new Sensor(null);
        Map<String, Object> record = LuaTables.sensorRecord(sensor.view(), false, T0);
        assertFalse(record.containsKey("owner"), "owner of an unowned sensor");
        assertFalse(record.containsKey("ownerName"), "ownerName of an unowned sensor");
        assertFalse(record.containsKey("lastSample"), "lastSample before the first sample");
        assertFalse(record.containsKey("ageSeconds"), "ageSeconds before the first sample");
        assertEquals(Boolean.FALSE, record.get("historyLoaded"));
        assertEquals(T0 - 600L, record.get("lastSeen"), "a registered sensor was always seen at least once");
        assertNoNulls("record", record);

        // A restored entry whose stored lastSeen is 0 drops the key rather than reporting the epoch.
        sensor.entry.restoreState(SensorState.UNLOADED, RemovalCause.NONE, T0 - 600L, 0L);
        assertFalse(
            LuaTables.sensorRecord(sensor.view(), false, T0)
                .containsKey("lastSeen"),
            "lastSeen of an entry that records none");
    }

    @Test
    void aSensorWithNoSnapshotReadsTheV1ReservedUnavailable() {
        Map<String, Object> record = LuaTables.sensorRecord(new Sensor(OWNER).view(), true, T0);
        assertEquals("unavailable", record.get("state"));
        assertEquals("machine_unavailable", record.get("statusId"));
    }

    @Test
    void anUnloadedSensorStillReportsItsLastSnapshotQualifiedByAge() {
        Sensor sensor = live();
        sensor.entry.restoreState(SensorState.UNLOADED, RemovalCause.NONE, T0 - 300L, T0 - 300L);
        Map<String, Object> record = LuaTables.sensorRecord(sensor.view(), true, T0);
        assertEquals("unloaded", record.get("availability"));
        assertEquals(T0 - 300L, record.get("availabilitySince"));
        assertEquals("running", record.get("state"), "the last known state, not a zero-filled one");
        assertEquals(1L, record.get("ageSeconds"), "the qualifier that says how old that state is");
    }

    @Test
    void ageSecondsNeverGoesNegativeWhenTheClockStepsBack() {
        Map<String, Object> record = LuaTables.sensorRecord(live().view(), true, T0 - 60L);
        assertEquals(0L, record.get("ageSeconds"));
    }

    @Test
    void availabilityIdsArePinned() {
        assertEquals("live", LuaTables.availability(SensorState.LIVE));
        assertEquals("unloaded", LuaTables.availability(SensorState.UNLOADED));
        assertEquals("over_cap", LuaTables.availability(SensorState.OVER_CAP));
        assertEquals("missing", LuaTables.availability(SensorState.MISSING));
        assertEquals("in_item", LuaTables.availability(SensorState.IN_ITEM));
        assertEquals("removed", LuaTables.availability(SensorState.REMOVED));
        assertEquals("", LuaTables.availability(null));
        // Never SensorState.label(), which spells two of them with a space for the cover tooltip.
        assertEquals("over cap", SensorState.OVER_CAP.label());
        assertEquals("in item", SensorState.IN_ITEM.label());
    }

    @Test
    void sideNamesArePinnedInForgeDirectionOrder() {
        assertEquals("down", LuaTables.sideName(0));
        assertEquals("up", LuaTables.sideName(1));
        assertEquals("north", LuaTables.sideName(2));
        assertEquals("south", LuaTables.sideName(3));
        assertEquals("west", LuaTables.sideName(4));
        assertEquals("east", LuaTables.sideName(5));
        assertEquals("unknown", LuaTables.sideName(6), "ForgeDirection.UNKNOWN");
        assertEquals("unknown", LuaTables.sideName(-1));
    }

    // --- section 10.4 arguments ---

    @Test
    void resolutionsAndStepsArePinned() {
        assertTrue(LuaTables.isResolution("second"));
        assertTrue(LuaTables.isResolution("minute"));
        assertFalse(LuaTables.isResolution("hour"));
        assertFalse(LuaTables.isResolution(""));
        assertFalse(LuaTables.isResolution(null));
        assertEquals(1, LuaTables.step("second"));
        assertEquals(60, LuaTables.step("minute"));
        assertThrows(IllegalArgumentException.class, () -> LuaTables.step("hour"));
    }

    @Test
    void countIsClampedNeverRefused() {
        assertEquals(1, LuaTables.clampCount("second", 0));
        assertEquals(1, LuaTables.clampCount("second", -7));
        assertEquals(60, LuaTables.clampCount("second", 60));
        assertEquals(300, LuaTables.clampCount("second", 300));
        assertEquals(300, LuaTables.clampCount("second", 301));
        assertEquals(300, LuaTables.clampCount("second", Integer.MAX_VALUE));
        assertEquals(1, LuaTables.clampCount("minute", 0));
        assertEquals(240, LuaTables.clampCount("minute", 240));
        assertEquals(240, LuaTables.clampCount("minute", 100_000));
        assertEquals(60, LuaTables.DEFAULT_COUNT);
    }

    @Test
    void beforeIsClampedIntoWhatASlotLayoutCanIndex() {
        assertEquals(0L, LuaTables.clampBefore(-1L));
        assertEquals(T0, LuaTables.clampBefore(T0));
        assertEquals(LuaTables.MAX_BEFORE, LuaTables.clampBefore(Long.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, (int) (LuaTables.MAX_BEFORE / 60L));
    }

    // --- section 10.4 minute history ---

    @Test
    void minuteWindowIsAlignedAndHoldsExactlyCountMinutes() {
        MinuteRing ring = ringOf(NOW_MINUTE - 3, NOW_MINUTE);
        // A `before` in the middle of the open minute floors to its start: the open minute is never a row.
        Map<String, Object> table = LuaTables.minuteHistory(ID, ring, 3, T0 + 37L, EXPECTED_PER_MINUTE, noRuns());
        assertEquals(1, table.get("historyVersion"));
        assertEquals(ID.toString(), table.get("id"));
        assertEquals("minute", table.get("resolution"));
        assertEquals(60, table.get("step"));
        assertEquals(T0 - 180L, table.get("from"));
        assertEquals(T0, table.get("to"));
        assertEquals(3L, (long) rows(table).size());
    }

    @Test
    void minuteRowsAreOldestFirst() {
        MinuteRing ring = ringOf(NOW_MINUTE - 5, NOW_MINUTE);
        List<Map<String, Object>> rows = rows(LuaTables.minuteHistory(ID, ring, 5, T0, EXPECTED_PER_MINUTE, noRuns()));
        assertEquals(5, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(
                (NOW_MINUTE - 5 + i) * 60L,
                rows.get(i)
                    .get("t"),
                "row " + i);
        }
    }

    @Test
    void passingTheReturnedFromBackPagesOneWindowFurtherBack() {
        MinuteRing ring = ringOf(NOW_MINUTE - 10, NOW_MINUTE);
        Map<String, Object> page1 = LuaTables.minuteHistory(ID, ring, 5, T0, EXPECTED_PER_MINUTE, noRuns());
        long from = (Long) page1.get("from");
        Map<String, Object> page2 = LuaTables.minuteHistory(ID, ring, 5, from, EXPECTED_PER_MINUTE, noRuns());
        assertEquals(from, page2.get("to"), "the older page ends exactly where the newer one starts");
        assertEquals(from - 300L, page2.get("from"));
        List<Map<String, Object>> older = rows(page2);
        List<Map<String, Object>> newer = rows(page1);
        assertEquals(5, older.size());
        assertEquals(5, newer.size());
        assertTrue(
            (Long) older.get(older.size() - 1)
                .get("t")
                < (Long) newer.get(0)
                    .get("t"),
            "pages overlap");
    }

    @Test
    void onlyObservedOrPartiallyObservedMinutesAreRows() {
        MinuteRing ring = new MinuteRing();
        ring.put(observed(NOW_MINUTE - 3, 40, 20));
        // samples == 0 with a stored reason: a gap by the section 7.5 rule 2, never a row.
        ring.put(
            MinuteSlot.builder(NOW_MINUTE - 2)
                .expectedSamples(EXPECTED_PER_MINUTE)
                .gapMask(GapReason.CHUNK_UNLOADED.mask())
                .build());
        ring.put(observed(NOW_MINUTE - 1, 30, 30));
        Map<String, Object> table = LuaTables.minuteHistory(ID, ring, 3, T0, EXPECTED_PER_MINUTE, noRuns());
        List<Map<String, Object>> rows = rows(table);
        assertEquals(2, rows.size(), "the gap minute is not a row");
        assertEquals(
            (NOW_MINUTE - 3) * 60L,
            rows.get(0)
                .get("t"));
        assertEquals(
            (NOW_MINUTE - 1) * 60L,
            rows.get(1)
                .get("t"));
        List<Map<String, Object>> gaps = gaps(table);
        assertEquals(1, gaps.size());
        assertEquals(
            (NOW_MINUTE - 2) * 60L,
            gaps.get(0)
                .get("from"));
        assertEquals(
            (NOW_MINUTE - 1) * 60L,
            gaps.get(0)
                .get("to"));
        assertEquals(
            "chunk_unloaded",
            gaps.get(0)
                .get("reason"));
    }

    @Test
    void aMinuteRowCarriesTheSectionTenFourKeys() {
        MinuteRing ring = new MinuteRing();
        ring.put(
            MinuteSlot.builder(NOW_MINUTE - 1)
                .samples(60)
                .expectedSamples(60)
                .lastStateCode(StateCodes.RUNNING)
                .stateSamples(StateCodes.RUNNING, 50)
                .stateSamples(StateCodes.IDLE, 10)
                .maintenanceMax(2)
                .serverTicks(1200)
                .euSamples(60)
                .euPerTickAvg(1920L)
                .euPerTickMin(0L)
                .euPerTickMax(2048L)
                .energyStoredLast(1_200_000L)
                .recipesCompletedDelta(7)
                .build());
        Map<String, Object> row = rows(LuaTables.minuteHistory(ID, ring, 1, T0, EXPECTED_PER_MINUTE, noRuns())).get(0);
        assertEquals((NOW_MINUTE - 1) * 60L, row.get("t"));
        assertEquals(60, row.get("samples"));
        assertEquals(60, row.get("expected"));
        assertEquals(1.0, row.get("coverage"));
        assertEquals("running", row.get("lastState"));
        assertEquals(2, row.get("maintenanceMax"));
        assertEquals(1200, row.get("serverTicks"));
        assertEquals(1920L, row.get("euPerTickAvg"));
        assertEquals(0L, row.get("euPerTickMin"));
        assertEquals(2048L, row.get("euPerTickMax"));
        assertEquals(1_200_000L, row.get("energyStored"));
        assertEquals(7, row.get("recipesCompleted"));
        assertFalse(row.containsKey("gaps"), "a clean minute carries no gaps list");
        @SuppressWarnings("unchecked")
        Map<String, Object> states = (Map<String, Object>) row.get("stateSeconds");
        assertEquals(new LinkedHashSet<>(Arrays.asList("running", "idle")), states.keySet(), "only observed states");
        assertEquals(50, states.get("running"));
        assertEquals(10, states.get("idle"));
    }

    @Test
    void aMinuteRowOmitsEveryValueItDoesNotHave() {
        MinuteRing ring = new MinuteRing();
        ring.put(
            MinuteSlot.builder(NOW_MINUTE - 1)
                .samples(30)
                .expectedSamples(60)
                .lastStateCode(StateCodes.IDLE)
                .stateSamples(StateCodes.IDLE, 30)
                .gapMask(GapReason.PROBE_ERROR.mask())
                .build());
        Map<String, Object> table = LuaTables.minuteHistory(ID, ring, 1, T0, EXPECTED_PER_MINUTE, noRuns());
        Map<String, Object> row = rows(table).get(0);
        assertFalse(row.containsKey("euPerTickAvg"), "euPerTickAvg with no EU sample");
        assertFalse(row.containsKey("euPerTickMin"), "euPerTickMin with no EU sample");
        assertFalse(row.containsKey("euPerTickMax"), "euPerTickMax with no EU sample");
        assertFalse(row.containsKey("energyStored"), "energyStored with none stored");
        assertFalse(row.containsKey("recipesCompleted"), "recipesCompleted of a single block");
        assertEquals(0.5, row.get("coverage"), "a partially observed minute is not zero-filled");
        assertEquals(Collections.singletonList("probe_error"), row.get("gaps"));
        // A partially observed minute is a row, so its seconds are not also reported as a gap range.
        assertEquals(Collections.emptyList(), table.get("gaps"));
        assertNoNulls("minute history", table);
    }

    @Test
    void coverageNeverExceedsOne() {
        MinuteRing ring = new MinuteRing();
        ring.put(
            MinuteSlot.builder(NOW_MINUTE - 1)
                .samples(90)
                .expectedSamples(60)
                .lastStateCode(StateCodes.RUNNING)
                .stateSamples(StateCodes.RUNNING, 90)
                .build());
        Map<String, Object> row = rows(LuaTables.minuteHistory(ID, ring, 1, T0, EXPECTED_PER_MINUTE, noRuns())).get(0);
        assertEquals(1.0, row.get("coverage"));
    }

    @Test
    void missingMinutesGetTheirReasonFromTheRunsTableAndTheRegistry() {
        MinuteRing ring = ringOf(NOW_MINUTE - 1, NOW_MINUTE);
        // Minutes -4..-2 are missing. With no server run covering them, rule 3 says server_offline.
        Map<String, Object> offline = LuaTables.minuteHistory(ID, ring, 4, T0, EXPECTED_PER_MINUTE, noRuns());
        assertEquals(
            Collections.singletonList(range(T0 - 240L, T0 - 60L, "server_offline")),
            simpleGaps(offline),
            "three adjacent missing minutes merge into one range");

        // Inside a run, with the registry saying UNLOADED since before the window, rule 3 says chunk_unloaded.
        GapRanges.Context unloaded = GapRanges.Context.unloaded(
            Collections.singletonList(new GapRanges.Run(T0 - 3600L, T0)),
            T0 - 3600L,
            GapReason.CHUNK_UNLOADED);
        Map<String, Object> table = LuaTables.minuteHistory(ID, ring, 4, T0, EXPECTED_PER_MINUTE, unloaded);
        assertEquals(Collections.singletonList(range(T0 - 240L, T0 - 60L, "chunk_unloaded")), simpleGaps(table));
    }

    @Test
    void minuteHistoryRejectsArgumentsTheCallbackMustClampFirst() {
        MinuteRing ring = new MinuteRing();
        assertThrows(
            IllegalArgumentException.class,
            () -> LuaTables.minuteHistory(ID, null, 1, T0, EXPECTED_PER_MINUTE, noRuns()));
        assertThrows(
            IllegalArgumentException.class,
            () -> LuaTables.minuteHistory(ID, ring, 0, T0, EXPECTED_PER_MINUTE, noRuns()));
        assertThrows(IllegalArgumentException.class, () -> LuaTables.minuteHistory(ID, ring, 1, T0, 0, noRuns()));
        assertThrows(
            IllegalArgumentException.class,
            () -> LuaTables.minuteHistory(ID, ring, 1, T0, EXPECTED_PER_MINUTE, null));
    }

    // --- section 10.4 second history ---

    @Test
    void secondRowsAreOldestFirstAndCarryTheSectionTenFourKeys() {
        SecondRing ring = new SecondRing();
        ring.appendSample(
            (int) (T0 - 3L),
            StateCodes.RUNNING,
            SecondRing.FLAG_ACTIVE | SecondRing.FLAG_ALLOWED_TO_WORK
                | SecondRing.FLAG_FORMED_KNOWN
                | SecondRing.FLAG_FORMED
                | SecondRing.FLAG_HAS_EU
                | SecondRing.FLAG_HAS_ENERGY,
            1,
            0.42,
            1920L,
            1_200_000L);
        ring.appendSample((int) (T0 - 2L), StateCodes.IDLE, 0, 0, 0.0, 0L, 0L);
        Map<String, Object> table = LuaTables.secondHistory(ID, ring, 3, T0, noRuns());
        assertEquals(1, table.get("historyVersion"));
        assertEquals("second", table.get("resolution"));
        assertEquals(1, table.get("step"));
        assertEquals(T0 - 3L, table.get("from"));
        assertEquals(T0, table.get("to"));

        List<Map<String, Object>> rows = rows(table);
        assertEquals(2, rows.size());
        Map<String, Object> first = rows.get(0);
        assertEquals(T0 - 3L, first.get("t"));
        assertEquals("running", first.get("state"));
        assertEquals(Boolean.TRUE, first.get("active"));
        assertEquals(Boolean.TRUE, first.get("allowedToWork"));
        assertEquals(Boolean.TRUE, first.get("formed"));
        assertEquals(Boolean.FALSE, first.get("wasShutdown"));
        assertEquals(0.42, (Double) first.get("progress"), 1.0e-9);
        assertEquals(1920L, first.get("euPerTick"));
        assertEquals(1_200_000L, first.get("energyStored"));
        assertEquals(1, first.get("maintenanceIssues"));

        Map<String, Object> second = rows.get(1);
        assertFalse(second.containsKey("formed"), "formed is absent when the sample did not know it");
        assertFalse(second.containsKey("euPerTick"), "euPerTick is absent when the sample carried none");
        assertFalse(second.containsKey("energyStored"), "energyStored is absent when the sample carried none");
        assertNoNulls("second history", table);
    }

    @Test
    void gapSecondsAreNotRowsAndMergeIntoRanges() {
        SecondRing ring = new SecondRing();
        ring.appendSample((int) (T0 - 5L), StateCodes.RUNNING, SecondRing.FLAG_ACTIVE, 0, 1.0, 0L, 0L);
        ring.appendGap((int) (T0 - 4L), GapReason.SAMPLING_SKIPPED);
        ring.appendGap((int) (T0 - 3L), GapReason.SAMPLING_SKIPPED);
        ring.appendSample((int) (T0 - 2L), StateCodes.RUNNING, SecondRing.FLAG_ACTIVE, 0, 1.0, 0L, 0L);
        // T0-1 was never written at all: rule 3 decides its reason, and there is no run covering it.
        Map<String, Object> table = LuaTables.secondHistory(ID, ring, 5, T0, noRuns());
        assertEquals(2, rows(table).size());
        assertEquals(
            Arrays.asList(range(T0 - 4L, T0 - 2L, "sampling_skipped"), range(T0 - 1L, T0, "server_offline")),
            simpleGaps(table),
            "two adjacent skipped seconds merge; the unwritten one keeps its own reason");
    }

    @Test
    void anObservedSampleBeatsAGapWrittenInTheSameSecond() {
        SecondRing ring = new SecondRing();
        ring.appendGap((int) (T0 - 1L), GapReason.PROBE_ERROR);
        ring.appendSample((int) (T0 - 1L), StateCodes.RUNNING, SecondRing.FLAG_ACTIVE, 0, 1.0, 0L, 0L);
        Map<String, Object> table = LuaTables.secondHistory(ID, ring, 1, T0, noRuns());
        assertEquals(1, rows(table).size());
        assertEquals(Collections.emptyList(), table.get("gaps"), "the second was observed, so it is not a gap");
    }

    @Test
    void aSensorWithNoSecondRingIsAllGaps() {
        GapRanges.Context unloaded = GapRanges.Context.unloaded(
            Collections.singletonList(new GapRanges.Run(T0 - 3600L, T0)),
            T0 - 3600L,
            GapReason.DIMENSION_UNLOADED);
        Map<String, Object> table = LuaTables.secondHistory(ID, null, 4, T0, unloaded);
        assertEquals(Collections.emptyList(), table.get("rows"));
        assertEquals(Collections.singletonList(range(T0 - 4L, T0, "dimension_unloaded")), simpleGaps(table));
    }

    @Test
    void secondHistoryRejectsArgumentsTheCallbackMustClampFirst() {
        assertThrows(IllegalArgumentException.class, () -> LuaTables.secondHistory(ID, null, 0, T0, noRuns()));
        assertThrows(IllegalArgumentException.class, () -> LuaTables.secondHistory(ID, null, 1, T0, null));
    }

    @Test
    void theHistoryTableNeverHidesAnIdAndAlwaysCarriesBothSequences() {
        Map<String, Object> table = LuaTables.secondHistory(null, null, 1, T0, noRuns());
        assertFalse(table.containsKey("id"), "an id-less caller gets no nil id key");
        assertTrue(table.get("rows") instanceof List, "rows is always a sequence");
        assertTrue(table.get("gaps") instanceof List, "gaps is always a sequence");
    }

    @Test
    void theErrorMessagesArePinned() {
        assertEquals("no sensor", LuaTables.ERROR_NO_SENSOR);
        assertEquals("sensor not found", LuaTables.ERROR_SENSOR_NOT_FOUND);
        assertEquals("ambiguous id", LuaTables.ERROR_AMBIGUOUS_ID);
        assertEquals("bad resolution", LuaTables.ERROR_BAD_RESOLUTION);
        assertEquals("history loading", LuaTables.ERROR_HISTORY_LOADING);
        assertEquals(1, LuaTables.SENSOR_RECORD_VERSION);
        assertEquals(1, LuaTables.HISTORY_VERSION);
    }

    // --- fixtures ---

    private static GapRanges.Context noRuns() {
        return GapRanges.Context.of(Collections.<GapRanges.Run>emptyList());
    }

    private static MinuteRing ringOf(int fromMinute, int toMinute) {
        MinuteRing ring = new MinuteRing();
        for (int m = fromMinute; m < toMinute; m++) {
            ring.put(observed(m, 50, 10));
        }
        return ring;
    }

    private static MinuteSlot observed(int epochMinute, int running, int idle) {
        return MinuteSlot.builder(epochMinute)
            .samples(running + idle)
            .expectedSamples(EXPECTED_PER_MINUTE)
            .lastStateCode(StateCodes.RUNNING)
            .stateSamples(StateCodes.RUNNING, running)
            .stateSamples(StateCodes.IDLE, idle)
            .serverTicks(1200)
            .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> table) {
        return (List<Map<String, Object>>) (List<?>) table.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> gaps(Map<String, Object> table) {
        return (List<Map<String, Object>>) (List<?>) table.get("gaps");
    }

    private static List<String> simpleGaps(Map<String, Object> table) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> gap : gaps(table)) {
            out.add(gap.get("from") + ".." + gap.get("to") + " " + gap.get("reason"));
        }
        return out;
    }

    private static String range(long from, long to, String reason) {
        return from + ".." + to + " " + reason;
    }

    /** The section 10.4 absent-key rule: no value anywhere in a returned table is null. */
    private static void assertNoNulls(String where, Object value) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                assertTrue(e.getKey() != null, where + ": null key");
                assertTrue(e.getValue() != null, where + ": null value at " + e.getKey());
                assertNoNulls(where + "." + e.getKey(), e.getValue());
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                assertTrue(list.get(i) != null, where + ": null element " + i);
                assertNoNulls(where + "[" + i + "]", list.get(i));
            }
        }
    }

    /** Builds a {@link SensorView} the way the sampler would. */
    private static final class Sensor {

        private final SensorEntry entry;

        Sensor(UUID owner) {
            SensorIdentity identity = new SensorIdentity(
                ID,
                "EBF North",
                owner,
                owner == null ? null : "Steve",
                T0 - 86_400L);
            this.entry = new SensorEntry(identity, SensorKind.MACHINE, 0, 120, 64, -30, 4, SensorState.LIVE, T0 - 600L);
            entry.setMachineMetadata(1001, "machine.ebf", "Electric Blast Furnace", "running");
        }

        Sensor sampled() {
            entry.setLastSeenEpochSec(T0 - 5L);
            entry.setLastSampleEpochSec(T0 - 1L);
            entry.setLastSnapshot(
                MachineSnapshot.builder()
                    .kind(MachineKind.MULTIBLOCK)
                    .state(MachineState.RUNNING)
                    .statusId("running")
                    .statusText("Running perfectly")
                    .name("Electric Blast Furnace")
                    .metaName("machine.ebf")
                    .metaId(1001)
                    .machineClass("MTEBlastFurnace")
                    .dimension(0)
                    .x(120)
                    .y(64)
                    .z(-30)
                    .active(true)
                    .allowedToWork(true)
                    .hasThingsToDo(true)
                    .wasShutdown(false)
                    .progressTicks(42)
                    .maxProgressTicks(100)
                    .progress(0.42)
                    .warnings(Collections.<String>emptyList())
                    .euPerTick(1920L)
                    .build());
            return this;
        }

        SensorView view() {
            return new SensorView(entry);
        }
    }

    private static Sensor live() {
        return new Sensor(OWNER).sampled();
    }
}
