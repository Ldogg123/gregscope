package io.github.ldogg123.gregscope;

import java.util.UUID;

import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.HistoryIo;
import io.github.ldogg123.gregscope.history.HistoryPersistence;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.sampling.Clock;
import io.github.ldogg123.gregscope.sampling.TelemetrySampler;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorCovers;
import io.github.ldogg123.gregscope.sensor.SensorEvents;

/**
 * Hooks for GregScope's Horizon-QA tests, which cannot advance server time or restart the server (design-v0.2 §13.1).
 * Every hook is a no-op returning {@code false} unless the JVM runs with {@code -Dgregscope.testHooks=true}. The dev
 * {@code runServer}/{@code runClient} tasks set it (addon.gradle); a normal server does not. Later tickets add the
 * clock, sampling, heartbeat and I/O hooks here.
 */
public final class GregScopeTestHooks {

    public static final String PROPERTY = "gregscope.testHooks";

    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private GregScopeTestHooks() {}

    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Replaces the active settings until {@link #clearSettingsOverride()} or server stop.
     *
     * @return true if applied, false if hooks are disabled
     */
    public static boolean overrideSettings(Settings settings) {
        if (!ENABLED) {
            return false;
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings");
        }
        GregScope.setSettingsOverride(settings);
        return true;
    }

    /** @return true if applied, false if hooks are disabled */
    public static boolean clearSettingsOverride() {
        if (!ENABLED) {
            return false;
        }
        GregScope.setSettingsOverride(null);
        return true;
    }

    /**
     * Replaces the wall clock GregScope timestamps with ({@code null} restores the system clock), so tests can pin a
     * creation time or move time without waiting (design-v0.2 §13.1 {@code setClock}).
     *
     * @return true if applied, false if hooks are disabled
     */
    public static boolean setClock(Clock clock) {
        if (!ENABLED) {
            return false;
        }
        GregScope.setClock(clock);
        return true;
    }

    /**
     * Installs the listener sensor covers report to ({@code null} restores the no-op), so a test can watch heartbeats,
     * unloads and removals before GS-107's registry exists. The caller restores the previous listener, which
     * {@link SensorCovers#events()} returns.
     *
     * @return true if applied, false if hooks are disabled
     */
    public static boolean setSensorEvents(SensorEvents events) {
        if (!ENABLED) {
            return false;
        }
        SensorCovers.setEvents(events);
        return true;
    }

    /**
     * Runs one cover heartbeat now, through the cover's own {@code doCoverThings} (design-v0.2 section 13.1
     * {@code heartbeatNow}). A Horizon-QA warp does not advance {@code MinecraftServer.getTickCounter()}, so GT would
     * otherwise only tick the cover on a real tick that happens to be a multiple of its rate (errata E2).
     *
     * @return true if applied, false if hooks are disabled
     */
    public static boolean heartbeatNow(MachineSensorCover cover) {
        if (!ENABLED) {
            return false;
        }
        if (cover == null) {
            throw new IllegalArgumentException("cover");
        }
        cover.doCoverThings((byte) 0, 0L);
        return true;
    }

    /**
     * Runs one whole sample now (design-v0.2 section 13.1 {@code sampleNow}): the section 6.2 target resolution with
     * its section 4.3 consequences (UNLOADED, a {@code target_missing} strike, or a strike reset), then the probe
     * call and the fold into the second ring, the open minute and the counters.
     *
     * @return true if the sensor is LIVE and its cover was found; false if hooks are disabled, there is no sampler,
     *         or the target did not resolve
     */
    public static boolean sampleNow(UUID sensorId) {
        if (!ENABLED) {
            return false;
        }
        TelemetrySampler sampler = GregScope.sampler();
        if (sampler != null) {
            return sampler.sampleNow(sensorId);
        }
        SensorRegistry registry = GregScope.registry();
        return registry != null && registry.validate(sensorId);
    }

    /**
     * Runs one sampler tick now (design-v0.2 section 6.1), the way the {@code ServerTickEvent} END handler would. A
     * Horizon-QA warp fires no server tick at all (errata E2), so this is how a test drives the sampler.
     *
     * @return true if applied, false if hooks are disabled or there is no sampler
     */
    public static boolean runTickNow() {
        if (!ENABLED) {
            return false;
        }
        TelemetrySampler sampler = GregScope.sampler();
        if (sampler == null) {
            return false;
        }
        sampler.runTick();
        return true;
    }

    /**
     * Runs a whole sampling interval now (design-v0.2 section 13.1 {@code runIntervalNow}): every bucket is served
     * once and the interval ends with the minute sweep and a published {@code TelemetryFrame}.
     *
     * @return true if applied, false if hooks are disabled or there is no sampler
     */
    public static boolean runIntervalNow() {
        if (!ENABLED) {
            return false;
        }
        TelemetrySampler sampler = GregScope.sampler();
        if (sampler == null) {
            return false;
        }
        sampler.runInterval();
        return true;
    }

    /**
     * Waits until the I/O thread has written everything queued so far (design-v0.2 section 13.1 {@code flushIo}), so
     * a test can read a {@code .gsh} or {@code registry.dat} back without sleeping and guessing.
     *
     * @return true if the queue drained (or there is no I/O thread at all, for example with
     *         {@code history.persist=false}); false if hooks are disabled or the flush timed out
     */
    public static boolean flushIo() {
        if (!ENABLED) {
            return false;
        }
        HistoryIo io = GregScope.historyIo();
        return io == null || io.flush(HistoryIo.STOP_TIMEOUT_MILLIS);
    }

    /**
     * Applies every finished history read now (design-v0.2 section 8.4), the way the sampler's per-interval drain
     * does, without taking a sample. A test that wants a sensor's file created or merged before it starts recording
     * needs exactly this and not the stray sample {@link #runIntervalNow()} would take.
     *
     * @return how many load results were applied, or -1 if hooks are disabled or there is no persistence service
     */
    public static int applyHistoryLoadsNow() {
        if (!ENABLED) {
            return -1;
        }
        HistoryPersistence history = GregScope.history();
        return history == null ? -1 : history.drain();
    }

    /**
     * Saves {@code registry.dat} now (design-v0.2 section 8.3), the way the overworld's {@code WorldEvent.Save} does.
     *
     * @param force save although nothing is dirty, as the shutdown save does
     * @return true if a write was queued; false if hooks are disabled or there was nothing to write to
     */
    public static boolean saveRegistryNow(boolean force) {
        return ENABLED && GregScope.saveRegistry(force);
    }

    /**
     * Restarts GregScope's server services in this JVM (design-v0.2 section 14 "a simulated restart"): everything
     * {@code serverStopping} and {@code serverStopped} do, then everything {@code serverStarting} does. The registry
     * and the minute rings are therefore rebuilt from {@code registry.dat} and the {@code .gsh} files that were just
     * written, which is the only way to prove the restart path without stopping a Horizon-QA server.
     *
     * <p>
     * The clock and any settings override survive on purpose, so a test can keep its fake clock across the restart.
     * The covers in the world keep their identity, so they re-register on their next heartbeat.
     *
     * @return true if applied, false if hooks are disabled
     */
    public static boolean simulateRestart() {
        if (!ENABLED) {
            return false;
        }
        GregScope.simulateRestart();
        return true;
    }

    /**
     * The first half of {@link #simulateRestart()}: everything {@code serverStopping} and {@code serverStopped} do to
     * GregScope's own services - partial minutes closed, the run closed, the registry written, the I/O thread flushed
     * and joined. A test that wants downtime <em>between</em> the two runs (so the minutes in between read as
     * {@code server_offline}, design-v0.2 section 7.5 rule 3) calls this, moves its fake clock, then
     * {@link #startServicesNow()}.
     *
     * @return true if applied, false if hooks are disabled
     */
    public static boolean stopServicesNow() {
        if (!ENABLED) {
            return false;
        }
        GregScope.finalizeServices();
        GregScope.stopServices();
        return true;
    }

    /**
     * The second half of {@link #simulateRestart()}: everything {@code serverStarting} does - the I/O thread,
     * {@code registry.dat} read back, this run appended to the runs table, the sampler, housekeeping and the history
     * reads.
     *
     * @return true if applied, false if hooks are disabled
     */
    public static boolean startServicesNow() {
        if (!ENABLED) {
            return false;
        }
        GregScope.startServices();
        return true;
    }

    /**
     * Runs registry housekeeping now (design-v0.2 section 4.3): expiry and the tombstone cap, which a running server
     * only does every 72,000 ticks.
     *
     * @return how many entries were removed, or -1 if hooks are disabled or there is no registry
     */
    public static int housekeepingNow() {
        if (!ENABLED) {
            return -1;
        }
        SensorRegistry registry = GregScope.registry();
        return registry == null ? -1 : registry.housekeeping();
    }

    /**
     * Empties the registry, the way {@code /gregscope purge} does for one sensor (design-v0.2 section 4.3), so a test
     * can start from a known state although Horizon-QA keeps every finished cell loaded and heartbeating.
     *
     * @return how many entries were removed, or -1 if hooks are disabled or there is no registry
     */
    public static int purgeAllNow() {
        if (!ENABLED) {
            return -1;
        }
        SensorRegistry registry = GregScope.registry();
        return registry == null ? -1 : registry.purgeAll();
    }
}
