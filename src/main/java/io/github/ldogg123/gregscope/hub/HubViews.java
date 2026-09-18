package io.github.ldogg123.gregscope.hub;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The open-view cap of design-v0.2 section 9.1: at most {@code limits.maxOpenHubViews} Telemetry Hub GUIs are open at
 * the same time, server-wide (section 12.3). [pure]
 *
 * <p>
 * A viewer is a player UUID, so the same player re-opening a Hub (or opening a second one) never counts twice and can
 * never be told "busy" by their own view. Server thread only, like everything else that answers a right-click; nothing
 * here is synchronized.
 *
 * <p>
 * Only {@link #canOpen} is asked on the right-click path: nothing is reserved for a window that does not exist, which
 * is why a player cannot leak a slot by clicking a Hub. GS-114 hangs {@link #opened} and {@link #closed} off
 * ModularUI2's own panel lifecycle instead ({@code TileTelemetryHub.buildUI} registers them as the panel's open and
 * close listeners), so a slot is held exactly while a panel is really open - including the erratum E9 case, where
 * {@code GuiManager.open} returns immediately for a FakePlayer and no listener ever fires.
 *
 * <p>
 * <b>Two releases ModularUI2's close listener does not give.</b> That listener only runs when the client sends a
 * {@code CloseGuiPacket}, so two paths have to release the slot themselves, or it would be held until server stop:
 * <ul>
 * <li>a disconnect - {@code ServerConfigurationManager.playerLoggedOut} closes no container and ModularUI2's
 * {@code ModularNetworkSide.onPlayerLeave} only clears its own maps - which {@code HubViewLifecycle} handles;</li>
 * <li>a server-side force close, where {@code EntityPlayerMP.closeScreen} reaches only the empty
 * {@code ModularContainer.onModularContainerClosed}, which {@code TileTelemetryHub.canInteractWith} handles.</li>
 * </ul>
 */
public final class HubViews {

    private final Set<UUID> open = new LinkedHashSet<>();

    /**
     * Whether {@code viewer} may open a Hub view now: either they already have one open, or fewer than {@code cap}
     * views are open. A null viewer (no player UUID) can never open one.
     *
     * @param cap {@code limits.maxOpenHubViews}; 0 or less allows nothing
     */
    public boolean canOpen(UUID viewer, int cap) {
        if (viewer == null) {
            return false;
        }
        return open.contains(viewer) || open.size() < cap;
    }

    /**
     * Records that {@code viewer}'s view is open.
     *
     * @return true if this viewer had no view open before
     */
    public boolean opened(UUID viewer) {
        if (viewer == null) {
            throw new IllegalArgumentException("viewer");
        }
        return open.add(viewer);
    }

    /**
     * Records that {@code viewer}'s view is closed.
     *
     * @return true if this viewer had a view open
     */
    public boolean closed(UUID viewer) {
        return viewer != null && open.remove(viewer);
    }

    public boolean isOpen(UUID viewer) {
        return viewer != null && open.contains(viewer);
    }

    /** How many views are open right now. */
    public int size() {
        return open.size();
    }

    /** Forgets every open view; server stop, so the next run starts at zero. */
    public void clear() {
        open.clear();
    }

    @Override
    public String toString() {
        return "HubViews[" + open.size() + " open]";
    }
}
