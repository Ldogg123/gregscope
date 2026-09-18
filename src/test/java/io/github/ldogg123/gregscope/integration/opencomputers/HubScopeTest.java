package io.github.ldogg123.gregscope.integration.opencomputers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.access.AccessPolicy;
import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.registry.RemovalCause;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.LimitsView;
import io.github.ldogg123.gregscope.sampling.SamplerStats;
import io.github.ldogg123.gregscope.sampling.SamplerStatsView;
import io.github.ldogg123.gregscope.sampling.SensorView;
import io.github.ldogg123.gregscope.sampling.TelemetryFrame;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-116: the Telemetry Hub's OpenComputers scope (design-v0.2 sections 10.3 and 5) and the two tables built from it
 * ({@code getInfo} and {@code listSensors}). The scope filter, the clamps and the id resolution are the parts a Lua
 * caller can reach with any argument at all, so they are pinned here rather than only in the game test.
 */
class HubScopeTest {

    /** 2026-01-01T00:00:00Z. */
    private static final long T0 = 1_767_225_600L;

    private static final UUID HUB_OWNER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final UUID MATE = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-4000-8000-0000000000cc");

    private static final UUID OWN = UUID.fromString("11111111-1111-4000-8000-000000000001");
    private static final UUID OWN_SIBLING = UUID.fromString("11111111-1111-4000-8000-000000000002");
    private static final UUID MATES = UUID.fromString("22222222-2222-4000-8000-000000000001");
    private static final UUID THEIRS = UUID.fromString("33333333-3333-4000-8000-000000000001");

    // --- the scope filter (section 5) ---

    @Test
    void scopeIsTheHubOwnersOwnAndTheirTeams() {
        HubScope scope = scope(frame(1L, live(THEIRS, STRANGER), live(OWN, HUB_OWNER), live(MATES, MATE)), HUB_OWNER);
        assertEquals(Arrays.asList(OWN, MATES), ids(scope));
        assertEquals(2, scope.total());
        assertEquals(2, scope.live());
        assertEquals(HUB_OWNER, scope.owner());
        assertEquals("Steve", scope.ownerName());
    }

    /** Section 10.3: the frame is sorted by sensor UUID and filtering keeps that order, so paging is stable. */
    @Test
    void scopeKeepsTheFramesSensorUuidOrder() {
        HubScope scope = scope(
            frame(1L, live(OWN_SIBLING, HUB_OWNER), live(MATES, MATE), live(OWN, HUB_OWNER)),
            HUB_OWNER);
        assertEquals(Arrays.asList(OWN, OWN_SIBLING, MATES), ids(scope));
    }

    /** Section 5: an unowned Hub has no scope at all, and neither does a Hub whose record is unsupported. */
    @Test
    void unownedHubSeesNothing() {
        HubScope scope = scope(frame(1L, live(OWN, HUB_OWNER), live(THEIRS, null)), null);
        assertEquals(0, scope.total());
        assertEquals(0, scope.live());
        assertNull(scope.owner());
        assertEquals("", scope.ownerName());
        assertTrue(
            scope.entries()
                .isEmpty());
    }

    /** An unowned sensor belongs to no Hub, whoever owns the Hub (section 5). */
    @Test
    void unownedSensorIsInNoHubsScope() {
        HubScope scope = scope(frame(1L, live(OWN, HUB_OWNER), live(THEIRS, null)), HUB_OWNER);
        assertEquals(Collections.singletonList(OWN), ids(scope));
    }

    @Test
    void liveCountsOnlyLiveSensors() {
        HubScope scope = scope(
            frame(1L, live(OWN, HUB_OWNER), state(OWN_SIBLING, HUB_OWNER, SensorState.UNLOADED)),
            HUB_OWNER);
        assertEquals(2, scope.total());
        assertEquals(1, scope.live());
    }

    @Test
    void historyLoadedIsAskedOncePerSensorInScope() {
        List<UUID> asked = new ArrayList<>();
        Predicate<UUID> loaded = id -> {
            asked.add(id);
            return OWN.equals(id);
        };
        HubScope scope = HubScope.of(
            frame(1L, live(OWN, HUB_OWNER), live(MATES, MATE), live(THEIRS, STRANGER)),
            HUB_OWNER,
            "Steve",
            new AccessPolicy(Settings.DEFAULTS),
            teams(),
            loaded);
        assertEquals(Arrays.asList(OWN, MATES), asked, "a sensor outside the scope is never asked about");
        assertTrue(
            scope.entries()
                .get(0)
                .historyLoaded());
        assertFalse(
            scope.entries()
                .get(1)
                .historyLoaded());
    }

    @Test
    void entriesAreUnmodifiableAndTheEmptyScopeIsUsable() {
        HubScope scope = scope(frame(1L, live(OWN, HUB_OWNER)), HUB_OWNER);
        assertThrows(
            UnsupportedOperationException.class,
            () -> scope.entries()
                .clear());
        assertEquals(0, HubScope.EMPTY.total());
        assertNull(HubScope.EMPTY.owner());
        assertSame(TelemetryFrame.EMPTY, HubScope.EMPTY.frame());
        assertEquals(
            HubScope.Lookup.class,
            HubScope.EMPTY.lookup(OWN.toString())
                .getClass());
        assertEquals(
            LuaTables.ERROR_SENSOR_NOT_FOUND,
            HubScope.EMPTY.lookup(OWN.toString())
                .error());
    }

    @Test
    void nullArgumentsAreRefused() {
        AccessPolicy policy = new AccessPolicy(Settings.DEFAULTS);
        Predicate<UUID> loaded = id -> true;
        assertThrows(
            IllegalArgumentException.class,
            () -> HubScope.of(null, HUB_OWNER, "Steve", policy, teams(), loaded));
        assertThrows(
            IllegalArgumentException.class,
            () -> HubScope.of(frame(1L), HUB_OWNER, "Steve", null, teams(), loaded));
        assertThrows(
            IllegalArgumentException.class,
            () -> HubScope.of(frame(1L), HUB_OWNER, "Steve", policy, null, loaded));
        assertThrows(
            IllegalArgumentException.class,
            () -> HubScope.of(frame(1L), HUB_OWNER, "Steve", policy, teams(), null));
    }

    // --- clamps and paging (section 10.3) ---

    @Test
    void limitIsClampedIntoOneToSixtyFour() {
        assertEquals(1, HubScope.clampLimit(Integer.MIN_VALUE));
        assertEquals(1, HubScope.clampLimit(-5));
        assertEquals(1, HubScope.clampLimit(0));
        assertEquals(1, HubScope.clampLimit(1));
        assertEquals(32, HubScope.clampLimit(HubScope.DEFAULT_LIST_LIMIT));
        assertEquals(64, HubScope.clampLimit(64));
        assertEquals(64, HubScope.clampLimit(65));
        assertEquals(64, HubScope.clampLimit(Integer.MAX_VALUE));
        assertEquals(32, HubScope.DEFAULT_LIST_LIMIT, "the section 10.3 default page size");
        assertEquals(64, HubScope.MAX_LIST_LIMIT, "the section 10.3 cap");
    }

    @Test
    void offsetIsClampedAtZero() {
        assertEquals(0, HubScope.clampOffset(Integer.MIN_VALUE));
        assertEquals(0, HubScope.clampOffset(-1));
        assertEquals(0, HubScope.clampOffset(0));
        assertEquals(7, HubScope.clampOffset(7));
    }

    @Test
    void pagingMeetsExactlyAndStopsAtTheEnd() {
        HubScope scope = scope(
            frame(1L, live(OWN, HUB_OWNER), live(OWN_SIBLING, HUB_OWNER), live(MATES, MATE)),
            HUB_OWNER);
        assertEquals(Arrays.asList(OWN, OWN_SIBLING), ids(scope.page(0, 2)));
        assertEquals(Collections.singletonList(MATES), ids(scope.page(2, 2)));
        assertTrue(
            scope.page(3, 2)
                .isEmpty(),
            "an offset at the end is an empty page");
        assertTrue(
            scope.page(99, 64)
                .isEmpty(),
            "an offset past the end is an empty page, not an error");
        assertEquals(Arrays.asList(OWN, OWN_SIBLING, MATES), ids(scope.page(-4, 1000)), "both arguments are clamped");
        assertEquals(Collections.singletonList(OWN), ids(scope.page(0, 0)), "limit 0 is one row");
    }

    // --- id resolution (section 10.3, section 5) ---

    @Test
    void lookupResolvesAFullIdAndAnEightCharacterPrefix() {
        HubScope scope = scope(frame(1L, live(OWN, HUB_OWNER), live(MATES, MATE)), HUB_OWNER);
        assertEquals(OWN, found(scope, OWN.toString()));
        assertEquals(
            OWN,
            found(
                scope,
                OWN.toString()
                    .toUpperCase(java.util.Locale.ROOT)),
            "matching ignores case");
        assertEquals(MATES, found(scope, "22222222"));
        assertEquals(MATES, found(scope, "22222222-2222"), "dashes are ignored, as /gregscope ignores them");
    }

    @Test
    void lookupRefusesAPrefixShorterThanEight() {
        HubScope scope = scope(frame(1L, live(OWN, HUB_OWNER)), HUB_OWNER);
        assertEquals(8, HubScope.MIN_ID_PREFIX);
        assertEquals(LuaTables.ERROR_SENSOR_NOT_FOUND, error(scope, "1111111"), "seven characters is not an id");
        assertEquals(LuaTables.ERROR_SENSOR_NOT_FOUND, error(scope, "1111-111"), "dashes do not count");
        assertEquals(LuaTables.ERROR_SENSOR_NOT_FOUND, error(scope, ""));
        assertEquals(LuaTables.ERROR_SENSOR_NOT_FOUND, error(scope, null));
        assertEquals(OWN, found(scope, "11111111"), "eight characters do resolve");
    }

    @Test
    void lookupReportsAnAmbiguousPrefix() {
        HubScope scope = scope(frame(1L, live(OWN, HUB_OWNER), live(OWN_SIBLING, HUB_OWNER)), HUB_OWNER);
        assertEquals(LuaTables.ERROR_AMBIGUOUS_ID, error(scope, "11111111"));
        assertEquals(LuaTables.ERROR_AMBIGUOUS_ID, error(scope, "11111111-1111-4000-8000-00000000000"));
        assertEquals(OWN, found(scope, OWN.toString()), "the full id is still unique");
    }

    /** Section 5: an id outside the scope is answered exactly like one that does not exist. */
    @Test
    void lookupHidesSensorsOutsideTheScope() {
        HubScope scope = scope(frame(1L, live(OWN, HUB_OWNER), live(THEIRS, STRANGER)), HUB_OWNER);
        assertEquals(LuaTables.ERROR_SENSOR_NOT_FOUND, error(scope, THEIRS.toString()));
        assertEquals(LuaTables.ERROR_SENSOR_NOT_FOUND, error(scope, "33333333"));
        assertEquals(
            error(scope, "ffffffff-ffff-4000-8000-ffffffffffff"),
            error(scope, THEIRS.toString()),
            "an out-of-scope id and an unknown id are the same answer");
    }

    // --- getInfo (section 10.3) ---

    @Test
    void hubInfoCarriesEverySectionTenThreeKey() {
        HubScope scope = scope(frame(7L, live(OWN, HUB_OWNER), state(MATES, MATE, SensorState.MISSING)), HUB_OWNER);
        Map<String, Object> info = LuaTables.hubInfo(scope, "0.2.0", 20, T0 + 3L);
        assertEquals(
            new LinkedHashSet<>(
                Arrays.asList(
                    "apiVersion",
                    "schemaVersion",
                    "historyVersion",
                    "sensorRecordVersion",
                    "gregscopeVersion",
                    "owner",
                    "ownerName",
                    "visible",
                    "live",
                    "maxSensors",
                    "intervalTicks",
                    "secondsCapacity",
                    "minutesCapacity",
                    "frameSequence",
                    "frameAgeSeconds")),
            new LinkedHashSet<>(info.keySet()));
        assertEquals(2, info.get("apiVersion"));
        assertEquals(1, info.get("schemaVersion"));
        assertEquals(1, info.get("historyVersion"));
        assertEquals(1, info.get("sensorRecordVersion"));
        assertEquals("0.2.0", info.get("gregscopeVersion"));
        assertEquals(HUB_OWNER.toString(), info.get("owner"));
        assertEquals("Steve", info.get("ownerName"));
        assertEquals(2, info.get("visible"));
        assertEquals(1, info.get("live"));
        assertEquals(Settings.DEFAULTS.maxSensors(), info.get("maxSensors"));
        assertEquals(20, info.get("intervalTicks"));
        assertEquals(300, info.get("secondsCapacity"));
        assertEquals(1440, info.get("minutesCapacity"));
        assertEquals(7L, info.get("frameSequence"));
        assertEquals(3L, info.get("frameAgeSeconds"));
        for (Map.Entry<String, Object> e : info.entrySet()) {
            assertNotNull(e.getValue(), "nil value for key " + e.getKey());
        }
    }

    /** The capacities a computer reads are the rings' own, not a second copy of the numbers. */
    @Test
    void capacitiesComeFromTheRings() {
        assertEquals(io.github.ldogg123.gregscope.history.SecondRing.CAPACITY, LuaTables.SECONDS_CAPACITY);
        assertEquals(io.github.ldogg123.gregscope.history.MinuteRing.SLOTS, LuaTables.MINUTES_CAPACITY);
        assertEquals(300, LuaTables.SECONDS_CAPACITY);
        assertEquals(1440, LuaTables.MINUTES_CAPACITY);
    }

    @Test
    void hubInfoOfAnUnownedHubHasNoOwnerKeys() {
        Map<String, Object> info = LuaTables.hubInfo(scope(frame(1L, live(OWN, HUB_OWNER)), null), "0.2.0", 20, T0);
        assertFalse(info.containsKey("owner"), info.toString());
        assertFalse(info.containsKey("ownerName"), info.toString());
        assertEquals(0, info.get("visible"));
        assertEquals(0, info.get("live"));
    }

    /** Before the sampler published anything there is no interval and no publication time in the frame. */
    @Test
    void hubInfoFallsBackToTheConfiguredIntervalAndZeroAge() {
        Map<String, Object> info = LuaTables.hubInfo(scope(TelemetryFrame.EMPTY, HUB_OWNER), "0.2.0", 40, T0 + 10_000L);
        assertEquals(0L, info.get("frameSequence"));
        assertEquals(0L, info.get("frameAgeSeconds"), "an age is not invented for a frame that was never published");
        assertEquals(40, info.get("intervalTicks"));
    }

    /** A clock that went backwards reads as a fresh frame, never as a negative age. */
    @Test
    void frameAgeIsNeverNegative() {
        Map<String, Object> info = LuaTables.hubInfo(scope(frame(3L), HUB_OWNER), "0.2.0", 20, T0 - 600L);
        assertEquals(0L, info.get("frameAgeSeconds"));
    }

    @Test
    void hubInfoRefusesANullScope() {
        assertThrows(IllegalArgumentException.class, () -> LuaTables.hubInfo(null, "0.2.0", 20, T0));
        assertThrows(IllegalArgumentException.class, () -> LuaTables.sensorList(null, 0, 32, T0));
    }

    // --- listSensors (section 10.3) ---

    @Test
    void sensorListIsAPageOfRecordsWithTheWholeTotal() {
        HubScope scope = scope(
            frame(1L, live(OWN, HUB_OWNER), live(OWN_SIBLING, HUB_OWNER), live(MATES, MATE)),
            HUB_OWNER);
        Map<String, Object> page = LuaTables.sensorList(scope, 1, 1, T0);
        assertEquals(new LinkedHashSet<>(Arrays.asList("total", "offset", "sensors")), new HashSet<>(page.keySet()));
        assertEquals(3, page.get("total"), "total counts the scope, not the page");
        assertEquals(1, page.get("offset"));
        List<?> sensors = (List<?>) page.get("sensors");
        assertEquals(1, sensors.size());
        Map<?, ?> record = (Map<?, ?>) sensors.get(0);
        assertEquals(OWN_SIBLING.toString(), record.get("id"));
        assertEquals(1, record.get("sensorRecordVersion"));
        assertEquals("live", record.get("availability"));
        assertEquals(true, record.get("historyLoaded"));
    }

    @Test
    void sensorListClampsBothArgumentsAndEchoesTheClampedOffset() {
        HubScope scope = scope(frame(1L, live(OWN, HUB_OWNER), live(OWN_SIBLING, HUB_OWNER)), HUB_OWNER);
        assertEquals(
            0,
            LuaTables.sensorList(scope, -3, 64, T0)
                .get("offset"));
        assertEquals(
            2,
            ((List<?>) LuaTables.sensorList(scope, -3, 64, T0)
                .get("sensors")).size());
        assertEquals(
            1,
            ((List<?>) LuaTables.sensorList(scope, 0, 0, T0)
                .get("sensors")).size(),
            "limit 0 is one row");
        assertEquals(
            2,
            ((List<?>) LuaTables.sensorList(scope, 0, 1000, T0)
                .get("sensors")).size(),
            "an oversize limit is clamped, not refused");
        Map<String, Object> beyond = LuaTables.sensorList(scope, 9, 32, T0);
        assertEquals(9, beyond.get("offset"), "the offset a caller asked for is echoed");
        assertEquals(2, beyond.get("total"));
        assertTrue(((List<?>) beyond.get("sensors")).isEmpty());
    }

    @Test
    void sensorListOfAnUnownedHubIsEmptyButStillATable() {
        Map<String, Object> page = LuaTables.sensorList(scope(frame(1L, live(OWN, HUB_OWNER)), null), 0, 32, T0);
        assertEquals(0, page.get("total"));
        assertEquals(0, page.get("offset"));
        assertTrue(((List<?>) page.get("sensors")).isEmpty());
    }

    // --- fixtures ---

    private static HubScope scope(TelemetryFrame frame, UUID hubOwner) {
        return HubScope.of(
            frame,
            hubOwner,
            hubOwner == null ? null : "Steve",
            new AccessPolicy(Settings.DEFAULTS),
            teams(),
            id -> true);
    }

    private static List<UUID> ids(HubScope scope) {
        return ids(scope.entries());
    }

    private static List<UUID> ids(List<HubScope.Entry> entries) {
        List<UUID> out = new ArrayList<>();
        for (HubScope.Entry entry : entries) {
            out.add(
                entry.view()
                    .id());
        }
        return out;
    }

    private static UUID found(HubScope scope, String idOrPrefix) {
        HubScope.Lookup lookup = scope.lookup(idOrPrefix);
        assertNull(lookup.error(), "lookup of " + idOrPrefix + " failed: " + lookup.error());
        assertNotNull(lookup.entry());
        return lookup.entry()
            .view()
            .id();
    }

    private static String error(HubScope scope, String idOrPrefix) {
        HubScope.Lookup lookup = scope.lookup(idOrPrefix);
        assertNull(lookup.entry(), "lookup of " + idOrPrefix + " unexpectedly resolved");
        return lookup.error();
    }

    /** The Hub owner and one team mate; everyone else is a stranger. */
    private static TeamResolver<String> teams() {
        return new TeamResolver<String>() {

            private final Set<UUID> team = new HashSet<>(Arrays.asList(HUB_OWNER, MATE));

            @Override
            public String teamOf(UUID player) {
                return team.contains(player) ? "the team" : null;
            }

            @Override
            public boolean isMember(String t, UUID player) {
                return t != null && team.contains(player);
            }

            @Override
            public boolean isOfficerOrOwner(String t, UUID player) {
                return isMember(t, player);
            }
        };
    }

    private static TelemetryFrame frame(long sequence, SensorView... views) {
        return new TelemetryFrame(
            sequence,
            1_000L,
            T0 * 1000L,
            20,
            Arrays.asList(views),
            new SamplerStatsView(new SamplerStats(), 0L, 0L),
            new LimitsView(Settings.DEFAULTS));
    }

    private static SensorView live(UUID id, UUID owner) {
        return state(id, owner, SensorState.LIVE);
    }

    private static SensorView state(UUID id, UUID owner, SensorState sensorState) {
        SensorIdentity identity = new SensorIdentity(id, "", owner, owner == null ? null : "Owner", T0 - 86_400L);
        SensorEntry entry = new SensorEntry(
            identity,
            SensorKind.MACHINE,
            0,
            120,
            64,
            -30,
            4,
            SensorState.LIVE,
            T0 - 600L);
        entry.setMachineMetadata(1001, "machine.ebf", "Electric Blast Furnace", "running");
        entry.setLastSeenEpochSec(T0 - 5L);
        if (sensorState != SensorState.LIVE) {
            entry.restoreState(sensorState, RemovalCause.NONE, T0 - 720L, T0 - 720L);
        }
        return new SensorView(entry);
    }
}
