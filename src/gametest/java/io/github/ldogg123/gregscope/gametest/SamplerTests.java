package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.AfterBatch;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.probe.GregTechMachineProbe;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorRegistryCore;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.SamplerStats;
import io.github.ldogg123.gregscope.sampling.SensorView;
import io.github.ldogg123.gregscope.sampling.TelemetryFrame;
import io.github.ldogg123.gregscope.sampling.TelemetrySampler;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-108 (design-v0.2 sections 6.1, 6.2, 6.3 and 7.7): the sampler on a real server. One sample per LIVE sensor per
 * interval, a sample that equals what the probe reads, a published {@code TelemetryFrame}, and the two ways a target
 * can be away without the sampler ever loading it.
 *
 * <p>
 * A Horizon-QA warp fires no server tick at all (errata E2), so the sampler is driven with the section 13.1 hooks
 * {@code runIntervalNow} and {@code sampleNow}, except in {@code realTicksSampleOncePerSecond}, which waits real
 * ticks on purpose to prove the {@code ServerTickEvent} wiring.
 *
 * <p>
 * Horizon-QA keeps finished cells loaded, so sensors from earlier tests stay registered and are sampled too. Every
 * assertion here is about a named sensor UUID or about a delta of the global counters, never about an absolute total.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope" })
public class SamplerTests {

    /** The hook-driven tests; they run in one tick and cannot disturb each other. */
    private static final String BATCH = "gregscope.sampler";
    /** Waits real ticks, so it must not share a batch with anything that drives the sampler by hand. */
    private static final String REAL_BATCH = "gregscope.sampler.real";
    /** Overrides process-wide settings and fills the registry, so it runs alone (design-v0.2 section 13.2). */
    private static final String BUDGET_BATCH = "gregscope.sampler.budget";
    /** Turns sampling off process-wide, so it runs alone too. */
    private static final String DISABLED_BATCH = "gregscope.sampler.disabled";
    /** 64 machines do not fit in the default 5x1x5 cell. */
    private static final String BUDGET_TEMPLATE = "gregscope:empty_5x3x5";

    private static final GregTechMachineProbe PROBE = new GregTechMachineProbe();
    private static final TestPos MACHINE = at(1, 0, 1);
    private static final ForgeDirection COVERED = ForgeDirection.UP;
    /** Real ticks waited in the one test that does not use the hooks; 3 intervals at the default 20 ticks. */
    private static final int REAL_TICKS = 60;
    /** A chunk far from every test cell, which nothing has loaded. */
    private static final int FAR_X = 500_000;
    private static final int FAR_Z = 500_000;
    /** Sensors the budget test places; design-v0.2 section 14 names 64. */
    private static final int BUDGET_SENSORS = 64;
    /** Intervals the budget test runs for, in real ticks. */
    private static final int BUDGET_INTERVALS = 6;
    /** Rounds over every sensor for the warm-sample measurement the budget test logs. */
    private static final int WARM_ROUNDS = 20;
    /** The smallest {@code sampling.tickBudgetMicros} the config allows. */
    private static final int TINY_BUDGET_MICROS = 100;

    private SamplerTests() {}

    /**
     * GS107-T3: the sensors this batch left in its cells would keep heartbeating into the registry and, together with
     * every other batch's, fill the shared unowned {@code limits.maxSensorsPerTeam} quota. See {@link SensorCleanup}.
     */
    @AfterBatch(BATCH)
    public static void cleanUpAfterSampler() {
        SensorCleanup.detachAllAndPurge();
    }

    /**
     * GS107-T3: the sensors this batch left in its cells would keep heartbeating into the registry and, together with
     * every other batch's, fill the shared unowned {@code limits.maxSensorsPerTeam} quota. See {@link SensorCleanup}.
     */
    @AfterBatch(REAL_BATCH)
    public static void cleanUpAfterSamplerReal() {
        SensorCleanup.detachAllAndPurge();
    }

    /**
     * GS107-T3: the sensors this batch left in its cells would keep heartbeating into the registry and, together with
     * every other batch's, fill the shared unowned {@code limits.maxSensorsPerTeam} quota. See {@link SensorCleanup}.
     */
    @AfterBatch(BUDGET_BATCH)
    public static void cleanUpAfterSamplerBudget() {
        SensorCleanup.detachAllAndPurge();
    }

    /**
     * GS107-T3: the sensors this batch left in its cells would keep heartbeating into the registry and, together with
     * every other batch's, fill the shared unowned {@code limits.maxSensorsPerTeam} quota. See {@link SensorCleanup}.
     */
    @AfterBatch(DISABLED_BATCH)
    public static void cleanUpAfterSamplerDisabled() {
        SensorCleanup.detachAllAndPurge();
    }

    // --- sampling (design-v0.2 sections 6.1 and 6.2) ---

    /**
     * The {@code ServerTickEvent} wiring itself: with no hook at all, real server ticks sample the sensor once per
     * second.
     */
    @GameTest(batch = REAL_BATCH, timeoutTicks = 100)
    public static void realTicksSampleOncePerSecond(GameTestHelper helper) {
        MachineSensorCover cover = attach(helper, placeMachine(helper, MACHINE), COVERED);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        SensorEntry entry = entry(helper, id);
        long before = entry.counters()
            .samplesTotal();

        helper.startSequence()
            .thenIdle(REAL_TICKS)
            .thenExecute("count the samples", () -> {
                long samples = entry.counters()
                    .samplesTotal() - before;
                Snapshots.log("sampler#1 samples in " + REAL_TICKS + " real ticks", String.valueOf(samples));
                helper.assertTrue(samples >= 2 && samples <= 4, "samples in 60 real ticks: " + samples);
                helper.assertNotNull(entry.lastSnapshot(), "no snapshot was kept");
                helper.assertTrue(
                    entry.seconds()
                        .size() >= 2,
                    "second ring entries: " + entry.seconds()
                        .size());
                helper.assertEquals(SensorState.LIVE, entry.state(), "state");
                helper.assertTrue(entry.bucket() >= 0, "a live sensor must have a sampler bucket");
            })
            .thenSucceed();
    }

    /** Design-v0.2 section 6.2: what the sampler keeps is exactly what the probe reads. */
    @GameTest(batch = BATCH, timeoutTicks = 60)
    public static void sampleMatchesProbe(GameTestHelper helper) {
        IGregTechTileEntity machine = placeMachine(helper, MACHINE);
        MachineSensorCover cover = attach(helper, machine, COVERED);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);

        helper.assertTrue(GregScopeTestHooks.sampleNow(id), "the sample did not resolve its target");

        SensorEntry entry = entry(helper, id);
        MachineSnapshot kept = entry.lastSnapshot();
        helper.assertNotNull(kept, "the sample kept no snapshot");
        MachineSnapshot direct = PROBE.snapshot((TileEntity) machine);
        helper.assertNotNull(direct, "the probe read nothing");
        Map<String, Object> keptMap = kept.toMap();
        helper.assertEquals(direct.toMap(), keptMap, "the kept snapshot differs from the probe's");
        helper.assertEquals(direct.statusId(), entry.lastStatusId(), "lastStatusId");
        helper.assertEquals(keptMap.get("metaName"), entry.metaName(), "metaName");
        helper.assertEquals(keptMap.get("name"), entry.machineName(), "machineName");
        helper.assertEquals(keptMap.get("metaId"), Integer.valueOf(entry.metaId()), "metaId");
        helper.assertEquals(
            1L,
            entry.counters()
                .samplesTotal(),
            "samples after one sampleNow");
        Snapshots.log("sampler#2 sampled snapshot", keptMap.toString());
        helper.succeed();
    }

    /** Design-v0.2 section 7.7: the frame is republished every interval, sorted, immutable and complete. */
    @GameTest(batch = BATCH, timeoutTicks = 60)
    public static void everyIntervalPublishesAnImmutableFrame(GameTestHelper helper) {
        MachineSensorCover cover = attach(helper, placeMachine(helper, MACHINE), COVERED);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);

        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");
        TelemetryFrame first = GregScope.frame();
        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");
        TelemetryFrame second = GregScope.frame();

        helper.assertTrue(
            second.sequence() > first.sequence(),
            "the frame sequence must increase: " + first.sequence() + " -> " + second.sequence());
        helper.assertNotSame(first, second, "a new frame object per interval");
        SensorView view = second.sensor(id);
        helper.assertNotNull(view, "the sensor is not in the frame");
        helper.assertEquals(SensorKind.MACHINE, view.kind(), "kind");
        helper.assertEquals(SensorState.LIVE, view.state(), "state");
        helper.assertNotNull(view.lastSnapshot(), "the frame carries the last snapshot");
        helper.assertNotNull(view.counters(), "the frame carries the counters");
        helper.assertEquals(20, second.intervalTicks(), "the frame reports the sampling interval");
        helper.assertEquals(
            GregScope.settings()
                .maxSensors(),
            second.limits()
                .maxSensors(),
            "the frame reports the limits");
        try {
            second.sensors()
                .clear();
            helper.fail("the frame's sensor list is modifiable");
        } catch (UnsupportedOperationException expected) {
            // design-v0.2 section 7.7: unmodifiable.
        }
        // Sorted by UUID, so a reader's paging is stable.
        for (int i = 1; i < second.sensors()
            .size(); i++) {
            helper.assertTrue(
                second.sensors()
                    .get(i - 1)
                    .id()
                    .compareTo(
                        second.sensors()
                            .get(i)
                            .id())
                    < 0,
                "the frame is not sorted by id");
        }
        helper.succeed();
    }

    // --- never loading anything (design-v0.2 sections 1.4, 6.2 and 13.2) ---

    /**
     * Design-v0.2 section 13.2 negative control: the sampler resolves a LIVE sensor in an unloaded chunk without
     * loading it, and records {@code chunk_unloaded}. Removing the {@code blockExists} guard in {@code TargetResolver}
     * makes this test fail.
     *
     * <p>
     * The entry is registered straight through the registry core rather than by attaching a cover, because a real
     * cover reports its own chunk unload and the entry would be UNLOADED before the sampler ever looked at it. What is
     * under test is the sampler's own resolution of a LIVE entry whose chunk is gone.
     */
    @GameTest(batch = BATCH, timeoutTicks = 60)
    public static void unloadedChunkNotLoadedBySampler(GameTestHelper helper) {
        World world = helper.getWorld();
        int dim = world.provider.dimensionId;
        helper.assertFalse(isChunkLoaded(world, FAR_X, FAR_Z), "the far chunk is loaded before the test");
        UUID id = registerSyntheticSensor(helper, dim, FAR_X, 64, FAR_Z);

        for (int i = 0; i < 3; i++) {
            helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");
        }

        helper.assertFalse(isChunkLoaded(world, FAR_X, FAR_Z), "the sampler loaded the chunk");
        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.UNLOADED, entry.state(), "state");
        helper.assertEquals(GapReason.CHUNK_UNLOADED.bit(), entry.lastGapReason(), "gap reason");
        helper.assertEquals(-1, entry.bucket(), "an unloaded sensor leaves the schedule");
        helper.succeed();
    }

    /** Design-v0.2 section 6.2: a dimension that is not running is never started to look at a sensor. */
    @GameTest(batch = BATCH, timeoutTicks = 60)
    public static void unloadedDimensionNotInitialized(GameTestHelper helper) {
        int dim = unusedDimension(helper);
        helper.assertNull(DimensionManager.getWorld(dim), "dimension " + dim + " is already running");
        UUID id = registerSyntheticSensor(helper, dim, 0, 64, 0);

        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");

        helper.assertNull(DimensionManager.getWorld(dim), "the sampler started dimension " + dim);
        helper.assertFalse(DimensionManager.isDimensionRegistered(dim), "dimension " + dim + " got registered");
        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.UNLOADED, entry.state(), "state");
        helper.assertEquals(GapReason.DIMENSION_UNLOADED.bit(), entry.lastGapReason(), "gap reason");
        helper.succeed();
    }

    // --- the budget (design-v0.2 section 6.3) ---

    /**
     * Design-v0.2 section 14 {@code tinyBudgetSkips}: {@code sampling.tickBudgetMicros=100} with
     * {@link #BUDGET_SENSORS} sensors, driven by real ticks.
     *
     * <p>
     * The invariant asserted is the one design-v0.2 section 6.3 guarantees whatever the hardware does: over
     * {@link #BUDGET_INTERVALS} intervals every LIVE sensor is either sampled or given up on with a
     * {@code sampling_skipped} gap, never silently dropped - plus the tie between the two halves of section 6.3: a
     * skip only ever follows a budget-exceeded tick, and if nothing was skipped then every sensor was sampled in
     * every interval. The measured cost, the budget-exceeded ticks and the skips are logged rather than asserted,
     * because how hard a 100 us budget bites depends on how far HotSpot has compiled the sample path; the budget
     * arithmetic itself is pinned deterministically by {@code SamplerScheduleTest} over a fake {@code nanoTime}. The
     * observed numbers are in docs/testing.md.
     *
     * <p>
     * The machines are broken again at the end, because Horizon-QA keeps finished cells loaded and 64 sensors that
     * keep heartbeating would fill {@code limits.maxSensorsPerTeam} (all of them are unowned, so they share one quota)
     * for every later batch.
     */
    @GameTest(template = BUDGET_TEMPLATE, batch = BUDGET_BATCH, timeoutTicks = 200)
    public static void tinyBudgetSkips(GameTestHelper helper) {
        TelemetrySampler sampler = sampler(helper);
        helper.assertTrue(
            GregScopeTestHooks.overrideSettings(
                Settings.builder()
                    .tickBudgetMicros(TINY_BUDGET_MICROS)
                    .maxSensors(1024)
                    .maxSensorsPerTeam(0)
                    .build()),
            "GregScope test hooks are disabled");
        helper.afterTest(GregScopeTestHooks::clearSettingsOverride);
        helper.assertTrue(GregScopeTestHooks.purgeAllNow() >= 0, "the registry could not be emptied");

        List<TestPos> positions = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < BUDGET_SENSORS; i++) {
            TestPos pos = at(i % 5, i / 25, (i / 5) % 5);
            positions.add(pos);
            MachineSensorCover cover = attach(helper, placeMachine(helper, pos), COVERED);
            heartbeat(helper, cover);
            ids.add(
                cover.identity()
                    .id());
        }
        SensorRegistryCore core = registry(helper).core();
        int live = 0;
        for (UUID id : ids) {
            SensorEntry entry = core.entry(id);
            if (entry != null && entry.state() == SensorState.LIVE) {
                live++;
            }
        }
        helper.assertEquals(BUDGET_SENSORS, live, "live sensors before the budget run");

        SamplerStats stats = sampler.stats();
        long samplesBefore = stats.samplesTotal();
        long nanosBefore = stats.sampleNanosTotal();
        long skippedBefore = stats.samplingSkippedTotal();
        long exceededBefore = stats.budgetExceededTicksTotal();
        stats.rollWindow();

        helper.startSequence()
            .thenIdle(BUDGET_INTERVALS * 20)
            .thenExecute("report what the budget did", () -> {
                long samples = stats.samplesTotal() - samplesBefore;
                long nanos = stats.sampleNanosTotal() - nanosBefore;
                long skipped = stats.samplingSkippedTotal() - skippedBefore;
                long exceeded = stats.budgetExceededTicksTotal() - exceededBefore;
                stats.rollWindow();
                Snapshots.log(
                    "sampler#budget " + BUDGET_SENSORS + " sensors, " + TINY_BUDGET_MICROS + " us budget",
                    "samples=" + samples
                        + " meanSampleUs="
                        + (samples == 0 ? 0 : nanos / samples / 1000)
                        + " tickUs p50/p99/max="
                        + stats.cycleMicrosQuantile(0.50)
                        + "/"
                        + stats.cycleMicrosQuantile(0.99)
                        + "/"
                        + stats.cycleMicrosMax()
                        + " budgetExceededTicks="
                        + exceeded
                        + " skipped="
                        + skipped
                        + " carriedOver="
                        + sampler.schedule()
                            .carriedOver());
                helper.assertEquals(
                    TINY_BUDGET_MICROS,
                    GregScope.frame()
                        .limits()
                        .tickBudgetMicros(),
                    "the budget override did not reach the sampler");
                // Design-v0.2 section 6.3: nothing is silently dropped. Over BUDGET_INTERVALS intervals every sensor
                // is either sampled or given up on with a sampling_skipped gap.
                int worst = Integer.MAX_VALUE;
                int worstSampled = Integer.MAX_VALUE;
                long ourSkips = 0;
                for (UUID id : ids) {
                    SensorEntry entry = core.entry(id);
                    helper.assertNotNull(entry, "sensor " + id + " lost its entry");
                    long sampled = entry.counters()
                        .samplesTotal();
                    long ownSkips = entry.counters()
                        .gapSecondsTotal(GapReason.SAMPLING_SKIPPED);
                    ourSkips += ownSkips;
                    worst = Math.min(worst, (int) (sampled + ownSkips));
                    worstSampled = Math.min(worstSampled, (int) sampled);
                }
                helper.assertTrue(
                    worst >= 2,
                    "a sensor was neither sampled nor skipped often enough in " + BUDGET_INTERVALS
                        + " intervals: "
                        + worst);
                // How hard the budget bites depends on how far HotSpot has compiled the sample path, which is not a
                // property of design-v0.2 section 6.3 (the cold path costs 80-160 us per sample, a warm one 6-8;
                // see docs/testing.md "Sampler cost"). So the measured numbers are logged, not asserted, and what is
                // asserted here is the part that holds whatever the JIT has done. The budget arithmetic itself is
                // pinned deterministically by SamplerScheduleTest over a fake nanoTime.
                if (ourSkips > 0) {
                    helper.assertTrue(
                        exceeded > 0,
                        "a sampling_skipped gap without a single budget-exceeded tick: " + exceeded);
                } else {
                    helper.assertTrue(
                        worstSampled >= BUDGET_INTERVALS - 1,
                        "no sensor was skipped, so every sensor must have been sampled every interval; worst: "
                            + worstSampled);
                }
            })
            .thenExecute("measure a warm sample", () -> {
                // The same work again in one tick, after a warm-up: this separates the cost of the sample itself
                // from JIT warm-up and from what a real tick boundary adds (cold caches, OS scheduling).
                for (int round = 0; round < WARM_ROUNDS; round++) {
                    for (UUID id : ids) {
                        GregScopeTestHooks.sampleNow(id);
                    }
                }
                long started = System.nanoTime();
                int taken = 0;
                for (int round = 0; round < WARM_ROUNDS; round++) {
                    for (UUID id : ids) {
                        if (GregScopeTestHooks.sampleNow(id)) {
                            taken++;
                        }
                    }
                }
                long elapsed = System.nanoTime() - started;
                Snapshots.log(
                    "sampler#budget warm sampleNow",
                    taken + " samples after "
                        + (WARM_ROUNDS * BUDGET_SENSORS)
                        + " warm-up calls, mean "
                        + (taken == 0 ? 0 : elapsed / taken)
                        + " ns");
            })
            .thenExecute("break the machines again", () -> {
                for (TestPos pos : positions) {
                    helper.destroyBlock(pos);
                }
                helper.assertTrue(GregScopeTestHooks.purgeAllNow() >= 0, "the registry could not be emptied");
            })
            .thenSucceed();
    }

    /**
     * Design-v0.2 section 7.2 gap bit 3: with {@code sampling.enabled=false} every due sensor is skipped, which
     * records a real {@code sampling_skipped} second and counts. This is the same skip path the budget uses.
     */
    @GameTest(batch = DISABLED_BATCH, timeoutTicks = 60)
    public static void samplingDisabledRecordsSkippedGaps(GameTestHelper helper) {
        TelemetrySampler sampler = sampler(helper);
        MachineSensorCover cover = attach(helper, placeMachine(helper, MACHINE), COVERED);
        UUID id = cover.identity()
            .id();
        heartbeat(helper, cover);
        SensorEntry entry = entry(helper, id);
        helper.assertTrue(
            GregScopeTestHooks.overrideSettings(
                Settings.builder()
                    .samplingEnabled(false)
                    .build()),
            "GregScope test hooks are disabled");
        helper.afterTest(GregScopeTestHooks::clearSettingsOverride);
        long samplesBefore = entry.counters()
            .samplesTotal();
        long gapsBefore = entry.counters()
            .gapSecondsTotal(GapReason.SAMPLING_SKIPPED);
        long skippedBefore = sampler.stats()
            .samplingSkippedTotal();

        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");

        helper.assertEquals(
            samplesBefore,
            entry.counters()
                .samplesTotal(),
            "nothing may be sampled while sampling is off");
        helper.assertEquals(
            gapsBefore + 1L,
            entry.counters()
                .gapSecondsTotal(GapReason.SAMPLING_SKIPPED),
            "a skipped interval must record a sampling_skipped second");
        helper.assertEquals(
            skippedBefore + 1L,
            sampler.stats()
                .samplingSkippedTotal(),
            "samplingSkippedTotal");
        helper.assertEquals(GapReason.SAMPLING_SKIPPED.bit(), entry.lastGapReason(), "the last gap reason");
        helper.assertEquals(SensorState.LIVE, entry.state(), "a skipped sensor stays LIVE");
        helper.succeed();
    }

    // --- helpers ---

    private static SensorRegistry registry(GameTestHelper helper) {
        SensorRegistry registry = GregScope.registry();
        helper.assertNotNull(registry, "GregScope has no registry; is the server running?");
        return registry;
    }

    private static TelemetrySampler sampler(GameTestHelper helper) {
        TelemetrySampler sampler = GregScope.sampler();
        helper.assertNotNull(sampler, "GregScope has no sampler; is the server running?");
        return sampler;
    }

    private static SensorEntry entry(GameTestHelper helper, UUID id) {
        SensorEntry entry = registry(helper).core()
            .entry(id);
        helper.assertNotNull(entry, "no registry entry for sensor " + id);
        return entry;
    }

    private static void heartbeat(GameTestHelper helper, MachineSensorCover cover) {
        helper.assertTrue(GregScopeTestHooks.heartbeatNow(cover), "GregScope test hooks are disabled");
    }

    private static IGregTechTileEntity placeMachine(GameTestHelper helper, TestPos pos) {
        return GtPlacement.placeMachine(helper, pos, ItemList.Machine_LV_E_Furnace.get(1L));
    }

    private static MachineSensorCover attach(GameTestHelper helper, IGregTechTileEntity holder, ForgeDirection side) {
        helper.assertTrue(
            SensorPlacementTests.placeViaCoverPlacer(helper, holder, side),
            "the sensor was refused on " + side);
        MachineSensorCover cover = helper
            .assertInstanceOf(MachineSensorCover.class, holder.getCoverAtSide(side), "sensor cover on " + side);
        helper.assertNotNull(cover.identity(), "the attached cover has no identity");
        return cover;
    }

    /**
     * A LIVE entry at a position no cover sits at, registered straight through the registry core. It is removed again
     * after the test, so no later test sees it.
     */
    private static UUID registerSyntheticSensor(GameTestHelper helper, int dim, int x, int y, int z) {
        SensorRegistryCore core = registry(helper).core();
        SensorIdentity identity = new SensorIdentity(
            UUID.randomUUID(),
            "synthetic",
            null,
            null,
            GregScope.clock()
                .epochSec());
        SensorRegistryCore.Heartbeat result = core
            .heartbeat(identity, SensorKind.MACHINE, dim, x, y, z, COVERED.ordinal(), 0L);
        helper.assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, result, "the synthetic sensor did not register");
        helper.afterTest(() -> core.purge(identity.id()));
        return identity.id();
    }

    private static boolean isChunkLoaded(World world, int x, int z) {
        return world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4);
    }

    /** The lowest dimension id at or above 1000 that nothing has registered. */
    private static int unusedDimension(GameTestHelper helper) {
        for (int id = 1000; id < 1100; id++) {
            if (!DimensionManager.isDimensionRegistered(id) && DimensionManager.getWorld(id) == null) {
                return id;
            }
        }
        helper.fail("no unused dimension id between 1000 and 1099");
        return 0;
    }
}
