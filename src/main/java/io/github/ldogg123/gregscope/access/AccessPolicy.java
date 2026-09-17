package io.github.ldogg123.gregscope.access;

import java.util.UUID;

import io.github.ldogg123.gregscope.config.Settings;

/**
 * The ownership and permission rules of design-v0.2 §5, one method per table row. [pure]
 *
 * <p>
 * "Op" means a viewer that holds {@code permissions.opLevel} (default 2); the console always counts as op, and
 * {@code opLevel=0} makes every player an op. Owners are
 * player UUIDs; a null owner is "unowned". Team questions go to a {@link TeamResolver} that the caller keeps for one
 * rebuild or sequence, so nothing about teams is stored here.
 *
 * <p>
 * Rows that are not decisions of this class: OC {@code gt_machine} callbacks keep v0.1 physical adjacency, and OC
 * {@code gregscope_hub} adds adjacency to the Hub on top of {@link #inHubScope}.
 */
public final class AccessPolicy {

    private final int opLevel;
    private final boolean renameRequiresOfficer;

    public AccessPolicy(Settings settings) {
        this.opLevel = settings.opLevel();
        this.renameRequiresOfficer = settings.renameRequiresOfficer();
    }

    /**
     * The console, or a player holding {@code permissions.opLevel}. {@code opLevel=0} means every player, without
     * asking the viewer's permission check: in 1.7.10 {@code EntityPlayerMP.canCommandSenderUseCommand} returns false
     * for any player not on the ops list whatever the level, so the game's check alone would make 0 behave like 1.
     * Levels 1-4 follow vanilla: only players on the ops list with at least that level.
     */
    public boolean isOp(Viewer viewer) {
        return viewer.isConsole() || opLevel <= 0 || viewer.hasPermissionLevel(opLevel);
    }

    /** Open a Hub: the viewer is the Hub owner, or in the same team as the Hub owner, or op. */
    public <T> boolean canOpenHub(Viewer viewer, UUID hubOwner, TeamResolver<T> teams) {
        return ownerOrSameTeam(viewer.uuid(), hubOwner, teams) || isOp(viewer);
    }

    /**
     * Sensors visible in a Hub, in the GUI and through OC: the Hub has an owner, and the sensor's owner is the Hub
     * owner or in the Hub owner's team. There is deliberately no viewer parameter: an op who opens someone else's Hub
     * sees
     * that Hub's scope and gains no extra visibility. Unowned sensors are never in any Hub's scope.
     */
    public <T> boolean inHubScope(UUID hubOwner, UUID sensorOwner, TeamResolver<T> teams) {
        return ownerOrSameTeam(hubOwner, sensorOwner, teams);
    }

    /**
     * Rename a sensor: the viewer is its owner, or op, or in the same team as its owner. With
     * {@code permissions.renameRequiresOfficer=true} the same-team case is limited to officers and owners of that team.
     * The owner and ops are never limited. An unowned sensor can be renamed by ops only.
     */
    public <T> boolean canRename(Viewer viewer, UUID sensorOwner, TeamResolver<T> teams) {
        UUID player = viewer.uuid();
        if (player != null && player.equals(sensorOwner)) {
            return true;
        }
        if (isOp(viewer)) {
            return true;
        }
        if (player == null || sensorOwner == null) {
            return false;
        }
        // The team sameTeam(viewer, owner) looks at: the viewer's team, which must contain the owner.
        T team = teams.teamOf(player);
        if (team == null || !teams.isMember(team, sensorOwner)) {
            return false;
        }
        return !renameRequiresOfficer || teams.isOfficerOrOwner(team, player);
    }

    /**
     * See a sensor in {@code /gregscope list}, {@code info} and {@code stats}: the viewer is its owner, in the same
     * team as its owner, or op (the console included). Unowned sensors are visible to ops only.
     */
    public <T> boolean canView(Viewer viewer, UUID sensorOwner, TeamResolver<T> teams) {
        return ownerOrSameTeam(viewer.uuid(), sensorOwner, teams) || isOp(viewer);
    }

    /** {@code /gregscope purge}: op only. */
    public boolean canPurge(Viewer viewer) {
        return isOp(viewer);
    }

    public int opLevel() {
        return opLevel;
    }

    public boolean renameRequiresOfficer() {
        return renameRequiresOfficer;
    }

    /** {@code a != null && b != null && (a.equals(b) || sameTeam(a, b))}. */
    private static <T> boolean ownerOrSameTeam(UUID a, UUID b, TeamResolver<T> teams) {
        return a != null && b != null && (a.equals(b) || teams.sameTeam(a, b));
    }
}
