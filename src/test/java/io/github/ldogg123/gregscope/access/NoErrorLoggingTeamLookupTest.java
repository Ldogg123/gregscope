package io.github.ldogg123.gregscope.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Errata E4 as a source grep over everything shipped from {@code src/main} (Java sources and resources, comments
 * included): GTNHLib 0.11.46 {@code TeamManager.getTeamByPlayer} logs an ERROR for every player without a team, and
 * {@code TeamManager.getOrCreateTeam} calls it first, so neither name may appear. Team lookups go through the map scan
 * in {@link GtnhlibTeamResolver}, the only class allowed to name the GTNHLib teams package. Team IDs are never read, so
 * none can be stored.
 */
class NoErrorLoggingTeamLookupTest {

    private static final Path MAIN = Paths.get("src/main");
    private static final String RESOLVER = "java/io/github/ldogg123/gregscope/access/GtnhlibTeamResolver.java";

    /** Names that must not appear anywhere in src/main. */
    static final List<String> FORBIDDEN = Arrays.asList("getTeamByPlayer", "getOrCreateTeam", "getTeamId");
    static final String TEAMS_PACKAGE = "gtnhlib.teams";

    @Test
    void noErrorLoggingLookupOrTeamIdAnywhereInMain() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> files = files();
        assertTrue(files.size() > 50, "scan found only " + files.size() + " files under " + MAIN.toAbsolutePath());
        for (Path file : files) {
            violations.addAll(forbiddenNames(MAIN.relativize(file) + "", read(file)));
        }
        assertEquals(new ArrayList<String>(), violations, "error-logging team lookups or team IDs in src/main");
    }

    @Test
    void onlyTheResolverNamesGtnhlibTeams() throws IOException {
        List<String> users = new ArrayList<>();
        for (Path file : files()) {
            if (read(file).contains(TEAMS_PACKAGE)) {
                users.add(
                    MAIN.relativize(file)
                        .toString()
                        .replace('\\', '/'));
            }
        }
        assertEquals(Arrays.asList(RESOLVER), users, "files naming " + TEAMS_PACKAGE);
    }

    @Test
    void resolverUsesTheMapScan() throws IOException {
        String source = read(MAIN.resolve(RESOLVER));
        assertTrue(source.contains("TeamManager.getTeamMap()"), "resolver does not scan getTeamMap()");
        assertTrue(source.contains("team.isMember(player)"), "resolver does not test membership per team");
    }

    /** Negative control: the scanner must find the names in code, comments and strings, one entry per name. */
    @Test
    void scannerCatchesViolations() {
        String bad = "class Y {\n" + "    Team t = TeamManager.getTeamByPlayer(uuid);\n"
            + "    // TeamManager.getOrCreateTeam(name, uuid) in a comment\n"
            + "    String s = \"getTeamId\";\n"
            + "}\n";
        List<String> found = forbiddenNames("Y.java", bad);
        assertEquals(3, found.size(), found.toString());
        assertTrue(forbiddenNames("Z.java", "class Z { Object m = TeamManager.getTeamMap(); }\n").isEmpty());
    }

    static List<String> forbiddenNames(String name, String source) {
        List<String> out = new ArrayList<>();
        String[] lines = source.split("\n");
        for (int i = 0; i < lines.length; i++) {
            for (String forbidden : FORBIDDEN) {
                if (lines[i].contains(forbidden)) {
                    out.add(name + ":" + (i + 1) + ": " + lines[i].trim());
                }
            }
        }
        return out;
    }

    private static List<Path> files() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(Files::isRegularFile)
                .filter(
                    p -> !p.toString()
                        .endsWith(".png"))
                .collect(Collectors.toList());
        }
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }
}
