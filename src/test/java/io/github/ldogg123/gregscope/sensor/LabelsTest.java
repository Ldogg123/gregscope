package io.github.ldogg123.gregscope.sensor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LabelsTest {

    private static final String GRIN = new String(Character.toChars(0x1F600)); // 2 UTF-16 units

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(s);
        }
        return b.toString();
    }

    static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of("null", null, ""),
            Arguments.of("empty", "", ""),
            Arguments.of("plain", "EBF North", "EBF North"),
            Arguments.of("section codes", "\u00A7aEBF \u00A7lNorth\u00A7r", "EBF North"),
            Arguments.of("section with any next char", "\u00A7zA\u00A7 B", "AB"),
            Arguments.of("lone trailing section", "EBF\u00A7", "EBF"),
            Arguments.of("double section", "\u00A7\u00A7a", "a"),
            Arguments.of("section before surrogate pair", "\u00A7" + GRIN + "x", "x"),
            Arguments.of("C0 and C1 controls", "a\u0000b\u0007c\u007Fd\u0085e", "abcde"),
            Arguments.of("tab and newline are controls, removed", "a\tb\nc\r\nd", "abcd"),
            Arguments.of("BOM", "\uFEFFEBF\uFEFF", "EBF"),
            Arguments.of("line and paragraph separators", "a\u2028b\u2029c", "abc"),
            Arguments.of("format chars (ZWJ, RLO, soft hyphen)", "a\u200Db\u202Ec\u00ADd", "abcd"),
            Arguments.of(
                "private use BMP and supplementary",
                "a\uE000b" + new String(Character.toChars(0xF0000)) + "c",
                "abc"),
            Arguments.of("lone high surrogate", "a\uD800b", "ab"),
            Arguments.of("lone low surrogate", "\uDC00ab", "ab"),
            Arguments.of("reversed surrogate pair", "a\uDE00\uD83Db", "ab"),
            Arguments.of("valid surrogate pair kept", "EBF " + GRIN, "EBF " + GRIN),
            Arguments.of("whitespace collapsed and trimmed", "   Macerator    line  2  ", "Macerator line 2"),
            Arguments.of("NBSP and ideographic space collapse", "a\u00A0\u00A0b\u3000c", "a b c"),
            Arguments.of("space left by a removed control collapses", "a \u0000 b", "a b"),
            Arguments.of("truncated to 32 code points", repeat("x", 40), repeat("x", 32)),
            Arguments.of("exactly 32 kept", repeat("y", 32), repeat("y", 32)),
            Arguments.of("surrogate pair ending at the cap", repeat("a", 31) + GRIN + "b", repeat("a", 31) + GRIN),
            Arguments.of("32 surrogate pairs are 32 code points", repeat(GRIN, 33), repeat(GRIN, 32)),
            Arguments.of("trailing space exposed by the cut", repeat("a", 31) + " bcd", repeat("a", 31)),
            Arguments.of(
                "section codes do not count toward the cap",
                repeat("\u00A7a", 10) + repeat("z", 32),
                repeat("z", 32)),
            Arguments.of(
                "non-Latin text kept",
                "\u9AD8\u7089 \u0414\u043E\u043C\u043D\u0430",
                "\u9AD8\u7089 \u0414\u043E\u043C\u043D\u0430"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void sanitize(String name, String raw, String expected) {
        String clean = Labels.sanitize(raw);
        assertEquals(expected, clean);
        assertTrue(clean.codePointCount(0, clean.length()) <= Labels.MAX_CODE_POINTS);
        assertEquals(clean, Labels.sanitize(clean), "idempotent");
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < clean.length() && Character.isLowSurrogate(clean.charAt(i + 1)), "split pair");
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(c), "lone low surrogate");
            }
        }
    }

    @Test
    void inputOver64UnitsIsRejectedBeforeSanitizing() {
        assertTrue(Labels.acceptsInput(""));
        assertTrue(Labels.acceptsInput(repeat("a", 64)));
        assertFalse(Labels.acceptsInput(repeat("a", 65)));
        assertFalse(Labels.acceptsInput(null));
        // Units, not code points: 32 pairs are 64 units, 33 pairs are 66.
        assertTrue(Labels.acceptsInput(repeat(GRIN, 32)));
        assertFalse(Labels.acceptsInput(repeat(GRIN, 33)));
        // Rejection is on the raw length, even if sanitizing would shorten it.
        assertFalse(Labels.acceptsInput(repeat("\u00A7a", 33)));
    }

    @Test
    void shortIdIsFirstEightHexDigits() {
        UUID id = UUID.fromString("3fa2c1d0-1234-4abc-8def-0123456789ab");
        assertEquals("3fa2c1d0", Labels.shortId(id));
    }

    @Test
    void displayNameFallbacks() {
        UUID id = UUID.fromString("3fa2c1d0-1234-4abc-8def-0123456789ab");
        assertEquals(
            "EBF North",
            Labels.displayName("EBF North", "Electric Blast Furnace", "multimachine.blastfurnace", id));
        assertEquals("Electric Blast Furnace #3fa2c1d0", Labels.displayName("", "Electric Blast Furnace", "meta", id));
        assertEquals(
            "Electric Blast Furnace #3fa2c1d0",
            Labels.displayName("\u00A7a ", "Electric Blast Furnace", "meta", id));
        assertEquals(
            "multimachine.blastfurnace #3fa2c1d0",
            Labels.displayName(null, null, "multimachine.blastfurnace", id));
        assertEquals(
            "multimachine.blastfurnace #3fa2c1d0",
            Labels.displayName(null, "  ", "multimachine.blastfurnace", id));
        assertEquals("#3fa2c1d0", Labels.displayName(null, null, null, id));
    }
}
