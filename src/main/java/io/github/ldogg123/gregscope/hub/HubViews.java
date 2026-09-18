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
 * GS-112 ships the question and the bookkeeping but opens no GUI yet, so only {@link #canOpen} is asked on the
 * right-click path: nothing is reserved for a window that does not exist, which is why a player cannot leak a slot by
 * clicking a Hub. GS-114 calls {@link #opened} once the MUI2 panel is really open and {@link #closed} when it closes.
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
