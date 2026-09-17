package io.github.ldogg123.gregscope.sensor;

import java.util.UUID;

/**
 * Sensor labels (design-v0.2 §3.5). [pure]
 *
 * <p>
 * {@link #sanitize} is applied to every label that is stored or read back, and is idempotent. Raw input from a client
 * or a command is first checked with {@link #acceptsInput}, because MUI2 accepts strings of up to 32,767 units.
 */
public final class Labels {

    /** Longest stored label, in code points. */
    public static final int MAX_CODE_POINTS = 32;
    /** Longest raw input accepted before sanitizing, in UTF-16 units. */
    public static final int MAX_INPUT_UNITS = 64;
    /** Hex digits of a short id. */
    public static final int SHORT_ID_LENGTH = 8;

    private static final char SECTION = '\u00A7';

    private Labels() {}

    /** False for {@code null} or input longer than {@link #MAX_INPUT_UNITS} UTF-16 units: reject, do not sanitize. */
    public static boolean acceptsInput(String raw) {
        return raw != null && raw.length() <= MAX_INPUT_UNITS;
    }

    /**
     * <ol>
     * <li>Strips {@code §x} pairs (a {@code §} and the code point after it) and a lone trailing {@code §}.</li>
     * <li>Removes ISO control characters, format (Cf), private-use (Co) and lone surrogate (Cs) code points, U+2028,
     * U+2029 and U+FEFF.</li>
     * <li>Collapses each run of whitespace ({@link Character#isWhitespace} or {@link Character#isSpaceChar}) to one
     * U+0020 and trims.</li>
     * <li>Truncates to {@link #MAX_CODE_POINTS} code points (a surrogate pair is one code point, so it is never split)
     * and trims a trailing space the cut exposed.</li>
     * <li>{@code null} becomes {@code ""}.</li>
     * </ol>
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == SECTION) {
                if (i < raw.length()) {
                    i += Character.charCount(raw.codePointAt(i));
                }
                continue;
            }
            if (isRemoved(cp)) {
                continue;
            }
            kept.appendCodePoint(cp);
        }
        StringBuilder out = new StringBuilder(kept.length());
        int codePoints = 0;
        boolean pendingSpace = false;
        int j = 0;
        while (j < kept.length() && codePoints < MAX_CODE_POINTS) {
            int cp = kept.codePointAt(j);
            j += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                codePoints++;
                pendingSpace = false;
                if (codePoints == MAX_CODE_POINTS) {
                    break;
                }
            }
            out.appendCodePoint(cp);
            codePoints++;
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') {
            end--;
        }
        return out.substring(0, end);
    }

    private static boolean isRemoved(int cp) {
        if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp == 0xFEFF) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.SURROGATE;
    }

    /** The first 8 hex digits of the UUID (lower case). */
    public static String shortId(UUID id) {
        return id.toString()
            .substring(0, SHORT_ID_LENGTH);
    }

    /**
     * The display name (§3.5): the label if it is not empty; otherwise the last snapshot name, else the meta name,
     * followed by {@code " #" + shortId}; with neither, {@code "#" + shortId}. The fallback names are sanitized too.
     */
    public static String displayName(String label, String snapshotName, String metaName, UUID id) {
        String clean = sanitize(label);
        if (!clean.isEmpty()) {
            return clean;
        }
        String base = sanitize(snapshotName);
        if (base.isEmpty()) {
            base = sanitize(metaName);
        }
        return base.isEmpty() ? "#" + shortId(id) : base + " #" + shortId(id);
    }
}
