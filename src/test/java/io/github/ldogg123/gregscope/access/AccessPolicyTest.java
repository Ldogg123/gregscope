package io.github.ldogg123.gregscope.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.ldogg123.gregscope.config.Settings;

/**
 * Truth table for design-v0.2 §5 with a fake resolver. Expected values are written out per row, not derived from the
 * rule, so a changed rule fails here.
 */
class AccessPolicyTest {

    // Team T: TO owner, OF officer, M and O plain members. Team X: XO owner. L and L2 have no team.
    private static final Map<String, UUID> P = new HashMap<>();
    private static final FakeTeams TEAMS = new FakeTeams();

    static {
        for (String name : new String[] { "TO", "OF", "M", "O", "XO", "L", "L2" }) {
            P.put(name, UUID.nameUUIDFromBytes(name.getBytes()));
        }
        TEAMS.team("T")
            .owner(P.get("TO"))
            .officer(P.get("OF"))
            .member(P.get("M"))
            .member(P.get("O"));
        TEAMS.team("X")
            .owner(P.get("XO"));
    }

    private static final AccessPolicy DEFAULT = new AccessPolicy(Settings.DEFAULTS);
    private static final AccessPolicy OFFICER_ONLY = new AccessPolicy(
        Settings.builder()
            .renameRequiresOfficer(true)
            .build());

    private static UUID uuid(String name) {
        if (name == null) {
            return null;
        }
        UUID id = P.get(name);
        if (id == null) {
            throw new IllegalArgumentException(name);
        }
        return id;
    }

    /** A viewer that holds every level up to {@code maxLevel} (a vanilla op entry of that level); -1 holds none. */
    private static Viewer viewer(String name, int maxLevel) {
        return "CONSOLE".equals(name) ? Viewer.CONSOLE : Viewer.player(uuid(name), level -> level <= maxLevel);
    }

    private static final int NOT_OP = 0;
    private static final int OP = 2;
    private static final int CON = -99;

    static Stream<Arguments> table() {
        return Stream.of(
            // viewer, level, sensor/hub owner -> view, rename, rename(officerOnly), openHub(owner)
            // Sensor owned by O, a plain member of T.
            row("O", NOT_OP, "O", true, true, true, true),
            row("TO", NOT_OP, "O", true, true, true, true),
            row("OF", NOT_OP, "O", true, true, true, true),
            row("M", NOT_OP, "O", true, true, false, true),
            row("XO", NOT_OP, "O", false, false, false, false),
            row("L", NOT_OP, "O", false, false, false, false),
            row("XO", OP, "O", true, true, true, true),
            row("L", OP, "O", true, true, true, true),
            row("CONSOLE", CON, "O", true, true, true, true),
            // Sensor owned by TO, the owner of T: a plain member is still limited, the owner is not.
            row("TO", NOT_OP, "TO", true, true, true, true),
            row("O", NOT_OP, "TO", true, true, false, true),
            row("OF", NOT_OP, "TO", true, true, true, true),
            row("XO", NOT_OP, "TO", false, false, false, false),
            // Teamless owner: only themselves (and ops).
            row("L", NOT_OP, "L", true, true, true, true),
            row("L2", NOT_OP, "L", false, false, false, false),
            row("TO", NOT_OP, "L", false, false, false, false),
            row("L2", OP, "L", true, true, true, true),
            // Teamless viewer and a team owner: no relation.
            row("L", NOT_OP, "TO", false, false, false, false),
            // Unowned: ops (and the console) only.
            row("O", NOT_OP, null, false, false, false, false),
            row("L", NOT_OP, null, false, false, false, false),
            row("TO", NOT_OP, null, false, false, false, false),
            row("XO", OP, null, true, true, true, true),
            row("CONSOLE", CON, null, true, true, true, true));
    }

    private static Arguments row(String viewer, int level, String owner, boolean view, boolean rename,
        boolean renameOfficerOnly, boolean openHub) {
        return Arguments.of(viewer, level, owner, view, rename, renameOfficerOnly, openHub);
    }

    @ParameterizedTest(name = "{0}(level {1}) on owner {2}")
    @MethodSource("table")
    void truthTable(String viewerName, int level, String ownerName, boolean view, boolean rename,
        boolean renameOfficerOnly, boolean openHub) {
        Viewer viewer = viewer(viewerName, level);
        UUID owner = uuid(ownerName);
        assertEquals(view, DEFAULT.canView(viewer, owner, TEAMS), "canView");
        assertEquals(view, OFFICER_ONLY.canView(viewer, owner, TEAMS), "canView does not depend on the officer flag");
        assertEquals(rename, DEFAULT.canRename(viewer, owner, TEAMS), "canRename");
        assertEquals(renameOfficerOnly, OFFICER_ONLY.canRename(viewer, owner, TEAMS), "canRename (officer only)");
        assertEquals(openHub, DEFAULT.canOpenHub(viewer, owner, TEAMS), "canOpenHub");
        assertEquals(openHub, OFFICER_ONLY.canOpenHub(viewer, owner, TEAMS), "canOpenHub (officer only)");
        boolean op = level == OP || level == CON;
        assertEquals(op, DEFAULT.canPurge(viewer), "canPurge");
        assertEquals(op, DEFAULT.isOp(viewer), "isOp");
    }

    static Stream<Arguments> hubScope() {
        return Stream.of(
            Arguments.of("O", "O", true),
            Arguments.of("O", "M", true),
            Arguments.of("O", "TO", true),
            Arguments.of("TO", "OF", true),
            Arguments.of("M", "TO", true),
            Arguments.of("O", "XO", false),
            Arguments.of("XO", "O", false),
            Arguments.of("L", "L", true),
            Arguments.of("L", "L2", false),
            Arguments.of("L", "O", false),
            Arguments.of("O", "L", false),
            Arguments.of("O", null, false),
            Arguments.of(null, "O", false),
            Arguments.of(null, null, false));
    }

    @ParameterizedTest(name = "hub owner {0}, sensor owner {1}")
    @MethodSource("hubScope")
    void hubScopeTable(String hubOwner, String sensorOwner, boolean expected) {
        assertEquals(expected, DEFAULT.inHubScope(uuid(hubOwner), uuid(sensorOwner), TEAMS));
        assertEquals(expected, OFFICER_ONLY.inHubScope(uuid(hubOwner), uuid(sensorOwner), TEAMS));
    }

    /** An op who opens someone else's Hub sees that Hub's scope and gains no extra visibility. */
    @Test
    void opOpeningAnotherHubGetsNoEscalation() {
        Viewer op = viewer("XO", OP);
        UUID hubOwner = uuid("O");
        assertTrue(DEFAULT.canOpenHub(op, hubOwner, TEAMS), "op may open the Hub");
        assertTrue(DEFAULT.inHubScope(hubOwner, uuid("M"), TEAMS), "the Hub owner's team is in scope");
        assertFalse(DEFAULT.inHubScope(hubOwner, uuid("XO"), TEAMS), "the op's own sensor is not added to the scope");
        assertFalse(DEFAULT.inHubScope(hubOwner, uuid("L"), TEAMS), "strangers' sensors stay out of scope");
        assertFalse(DEFAULT.inHubScope(hubOwner, null, TEAMS), "unowned sensors stay out of scope");
        // Commands are different: op sees everything there.
        assertTrue(DEFAULT.canView(op, uuid("L"), TEAMS));
        assertTrue(DEFAULT.canView(op, null, TEAMS));
        // An unowned Hub (placed by a FakePlayer) shows nothing, even to an op who may open it.
        assertTrue(DEFAULT.canOpenHub(op, null, TEAMS));
        assertFalse(DEFAULT.canOpenHub(viewer("O", NOT_OP), null, TEAMS));
        assertFalse(DEFAULT.inHubScope(null, uuid("XO"), TEAMS));
    }

    @Test
    void opLevelComesFromSettings() {
        Viewer level1 = viewer("L", 1);
        Viewer level3 = viewer("L", 3);
        Viewer none = viewer("L", -1);
        AccessPolicy defaults = DEFAULT;
        assertEquals(2, defaults.opLevel(), "permissions.opLevel default");
        assertFalse(defaults.renameRequiresOfficer(), "permissions.renameRequiresOfficer default");
        assertFalse(defaults.isOp(level1));
        assertTrue(defaults.isOp(level3));
        assertFalse(defaults.canPurge(level1));

        AccessPolicy level0 = new AccessPolicy(
            Settings.builder()
                .opLevel(0)
                .build());
        assertTrue(level0.isOp(viewer("L", 0)), "opLevel=0 makes every player an op");
        // A non-op player: vanilla's canCommandSenderUseCommand is false at every level for players not on the ops
        // list, so opLevel=0 must not depend on the check (otherwise 0 would behave like 1).
        assertTrue(level0.isOp(none), "opLevel=0 includes players the game's check rejects");
        assertTrue(level0.canView(none, uuid("XO"), TEAMS));
        assertTrue(level0.canPurge(none));
        int[] asked = { 0 };
        Viewer counting = Viewer.player(uuid("L2"), level -> {
            asked[0]++;
            return false;
        });
        assertTrue(level0.canRename(counting, uuid("XO"), TEAMS));
        assertEquals(0, asked[0], "opLevel=0 never asks the permission check");

        AccessPolicy level1Policy = new AccessPolicy(
            Settings.builder()
                .opLevel(1)
                .build());
        assertFalse(level1Policy.isOp(none), "opLevel=1 still requires the game's check");
        assertTrue(level1Policy.isOp(level1));

        AccessPolicy level4 = new AccessPolicy(
            Settings.builder()
                .opLevel(4)
                .build());
        assertFalse(level4.isOp(level3));
        assertFalse(level4.canRename(level3, uuid("XO"), TEAMS));
        assertTrue(level4.isOp(Viewer.CONSOLE), "the console counts as op at any level");
        assertTrue(level4.canPurge(Viewer.CONSOLE));
    }

    @Test
    void permissionCheckIsAskedForTheConfiguredLevel() {
        int[] asked = { -1 };
        Viewer probe = Viewer.player(uuid("L"), level -> {
            asked[0] = level;
            return false;
        });
        new AccessPolicy(
            Settings.builder()
                .opLevel(3)
                .build()).canPurge(probe);
        assertEquals(3, asked[0]);
    }

    @Test
    void ownerAndOpDecisionsNeedNoTeamLookup() {
        TEAMS.teamOfCalls = 0;
        assertTrue(OFFICER_ONLY.canRename(viewer("M", NOT_OP), uuid("M"), TEAMS));
        assertTrue(DEFAULT.canView(viewer("O", NOT_OP), uuid("O"), TEAMS));
        assertTrue(DEFAULT.canOpenHub(viewer("O", NOT_OP), uuid("O"), TEAMS));
        assertTrue(DEFAULT.inHubScope(uuid("L"), uuid("L"), TEAMS));
        assertTrue(DEFAULT.canRename(viewer("XO", OP), uuid("O"), TEAMS));
        assertFalse(DEFAULT.canRename(viewer("O", NOT_OP), null, TEAMS));
        assertEquals(0, TEAMS.teamOfCalls, "teamOf calls");
    }

    @Test
    void sameTeamDefault() {
        TEAMS.teamOfCalls = 0;
        assertFalse(TEAMS.sameTeam(null, uuid("O")));
        assertFalse(TEAMS.sameTeam(uuid("O"), null));
        assertFalse(TEAMS.sameTeam(null, null));
        assertTrue(TEAMS.sameTeam(uuid("L"), uuid("L")), "a teamless player is in the same team as themselves");
        assertEquals(0, TEAMS.teamOfCalls, "equal or null UUIDs need no lookup");
        assertTrue(TEAMS.sameTeam(uuid("O"), uuid("TO")));
        assertTrue(TEAMS.sameTeam(uuid("TO"), uuid("O")));
        assertFalse(TEAMS.sameTeam(uuid("O"), uuid("XO")));
        assertFalse(TEAMS.sameTeam(uuid("L"), uuid("L2")));
        assertFalse(TEAMS.sameTeam(uuid("L"), uuid("TO")));
        assertNull(TEAMS.teamOf(uuid("L")));
    }

    @Test
    void viewerRequiresUuidAndCheck() {
        assertThrows(IllegalArgumentException.class, () -> Viewer.player(null, level -> true));
        assertThrows(IllegalArgumentException.class, () -> Viewer.player(uuid("L"), null));
        assertTrue(Viewer.CONSOLE.isConsole());
        assertNull(Viewer.CONSOLE.uuid());
        assertFalse(viewer("L", OP).isConsole());
    }
}
