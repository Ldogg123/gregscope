package io.github.ldogg123.gregscope.history;

/**
 * 1,440 minute slots (24 hours) kept as their encoded 64-byte form, 92,160 B (design-v0.2 §7.4). Server thread only.
 * [pure]
 *
 * <p>
 * A slot lives at index {@code floorMod(epochMinute, 1440)}, the same index as in the history file body. Reading
 * checks the CRC and the window {@code newest-1439 <= epochMinute <= newest}, so a torn or stale slot reads as
 * missing (a gap). {@code newest} is the largest minute ever stored or loaded.
 */
public final class MinuteRing implements MinuteSource {

    public static final int SLOTS = 1440;
    public static final int BYTES = SLOTS * MinuteSlot.SIZE;

    private final byte[] data = new byte[BYTES];
    /** 0 while nothing was stored (0 is also the "empty" epochMinute). */
    private int newest;

    public static int index(int epochMinute) {
        return Math.floorMod(epochMinute, SLOTS);
    }

    /** The newest minute stored or loaded, or 0 if none. */
    public int newestEpochMinute() {
        return newest;
    }

    /**
     * Stores a closed minute. If a valid slot for the same minute is already present, the two are combined with
     * {@link MinuteSlot#merge} (the stored one is older). A minute older than the window of the newest minute is
     * refused.
     *
     * @return the slot now stored (merged if applicable), whose bytes are what must be written to disk; or
     *         {@code null} if the minute was refused as stale
     */
    public MinuteSlot put(MinuteSlot slot) {
        int minute = slot.epochMinute();
        if (newest != 0 && minute < newest && !MinuteSlot.isWithinWindow(minute, newest)) {
            return null;
        }
        int off = index(minute) * MinuteSlot.SIZE;
        MinuteSlot existing = MinuteSlot.decode(data, off);
        MinuteSlot stored = existing != null && existing.epochMinute() == minute ? MinuteSlot.merge(existing, slot)
            : slot;
        stored.encode(data, off);
        if (newest == 0 || minute > newest) {
            newest = minute;
        }
        return stored;
    }

    /**
     * Merges a history file body loaded asynchronously (§8.4): for each index, a valid slot already in RAM wins; a
     * valid loaded slot fills an index that holds no valid slot in RAM. Invalid loaded slots are ignored.
     *
     * @param body   1,440 x 64 bytes in index order
     * @param offset where the body starts
     * @return the number of slots taken from the loaded body
     */
    public int mergeLoaded(byte[] body, int offset) {
        if (offset < 0 || offset > body.length - BYTES) {
            throw new IndexOutOfBoundsException("offset " + offset);
        }
        int taken = 0;
        for (int i = 0; i < SLOTS; i++) {
            int off = i * MinuteSlot.SIZE;
            if (MinuteSlot.decode(data, off) != null) {
                continue;
            }
            MinuteSlot loaded = MinuteSlot.decode(body, offset + off);
            if (loaded == null || index(loaded.epochMinute()) != i) {
                continue;
            }
            System.arraycopy(body, offset + off, data, off, MinuteSlot.SIZE);
            if (newest == 0 || loaded.epochMinute() > newest) {
                newest = loaded.epochMinute();
            }
            taken++;
        }
        return taken;
    }

    @Override
    public MinuteSlot slot(int epochMinute) {
        if (epochMinute == 0 || newest == 0 || !MinuteSlot.isWithinWindow(epochMinute, newest)) {
            return null;
        }
        MinuteSlot slot = MinuteSlot.decode(data, index(epochMinute) * MinuteSlot.SIZE);
        return slot != null && slot.epochMinute() == epochMinute ? slot : null;
    }

    /** Copies the raw 64 bytes at {@code index} (for writing a slot to disk or for tests). */
    public void copyRaw(int index, byte[] out, int off) {
        if (index < 0 || index >= SLOTS) {
            throw new IndexOutOfBoundsException("index " + index);
        }
        System.arraycopy(data, index * MinuteSlot.SIZE, out, off, MinuteSlot.SIZE);
    }
}
