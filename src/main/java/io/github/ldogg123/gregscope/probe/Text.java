package io.github.ldogg123.gregscope.probe;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

/** Plain-text helpers for display strings. */
public final class Text {

    private static final Pattern FORMATTING = Pattern.compile("\u00A7[0-9a-fk-orA-FK-OR]");

    private Text() {}

    /** Removes Minecraft formatting codes ({@code §} followed by one code character). */
    @Nullable
    public static String stripFormatting(@Nullable String text) {
        if (text == null) {
            return null;
        }
        return FORMATTING.matcher(text)
            .replaceAll("");
    }

    /** Returns the first argument that is non-null and non-empty, or null. */
    @Nullable
    public static String firstNonEmpty(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return null;
    }
}
