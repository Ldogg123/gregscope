package io.github.ldogg123.gregscope.access;

import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Who is asking {@link AccessPolicy}: a player (UUID plus a permission-level check) or the server console. [pure]
 *
 * <p>
 * Adapters build a player viewer with the game's own check, for example
 * {@code Viewer.player(player.getUniqueID(), level -> player.canCommandSenderUseCommand(level, "gregscope"))}, so
 * operator levels, single-player owners and cheats follow vanilla rules. The check is called only when a rule needs
 * it, with {@code permissions.opLevel} of 1 to 4. Vanilla's check is false for every player not on the ops list, at any
 * level, so {@link AccessPolicy#isOp} handles {@code opLevel=0} ("every player") itself and never asks the check.
 */
public final class Viewer {

    /** The server console, or any non-player command sender with full permissions. It counts as op (§5). */
    public static final Viewer CONSOLE = new Viewer(null, level -> true);

    private final UUID uuid;
    private final IntPredicate hasPermissionLevel;

    private Viewer(UUID uuid, IntPredicate hasPermissionLevel) {
        this.uuid = uuid;
        this.hasPermissionLevel = hasPermissionLevel;
    }

    /**
     * @param uuid               the player's UUID, not null
     * @param hasPermissionLevel whether the player holds the given permission level (0-4)
     */
    public static Viewer player(UUID uuid, IntPredicate hasPermissionLevel) {
        if (uuid == null || hasPermissionLevel == null) {
            throw new IllegalArgumentException("uuid and hasPermissionLevel are required");
        }
        return new Viewer(uuid, hasPermissionLevel);
    }

    /** The player's UUID, or null for {@link #CONSOLE}. */
    public UUID uuid() {
        return uuid;
    }

    public boolean isConsole() {
        return uuid == null;
    }

    boolean hasPermissionLevel(int level) {
        return hasPermissionLevel.test(level);
    }

    @Override
    public String toString() {
        return isConsole() ? "Viewer[console]" : "Viewer[" + uuid + "]";
    }
}
