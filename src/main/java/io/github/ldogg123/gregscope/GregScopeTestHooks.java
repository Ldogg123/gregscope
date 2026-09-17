package io.github.ldogg123.gregscope;

import java.util.UUID;

import io.github.ldogg123.gregscope.config.Settings;
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
