package io.github.ldogg123.gregscope.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.github.ldogg123.gregscope.sensor.Labels;

/**
 * Everything {@code /gregscope} can decide without the game: argument parsing, id-prefix matching, paging and the
 * small text helpers its output needs (design-v0.2 section 11). [pure]
 *
 * <p>
 * {@link GregScopeCommand} is the Minecraft adapter around this class: it turns an {@code ICommandSender} into a
 * {@code Viewer}, asks {@code AccessPolicy}, reads the registry and sends chat components. Nothing here knows about
 * any of that, so every parsing rule of section 11 is a plain-JVM unit test.
 *
 * <p>
 * <b>Id prefixes.</b> A prefix is matched against the sensor UUID with its dashes removed, case-insensitively, so
 * both the eight-character short id the other surfaces print and a full UUID (with or without dashes) work. Section
 * 11 requires at least four characters and lists at most {@link #MAX_MATCHES} candidates for an ambiguous prefix.
 */
public final class CommandArgs {

    /** Section 11: {@code list} shows 10 sensors per page. */
    public static final int PAGE_SIZE = 10;
    /** Section 11: {@code info <idPrefix>=4}. The same minimum applies to {@code label} and {@code purge}. */
    public static final int MIN_PREFIX = 4;
    /** Section 11: an ambiguous prefix lists up to five matches. */
    public static final int MAX_MATCHES = 5;
    /** Section 11: {@code list stale} means UNLOADED for more than seven days. */
    public static final int STALE_DAYS = 7;
    /** {@link #STALE_DAYS} in seconds. */
    public static final long STALE_SECONDS = STALE_DAYS * 86_400L;

    private static final String FLAG_TOMBSTONES = "--tombstones";
    private static final String FLAG_STALE = "--stale";

    /** The subcommands of section 11. */
    public enum Sub {
        STATS,
        LIST,
        INFO,
        LABEL,
        PURGE
    }

    /** The {@code list} filters of section 11; {@link #ALL} is the default (no filter argument). */
    public enum Filter {

        ALL("all"),
        LIVE("live"),
        UNLOADED("unloaded"),
        MISSING("missing"),
        TOMBSTONES("tombstones"),
        STALE("stale");

        private final String id;

        Filter(String id) {
            this.id = id;
        }

        /** The word a player types, and the one the header echoes. */
        public String id() {
            return id;
        }

        /** The filter for {@code name}, or null if it is not one. Case-insensitive. */
        public static Filter byId(String name) {
            if (name == null) {
                return null;
            }
            String lower = lower(name);
            for (Filter filter : values()) {
                if (filter.id.equals(lower)) {
                    return filter;
                }
            }
            return null;
        }
    }

    /** What {@code purge} was asked to remove. */
    public enum PurgeTarget {
        /** {@code purge <idPrefix>}: the one sensor the prefix resolves to. */
        ONE,
        /** {@code purge --tombstones}: every MISSING, IN_ITEM and REMOVED entry. */
        TOMBSTONES,
        /** {@code purge --stale}: every UNLOADED entry not seen for {@link #STALE_DAYS} days. */
        STALE
    }

    /** Why a command line could not be understood; {@link #NONE} means it was. */
    public enum Error {
        NONE,
        NO_SUBCOMMAND,
        UNKNOWN_SUBCOMMAND,
        MISSING_ID,
        PREFIX_TOO_SHORT,
        BAD_PAGE,
        BAD_FILTER,
        LABEL_TOO_LONG
    }

    private CommandArgs() {}

    /** One parsed command line. Immutable; fields that the subcommand does not use keep their defaults. */
    public static final class Parsed {

        private final Sub sub;
        private final Error error;
        private final String offending;
        private final Filter filter;
        private final int page;
        private final String idPrefix;
        private final String label;
        private final PurgeTarget purgeTarget;

        private Parsed(Sub sub, Error error, String offending, Filter filter, int page, String idPrefix, String label,
            PurgeTarget purgeTarget) {
            this.sub = sub;
            this.error = error;
            this.offending = offending;
            this.filter = filter;
            this.page = page;
            this.idPrefix = idPrefix;
            this.label = label;
            this.purgeTarget = purgeTarget;
        }

        /** The subcommand, or null when the line named none or named an unknown one. */
        public Sub sub() {
            return sub;
        }

        public Error error() {
            return error;
        }

        public boolean ok() {
            return error == Error.NONE;
        }

        /** The argument the error is about (an unknown subcommand, a bad page or filter), or "". */
        public String offending() {
            return offending;
        }

        /** {@code list}: never null, {@link Filter#ALL} without a filter argument. */
        public Filter filter() {
            return filter;
        }

        /** {@code list}: the requested page, 1-based; 1 without a page argument. */
        public int page() {
            return page;
        }

        /** {@code info}, {@code label}, {@code purge <idPrefix>}: the prefix as typed, or "". */
        public String idPrefix() {
            return idPrefix;
        }

        /** {@code label}: the raw text, joined with single spaces. Empty means "clear the label". */
        public String label() {
            return label;
        }

        /** {@code purge}: what to remove; null for every other subcommand. */
        public PurgeTarget purgeTarget() {
            return purgeTarget;
        }

        @Override
        public String toString() {
            return "Parsed{" + sub
                + ", error="
                + error
                + ", filter="
                + filter
                + ", page="
                + page
                + ", idPrefix='"
                + idPrefix
                + "', label='"
                + label
                + "', purge="
                + purgeTarget
                + "}";
        }
    }

    /**
     * Parses the argument array Minecraft hands a command (the subcommand is {@code args[0]}).
     *
     * <p>
     * {@code list} accepts its filter and its page in either order and either alone, because {@code /gregscope list 2}
     * is the natural way to ask for the second page of everything. A token that is neither a filter name nor a
     * positive integer is a {@link Error#BAD_FILTER}.
     */
    public static Parsed parse(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isEmpty()) {
            return error(null, Error.NO_SUBCOMMAND, "");
        }
        String name = lower(args[0]);
        if ("stats".equals(name)) {
            return new Parsed(Sub.STATS, Error.NONE, "", Filter.ALL, 1, "", "", null);
        }
        if ("list".equals(name)) {
            return parseList(args);
        }
        if ("info".equals(name)) {
            return parseInfo(args);
        }
        if ("label".equals(name)) {
            return parseLabel(args);
        }
        if ("purge".equals(name)) {
            return parsePurge(args);
        }
        return error(null, Error.UNKNOWN_SUBCOMMAND, args[0]);
    }

    private static Parsed parseList(String[] args) {
        Filter filter = Filter.ALL;
        int page = 1;
        boolean sawFilter = false;
        boolean sawPage = false;
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isEmpty()) {
                continue;
            }
            int number = positiveInt(token);
            if (number > 0) {
                if (sawPage) {
                    return error(Sub.LIST, Error.BAD_PAGE, token);
                }
                page = number;
                sawPage = true;
                continue;
            }
            if (isAllDigits(token)) {
                // "0" or a number too large to be a page: a page argument, but not a usable one.
                return error(Sub.LIST, Error.BAD_PAGE, token);
            }
            Filter named = Filter.byId(token);
            if (named == null || sawFilter) {
                return error(Sub.LIST, Error.BAD_FILTER, token);
            }
            filter = named;
            sawFilter = true;
        }
        return new Parsed(Sub.LIST, Error.NONE, "", filter, page, "", "", null);
    }

    private static Parsed parseInfo(String[] args) {
        if (args.length < 2 || args[1] == null || args[1].isEmpty()) {
            return error(Sub.INFO, Error.MISSING_ID, "");
        }
        String prefix = args[1];
        if (normalize(prefix).length() < MIN_PREFIX) {
            return error(Sub.INFO, Error.PREFIX_TOO_SHORT, prefix);
        }
        return new Parsed(Sub.INFO, Error.NONE, "", Filter.ALL, 1, prefix, "", null);
    }

    private static Parsed parseLabel(String[] args) {
        if (args.length < 2 || args[1] == null || args[1].isEmpty()) {
            return error(Sub.LABEL, Error.MISSING_ID, "");
        }
        String prefix = args[1];
        if (normalize(prefix).length() < MIN_PREFIX) {
            return error(Sub.LABEL, Error.PREFIX_TOO_SHORT, prefix);
        }
        StringBuilder text = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(args[i] == null ? "" : args[i]);
        }
        String raw = text.toString();
        // Section 3.5: anything longer than 64 UTF-16 units is refused before sanitizing, so a truncated label can
        // never look like the one that was typed.
        if (!Labels.acceptsInput(raw)) {
            return error(Sub.LABEL, Error.LABEL_TOO_LONG, prefix);
        }
        return new Parsed(Sub.LABEL, Error.NONE, "", Filter.ALL, 1, prefix, raw, null);
    }

    private static Parsed parsePurge(String[] args) {
        if (args.length < 2 || args[1] == null || args[1].isEmpty()) {
            return error(Sub.PURGE, Error.MISSING_ID, "");
        }
        String token = args[1];
        String lower = lower(token);
        if (FLAG_TOMBSTONES.equals(lower)) {
            return new Parsed(Sub.PURGE, Error.NONE, "", Filter.ALL, 1, "", "", PurgeTarget.TOMBSTONES);
        }
        if (FLAG_STALE.equals(lower)) {
            return new Parsed(Sub.PURGE, Error.NONE, "", Filter.ALL, 1, "", "", PurgeTarget.STALE);
        }
        if (normalize(token).length() < MIN_PREFIX) {
            return error(Sub.PURGE, Error.PREFIX_TOO_SHORT, token);
        }
        return new Parsed(Sub.PURGE, Error.NONE, "", Filter.ALL, 1, token, "", PurgeTarget.ONE);
    }

    private static Parsed error(Sub sub, Error error, String offending) {
        return new Parsed(sub, error, offending == null ? "" : offending, Filter.ALL, 1, "", "", null);
    }

    // --- id prefixes ---

    /** The prefix as it is compared: dashes removed, lower case. */
    public static String normalize(String prefix) {
        if (prefix == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(prefix.length());
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c != '-') {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /** True if {@code id} starts with {@code prefix} once both are normalized. An empty prefix matches nothing. */
    public static boolean matchesPrefix(UUID id, String prefix) {
        String normalized = normalize(prefix);
        if (id == null || normalized.isEmpty()) {
            return false;
        }
        return normalize(id.toString()).startsWith(normalized);
    }

    /**
     * Up to {@code limit} ids that {@code prefix} matches, in the order the collection yields them. The caller sorts
     * or scopes beforehand; {@code limit} is there so an ambiguous prefix costs nothing extra.
     */
    public static List<UUID> matches(Collection<UUID> ids, String prefix, int limit) {
        List<UUID> out = new ArrayList<>();
        if (ids == null || limit <= 0) {
            return out;
        }
        for (UUID id : ids) {
            if (matchesPrefix(id, prefix)) {
                out.add(id);
                if (out.size() >= limit) {
                    return out;
                }
            }
        }
        return out;
    }

    // --- paging ---

    /** How many pages {@code total} rows fill; at least 1, so an empty list still says "page 1/1". */
    public static int pageCount(int total) {
        if (total <= 0) {
            return 1;
        }
        return (total + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    /** The requested page clamped into {@code 1..pageCount(total)}. */
    public static int clampPage(int page, int total) {
        int pages = pageCount(total);
        if (page < 1) {
            return 1;
        }
        return page > pages ? pages : page;
    }

    /** The index of the first row of {@code page} (1-based page, 0-based index). */
    public static int firstIndex(int page, int total) {
        return (clampPage(page, total) - 1) * PAGE_SIZE;
    }

    // --- the section 11 "stale" rule ---

    /** Section 11: UNLOADED for more than {@link #STALE_DAYS} days, measured from the last time it was seen. */
    public static boolean isStale(boolean unloaded, long lastSeenEpochSec, long nowEpochSec) {
        return unloaded && nowEpochSec - lastSeenEpochSec > STALE_SECONDS;
    }

    // --- text helpers for the section 11 output ---

    /**
     * A short, ASCII age such as {@code "12s"}, {@code "5m"}, {@code "3h"} or {@code "2d"}; {@code "never"} for a
     * missing timestamp and {@code "now"} for a time in the future (a clock that went backwards).
     */
    public static String age(long thenEpochSec, long nowEpochSec) {
        if (thenEpochSec <= 0L) {
            return "never";
        }
        long seconds = nowEpochSec - thenEpochSec;
        if (seconds <= 0L) {
            return "now";
        }
        if (seconds < 60L) {
            return seconds + "s";
        }
        if (seconds < 3600L) {
            return seconds / 60L + "m";
        }
        if (seconds < 86_400L) {
            return seconds / 3600L + "h";
        }
        return seconds / 86_400L + "d";
    }

    /** A byte count as {@code "0 B"}, {@code "12.3 KB"}, {@code "1.2 MB"} or {@code "3.4 GB"} (1024-based). */
    public static String bytes(long value) {
        if (value < 0L) {
            return "?";
        }
        if (value < 1024L) {
            return value + " B";
        }
        String[] units = { "KB", "MB", "GB", "TB" };
        double scaled = value;
        int unit = -1;
        while (scaled >= 1024.0 && unit < units.length - 1) {
            scaled /= 1024.0;
            unit++;
        }
        long tenths = Math.round(scaled * 10.0);
        return tenths / 10 + "." + tenths % 10 + " " + units[unit];
    }

    /** A fraction in {@code 0..1} as whole percent, clamped; {@code -1} (no data) becomes {@code "-"}. */
    public static String percent(double fraction) {
        if (Double.isNaN(fraction) || fraction < 0.0) {
            return "-";
        }
        long whole = Math.round(fraction * 100.0);
        if (whole > 100L) {
            whole = 100L;
        }
        return whole + "%";
    }

    // --- small shared primitives ---

    /** {@code Locale.ROOT} lower case, without naming {@code java.util.Locale} rules per call site. */
    static String lower(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            out.append(Character.toLowerCase(text.charAt(i)));
        }
        return out.toString();
    }

    private static boolean isAllDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** The value of an all-digit token in {@code 1..Integer.MAX_VALUE}, or 0 for anything else. */
    private static int positiveInt(String text) {
        if (!isAllDigits(text) || text.length() > 9) {
            return 0;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            value = value * 10 + (text.charAt(i) - '0');
        }
        return value;
    }
}
