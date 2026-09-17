package io.github.ldogg123.gregscope.access;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** In-memory {@link TeamResolver} with GTNHLib's role rules: owners are officers, officers are members. */
final class FakeTeams implements TeamResolver<FakeTeams.Team> {

    static final class Team {

        final String name;
        final Set<UUID> owners = new HashSet<>();
        final Set<UUID> officers = new HashSet<>();
        final Set<UUID> members = new HashSet<>();

        Team(String name) {
            this.name = name;
        }

        Team owner(UUID p) {
            owners.add(p);
            return officer(p);
        }

        Team officer(UUID p) {
            officers.add(p);
            return member(p);
        }

        Team member(UUID p) {
            members.add(p);
            return this;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final List<Team> teams = new ArrayList<>();
    int teamOfCalls;

    Team team(String name) {
        Team t = new Team(name);
        teams.add(t);
        return t;
    }

    @Override
    public Team teamOf(UUID player) {
        teamOfCalls++;
        if (player == null) {
            return null;
        }
        for (Team t : teams) {
            if (t.members.contains(player)) {
                return t;
            }
        }
        return null;
    }

    @Override
    public boolean isMember(Team team, UUID player) {
        return team.members.contains(player);
    }

    @Override
    public boolean isOfficerOrOwner(Team team, UUID player) {
        return team.officers.contains(player) || team.owners.contains(player);
    }
}
