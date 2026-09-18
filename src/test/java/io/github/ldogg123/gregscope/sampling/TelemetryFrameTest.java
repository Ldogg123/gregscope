package io.github.ldogg123.gregscope.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-108 (design-v0.2 section 7.7): the published frame is immutable, its collections are unmodifiable, and nothing a
 * caller does after publishing can change what a reader sees. Also the design-v0.3 section 5.1 A6 shape: a nullable
 * snapshot and a {@link CountersView} interface.
 *
 * <p>
 * Reflection is used only to check that every field of the frame classes is final; shipped code uses none.
 */
class TelemetryFrameTest {

    private static final long T0 = 1_700_000_040L;

    private static SensorEntry entry(UUID id, String label, int x) {
        SensorIdentity identity = new SensorIdentity(id, label, null, null, T0);
        return new SensorEntry(identity, SensorKind.MACHINE, 0, x, 64, 7, 1, SensorState.LIVE, T0);
    }

    private static TelemetryFrame frame(List<SensorView> sensors) {
        return new TelemetryFrame(
            7L,
            123L,
            T0 * 1000L,
            20,
            sensors,
            new SamplerStatsView(new SamplerStats(), 0L, 0L),
            new LimitsView(Settings.DEFAULTS));
    }

    @Test
    void theSensorListIsUnmodifiable() {
        TelemetryFrame frame = frame(new ArrayList<>(Arrays.asList(new SensorView(entry(UUID.randomUUID(), "a", 1)))));

        assertThrows(
            UnsupportedOperationException.class,
            () -> frame.sensors()
                .add(null));
        assertThrows(
            UnsupportedOperationException.class,
            () -> frame.sensors()
                .clear());
    }

    @Test
    void mutatingTheSourceListDoesNotReachThePublishedFrame() {
        List<SensorView> source = new ArrayList<>();
        source.add(new SensorView(entry(UUID.randomUUID(), "a", 1)));
        TelemetryFrame frame = frame(source);

        source.add(new SensorView(entry(UUID.randomUUID(), "b", 2)));
        source.clear();

        assertEquals(
            1,
            frame.sensors()
                .size(),
            "the frame copied its list");
    }

    @Test
    void sensorsAreSortedByIdSoPagingIsStable() {
        UUID low = new UUID(0L, 1L);
        UUID middle = new UUID(1L, 0L);
        UUID high = new UUID(2L, 0L);
        List<SensorView> source = new ArrayList<>(
            Arrays.asList(
                new SensorView(entry(high, "c", 3)),
                new SensorView(entry(low, "a", 1)),
                new SensorView(entry(middle, "b", 2))));

        TelemetryFrame frame = frame(source);

        assertEquals(
            Arrays.asList(low, middle, high),
            Arrays.asList(
                frame.sensors()
                    .get(0)
                    .id(),
                frame.sensors()
                    .get(1)
                    .id(),
                frame.sensors()
                    .get(2)
                    .id()));
        assertEquals(
            "b",
            frame.sensor(middle)
                .label());
        assertNull(frame.sensor(UUID.randomUUID()), "an unknown id resolves to nothing");
        assertNull(frame.sensor(null));
    }

    /**
     * Every reader sees this frame during the first sampling interval of a server run (and again after a stop), so
     * it has to answer every question a published frame answers. {@code /gregscope stats} reads eleven of these
     * numbers in a row without a null check; a null here would turn an ordinary command into a stack trace.
     */
    @Test
    void theEmptyFrameIsUsableBeforeTheFirstPublish() {
        assertEquals(0L, TelemetryFrame.EMPTY.sequence());
        assertTrue(
            TelemetryFrame.EMPTY.sensors()
                .isEmpty());
        assertEquals(0, TelemetryFrame.EMPTY.count(SensorState.LIVE));

        SamplerStatsView stats = TelemetryFrame.EMPTY.stats();
        assertNotNull(stats, "the empty frame must carry stats, not null");
        assertEquals(0L, stats.cycleMicrosP50());
        assertEquals(0L, stats.cycleMicrosP99());
        assertEquals(0L, stats.cycleMicrosMax());
        assertEquals(0L, stats.windowTicks());
        assertEquals(0L, stats.cyclesTotal());
        assertEquals(0L, stats.samplesTotal());
        assertEquals(0L, stats.budgetExceededTicksTotal());
        assertEquals(0L, stats.samplingSkippedTotal());
        assertEquals(0L, stats.probeErrorsTotal());
        assertEquals(0L, stats.duplicatesRekeyedTotal());
        assertEquals(0L, stats.quotaRefusedTotal());
        assertEquals(0L, stats.clockSkewRefusedTotal());
        assertEquals(0L, stats.ioQueuedTotal());
        assertEquals(0L, stats.ioDroppedTotal());
        assertEquals(0L, stats.ioErrorsTotal());

        LimitsView limits = TelemetryFrame.EMPTY.limits();
        assertNotNull(limits, "the empty frame must carry limits, not null");
        assertEquals(Settings.DEFAULTS.maxSensors(), limits.maxSensors());
        assertEquals(Settings.DEFAULTS.tickBudgetMicros(), limits.tickBudgetMicros());
    }

    @Test
    void countsAreTakenOverTheFrameNotTheRegistry() {
        SensorEntry live = entry(new UUID(0L, 1L), "a", 1);
        SensorEntry unloaded = entry(new UUID(0L, 2L), "b", 2);
        unloaded.restoreState(SensorState.UNLOADED, null, T0, T0);
        TelemetryFrame frame = frame(new ArrayList<>(Arrays.asList(new SensorView(live), new SensorView(unloaded))));

        assertEquals(1, frame.count(SensorState.LIVE));
        assertEquals(1, frame.count(SensorState.UNLOADED));
        assertEquals(0, frame.count(SensorState.REMOVED));
    }

    @Test
    void aSensorViewIsASnapshotOfTheEntry() {
        SensorEntry source = entry(new UUID(3L, 4L), "Main EBF", 11);
        source.setMachineMetadata(7, "Electric Blast Furnace", "EBF", "running");
        source.setLastSampleEpochSec(T0);
        source.setLastGapReason(GapReason.CHUNK_UNLOADED.bit());

        SensorView view = new SensorView(source);

        assertEquals(new UUID(3L, 4L), view.id());
        assertEquals(SensorKind.MACHINE, view.kind());
        assertEquals("Main EBF", view.label());
        assertNull(view.owner(), "an unowned sensor has no owner");
        assertEquals(0, view.dim());
        assertEquals(11, view.x());
        assertEquals(64, view.y());
        assertEquals(7, view.z());
        assertEquals((byte) 1, view.side());
        assertEquals(SensorState.LIVE, view.state());
        assertEquals(T0, view.lastSampleEpochSec());
        assertEquals(GapReason.CHUNK_UNLOADED, view.lastGap());
        assertEquals(7, view.metaId());
        assertEquals("Electric Blast Furnace", view.metaName());
        assertEquals("EBF", view.machineName());
        assertEquals("running", view.lastStatusId());
        assertNull(view.lastSnapshot(), "never sampled, so no snapshot (design-v0.3 A6: it is nullable)");
        assertNull(view.counters(), "an entry without rings has no counters");

        // A later change to the entry must not reach the published view.
        source.setMachineMetadata(9, "changed", "changed", "idle");
        source.setLastGapReason(SensorEntry.NO_GAP);
        assertEquals(7, view.metaId());
        assertEquals("Electric Blast Furnace", view.metaName());
        assertEquals(GapReason.CHUNK_UNLOADED, view.lastGap());
    }

    @Test
    void machineCountersAreCopiedAndTheirArraysAreNotShared() {
        SensorCounters counters = new SensorCounters();
        counters.onSample(5, true, 64L, 20);
        counters.onGap(GapReason.PROBE_ERROR, 3L);
        counters.onProbeError();

        MachineCountersView view = new MachineCountersView(counters);
        counters.onSample(5, false, 0L, 20);
        counters.onGap(GapReason.PROBE_ERROR, 1L);

        assertEquals(SensorKind.MACHINE, view.kind());
        assertEquals(1L, view.samplesTotal(), "the view must not follow the live counters");
        assertEquals(1L, view.stateSamplesTotal(5));
        assertEquals(3L, view.gapSecondsTotal(GapReason.PROBE_ERROR));
        assertEquals(1L, view.probeErrorsTotal());
        assertEquals(64L * 20, view.euConsumedSampledTotal());
        assertNotSame(view.gapSecondsTotal(), view.gapSecondsTotal(), "the getter must return a copy");
        assertNotSame(view.stateSamplesTotal(), view.stateSamplesTotal());
        view.gapSecondsTotal()[GapReason.PROBE_ERROR.bit()] = 999L;
        assertEquals(3L, view.gapSecondsTotal(GapReason.PROBE_ERROR), "a caller cannot write through the copy");
        assertThrows(IllegalArgumentException.class, () -> view.gapSecondsTotal(GapReason.SERVER_OFFLINE));
        assertThrows(IllegalArgumentException.class, () -> view.stateSamplesTotal(99));
    }

    @Test
    void theStatsViewCopiesTheCountersAndTheRegistryTotals() {
        SamplerStats stats = new SamplerStats();
        stats.onTick();
        stats.onCycle(1_500_000L);
        stats.onSample(40_000L);
        stats.onBudgetExceeded();
        stats.onSamplingSkipped(2L);
        stats.onProbeError();
        stats.rollWindow();

        SamplerStatsView view = new SamplerStatsView(stats, 4L, 5L);
        stats.onTick();
        stats.onSample(1L);

        assertEquals(1L, view.ticksTotal(), "the view must not follow the live stats");
        assertEquals(1L, view.cyclesTotal());
        assertEquals(1L, view.samplesTotal());
        assertEquals(40_000L, view.sampleNanosTotal());
        assertEquals(1L, view.budgetExceededTicksTotal());
        assertEquals(2L, view.samplingSkippedTotal());
        assertEquals(1L, view.probeErrorsTotal());
        assertEquals(4L, view.duplicatesRekeyedTotal(), "the registry owns this number");
        assertEquals(5L, view.quotaRefusedTotal());
        assertEquals(1500L, view.cycleMicrosMax(), "1.5 ms is 1500 us");
        assertEquals(1L, view.windowTicks());
        assertEquals(1_500_000L, view.lastCycleNanos());
    }

    @Test
    void theLimitsViewCopiesTheSettings() {
        Settings settings = Settings.builder()
            .maxSensors(64)
            .maxSensorsPerTeam(16)
            .maxOpenHubViews(8)
            .tickBudgetMicros(500)
            .samplingEnabled(false)
            .build();

        LimitsView view = new LimitsView(settings);

        assertEquals(64, view.maxSensors());
        assertEquals(16, view.maxSensorsPerTeam());
        assertEquals(8, view.maxOpenHubViews());
        assertEquals(500, view.tickBudgetMicros());
        assertEquals(false, view.samplingEnabled());
    }

    @Test
    void everyFieldOfTheFrameClassesIsFinal() {
        for (Class<?> type : new Class<?>[] { TelemetryFrame.class, SensorView.class, SamplerStatsView.class,
            LimitsView.class, MachineCountersView.class }) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertTrue(
                    Modifier.isFinal(field.getModifiers()),
                    type.getSimpleName() + "." + field.getName() + " is not final");
            }
        }
    }

    @Test
    void theFrameKeepsTheViewsItWasGiven() {
        SensorView view = new SensorView(entry(UUID.randomUUID(), "a", 1));
        TelemetryFrame frame = frame(new ArrayList<>(Arrays.asList(view)));

        assertSame(
            view,
            frame.sensors()
                .get(0),
            "views are immutable, so the frame keeps them rather than copying again");
        assertNotNull(frame.stats());
        assertNotNull(frame.limits());
        assertEquals(7L, frame.sequence());
        assertEquals(20, frame.intervalTicks());
        assertEquals(T0 * 1000L, frame.publishedEpochMillis());
    }
}
