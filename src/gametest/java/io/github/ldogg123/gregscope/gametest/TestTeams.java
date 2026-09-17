package io.github.ldogg123.gregscope.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamManager;

/**
 * Creates real GTNHLib teams for fake player UUIDs and removes them on {@link #close()}, so tests leave no team in
 * the world's {@code gtnhteams} directory. GTNHLib saves only on a world save, never during a synchronous test body.
 *
 * <p>
 * Extends {@link TeamManager} only to reach its protected static tables for the cleanup; GTNHLib 0.11.46 has no public
 * way to delete a team.
 */
final class TestTeams extends TeamManager implements AutoCloseable {

    private final List<Team> created = new ArrayList<>();
    private final List<UUID> players = new ArrayList<>();

    /** A unique fake player UUID that no real account uses (version 3, name-based on a random tag). */
    UUID player(String tag) {
        UUID id = UUID.nameUUIDFromBytes(("gregscope-gametest:" + tag + ":" + UUID.randomUUID()).getBytes());
        players.add(id);
        return id;
    }

    /** {@code TeamManager.getOrCreateTeam(name, owner)}, as design-v0.2 §14 GS-104 prescribes for this test. */
    Team create(String name, UUID owner) {
        Team team = TeamManager.getOrCreateTeam(name, owner);
        created.add(team);
        return team;
    }

    /**
     * A registered team without any {@code ITeamData}, for merge tests. {@code TeamManager.mergeTeams} calls
     * {@code mergeData} only when both teams carry data, and GT5U's {@code GTPowerfailTracker.PowerfailData.mergeData}
     * queues the surviving team's ID for its end-of-tick flush, which then throws a NullPointerException (a server
     * crash) if the team was removed in between (observed with {@link #create} teams; GT5U 5.09.54.133
     * {@code GTPowerfailTracker.java:329-336,388-398}). Teams from here never reach that code.
     */
    Team createWithoutData(String name, UUID owner) {
        Team team = new Team(name, UUID.randomUUID(), false);
        team.addOwner(owner);
        if (!TeamManager.addTeamDeduplicated(team)) {
            throw new IllegalStateException("team already registered: " + name);
        }
        created.add(team);
        return team;
    }

    /** Also removes teams that a merge consumed and drops their pending-delete marks. */
    @Override
    public void close() {
        for (Team team : created) {
            TEAMS.remove(team);
            TEAM_MAP.remove(team.getTeamId());
            REMOVED_TEAMS.remove(team.getTeamId());
            PENDING_MERGE_REQUESTS.remove(team);
        }
        for (UUID player : players) {
            PLAYER_TEAM_CACHE.remove(player);
            PENDING_INVITES.remove(player);
        }
        created.clear();
        players.clear();
    }

    /** Whether any team created here is still registered (for the cleanup assertion). */
    static boolean anyRegistered(List<UUID> teamIds) {
        for (UUID id : teamIds) {
            if (TEAM_MAP.containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    List<UUID> teamIds() {
        List<UUID> ids = new ArrayList<>();
        for (Team team : created) {
            ids.add(team.getTeamId());
        }
        return ids;
    }
}
