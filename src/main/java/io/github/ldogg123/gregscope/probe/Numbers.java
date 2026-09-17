package io.github.ldogg123.gregscope.probe;

import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/** Defensive numeric parsing and saturating arithmetic. */
public final class Numbers {

    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    private Numbers() {}

    /** Parses a base-10 long; returns null for null, blank, malformed or out-of-range input. */
    @Nullable
    public static Long parseLong(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses a plain decimal number; returns null for malformed input, NaN and infinities. */
    @Nullable
    public static Double parseFiniteDouble(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!DECIMAL.matcher(trimmed)
            .matches()) {
            return null;
        }
        try {
            double value = Double.parseDouble(trimmed);
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static long longOrDefault(@Nullable Map<String, String> map, String key, long def) {
        Long value = map == null ? null : parseLong(map.get(key));
        return value == null ? def : value;
    }

    public static double doubleOrDefault(@Nullable Map<String, String> map, String key, double def) {
        Double value = map == null ? null : parseFiniteDouble(map.get(key));
        return value == null ? def : value;
    }

    /** Adds two longs, clamping to {@link Long#MIN_VALUE}/{@link Long#MAX_VALUE} instead of overflowing. */
    public static long saturatingAdd(long a, long b) {
        long sum = a + b;
        if (((a ^ sum) & (b ^ sum)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return sum;
    }

    /**
     * EU/t of a generator in snapshot sign convention (negative = generation): {@code -(eut * efficiency / 10000)} per
     * ampere, times {@code max(1, amperes)}. Intermediate overflow saturates instead of wrapping. Intended for
     * {@code eut > 0}.
     */
    public static long generatorEuPerTick(long eut, int efficiency, long amperes) {
        long perAmp;
        try {
            perAmp = Math.multiplyExact(eut, (long) efficiency) / 10000L;
        } catch (ArithmeticException e) {
            try {
                perAmp = Math.multiplyExact(eut / 10000L, (long) efficiency);
            } catch (ArithmeticException e2) {
                perAmp = (eut < 0) == (efficiency < 0) ? Long.MAX_VALUE : Long.MIN_VALUE;
            }
        }
        long total;
        try {
            total = Math.multiplyExact(perAmp, Math.max(1L, amperes));
        } catch (ArithmeticException e) {
            total = perAmp < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return total == Long.MIN_VALUE ? Long.MAX_VALUE : -total;
    }
}
