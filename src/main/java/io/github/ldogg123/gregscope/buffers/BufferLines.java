package io.github.ldogg123.gregscope.buffers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders a {@link BufferSet} as the {@code List<String>} a snapshot can carry. [pure]
 *
 * <p>
 * A snapshot value is a scalar or a list of strings - nested tables are deliberately not part of the contract, so
 * every consumer, OpenComputers included, can handle a snapshot without knowing about buffers. The encoding is
 * therefore one line per resource, and it is pinned here rather than left to a formatter somewhere in the UI:
 *
 * <pre>
 * f:water=4000/128000        a fluid, with the room it has
 * i:minecraft:cobblestone:0=17   an item; a slot reports no capacity, so none is written
 * other=250x6                the rollup: how much, across how many resources
 * me=2                       inputs served by an ME network
 * </pre>
 *
 * <p>
 * The capacity is omitted rather than written as {@code /0}, because "no capacity reported" and "a capacity of
 * zero" are different facts and a parser should not have to guess which one it is looking at.
 */
public final class BufferLines {

    /** Separates the resource key from its amount. */
    public static final char VALUE = '=';
    /** Separates the amount from the capacity, when there is one. */
    public static final char OF = '/';
    /** The rollup line's key. */
    public static final String OTHER = "other";
    /** The ME line's key. */
    public static final String ME = "me";

    private BufferLines() {}

    /**
     * One line per named resource, then the {@code other} rollup and the {@code me} count when they apply. Never
     * null; empty when the set has nothing to say.
     */
    public static List<String> of(BufferSet set) {
        if (set == null || set.isEmpty()) {
            return Collections.emptyList();
        }
        List<BufferReading> top = set.top();
        List<String> lines = new ArrayList<>(top.size() + 2);
        for (int i = 0; i < top.size(); i++) {
            BufferReading reading = top.get(i);
            StringBuilder line = new StringBuilder(reading.key()).append(VALUE)
                .append(reading.amount());
            if (reading.capacity() > BufferReading.UNKNOWN_CAPACITY) {
                line.append(OF)
                    .append(reading.capacity());
            }
            lines.add(line.toString());
        }
        if (set.otherCount() > 0) {
            lines.add(OTHER + VALUE + set.otherAmount() + "x" + set.otherCount());
        }
        if (set.meBacked() > 0) {
            lines.add(ME + VALUE + set.meBacked());
        }
        return Collections.unmodifiableList(lines);
    }
}
