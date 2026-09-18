package io.github.ldogg123.gregscope.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.sensor.Labels;

/**
 * GS-111 (design-v0.2 §11, §14): the parsing, prefix, paging and text rules of {@code /gregscope}, on a plain JVM.
 */
class CommandArgsTest {

    private static final UUID A = UUID.fromString("3fa2c1d0-0000-4000-8000-000000000001");
    private static final UUID B = UUID.fromString("3fa2c1d0-0000-4000-8000-000000000002");
    private static final UUID C = UUID.fromString("ffffffff-0000-4000-8000-000000000003");

    // --- subcommands ---

    @Test
    void noArgumentsIsNoSubcommand() {
        assertEquals(
            CommandArgs.Error.NO_SUBCOMMAND,
            CommandArgs.parse(new String[0])
                .error());
        assertEquals(
            CommandArgs.Error.NO_SUBCOMMAND,
            CommandArgs.parse(null)
                .error());
        assertEquals(
            CommandArgs.Error.NO_SUBCOMMAND,
            CommandArgs.parse(new String[] { "" })
                .error());
        assertEquals(
            null,
            CommandArgs.parse(new String[0])
                .sub());
    }

    @Test
    void unknownSubcommandKeepsTheWordForTheMessage() {
        CommandArgs.Parsed parsed = CommandArgs.parse(new String[] { "explode", "everything" });
        assertEquals(CommandArgs.Error.UNKNOWN_SUBCOMMAND, parsed.error());
        assertEquals("explode", parsed.offending());
        assertFalse(parsed.ok());
    }

    @Test
    void subcommandsAreCaseInsensitive() {
        assertEquals(
            CommandArgs.Sub.STATS,
            CommandArgs.parse(new String[] { "STATS" })
                .sub());
        assertEquals(
            CommandArgs.Sub.LIST,
            CommandArgs.parse(new String[] { "List" })
                .sub());
        assertEquals(
            CommandArgs.Sub.PURGE,
            CommandArgs.parse(new String[] { "pUrGe", "--STALE" })
                .sub());
    }

    @Test
    void statsTakesNoArguments() {
        CommandArgs.Parsed parsed = CommandArgs.parse(new String[] { "stats" });
        assertTrue(parsed.ok());
        assertEquals(CommandArgs.Sub.STATS, parsed.sub());
        // Extra words are ignored rather than refused: "stats" has nothing to disambiguate.
        assertTrue(
            CommandArgs.parse(new String[] { "stats", "please" })
                .ok());
    }

    // --- list ---

    @Test
    void listDefaultsToEverythingOnPageOne() {
        CommandArgs.Parsed parsed = CommandArgs.parse(new String[] { "list" });
        assertTrue(parsed.ok());
        assertEquals(CommandArgs.Filter.ALL, parsed.filter());
        assertEquals(1, parsed.page());
    }

    @Test
    void listAcceptsFilterAndPageInEitherOrderOrAlone() {
        assertEquals(
            CommandArgs.Filter.LIVE,
            CommandArgs.parse(new String[] { "list", "live" })
                .filter());
        assertEquals(
            3,
            CommandArgs.parse(new String[] { "list", "3" })
                .page());
        CommandArgs.Parsed both = CommandArgs.parse(new String[] { "list", "stale", "2" });
        assertEquals(CommandArgs.Filter.STALE, both.filter());
        assertEquals(2, both.page());
        CommandArgs.Parsed swapped = CommandArgs.parse(new String[] { "list", "2", "stale" });
        assertEquals(CommandArgs.Filter.STALE, swapped.filter());
        assertEquals(2, swapped.page());
    }

    @Test
    void listKnowsExactlyTheFiltersOfSection11() {
        for (String id : Arrays.asList("all", "live", "unloaded", "missing", "tombstones", "stale")) {
            assertTrue(
                CommandArgs.parse(new String[] { "list", id })
                    .ok(),
                id);
            assertEquals(
                id,
                CommandArgs.Filter.byId(id.toUpperCase(java.util.Locale.ROOT))
                    .id());
        }
        assertEquals(null, CommandArgs.Filter.byId("dead"));
        assertEquals(null, CommandArgs.Filter.byId(null));
    }

    @Test
    void listRejectsBadFiltersAndBadPages() {
        CommandArgs.Parsed filter = CommandArgs.parse(new String[] { "list", "dead" });
        assertEquals(CommandArgs.Error.BAD_FILTER, filter.error());
        assertEquals("dead", filter.offending());
        assertEquals(
            CommandArgs.Error.BAD_PAGE,
            CommandArgs.parse(new String[] { "list", "0" })
                .error());
        assertEquals(
            CommandArgs.Error.BAD_PAGE,
            CommandArgs.parse(new String[] { "list", "99999999999" })
                .error());
        assertEquals(
            CommandArgs.Error.BAD_PAGE,
            CommandArgs.parse(new String[] { "list", "1", "2" })
                .error());
        assertEquals(
            CommandArgs.Error.BAD_FILTER,
            CommandArgs.parse(new String[] { "list", "live", "live" })
                .error());
        assertEquals(
            CommandArgs.Error.BAD_FILTER,
            CommandArgs.parse(new String[] { "list", "-1" })
                .error());
    }

    // --- info ---

    @Test
    void infoNeedsAtLeastFourCharactersOfId() {
        assertEquals(
            CommandArgs.Error.MISSING_ID,
            CommandArgs.parse(new String[] { "info" })
                .error());
        assertEquals(
            CommandArgs.Error.PREFIX_TOO_SHORT,
            CommandArgs.parse(new String[] { "info", "3fa" })
                .error());
        // Dashes do not count towards the four characters.
        assertEquals(
            CommandArgs.Error.PREFIX_TOO_SHORT,
            CommandArgs.parse(new String[] { "info", "3f-a" })
                .error());
        CommandArgs.Parsed ok = CommandArgs.parse(new String[] { "info", "3fa2" });
        assertTrue(ok.ok());
        assertEquals("3fa2", ok.idPrefix());
    }

    // --- label ---

    @Test
    void labelJoinsTheRestOfTheLine() {
        CommandArgs.Parsed parsed = CommandArgs.parse(new String[] { "label", "3fa2c1d0", "EBF", "North", "2" });
        assertTrue(parsed.ok());
        assertEquals("3fa2c1d0", parsed.idPrefix());
        assertEquals("EBF North 2", parsed.label());
    }

    @Test
    void labelWithoutTextClearsTheLabel() {
        CommandArgs.Parsed parsed = CommandArgs.parse(new String[] { "label", "3fa2c1d0" });
        assertTrue(parsed.ok());
        assertEquals("", parsed.label());
    }

    @Test
    void labelNeedsAnIdAndRefusesOversizeText() {
        assertEquals(
            CommandArgs.Error.MISSING_ID,
            CommandArgs.parse(new String[] { "label" })
                .error());
        assertEquals(
            CommandArgs.Error.PREFIX_TOO_SHORT,
            CommandArgs.parse(new String[] { "label", "3f" })
                .error());
        StringBuilder text = new StringBuilder();
        while (text.length() <= Labels.MAX_INPUT_UNITS) {
            text.append('x');
        }
        assertFalse(Labels.acceptsInput(text.toString()));
        CommandArgs.Parsed parsed = CommandArgs.parse(new String[] { "label", "3fa2c1d0", text.toString() });
        assertEquals(CommandArgs.Error.LABEL_TOO_LONG, parsed.error());
        // Exactly at the limit is still accepted; section 3.5 truncates to 32 code points when sanitizing.
        String atLimit = text.substring(0, Labels.MAX_INPUT_UNITS);
        assertTrue(
            CommandArgs.parse(new String[] { "label", "3fa2c1d0", atLimit })
                .ok());
    }

    // --- purge ---

    @Test
    void purgeTakesAnIdOrOneOfTwoFlags() {
        assertEquals(
            CommandArgs.Error.MISSING_ID,
            CommandArgs.parse(new String[] { "purge" })
                .error());
        CommandArgs.Parsed one = CommandArgs.parse(new String[] { "purge", "3fa2c1d0" });
        assertEquals(CommandArgs.PurgeTarget.ONE, one.purgeTarget());
        assertEquals("3fa2c1d0", one.idPrefix());
        assertEquals(
            CommandArgs.PurgeTarget.TOMBSTONES,
            CommandArgs.parse(new String[] { "purge", "--tombstones" })
                .purgeTarget());
        assertEquals(
            CommandArgs.PurgeTarget.STALE,
            CommandArgs.parse(new String[] { "purge", "--stale" })
                .purgeTarget());
        assertEquals(
            CommandArgs.Error.PREFIX_TOO_SHORT,
            CommandArgs.parse(new String[] { "purge", "3fa" })
                .error());
        assertEquals(
            null,
            CommandArgs.parse(new String[] { "info", "3fa2" })
                .purgeTarget());
    }

    // --- id prefixes ---

    @Test
    void prefixMatchingIgnoresDashesAndCase() {
        assertTrue(CommandArgs.matchesPrefix(A, "3fa2"));
        assertTrue(CommandArgs.matchesPrefix(A, "3FA2C1D0"));
        assertTrue(CommandArgs.matchesPrefix(A, "3fa2-c1d0"));
        assertTrue(CommandArgs.matchesPrefix(A, A.toString()));
        assertTrue(
            CommandArgs.matchesPrefix(
                A,
                A.toString()
                    .replace("-", "")));
        assertFalse(CommandArgs.matchesPrefix(A, "ffff"));
        assertFalse(CommandArgs.matchesPrefix(A, ""));
        assertFalse(CommandArgs.matchesPrefix(A, null));
        assertFalse(CommandArgs.matchesPrefix(null, "3fa2"));
    }

    @Test
    void ambiguousPrefixReturnsUpToTheLimit() {
        List<UUID> ids = Arrays.asList(A, B, C);
        assertEquals(Arrays.asList(A, B), CommandArgs.matches(ids, "3fa2", 5));
        assertEquals(Arrays.asList(A), CommandArgs.matches(ids, "3fa2", 1));
        assertEquals(new ArrayList<UUID>(), CommandArgs.matches(ids, "3fa2", 0));
        assertEquals(new ArrayList<UUID>(), CommandArgs.matches(null, "3fa2", 5));
        assertEquals(Arrays.asList(A), CommandArgs.matches(ids, A.toString(), CommandArgs.MAX_MATCHES));
        assertEquals(new ArrayList<UUID>(), CommandArgs.matches(ids, "0000", CommandArgs.MAX_MATCHES));
    }

    // --- paging ---

    @Test
    void pagingIsTenPerPageAndClamps() {
        assertEquals(10, CommandArgs.PAGE_SIZE);
        assertEquals(1, CommandArgs.pageCount(0));
        assertEquals(1, CommandArgs.pageCount(10));
        assertEquals(2, CommandArgs.pageCount(11));
        assertEquals(3, CommandArgs.pageCount(25));
        assertEquals(1, CommandArgs.clampPage(-5, 25));
        assertEquals(3, CommandArgs.clampPage(999, 25));
        assertEquals(2, CommandArgs.clampPage(2, 25));
        assertEquals(0, CommandArgs.firstIndex(1, 25));
        assertEquals(10, CommandArgs.firstIndex(2, 25));
        assertEquals(20, CommandArgs.firstIndex(99, 25));
        assertEquals(0, CommandArgs.firstIndex(1, 0));
    }

    // --- the "stale" rule ---

    @Test
    void staleIsUnloadedForMoreThanSevenDays() {
        assertEquals(7, CommandArgs.STALE_DAYS);
        long now = 1_000_000L;
        assertFalse(CommandArgs.isStale(false, now - 100 * 86_400L, now), "a LIVE sensor is never stale");
        assertFalse(CommandArgs.isStale(true, now - CommandArgs.STALE_SECONDS, now), "exactly 7 days is not yet stale");
        assertTrue(CommandArgs.isStale(true, now - CommandArgs.STALE_SECONDS - 1L, now));
    }

    // --- text helpers ---

    @Test
    void ageIsShortAndAscii() {
        long now = 10_000_000L;
        assertEquals("never", CommandArgs.age(0L, now));
        assertEquals("now", CommandArgs.age(now + 5L, now));
        assertEquals("now", CommandArgs.age(now, now));
        assertEquals("1s", CommandArgs.age(now - 1L, now));
        assertEquals("30s", CommandArgs.age(now - 30L, now));
        assertEquals("5m", CommandArgs.age(now - 5 * 60L, now));
        assertEquals("3h", CommandArgs.age(now - 3 * 3600L, now));
        assertEquals("2d", CommandArgs.age(now - 2 * 86_400L, now));
    }

    @Test
    void bytesAreHumanReadable() {
        assertEquals("0 B", CommandArgs.bytes(0L));
        assertEquals("1023 B", CommandArgs.bytes(1023L));
        assertEquals("1.0 KB", CommandArgs.bytes(1024L));
        assertEquals("1.0 MB", CommandArgs.bytes(1024L * 1024L));
        assertEquals("26.5 MB", CommandArgs.bytes(27_787_264L));
        assertEquals("1.0 GB", CommandArgs.bytes(1024L * 1024L * 1024L));
        assertEquals("?", CommandArgs.bytes(-1L));
    }

    @Test
    void percentClampsAndShowsNoDataAsADash() {
        assertEquals("0%", CommandArgs.percent(0.0));
        assertEquals("50%", CommandArgs.percent(0.5));
        assertEquals("100%", CommandArgs.percent(1.0));
        assertEquals("100%", CommandArgs.percent(1.5));
        assertEquals("-", CommandArgs.percent(Double.NaN));
        assertEquals("-", CommandArgs.percent(-1.0));
    }

    @Test
    void normalizeStripsDashesAndLowercases() {
        assertEquals("", CommandArgs.normalize(null));
        assertEquals("", CommandArgs.normalize("---"));
        assertEquals("3fa2c1d0", CommandArgs.normalize("3FA2-C1D0"));
    }
}
