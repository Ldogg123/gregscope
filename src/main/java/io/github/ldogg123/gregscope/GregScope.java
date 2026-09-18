package io.github.ldogg123.gregscope;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraftforge.common.DimensionManager;

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
import io.github.ldogg123.gregscope.command.GregScopeCommand;
import io.github.ldogg123.gregscope.config.GregScopeConfig;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.HistoryIo;
import io.github.ldogg123.gregscope.history.HistoryPersistence;
import io.github.ldogg123.gregscope.history.IoListener;
import io.github.ldogg123.gregscope.history.NioFileStore;
import io.github.ldogg123.gregscope.history.SizeCeilings;
import io.github.ldogg123.gregscope.hub.HubViewLifecycle;
import io.github.ldogg123.gregscope.hub.TelemetryHubs;
import io.github.ldogg123.gregscope.registry.RegistryPersistence;
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
    private static volatile NioFileStore fileStore;
    private static volatile HistoryIo historyIo;
    /** An I/O thread a {@link #stopIo()} could not join; the next {@link #startIo()} has to deal with it. */
    private static volatile HistoryIo abandonedIo;
    private static volatile HistoryPersistence historyPersistence;
    private static volatile RegistryPersistence registryPersistence;
    private static volatile boolean finalized;
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

    /**
     * The file store under {@code <world>/gregscope/} (design-v0.2 section 8.1), or null outside a running server or
     * when there is no save root. Created in serverStarting and dropped in serverStopped. It exists even with
     * {@code history.persist=false}, because section 8.4 still saves the registry then.
     */
    public static NioFileStore fileStore() {
        return fileStore;
    }

    /**
     * The one daemon I/O thread (design-v0.2 section 8.4), or null outside a running server or when there is no save
     * root. GS-110 queues the slot writes, the registry saves and the history loads on it.
     */
    public static HistoryIo historyIo() {
        return historyIo;
    }

    /**
     * The {@code .gsh} persistence service of this server run (design-v0.2 section 8), or null outside a running
     * server. It exists even with {@code history.persist=false}; it then simply writes nothing.
     */
    public static HistoryPersistence history() {
        return historyPersistence;
    }

    /** The {@code registry.dat} service of this server run (design-v0.2 section 8.3), or null outside a server. */
    public static RegistryPersistence registryPersistence() {
        return registryPersistence;
    }

    /**
     * The save root GregScope keeps its files in: {@code DimensionManager.getCurrentSaveRootDirectory()} plus
     * {@code /gregscope/} (design-v0.2 section 8.1). It is always the overworld save root, never a {@code DIMn}
     * folder, because entries carry their own dimension. Null when no world is loaded.
     */
    public static Path saveRoot() {
        File world = DimensionManager.getCurrentSaveRootDirectory();
        return world == null ? null
            : world.toPath()
                .resolve(MODID);
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
        // GS-112 (design-v0.2 section 9.1): the Telemetry Hub block, its ItemBlock and its non-ticking tile entity.
        TelemetryHubs.install(creativeTab);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        phase = LifecyclePhase.INIT;
        proxy.init(event);
        // After GT (required-after:gregtech), as in the TecTech cover precedent; common code, both sides.
        SensorCovers.registerCover();
        // GS-110: the overworld save and unload triggers of design-v0.2 sections 8.3 and 8.4. Registered once, for
        // the life of the JVM; the handlers do nothing unless a server with GregScope services is running.
        GregScopeWorldEvents.register();
        // GS-114 follow-up: a disconnect never reaches ModularUI2's panel close listener, so the Hub's open-view cap
        // releases the slot from the FML logout event instead. Registered once, for the life of the JVM.
        HubViewLifecycle.register();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        phase = LifecyclePhase.POST_INIT;
        // GS-117: add recipes here, never in loadComplete (GT clears its postload lists at the end of its postInit).
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        phase = LifecyclePhase.SERVER_STARTING;
        // GS-111 (design-v0.2 section 11): one server command, registered before the services so a failure below
        // still leaves /gregscope answering "GregScope is not running on this world".
        event.registerServerCommand(new GregScopeCommand());
        startServices();
    }

    /**
     * Everything design-v0.2 section 2 puts in {@code serverStarting}: the save root, the I/O thread, the registry
     * loaded from {@code registry.dat}, this run appended to the runs table, the sampler, housekeeping and the
     * history reads. Idempotent only in the sense that {@link #stopServices()} must have run first;
     * {@code GregScopeTestHooks.simulateRestart} uses exactly this pair to restart in one JVM (section 14).
     *
     * <p>
     * The order matters and is the design's:
     * <ol>
     * <li>the store and the I/O thread, because everything below queues work on them;</li>
     * <li>the registry, then {@code registry.dat}: {@code RunsTable.loaded} closes every unclean run at the stored
     * {@code saved} before {@code startRun} appends this one (section 8.3);</li>
     * <li>the sampler, which installs itself as the registry's listener, with the history service behind it, so an
     * expiry in step 4 already deletes the file it should;</li>
     * <li>housekeeping, which drops what is too old before its history is ever read;</li>
     * <li>the history reads for what survived.</li>
     * </ol>
     */
    static void startServices() {
        finalized = false;
        startIo();
        // Settings and clock are read through the accessors, so a test hook installed later reaches the registry too.
        SensorRegistry started = new SensorRegistry(
            GregScope::settings,
            () -> clock().epochMillis(),
            new GtnhlibTeamResolver());
        registry = started;
        RegistryPersistence registryStore = new RegistryPersistence(fileStore, historyIo, LOG);
        registryStore.load(started.core(), clock().epochSec());
        registryPersistence = registryStore;
        HistoryPersistence history = new HistoryPersistence(historyIo, GregScope::settings, started.core()::entry);
        historyPersistence = history;
        // GS-108: the one ServerTickEvent END handler. start() subscribes it to the FML bus inside this handler, so
        // the listener belongs to the GregScope mod container, and makes it the registry's RegistryEvents listener.
        TelemetrySampler startedSampler = new TelemetrySampler(started, GregScope::settings);
        startedSampler.setHistory(history);
        startedSampler.start();
        sampler = startedSampler;
        int expired = started.housekeeping();
        int requested = history.requestAll(
            started.core()
                .entries());
        SensorCovers.setEvents(started);
        LOG.info(SizeCeilings.describe(settings().maxSensors()));
        if (expired > 0 || requested > 0) {
            LOG.info(
                "GregScope housekeeping expired {} entries at start; {} history files are being read",
                Integer.valueOf(expired),
                Integer.valueOf(requested));
        }
    }

    /**
     * GS-109/GS-110: resolve the save root and start the one daemon I/O thread (design-v0.2 sections 8.1 and 8.4).
     *
     * <p>
     * The thread is started whatever {@code history.persist} says, because section 8.4 keeps saving the registry when
     * history files are off ("no {@code .gsh} files, RAM rings only. The registry is still saved."). GS-109's note
     * said no store would be created at all; that was decided before the registry had a writer, and it is corrected
     * here. {@code HistoryPersistence} reads the flag live and writes no {@code .gsh}.
     *
     * <p>
     * Exactly one thread may write under a save root. A worker the previous run could not join is still draining its
     * queue into the same fixed {@code registry.dat.tmp}, so a second one beside it could interleave two images into
     * one temporary file and then move the result onto {@code registry.dat} - and the next save would copy that onto
     * {@code registry.dat.bak} as well, which is the one mitigation section 8.3 has. The old worker is therefore
     * interrupted first, and if it still will not go, this run keeps everything in RAM.
     */
    private static void startIo() {
        Path root = saveRoot();
        if (root == null) {
            LOG.warn("no save root: GregScope keeps everything in RAM only for this run");
            return;
        }
        if (!clearAbandonedIo()) {
            LOG.error(
                "the GregScope I/O thread of the previous run is still writing under {}; this run keeps minute"
                    + " history and the registry in RAM only, because two writers would share one registry.dat.tmp."
                    + " Restart the server to get persistence back.",
                root);
            return;
        }
        NioFileStore store = new NioFileStore(root);
        HistoryIo io = new HistoryIo(store, settings().ioQueueCapacity());
        io.setListener(new IoStatsLog());
        io.start();
        fileStore = store;
        historyIo = io;
        if (settings().historyPersist()) {
            LOG.info("GregScope history root {} , I/O queue {}", root, settings().ioQueueCapacity());
        } else {
            LOG.info(
                "history.persist is false: GregScope keeps minute history in RAM only and writes no .gsh files;"
                    + " the registry is still saved under {}",
                root);
        }
    }

    /**
     * Design-v0.2 section 8.4 at stop: close every open minute as a partial slot, close this run in the runs table
     * and queue the last registry write - all before {@link #stopServices()} flushes and joins the I/O thread.
     * Running twice does nothing the second time, which is what lets both {@code serverStopping} and the overworld's
     * {@code WorldEvent.Unload} call it.
     *
     * @return true if this call did the work
     */
    static boolean finalizeServices() {
        SensorRegistry active = registry;
        if (finalized || active == null) {
            return false;
        }
        finalized = true;
        long now = clock().epochSec();
        HistoryPersistence history = historyPersistence;
        if (history != null) {
            // Before the open minutes close: a sensor whose history file is still being read keeps its minutes as
            // ring indexes, and only an applied load result turns them into something the I/O thread can write.
            history.drain();
        }
        int closed = active.core()
            .flushOpenMinutes();
        active.core()
            .runs()
            .stopRun(now);
        RegistryPersistence store = registryPersistence;
        // Section 8.3 (GS-107-04): the shutdown save runs regardless of dirty, because `seen` never marks it dirty.
        boolean saved = store != null && store.save(active.core(), now, true);
        LOG.info(
            "GregScope is shutting down: {} partial minutes closed, {} slot writes queued, registry {}",
            Integer.valueOf(closed),
            Long.valueOf(history == null ? 0L : history.slotsWritten()),
            saved ? "queued" : "not written");
        return true;
    }

    /** Stops the sampler, flushes and joins the I/O thread and drops every per-server object. Idempotent. */
    static void stopServices() {
        TelemetrySampler stopping = sampler;
        if (stopping != null) {
            stopping.stop();
        }
        stopIo();
        sampler = null;
        registry = null;
        historyPersistence = null;
        registryPersistence = null;
        SensorCovers.setEvents(null);
        // GS-112: the open Hub views belong to one server run (design-v0.2 section 9.1 view cap).
        TelemetryHubs.reset();
    }

    /**
     * Design-v0.2 section 8.3: serialize the registry and queue the write. The overworld's {@code WorldEvent.Save}
     * calls it with {@code force=false}, so a clean registry costs nothing.
     *
     * @return true if a write was queued
     */
    static boolean saveRegistry(boolean force) {
        SensorRegistry active = registry;
        RegistryPersistence store = registryPersistence;
        if (active == null || store == null) {
            return false;
        }
        return store.save(active.core(), clock().epochSec(), force);
    }

    /**
     * Design-v0.2 section 14: a simulated restart - the services stop exactly as they would at server stop, and start
     * again in the same JVM, reading back what they just wrote. Only reachable through {@code GregScopeTestHooks}.
     * The clock and the settings override are deliberately left alone, so a test can keep its fake clock across the
     * "restart".
     */
    static void simulateRestart() {
        finalizeServices();
        stopServices();
        startServices();
    }

    /**
     * How long the last drain before the poison may wait for the queue. Small on purpose: it is added to the section
     * 8.4 stop budget, and the Horizon-QA CI step has to fit in 300 s.
     */
    private static final long FINAL_DRAIN_FLUSH_MILLIS = 2_000L;
    /** How long an abandoned I/O thread gets to answer an interrupt before the next run gives up on the disk. */
    private static final long TERMINATE_MILLIS = 2_000L;

    /** GS-109: flush, poison and join the I/O thread (design-v0.2 section 8.4). */
    private static void stopIo() {
        HistoryIo io = historyIo;
        HistoryPersistence history = historyPersistence;
        historyIo = null;
        fileStore = null;
        if (io == null) {
            return;
        }
        // A load that finished while the queue drained still has to be applied here: a sensor whose file was still
        // being read holds its minutes as ring indexes only, and the whole-file image that saves them is produced by
        // applying the result. After the poison nothing can be queued any more.
        if (history != null && io.flush(FINAL_DRAIN_FLUSH_MILLIS)) {
            history.drain();
        }
        if (!io.stop()) {
            LOG.warn(
                "the GregScope I/O thread did not finish within {} ms; it is a daemon, so the JVM is not held up."
                    + " The next server run will not share its save root until it has gone.",
                HistoryIo.STOP_TIMEOUT_MILLIS);
            abandonedIo = io;
        }
        if (io.droppedTotal() > 0 || io.errorsTotal() > 0) {
            LOG.warn("GregScope I/O finished with {} dropped tasks and {} errors", io.droppedTotal(), io.errorsTotal());
        }
    }

    /**
     * Gets rid of an I/O thread the previous run abandoned, so this one may write under the same save root.
     *
     * @return true if no previous worker is alive any more
     */
    private static boolean clearAbandonedIo() {
        HistoryIo previous = abandonedIo;
        if (previous == null) {
            return true;
        }
        if (!previous.isRunning()) {
            abandonedIo = null;
            return true;
        }
        LOG.warn("the GregScope I/O thread of the previous run is still running; interrupting it before this run");
        if (!previous.terminate(TERMINATE_MILLIS)) {
            return false;
        }
        abandonedIo = null;
        return true;
    }

    /**
     * The design-v0.2 section 7.6 I/O counters and the section 8.4 log lines. {@code onQueued} and {@code onDropped}
     * run on the server thread, so they write {@code SamplerStats} directly; {@code onError} runs on the I/O thread,
     * so its count is parked in an {@link AtomicLong} and folded in from the next server-thread callback, which keeps
     * {@code SamplerStats} a server-thread-only object.
     */
    private static final class IoStatsLog implements IoListener {

        private final AtomicLong pendingErrors = new AtomicLong();
        /** Failures not logged inside the current window. Written on the I/O thread only, like the two fields below. */
        private long suppressedErrors;
        private long lastErrorLogNanos;
        private boolean errorLogArmed = true;

        @Override
        public void onQueued() {
            TelemetrySampler active = sampler;
            if (active != null) {
                active.stats()
                    .onIoQueued();
                drainErrors(active);
            }
        }

        @Override
        public void onDropped() {
            TelemetrySampler active = sampler;
            if (active != null) {
                active.stats()
                    .onIoDropped();
                drainErrors(active);
            }
        }

        @Override
        public void onDropWarning(long droppedTotal, int queueCapacity) {
            LOG.warn(
                "GregScope dropped {} disk tasks; the I/O queue of {} is full. History still lives in RAM; dropped"
                    + " minutes read as gaps after a restart. Raise history.ioQueueCapacity if this keeps happening.",
                droppedTotal,
                queueCapacity);
        }

        /**
         * Rate-limited exactly like {@link #onDropWarning}: a full disk or an unwritable history folder fails one
         * task per sensor per minute, and a stack trace each time would cost more than the failure does. The first
         * failure is logged at once, then at most one line per window, carrying how many were left out.
         */
        @Override
        public void onError(String what, Throwable error) {
            pendingErrors.incrementAndGet();
            long now = System.nanoTime();
            if (!errorLogArmed && now - lastErrorLogNanos < HistoryIo.DROP_WARN_INTERVAL_NANOS) {
                suppressedErrors++;
                return;
            }
            long suppressed = suppressedErrors;
            suppressedErrors = 0L;
            errorLogArmed = false;
            lastErrorLogNanos = now;
            LOG.error(
                "GregScope I/O task failed: {} ({} further failures were not logged)",
                what,
                Long.valueOf(suppressed),
                error);
        }

        private void drainErrors(TelemetrySampler active) {
            long errors = pendingErrors.getAndSet(0L);
            for (long i = 0; i < errors; i++) {
                active.stats()
                    .onIoError();
            }
        }
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        phase = LifecyclePhase.SERVER_STARTED;
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        phase = LifecyclePhase.SERVER_STOPPING;
        // GS-110: the partial minutes and the last registry write are queued here, before serverStopped flushes and
        // joins the I/O thread (design-v0.2 section 8.4).
        finalizeServices();
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        phase = LifecyclePhase.SERVER_STOPPED;
        // Per-server static state is cleared here, so single-player world switches are safe. The overworld's
        // WorldEvent.Unload may already have done it (section 8.4); stopServices is idempotent.
        finalizeServices();
        stopServices();
        override = null;
        clock = Clock.SYSTEM;
    }
}
