package io.github.ldogg123.gregscope;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import io.github.ldogg123.gregscope.access.GtnhlibTeamResolver;
import io.github.ldogg123.gregscope.config.GregScopeConfig;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.sampling.Clock;
import io.github.ldogg123.gregscope.sampling.TelemetryFrame;
import io.github.ldogg123.gregscope.sampling.TelemetrySampler;
import io.github.ldogg123.gregscope.sensor.SensorCovers;

/**
 * GregScope mod entry point (design-v0.2 §2). v0.2 adds an item, a block and GUIs, so {@code acceptableRemoteVersions}
 * is gone: FML's handshake now requires clients to run the same GregScope version.
 */
@Mod(
    modid = GregScope.MODID,
    version = Tags.VERSION,
    name = GregScope.NAME,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = GregScope.DEPENDENCIES)
public class GregScope {

    public static final String MODID = "gregscope";
    public static final String NAME = "GregScope";
    public static final String DEPENDENCIES = "required-after:gregtech;required-after:OpenComputers;"
        + "required-after:modularui2;required-after:gtnhlib@[0.11.46,)";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(
        clientSide = "io.github.ldogg123.gregscope.ClientProxy",
        serverSide = "io.github.ldogg123.gregscope.CommonProxy")
    public static CommonProxy proxy;

    private static volatile LifecyclePhase phase = LifecyclePhase.CONSTRUCTED;
    private static volatile Settings configured = Settings.DEFAULTS;
    private static volatile Settings override;
    private static volatile Clock clock = Clock.SYSTEM;
    private static volatile SensorRegistry registry;
    private static volatile TelemetrySampler sampler;
    private static GregScopeCreativeTab creativeTab;

    /** The last lifecycle handler that ran. */
    public static LifecyclePhase phase() {
        return phase;
    }

    /** The active settings: the config file's, or a test hook override. Never null. */
    public static Settings settings() {
        Settings o = override;
        return o != null ? o : configured;
    }

    /** The settings read from {@code config/gregscope.cfg} in preInit, ignoring any test override. */
    public static Settings configuredSettings() {
        return configured;
    }

    public static GregScopeCreativeTab creativeTab() {
        return creativeTab;
    }

    /**
     * The wall clock GregScope timestamps with (design-v0.2 §6.2): the system clock, or a fake one installed by the
     * test hooks. Never null.
     */
    public static Clock clock() {
        return clock;
    }

    /**
     * The sensor registry of this server run (design-v0.2 §4), or null outside a running server. It is created in
     * serverStarting and dropped in serverStopped.
     */
    public static SensorRegistry registry() {
        return registry;
    }

    /**
     * The telemetry sampler of this server run (design-v0.2 sections 6.1 and 6.3), or null outside a running server.
     * It is GregScope's one {@code ServerTickEvent} handler; it is created in serverStarting and dropped in
     * serverStopped.
     */
    public static TelemetrySampler sampler() {
        return sampler;
    }

    /** The last published telemetry frame (design-v0.2 section 7.7); never null. Safe to read from any thread. */
    public static TelemetryFrame frame() {
        return TelemetrySampler.frame();
    }

    static void setSettingsOverride(Settings settings) {
        override = settings;
    }

    static void setClock(Clock replacement) {
        clock = replacement == null ? Clock.SYSTEM : replacement;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        phase = LifecyclePhase.PRE_INIT;
        configured = GregScopeConfig.load(event.getSuggestedConfigurationFile(), LOG);
        if (GregScopeTestHooks.enabled()) {
            LOG.warn(
                "Test hooks are enabled (-D{}=true). This is for GregScope's own tests only.",
                GregScopeTestHooks.PROPERTY);
        }
        creativeTab = new GregScopeCreativeTab();
        SensorCovers.registerItem(creativeTab);
        // GS-112: register the Telemetry Hub block and tile entity here.
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        phase = LifecyclePhase.INIT;
        proxy.init(event);
        // After GT (required-after:gregtech), as in the TecTech cover precedent; common code, both sides.
        SensorCovers.registerCover();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        phase = LifecyclePhase.POST_INIT;
        // GS-117: add recipes here, never in loadComplete (GT clears its postload lists at the end of its postInit).
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        phase = LifecyclePhase.SERVER_STARTING;
        // GS-109/GS-110: resolve the save root, load registry.dat into this registry, start I/O.
        // GS-111: register the command.
        // Settings and clock are read through the accessors, so a test hook installed later reaches the registry too.
        SensorRegistry started = new SensorRegistry(
            GregScope::settings,
            () -> clock().epochMillis(),
            new GtnhlibTeamResolver());
        started.housekeeping();
        registry = started;
        // GS-108: the one ServerTickEvent END handler. start() subscribes it to the FML bus inside this handler, so
        // the listener belongs to the GregScope mod container, and makes it the registry's RegistryEvents listener.
        TelemetrySampler startedSampler = new TelemetrySampler(started, GregScope::settings);
        startedSampler.start();
        sampler = startedSampler;
        SensorCovers.setEvents(started);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        phase = LifecyclePhase.SERVER_STARTED;
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        phase = LifecyclePhase.SERVER_STOPPING;
        // GS-110: close partial minutes, flush, close the run.
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        phase = LifecyclePhase.SERVER_STOPPED;
        // GS-109/GS-110: join I/O. Per-server static state is cleared here, so single-player world switches are safe.
        TelemetrySampler stopping = sampler;
        if (stopping != null) {
            stopping.stop();
        }
        sampler = null;
        override = null;
        clock = Clock.SYSTEM;
        registry = null;
        SensorCovers.setEvents(null);
    }
}
