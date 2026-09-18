package io.github.ldogg123.gregscope;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * GS-110: the two world events design-v0.2 section 8 hangs persistence on, and nothing else.
 *
 * <ul>
 * <li><b>Section 8.3 "Saving":</b> the registry is written when it is dirty, on the <em>overworld's</em>
 * {@code WorldEvent.Save} - one save per world save, not one per dimension.</li>
 * <li><b>Section 8.4 "Shutdown triggers":</b> the overworld's {@code WorldEvent.Unload} finalizes and flushes just
 * like {@code FMLServerStoppedEvent}, so a single-player world switch cannot leave the previous world's services
 * running. Vanilla unloads dimension 0 only while the server is stopping.</li>
 * </ul>
 *
 * <p>
 * <b>Why this is a second event subscriber.</b> Design-v0.2 section 1.4 lifts "no global tick handler" for exactly one
 * class, {@code TelemetrySampler}, and {@code ShippedClassesTest} keeps that lift to it. A world save is not periodic
 * work - it fires when Minecraft decides to write the world, at most once per autosave - but it does need
 * {@code @SubscribeEvent} on the <em>Forge</em> bus, which no shipped class named before. This class is therefore the
 * one deliberate second lift: it may name {@code MinecraftForge}, its bus and {@code SubscribeEvent}, and nothing
 * else on the periodic-work list (no tick event, no {@code FMLCommonHandler}, no thread, timer or executor).
 * {@code ShippedClassesTest.theOnlyWorldEventHandlerIsThePersistenceHook} checks that the lift is neither wider nor
 * vacuous, and {@code IdleCostTests} checks the running server holds exactly this one listener on the Forge bus.
 *
 * <p>
 * Two handler methods, not one taking {@code WorldEvent}: {@code WorldEvent.PotentialSpawns} is a {@code WorldEvent}
 * too and fires several times per chunk per tick, so a base-class handler would put GregScope on a hot path.
 *
 * <p>
 * The instance is registered once in {@code init} and stays registered; every handler returns immediately unless a
 * server with GregScope services is running, which makes a single-player world switch safe.
 */
public final class GregScopeWorldEvents {

    /** The overworld: design-v0.2 section 8.1 keeps everything under the overworld save root. */
    public static final int OVERWORLD = 0;

    private static final GregScopeWorldEvents INSTANCE = new GregScopeWorldEvents();

    private static boolean registered;

    private GregScopeWorldEvents() {}

    /** The subscribed instance, so a test can assert the server holds exactly this listener. */
    public static GregScopeWorldEvents instance() {
        return INSTANCE;
    }

    /** Subscribes once to the Forge event bus. Called from {@code init}; calling it again does nothing. */
    static void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        registered = true;
    }

    /** Design-v0.2 section 8.3: save the registry when it is dirty, on the overworld's save. */
    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (isOverworld(event)) {
            GregScope.saveRegistry(false);
        }
    }

    /** Design-v0.2 section 8.4: the overworld going away flushes and clears everything, like a server stop. */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (isOverworld(event)) {
            GregScope.finalizeServices();
            GregScope.stopServices();
        }
    }

    private static boolean isOverworld(WorldEvent event) {
        return event.world != null && !event.world.isRemote
            && event.world.provider != null
            && event.world.provider.dimensionId == OVERWORLD;
    }
}
