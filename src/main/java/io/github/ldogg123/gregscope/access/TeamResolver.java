package io.github.ldogg123.gregscope.access;

import java.util.UUID;

/**
 * Answers team questions about player UUIDs for {@link AccessPolicy} (design-v0.2 §5). The only production
 * implementation is {@link GtnhlibTeamResolver}; unit tests use a fake. [pure]
 *
 * <p>
 * A resolver is meant to live for one Hub view rebuild or one sampler-frame sequence and may cache within that span.
 * Team handles are transient: callers never store them, nor any team ID, so merges, leaves and kicks take effect with
 * the next resolver (or the next {@code clearCache}).
 *
 * @param <T> the team handle type of the backing team system
 */
public interface TeamResolver<T> {

    /** The first team that has {@code player} as a member, or null if there is none or {@code player} is null. */
    T teamOf(UUID player);

    /** Whether {@code player} is a member of {@code team} (owners and officers are always members). */
    boolean isMember(T team, UUID player);

    /** Whether {@code player} is an officer or an owner of {@code team}. */
    boolean isOfficerOrOwner(T team, UUID player);

    /**
     * {@code a.equals(b) || (t = teamOf(a)) != null && t.isMember(b)}; false if either UUID is null. A player without a
     * team is only in the same team as themselves.
     */
    default boolean sameTeam(UUID a, UUID b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        T team = teamOf(a);
        return team != null && isMember(team, b);
    }
}
