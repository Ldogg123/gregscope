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
import io.github.ldogg123.gregscope.config.GregScopeConfig;
import io.github.ldogg123.gregscope.config.Settings;

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

    static void setSettingsOverride(Settings settings) {
        override = settings;
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
        // GS-105/GS-112: register the Machine Sensor item and the Telemetry Hub block and tile entity here.
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        phase = LifecyclePhase.INIT;
        proxy.init(event);
        // GS-105: register the Machine Sensor cover here (after GT through required-after:gregtech).
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        phase = LifecyclePhase.POST_INIT;
        // GS-117: add recipes here, never in loadComplete (GT clears its postload lists at the end of its postInit).
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        phase = LifecyclePhase.SERVER_STARTING;
        // GS-110/GS-111: resolve the save root, load the registry, start I/O, register the command.
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
        override = null;
    }
}
