package io.github.ldogg123.gregscope.hub;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Releases a viewer's slot of the design-v0.2 section 9.1 open-view cap when that player leaves the server.
 *
 * <p>
 * <b>Why this exists.</b> {@code TileTelemetryHub.buildUI} hangs {@link HubViews#opened} and {@link HubViews#closed}
 * off ModularUI2's own panel lifecycle, and that close listener runs from {@code PanelSyncManager.onClose}, which is
 * reached only through {@code ModularSyncManager.dispose()} / {@code close(name)} - both driven by the client's
 * {@code CloseGuiPacket}. A player who alt-F4s, times out or crashes with the Hub GUI on screen never sends one:
 * {@code ServerConfigurationManager.playerLoggedOut} closes no container, and ModularUI2's own
 * {@code CommonProxy.onPlayerLeave} -> {@code ModularNetworkSide.onPlayerLeave} only clears its two network maps. The
 * slot would then be held for the rest of the server run, and {@code limits.maxOpenHubViews} such disconnects (32 by
 * default) would answer every right-click on every Hub with "busy", for players who had never opened one.
 *
 * <p>
 * <b>The lift.</b> Design-v0.2 section 1.4 forbids shipped classes the FML event bus; GS-108 lifts it for the sampler
 * alone and GS-110 lifted the <em>Forge</em> bus for {@code GregScopeWorldEvents}. A logout is not periodic work - it
 * fires once per player per session - but {@code PlayerEvent.PlayerLoggedOutEvent} is posted on the FML bus, so this
 * is the one deliberate third lift: it may name {@code FMLCommonHandler}, its bus and {@code SubscribeEvent}, and
 * nothing else on the periodic-work list. The tick event in particular stays forbidden here, which
 * {@code ShippedClassesTest.theOnlyLogoutHandlerIsTheHubViewRelease} checks together with the single event it really
 * subscribes to; {@code IdleCostTests} checks that the running server holds exactly this one extra FML-bus listener.
 *
 * <p>
 * The instance is registered once in {@code init} and stays registered; the handler touches nothing but the view
 * register, which is server-thread state, and a logout is delivered on the server thread.
 */
public final class HubViewLifecycle {

    private static final HubViewLifecycle INSTANCE = new HubViewLifecycle();

    private static boolean registered;

    private HubViewLifecycle() {}

    /** The subscribed instance, so a test can assert the server holds exactly this listener. */
    public static HubViewLifecycle instance() {
        return INSTANCE;
    }

    /** Subscribes once to the FML event bus. Called from {@code init}; calling it again does nothing. */
    public static void register() {
        if (registered) {
            return;
        }
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
        registered = true;
    }

    /** Design-v0.2 section 9.1: a player who left holds no Hub view, whatever their client did or did not send. */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event == null || event.player == null) {
            return;
        }
        TelemetryHubs.views()
            .closed(event.player.getUniqueID());
    }
}
