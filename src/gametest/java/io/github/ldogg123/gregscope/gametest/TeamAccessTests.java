package io.github.ldogg123.gregscope.gametest;

import java.util.List;
import java.util.UUID;

import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamManager;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import io.github.ldogg123.gregscope.access.AccessPolicy;
import io.github.ldogg123.gregscope.access.GtnhlibTeamResolver;
import io.github.ldogg123.gregscope.access.Viewer;
import io.github.ldogg123.gregscope.config.Settings;

/**
 * GS-104: {@link GtnhlibTeamResolver} and {@link AccessPolicy} against real GTNHLib 0.11.46 teams on the dedicated
 * server. Every test builds its teams for unique fake UUIDs and removes them in the same synchronous call.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope", "gtnhlib" })
public class TeamAccessTests {

    private static final String BATCH = "gregscope.access";
    /** The message GTNHLib's by-player lookup logs at ERROR for a player without a team (errata E4). */
    private static final String E4_MESSAGE = "Unable to find team";

    private TeamAccessTests() {}

    @GameTest(batch = BATCH)
    public static void sameTeamResolvesGtnhlibTeams(GameTestHelper helper) {
        List<UUID> ids;
        try (TestTeams teams = new TestTeams()) {
            UUID aOwner = teams.player("aOwner");
            UUID aOfficer = teams.player("aOfficer");
            UUID aMember = teams.player("aMember");
            UUID bOwner = teams.player("bOwner");
            UUID loner = teams.player("loner");
            Team a = teams.create("gsTeamA", aOwner);
            a.addOfficer(aOfficer);
            a.addMember(aMember);
            Team b = teams.create("gsTeamB", bOwner);
            ids = teams.teamIds();
            helper.assertNotSame(a, b, "getOrCreateTeam returned one team for two new players");
            helper.assertTrue(
                TeamManager.getTeamMap()
                    .containsValue(a),
                "team A not in the team map");

            GtnhlibTeamResolver resolver = new GtnhlibTeamResolver();
            helper.assertSame(a, resolver.teamOf(aOwner), "teamOf(A owner)");
            helper.assertSame(a, resolver.teamOf(aOfficer), "teamOf(A officer)");
            helper.assertSame(a, resolver.teamOf(aMember), "teamOf(A member)");
            helper.assertSame(b, resolver.teamOf(bOwner), "teamOf(B owner)");
            helper.assertNull(resolver.teamOf(loner), "teamOf(teamless)");
            helper.assertNull(resolver.teamOf(null), "teamOf(null)");

            helper.assertTrue(resolver.sameTeam(aOwner, aMember), "owner ~ member");
            helper.assertTrue(resolver.sameTeam(aMember, aOwner), "member ~ owner");
            helper.assertTrue(resolver.sameTeam(aMember, aOfficer), "member ~ officer");
            helper.assertFalse(resolver.sameTeam(aOwner, bOwner), "A owner ~ B owner");
            helper.assertFalse(resolver.sameTeam(bOwner, aMember), "B owner ~ A member");
            helper.assertFalse(resolver.sameTeam(aOwner, loner), "A owner ~ teamless");
            helper.assertFalse(resolver.sameTeam(loner, aOwner), "teamless ~ A owner");
            helper.assertTrue(resolver.sameTeam(loner, loner), "teamless ~ self");
            helper.assertFalse(resolver.sameTeam(aOwner, null), "owner ~ null");

            helper.assertTrue(resolver.isOfficerOrOwner(a, aOwner), "A owner is officer-or-owner");
            helper.assertTrue(resolver.isOfficerOrOwner(a, aOfficer), "A officer is officer-or-owner");
            helper.assertFalse(resolver.isOfficerOrOwner(a, aMember), "A member is not officer-or-owner");
            helper.assertFalse(resolver.isOfficerOrOwner(b, aOwner), "A owner is not officer-or-owner of B");

            // §5 rules over the real resolver.
            AccessPolicy policy = new AccessPolicy(Settings.DEFAULTS);
            AccessPolicy officerOnly = new AccessPolicy(
                Settings.DEFAULTS.toBuilder()
                    .renameRequiresOfficer(true)
                    .build());
            helper.assertTrue(policy.canView(player(aMember), aOwner, resolver), "member views owner's sensor");
            helper.assertFalse(policy.canView(player(bOwner), aOwner, resolver), "B views A's sensor");
            helper.assertTrue(policy.canOpenHub(player(aOfficer), aMember, resolver), "officer opens member's Hub");
            helper.assertFalse(policy.canOpenHub(player(loner), aOwner, resolver), "teamless opens A's Hub");
            helper.assertTrue(policy.canRename(player(aMember), aOwner, resolver), "member renames (default)");
            helper
                .assertFalse(officerOnly.canRename(player(aMember), aOwner, resolver), "member renames (officer only)");
            helper.assertTrue(officerOnly.canRename(player(aOfficer), aOwner, resolver), "officer renames");
            helper.assertTrue(policy.inHubScope(aOwner, aMember, resolver), "A member's sensor in A owner's Hub");
            helper.assertFalse(policy.inHubScope(aOwner, bOwner, resolver), "B's sensor in A owner's Hub");
            Viewer op = Viewer.player(bOwner, level -> true);
            helper.assertTrue(policy.canOpenHub(op, aOwner, resolver), "op opens A's Hub");
            helper.assertFalse(policy.inHubScope(aOwner, bOwner, resolver), "op's own sensor still out of A's scope");
        }
        helper.assertFalse(TestTeams.anyRegistered(ids), "test teams were not removed");
        helper.succeed();
    }

    /**
     * Resolver lookups for a random UUID log nothing. Positive controls: {@code getOrCreateTeam} for a new player, and
     * GTNHLib's by-player lookup for a random UUID, both log the E4 line through the same capture.
     */
    @GameTest(batch = BATCH)
    public static void randomUuidLookupLogsNoError(GameTestHelper helper) {
        try (LogCapture log = LogCapture.attach(E4_MESSAGE, "gtnhlib"); TestTeams teams = new TestTeams()) {
            UUID owner = teams.player("logOwner");
            Team team = teams.create("gsTeamLog", owner);
            helper.assertNotNull(team, "team");
            helper.assertEquals(
                1,
                log.count(),
                "positive control: getOrCreateTeam for a new player logs the E4 line once: " + log.lines());
            log.clear();

            UUID stranger = UUID.randomUUID();
            GtnhlibTeamResolver resolver = new GtnhlibTeamResolver();
            helper.assertNull(resolver.teamOf(stranger), "teamOf(random)");
            resolver.clearCache();
            helper.assertNull(resolver.teamOf(stranger), "teamOf(random) after clearCache");
            helper.assertFalse(resolver.sameTeam(stranger, owner), "random ~ owner");
            helper.assertFalse(resolver.sameTeam(owner, stranger), "owner ~ random");
            AccessPolicy policy = new AccessPolicy(Settings.DEFAULTS);
            Viewer viewer = Viewer.player(stranger, level -> false);
            helper.assertFalse(policy.canView(viewer, owner, resolver), "canView");
            helper.assertFalse(policy.canRename(viewer, owner, resolver), "canRename");
            helper.assertFalse(policy.canOpenHub(viewer, owner, resolver), "canOpenHub");
            helper.assertFalse(policy.inHubScope(stranger, owner, resolver), "inHubScope");
            helper.assertEquals(0, log.count(), "resolver lookups logged: " + log.lines());

            // Errata E4 still holds for this GTNHLib: the by-player lookup logs on a miss.
            helper.assertNull(TeamManager.getTeamByPlayer(UUID.randomUUID()), "getTeamByPlayer(random)");
            helper.assertEquals(1, log.count(), "positive control: getTeamByPlayer logs the E4 line: " + log.lines());
            Snapshots.log("access#e4", "captured only the controls; resolver lookups logged nothing");
        }
        helper.succeed();
    }

    /** teamOf is cached per rebuild; a fresh resolver (or clearCache) sees joins, leaves and merges. */
    @GameTest(batch = BATCH)
    public static void cacheLastsOneRebuildAndTeamChangesApplyNext(GameTestHelper helper) {
        List<UUID> ids;
        try (TestTeams teams = new TestTeams()) {
            UUID aOwner = teams.player("cacheAOwner");
            UUID bOwner = teams.player("cacheBOwner");
            UUID newcomer = teams.player("newcomer");
            // Data-less teams: see TestTeams.createWithoutData for the GT crash a merge of create() teams causes.
            Team a = teams.createWithoutData("gsTeamCacheA-" + aOwner, aOwner);
            Team b = teams.createWithoutData("gsTeamCacheB-" + bOwner, bOwner);
            ids = teams.teamIds();

            // Join: invisible to the current rebuild's cached "no team", visible after clearCache.
            GtnhlibTeamResolver rebuild = new GtnhlibTeamResolver();
            helper.assertNull(rebuild.teamOf(newcomer), "newcomer has no team yet");
            a.addMember(newcomer);
            helper.assertNull(rebuild.teamOf(newcomer), "cached within the rebuild");
            helper.assertFalse(rebuild.sameTeam(newcomer, aOwner), "cached team of newcomer");
            // Membership itself is read live from the owner's team.
            helper.assertTrue(rebuild.sameTeam(aOwner, newcomer), "live membership check");
            rebuild.clearCache();
            helper.assertSame(a, rebuild.teamOf(newcomer), "after clearCache");
            helper.assertTrue(rebuild.sameTeam(newcomer, aOwner), "after clearCache");

            // Leave (or kick): the next rebuild no longer finds a team.
            a.removeMember(newcomer);
            helper.assertNull(new GtnhlibTeamResolver().teamOf(newcomer), "after leaving");

            // Merge: B is consumed into A; the next rebuild puts both owners in one team.
            GtnhlibTeamResolver beforeMerge = new GtnhlibTeamResolver();
            helper.assertFalse(beforeMerge.sameTeam(bOwner, aOwner), "before merge");
            TeamManager.mergeTeams(a, b);
            helper.assertFalse(
                TeamManager.getTeamMap()
                    .containsValue(b),
                "consumed team still in the map");
            helper.assertSame(b, beforeMerge.teamOf(bOwner), "the old rebuild keeps its cached (now removed) team");
            GtnhlibTeamResolver afterMerge = new GtnhlibTeamResolver();
            helper.assertSame(a, afterMerge.teamOf(bOwner), "after merge");
            helper.assertTrue(afterMerge.sameTeam(bOwner, aOwner), "after merge");
        }
        helper.assertFalse(TestTeams.anyRegistered(ids), "test teams were not removed");
        helper.succeed();
    }

    private static Viewer player(UUID uuid) {
        return Viewer.player(uuid, level -> false);
    }
}
