package io.github.ldogg123.gregscope.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SettingsTest {

    private static final class Warnings {

        final List<String> messages = new ArrayList<>();

        void add(String message) {
            messages.add(message);
        }
    }

    private static Map<String, String> raw(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void defaultsMatchDesignTable() {
        Settings d = Settings.DEFAULTS;
        assertTrue(d.samplingEnabled());
        assertEquals(20, d.intervalTicks());
        assertEquals(1000, d.tickBudgetMicros());
        assertEquals(256, d.maxSensors());
        assertEquals(64, d.maxSensorsPerTeam());
        assertEquals(32, d.maxOpenHubViews());
        assertTrue(d.historyPersist());
        assertEquals(24, d.removedRetentionHours());
        assertEquals(30, d.staleExpiryDays());
        assertEquals(4096, d.ioQueueCapacity());
        assertEquals(2, d.opLevel());
        assertFalse(d.renameRequiresOfficer());
        assertEquals(5, d.renameCooldownSeconds());
    }

    @Test
    void keysMatchDesignTable() {
        List<String> expected = Arrays.asList(
            "sampling.enabled",
            "sampling.intervalTicks",
            "sampling.tickBudgetMicros",
            "limits.maxSensors",
            "limits.maxSensorsPerTeam",
            "limits.maxOpenHubViews",
            "history.persist",
            "history.removedRetentionHours",
            "history.staleExpiryDays",
            "history.ioQueueCapacity",
            "permissions.opLevel",
            "permissions.renameRequiresOfficer",
            "hub.renameCooldownSeconds");
        List<String> actual = new ArrayList<>();
        for (ConfigKeys.Key key : ConfigKeys.ALL) {
            actual.add(key.path());
        }
        assertEquals(expected, actual);
        assertRange(ConfigKeys.SAMPLING_TICK_BUDGET_MICROS, 100, 5000);
        assertRange(ConfigKeys.LIMITS_MAX_SENSORS, 16, 1024);
        assertRange(ConfigKeys.LIMITS_MAX_SENSORS_PER_TEAM, 0, 1024);
        assertRange(ConfigKeys.LIMITS_MAX_OPEN_HUB_VIEWS, 1, 256);
        assertRange(ConfigKeys.HISTORY_REMOVED_RETENTION_HOURS, 1, 168);
        assertRange(ConfigKeys.HISTORY_STALE_EXPIRY_DAYS, 1, 365);
        assertRange(ConfigKeys.HISTORY_IO_QUEUE_CAPACITY, 256, 65536);
        assertRange(ConfigKeys.PERMISSIONS_OP_LEVEL, 0, 4);
        assertRange(ConfigKeys.HUB_RENAME_COOLDOWN_SECONDS, 0, 300);
        assertTrue(
            Arrays.equals(new int[] { 20, 40, 60, 100 }, ConfigKeys.SAMPLING_INTERVAL_TICKS.allowedValues()),
            "intervalTicks allowed values");
        for (ConfigKeys.Key key : ConfigKeys.ALL) {
            if (!key.isBoolean()) {
                assertTrue(key.accepts(key.defaultInt()), key + " default is allowed");
            }
        }
    }

    private static void assertRange(ConfigKeys.Key key, int min, int max) {
        assertFalse(key.isBoolean(), key.path());
        assertEquals(null, key.allowedValues(), key.path());
        assertEquals(min, key.min(), key.path() + " min");
        assertEquals(max, key.max(), key.path() + " max");
        assertTrue(
            key.comment()
                .contains("[range: " + min + " ~ " + max + ", default: " + key.defaultInt() + "]"),
            key.comment());
    }

    @Test
    void emptyFileGivesDefaultsWithoutWarning() {
        Warnings warnings = new Warnings();
        assertEquals(Settings.DEFAULTS, Settings.fromRaw(new HashMap<>(), warnings::add));
        assertEquals(new ArrayList<String>(), warnings.messages);
    }

    @Test
    void validValuesAreReadWithoutWarning() {
        Warnings warnings = new Warnings();
        Settings s = Settings.fromRaw(
            raw(
                "sampling.enabled",
                "false",
                "sampling.intervalTicks",
                "60",
                "sampling.tickBudgetMicros",
                "100",
                "limits.maxSensors",
                "1024",
                "limits.maxSensorsPerTeam",
                "0",
                "limits.maxOpenHubViews",
                "1",
                "history.persist",
                "FALSE",
                "history.removedRetentionHours",
                "168",
                "history.staleExpiryDays",
                "365",
                "history.ioQueueCapacity",
                "256",
                "permissions.opLevel",
                "4",
                "permissions.renameRequiresOfficer",
                "True",
                "hub.renameCooldownSeconds",
                " 300 "),
            warnings::add);
        assertEquals(new ArrayList<String>(), warnings.messages);
        assertEquals(
            Settings.builder()
                .samplingEnabled(false)
                .intervalTicks(60)
                .tickBudgetMicros(100)
                .maxSensors(1024)
                .maxSensorsPerTeam(0)
                .maxOpenHubViews(1)
                .historyPersist(false)
                .removedRetentionHours(168)
                .staleExpiryDays(365)
                .ioQueueCapacity(256)
                .opLevel(4)
                .renameRequiresOfficer(true)
                .renameCooldownSeconds(300)
                .build(),
            s);
    }

    @ParameterizedTest
    @CsvSource({ "sampling.tickBudgetMicros, 99, 100", "sampling.tickBudgetMicros, 5001, 5000",
        "limits.maxSensors, 15, 16", "limits.maxSensors, 100000, 1024", "limits.maxSensorsPerTeam, -1, 0",
        "limits.maxSensorsPerTeam, 1025, 1024", "limits.maxOpenHubViews, 0, 1", "limits.maxOpenHubViews, 257, 256",
        "history.removedRetentionHours, 0, 1", "history.removedRetentionHours, 169, 168",
        "history.staleExpiryDays, -2147483648, 1", "history.staleExpiryDays, 2147483647, 365",
        "history.ioQueueCapacity, 255, 256", "history.ioQueueCapacity, 65537, 65536", "permissions.opLevel, -1, 0",
        "permissions.opLevel, 5, 4", "hub.renameCooldownSeconds, -1, 0", "hub.renameCooldownSeconds, 301, 300" })
    void outOfRangeIntegerIsClampedWithOneWarning(String path, String value, int expected) {
        Warnings warnings = new Warnings();
        Settings s = Settings.fromRaw(raw(path, value), warnings::add);
        assertEquals(1, warnings.messages.size(), "warnings: " + warnings.messages);
        assertTrue(
            warnings.messages.get(0)
                .contains(path + "=" + value + " is not allowed, using " + expected),
            warnings.messages.get(0));
        assertEquals(expected, intValue(s, path));
        // Every other value keeps its default.
        Map<String, String> onlyThis = raw(path, String.valueOf(expected));
        assertEquals(Settings.fromRaw(onlyThis, m -> { throw new AssertionError(m); }), s);
    }

    @ParameterizedTest
    @CsvSource({ "20, 20", "40, 40", "60, 60", "100, 100", "0, 20", "-7, 20", "25, 20", "30, 40", "35, 40", "50, 60",
        "70, 60", "80, 100", "99, 100", "1000, 100", "2147483647, 100", "-2147483648, 20" })
    void intervalTicksSnapsToNearestAllowedValue(int value, int expected) {
        Warnings warnings = new Warnings();
        Settings s = Settings.fromRaw(raw("sampling.intervalTicks", String.valueOf(value)), warnings::add);
        assertEquals(expected, s.intervalTicks());
        assertEquals(value == expected ? 0 : 1, warnings.messages.size(), "warnings: " + warnings.messages);
    }

    @Test
    void unparseableValuesFallBackToDefaultWithOneWarning() {
        Warnings warnings = new Warnings();
        Settings s = Settings.fromRaw(
            raw(
                "limits.maxSensors",
                "lots",
                "sampling.enabled",
                "yes",
                "hub.renameCooldownSeconds",
                "2.5",
                "history.ioQueueCapacity",
                "99999999999"),
            warnings::add);
        assertEquals(Settings.DEFAULTS, s);
        assertEquals(1, warnings.messages.size(), "warnings: " + warnings.messages);
        String message = warnings.messages.get(0);
        assertTrue(message.startsWith("gregscope.cfg: 4 values were invalid or out of range and adjusted: "), message);
        assertTrue(message.contains("limits.maxSensors=\"lots\" is not an integer, using default 256"), message);
        assertTrue(message.contains("sampling.enabled=\"yes\" is not true or false, using default true"), message);
        assertTrue(message.contains("hub.renameCooldownSeconds=\"2.5\" is not an integer, using default 5"), message);
        assertTrue(message.contains("history.ioQueueCapacity=\"99999999999\" is not an integer"), message);
    }

    @Test
    void manyAdjustmentsGiveExactlyOneWarningNamingEachKey() {
        Warnings warnings = new Warnings();
        Map<String, String> values = new HashMap<>();
        for (ConfigKeys.Key key : ConfigKeys.ALL) {
            values.put(key.path(), key.isBoolean() ? "maybe" : "-100000");
        }
        Settings s = Settings.fromRaw(values, warnings::add);
        assertEquals(1, warnings.messages.size(), "warnings: " + warnings.messages);
        for (ConfigKeys.Key key : ConfigKeys.ALL) {
            assertTrue(
                warnings.messages.get(0)
                    .contains(key.path() + "="),
                key.path());
        }
        assertEquals(20, s.intervalTicks());
        assertEquals(16, s.maxSensors());
        assertEquals(0, s.opLevel());
        assertTrue(s.samplingEnabled());
    }

    @Test
    void unknownKeysAreIgnored() {
        Warnings warnings = new Warnings();
        assertEquals(
            Settings.DEFAULTS,
            Settings.fromRaw(raw("exporter.enabled", "true", "limits.bogus", "-1"), warnings::add));
        assertEquals(new ArrayList<String>(), warnings.messages);
    }

    @Test
    void builderRejectsValuesTheFileWouldNotAllow() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Settings.builder()
                .maxSensors(15)
                .build());
        assertThrows(
            IllegalArgumentException.class,
            () -> Settings.builder()
                .intervalTicks(30)
                .build());
        assertThrows(
            IllegalArgumentException.class,
            () -> Settings.builder()
                .opLevel(5)
                .build());
        assertEquals(
            16,
            Settings.builder()
                .maxSensors(16)
                .build()
                .maxSensors());
    }

    @Test
    void toBuilderEqualsAndHashCode() {
        Settings s = Settings.builder()
            .maxSensors(16)
            .renameRequiresOfficer(true)
            .intervalTicks(100)
            .build();
        assertEquals(
            s,
            s.toBuilder()
                .build());
        assertEquals(
            s.hashCode(),
            s.toBuilder()
                .build()
                .hashCode());
        assertNotEquals(Settings.DEFAULTS, s);
        assertNotEquals(
            s,
            s.toBuilder()
                .historyPersist(false)
                .build());
        assertTrue(
            s.toString()
                .contains("limits.maxSensors=16"),
            s.toString());
    }

    /** The [pure] rule (design-v0.2 §2), checked over the source files of the pure classes this ticket adds. */
    @Test
    void pureClassesImportNoGameClasses() throws IOException {
        Path root = Paths.get("src/main/java/io/github/ldogg123/gregscope");
        List<Path> pure = Arrays.asList(
            root.resolve("config/Settings.java"),
            root.resolve("config/ConfigKeys.java"),
            root.resolve("LifecyclePhase.java"));
        Set<String> forbidden = new HashSet<>(
            Arrays.asList(
                "net.minecraft",
                "net.minecraftforge",
                "cpw.",
                "gregtech.",
                "li.cil.",
                "com.gtnewhorizon.",
                "com.gtnewhorizons.",
                "com.cleanroommc."));
        for (Path file : pure) {
            assertTrue(Files.isRegularFile(file), "missing " + file.toAbsolutePath());
            for (String line : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String prefix : forbidden) {
                    assertFalse(
                        trimmed.replace("import static ", "import ")
                            .startsWith("import " + prefix),
                        file + ": " + trimmed);
                }
            }
        }
    }

    private static int intValue(Settings s, String path) {
        switch (path) {
            case "sampling.tickBudgetMicros":
                return s.tickBudgetMicros();
            case "limits.maxSensors":
                return s.maxSensors();
            case "limits.maxSensorsPerTeam":
                return s.maxSensorsPerTeam();
            case "limits.maxOpenHubViews":
                return s.maxOpenHubViews();
            case "history.removedRetentionHours":
                return s.removedRetentionHours();
            case "history.staleExpiryDays":
                return s.staleExpiryDays();
            case "history.ioQueueCapacity":
                return s.ioQueueCapacity();
            case "permissions.opLevel":
                return s.opLevel();
            case "hub.renameCooldownSeconds":
                return s.renameCooldownSeconds();
            default:
                throw new IllegalArgumentException(path);
        }
    }
}
