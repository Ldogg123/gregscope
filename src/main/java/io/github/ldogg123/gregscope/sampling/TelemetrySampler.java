package io.github.ldogg123.gregscope.sampling;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.tileentity.TileEntity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.MinuteAccumulator;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.probe.GregTechMachineProbe;
import io.github.ldogg123.gregscope.registry.RegistryEvents;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorRegistryCore;
import io.github.ldogg123.gregscope.registry.SensorState;

/**
 * GregScope's single {@code ServerTickEvent} END handler (design-v0.2 sections 1.4, 6.1 and 6.3): it samples every
 * LIVE sensor once per interval inside a hard per-tick budget, folds each sample into that sensor's history, and
 * publishes an immutable {@link TelemetryFrame}. Server thread only.
 *
 * <p>
 * <b>This is the only periodic hook GregScope has.</b> Design-v0.2 section 1.4 lifts "no global tick handler" for
 * exactly one handler, and this class is it: it is the only shipped class that may name {@code TickEvent},
 * {@code SubscribeEvent}, {@code EventBus} or {@code FMLCommonHandler}, which {@code ShippedClassesTest} enforces, and
 * {@code IdleCostTests} checks the running server for exactly one GregScope listener, owned by GregScope, with exactly
 * one {@code @SubscribeEvent} method. Phase END means every world has already ticked, so the readings of one tick are
 * consistent with each other.
 *
 * <p>
 * <b>A tick with nothing to do costs nothing.</b> {@link SamplerSchedule#tick} returns before it reads
 * {@code nanoTime} when the carry-over list and the due bucket are both empty, and this handler then only bumps its
 * counters. With no LIVE sensors the per-interval work is one empty frame per second and nothing else.
 *
 * <p>
 * <b>What is read live.</b> {@code sampling.tickBudgetMicros} and {@code sampling.enabled} are read from
 * {@link Settings} on every tick, so a config reload or a test-hook override applies at once.
 * {@code sampling.intervalTicks} fixes the number of buckets and is therefore taken once, at construction, which is
 * what design-v0.2 section 12.3 ("a change needs a restart") says.
 *
 * <p>
 * <b>Registry seam.</b> The sampler is the registry's {@link RegistryEvents} listener: a sensor that becomes LIVE
 * gets a bucket, one that stops being LIVE loses it. Closed minutes and expired sensors are handed on unchanged; the
 * history files they belong to are GS-109's.
 */
public final class TelemetrySampler implements RegistryEvents, SamplerSchedule.Sink {

    /** Design-v0.2 section 6.2: at most one WARN per sensor per 10 minutes for probe errors. */
    public static final long PROBE_WARN_INTERVAL_SEC = 600L;
    /**
     * A wall clock below this is not a real time (the minute numbering of design-v0.2 section 7.4 reserves minute 0),
     * so history is not folded for it. Only a misconfigured test clock can reach this.
     */
    private static final long MIN_EPOCH_SEC = 60L;

    /** Design-v0.2 section 7.7: the frame is published through a {@code static volatile} field. */
    private static volatile TelemetryFrame frame = TelemetryFrame.EMPTY;

    /** The last published frame; never null. Safe to read from any thread. */
    public static TelemetryFrame frame() {
        return frame;
    }

    private final SensorRegistry registry;
    private final Supplier<Settings> settings;
    private final SamplerSchedule schedule;
    private final SamplerStats stats = new SamplerStats();
    private final SampleFolder folder = new SampleFolder();
    private final GregTechMachineProbe probe = new GregTechMachineProbe();

    private long sequence;
    private long windowTicks;
    private long housekeepingTicks;
    private boolean registered;
    private boolean warnedAboutClock;

    public TelemetrySampler(SensorRegistry registry, Supplier<Settings> settings) {
        if (registry == null || settings == null) {
            throw new IllegalArgumentException("registry and settings are required");
        }
        this.registry = registry;
        this.settings = settings;
        this.schedule = new SamplerSchedule(
            settings.get()
                .intervalTicks(),
            System::nanoTime);
    }

    /**
     * Subscribes to the FML event bus (where 1.7.10 posts {@code ServerTickEvent}) and becomes the registry's event
     * listener. Called from {@code serverStarting}, so the listener is owned by the GregScope mod container.
     */
    public void start() {
        registry.core()
            .setEvents(this);
        for (SensorEntry entry : registry.core()
            .entries()) {
            if (entry.state() == SensorState.LIVE) {
                schedule.add(entry);
            }
        }
        if (!registered) {
            FMLCommonHandler.instance()
                .bus()
                .register(this);
            registered = true;
        }
    }

    /** Unsubscribes and drops every bucket. Called from {@code serverStopped}. */
    public void stop() {
        if (registered) {
            FMLCommonHandler.instance()
                .bus()
                .unregister(this);
            registered = false;
        }
        registry.core()
            .setEvents(null);
        schedule.clear();
        frame = TelemetryFrame.EMPTY;
    }

    public SamplerStats stats() {
        return stats;
    }

    public SamplerSchedule schedule() {
        return schedule;
    }

    // --- the one tick handler ---

    /** The single {@code ServerTickEvent} handler of design-v0.2 section 1.4. Phase END only. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            runTick();
        }
    }

    /**
     * One sampler tick: carry-over, the due bucket, and once per interval the minute sweep and the frame. Also
     * reachable through {@code GregScopeTestHooks}, because a Horizon-QA warp never fires a server tick (errata E2).
     */
    public void runTick() {
        stats.onTick();
        Settings active = settings.get();
        SamplerSchedule.Result result = schedule
            .tick(active.tickBudgetMicros() * 1000L, active.samplingEnabled(), this);
        if (result.worked()) {
            stats.onCycle(result.cycleNanos());
            if (result.budgetExceeded()) {
                stats.onBudgetExceeded();
            }
            stats.onSamplingSkipped(result.skipped());
        }
        if (result.intervalEnded()) {
            sweepOpenMinutes();
            publishFrame(active);
        }
        if (++windowTicks >= SamplerStats.WINDOW_TICKS) {
            stats.rollWindow();
            windowTicks = 0;
        }
        if (++housekeepingTicks >= SensorRegistryCore.HOUSEKEEPING_INTERVAL_TICKS) {
            registry.housekeeping();
            housekeepingTicks = 0;
        }
    }

    /** Runs a whole interval's worth of ticks, which serves every bucket once and ends on an interval boundary. */
    public void runInterval() {
        int ticks = schedule.intervalTicks();
        for (int i = 0; i < ticks; i++) {
            runTick();
        }
    }

    // --- one sample (design-v0.2 section 6.2) ---

    @Override
    public void sample(SensorEntry entry) {
        sampleOne(entry);
    }

    /**
     * Samples one sensor by its UUID, whatever bucket it sits in (design-v0.2 section 13.1 {@code sampleNow}).
     *
     * @return true if the sensor is LIVE and the section 6.2 chain found its cover; the probe may still have failed,
     *         in which case a {@code probe_error} second was recorded
     */
    public boolean sampleNow(UUID id) {
        SensorEntry entry = registry.core()
            .entry(id);
        return entry != null && sampleOne(entry);
    }

    /** @return true if the section 6.2 chain resolved the target */
    private boolean sampleOne(SensorEntry entry) {
        long started = System.nanoTime();
        TileEntity tile = registry.validateAndResolve(entry.id());
        if (tile == null) {
            // The section 6.2 chain already applied the section 4.3 consequence (UNLOADED, or a strike).
            return false;
        }
        long epochSec = GregScope.clock()
            .epochSec();
        if (!usableClock(epochSec)) {
            return true;
        }
        MachineSnapshot snapshot;
        try {
            snapshot = probe.snapshot(tile);
        } catch (RuntimeException | LinkageError e) {
            probeError(entry, epochSec, e);
            return true;
        }
        if (snapshot == null) {
            probeError(entry, epochSec, null);
            return true;
        }
        folder.fold(entry, snapshot, epochSec, schedule.intervalTicks(), serverTickDelta(entry));
        store(entry);
        if (folder.metadataChanged()) {
            registry.core()
                .markDirty();
        }
        stats.onSample(System.nanoTime() - started);
        return true;
    }

    @Override
    public void skip(SensorEntry entry) {
        long epochSec = GregScope.clock()
            .epochSec();
        if (!usableClock(epochSec)) {
            return;
        }
        folder.gap(entry, GapReason.SAMPLING_SKIPPED, epochSec);
        store(entry);
    }

    private void probeError(SensorEntry entry, long epochSec, Throwable cause) {
        folder.gap(entry, GapReason.PROBE_ERROR, epochSec);
        store(entry);
        if (entry.counters() != null) {
            entry.counters()
                .onProbeError();
        }
        stats.onProbeError();
        GregScope.LOG.debug("Probe error on sensor {}", entry.id(), cause);
        if (epochSec - entry.lastProbeWarnEpochSec() >= PROBE_WARN_INTERVAL_SEC) {
            entry.setLastProbeWarnEpochSec(epochSec);
            GregScope.LOG.warn(
                "Sensor {} at dim {} ({}, {}, {}) could not be read: {}",
                entry.id(),
                Integer.valueOf(entry.dim()),
                Integer.valueOf(entry.x()),
                Integer.valueOf(entry.y()),
                Integer.valueOf(entry.z()),
                cause == null ? "the probe returned no snapshot" : cause.toString());
        }
    }

    private void store(SensorEntry entry) {
        // Every fold and every gap goes through here, so this is where the section 7.6 clock-skew counter is fed.
        stats.onClockSkewRefused(folder.clockSkewRefusedDelta());
        MinuteSlot closed = folder.closedMinute();
        if (closed != null) {
            registry.core()
                .storeClosedMinute(entry, closed);
        }
    }

    /** Server ticks since this sensor's previous sample, for the minute slot's {@code serverTicks} (section 7.4). */
    private int serverTickDelta(SensorEntry entry) {
        long now = schedule.ownTick();
        long previous = entry.lastSampleTick();
        entry.setLastSampleTick(now);
        if (previous == Long.MIN_VALUE || now <= previous) {
            return 0;
        }
        return (int) Math.min(now - previous, Integer.MAX_VALUE);
    }

    private boolean usableClock(long epochSec) {
        if (epochSec >= MIN_EPOCH_SEC) {
            return true;
        }
        if (!warnedAboutClock) {
            warnedAboutClock = true;
            GregScope.LOG
                .warn("The GregScope clock reads {} s since the epoch; no history is recorded at that time.", epochSec);
        }
        return false;
    }

    // --- once per interval (design-v0.2 section 6.1) ---

    /** Closes minutes of sensors that were not sampled this interval at their boundary. */
    private void sweepOpenMinutes() {
        long epochSec = GregScope.clock()
            .epochSec();
        if (!usableClock(epochSec)) {
            return;
        }
        int currentMinute = MinuteAccumulator.epochMinute(epochSec);
        for (SensorEntry entry : registry.core()
            .entries()) {
            MinuteAccumulator accumulator = entry.accumulator();
            if (accumulator == null) {
                continue;
            }
            MinuteSlot closed = accumulator.closeBefore(currentMinute);
            if (closed != null) {
                registry.core()
                    .storeClosedMinute(entry, closed);
            }
        }
    }

    /** Builds and publishes the immutable frame of design-v0.2 section 7.7. */
    private void publishFrame(Settings active) {
        long started = System.nanoTime();
        SensorRegistryCore core = registry.core();
        List<SensorView> views = new ArrayList<>(core.size());
        for (SensorEntry entry : core.entries()) {
            views.add(new SensorView(entry));
        }
        // lastFrameBuildNanos describes the frame before this one: the stats view has to be copied before the build
        // can be timed. Every other number in the view is current.
        frame = new TelemetryFrame(
            ++sequence,
            System.nanoTime(),
            GregScope.clock()
                .epochMillis(),
            schedule.intervalTicks(),
            views,
            new SamplerStatsView(stats, core.duplicatesRekeyedTotal(), core.quotaRefusedTotal()),
            new LimitsView(active));
        stats.onFramePublished(System.nanoTime() - started);
    }

    // --- registry events (design-v0.2 sections 4.3 and 6.1) ---

    @Override
    public void sensorLive(SensorEntry entry) {
        schedule.add(entry);
    }

    @Override
    public void sensorInactive(SensorEntry entry) {
        schedule.remove(entry);
    }

    @Override
    public void minuteClosed(SensorEntry entry, MinuteSlot slot) {
        // GS-109 queues the 64-byte write here.
    }

    @Override
    public void sensorExpired(UUID id, int kind) {
        // GS-109 deletes the history file here.
    }
}
