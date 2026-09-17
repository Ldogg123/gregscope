package io.github.ldogg123.gregscope.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Why history has no observation (design-v0.2 §7.2). [pure]
 *
 * <p>
 * The id and the bit are stable: bit {@code n} is position {@code n} in the minute slot's {@code gapMask}, and the
 * second sample stores {@code bit + 1} ({@code 0} = no gap). Only bits 0-5 are ever stored; {@code server_offline}
 * and {@code unknown} are derived by readers (§7.5).
 */
public enum GapReason {

    CHUNK_UNLOADED(0, "chunk_unloaded", true),
    DIMENSION_UNLOADED(1, "dimension_unloaded", true),
    TARGET_MISSING(2, "target_missing", true),
    SAMPLING_SKIPPED(3, "sampling_skipped", true),
    PROBE_ERROR(4, "probe_error", true),
    SENSOR_REMOVED(5, "sensor_removed", true),
    SERVER_OFFLINE(6, "server_offline", false),
    UNKNOWN(7, "unknown", false);

    /** Number of stored reasons (bits 0-5). */
    public static final int STORED_COUNT = 6;
    /** The bits a stored {@code gapMask} may contain. */
    public static final int STORED_MASK = (1 << STORED_COUNT) - 1;

    private static final GapReason[] BY_BIT = new GapReason[8];

    static {
        for (GapReason reason : values()) {
            BY_BIT[reason.bit] = reason;
        }
    }

    private final int bit;
    private final String id;
    private final boolean stored;

    GapReason(int bit, String id, boolean stored) {
        this.bit = bit;
        this.id = id;
        this.stored = stored;
    }

    public int bit() {
        return bit;
    }

    public int mask() {
        return 1 << bit;
    }

    /** The second-sample code: {@code bit + 1}. */
    public int secondCode() {
        return bit + 1;
    }

    public String id() {
        return id;
    }

    /** True for reasons that are written into rings and files; false for reader-derived ones. */
    public boolean isStored() {
        return stored;
    }

    /** The reason at {@code bit}, or {@code null} outside 0..7. */
    public static GapReason fromBit(int bit) {
        return bit >= 0 && bit < BY_BIT.length ? BY_BIT[bit] : null;
    }

    /** The reason of a second-sample code ({@code bit + 1}); {@code null} for 0 (no gap) or an unknown code. */
    public static GapReason fromSecondCode(int code) {
        return fromBit(code - 1);
    }

    /** The reason with this stable id, or {@code null}. */
    public static GapReason fromId(String id) {
        for (GapReason reason : values()) {
            if (reason.id.equals(id)) {
                return reason;
            }
        }
        return null;
    }

    /** The reasons set in {@code mask} (bits 0-7), in bit order; unmodifiable. */
    public static List<GapReason> fromMask(int mask) {
        List<GapReason> reasons = new ArrayList<>();
        for (GapReason reason : values()) {
            if ((mask & reason.mask()) != 0) {
                reasons.add(reason);
            }
        }
        return Collections.unmodifiableList(reasons);
    }
}
