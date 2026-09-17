package io.github.ldogg123.gregscope.sensor;

/**
 * The seam between {@link SensorNbtCodec} and Minecraft's {@code NBTTagCompound} (the cover's {@code d} compound). The
 * adapter lives with the cover; tests use an in-memory map. [pure]
 *
 * <p>
 * {@code hasX} is true only when the key holds a value of that type, so a foreign value under a GregScope key reads as
 * absent.
 */
public interface KeyValue {

    boolean hasByte(String key);

    byte getByte(String key);

    void putByte(String key, byte value);

    boolean hasLong(String key);

    long getLong(String key);

    void putLong(String key, long value);

    boolean hasString(String key);

    String getString(String key);

    void putString(String key, String value);

    void remove(String key);
}
