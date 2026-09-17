package io.github.ldogg123.gregscope.access;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamManager;

/**
 * {@link TeamResolver} over GTNHLib teams (GTNHLib 0.11.46), and the only GregScope class that touches
 * {@code com.gtnewhorizon.gtnhlib.teams} (design-v0.2 §5).
 *
 * <p>
 * Lookups scan {@link TeamManager#getTeamMap()} and ask {@link Team#isMember}. GTNHLib's own by-player lookup (and
 * {@code TeamManager}'s get-or-create, which calls it) logs an ERROR line for every player without a team (errata E4),
 * so neither is ever used; {@code NoErrorLoggingTeamLookupTest} enforces that over {@code src/main}.
 *
 * <p>
 * Use one instance per Hub view rebuild or sampler-frame sequence, on the server thread. {@link #teamOf} results,
 * including "no team", are cached until {@link #clearCache()} or until the instance is dropped; membership and role
 * checks always read the live team. Nothing is persisted and no team ID is read, so team merges, leaves and kicks apply
 * on the next rebuild. The cache holds at most one entry per distinct UUID asked about in the span.
 */
public final class GtnhlibTeamResolver implements TeamResolver<Team> {

    private final Map<UUID, Team> cache = new HashMap<>();

    @Override
    public Team teamOf(UUID player) {
        if (player == null) {
            return null;
        }
        if (cache.containsKey(player)) {
            return cache.get(player);
        }
        Team found = null;
        for (Team team : TeamManager.getTeamMap()
            .values()) {
            if (team.isMember(player)) {
                found = team;
                break;
            }
        }
        cache.put(player, found);
        return found;
    }

    @Override
    public boolean isMember(Team team, UUID player) {
        return team != null && player != null && team.isMember(player);
    }

    @Override
    public boolean isOfficerOrOwner(Team team, UUID player) {
        return team != null && player != null && (team.isOfficer(player) || team.isOwner(player));
    }

    /** Forgets cached {@link #teamOf} results, so the next lookup sees current teams. */
    public void clearCache() {
        cache.clear();
    }
}
