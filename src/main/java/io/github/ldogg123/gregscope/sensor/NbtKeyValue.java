package io.github.ldogg123.gregscope.sensor;

import net.minecraft.nbt.NBTTagCompound;

/**
 * The {@link KeyValue} adapter over Minecraft's {@code NBTTagCompound}, used for the cover's {@code d} compound
 * (design-v0.2 §3.3) and, since GS-112, for the Telemetry Hub tile entity's compound (§9.1). The pure
 * {@link SensorNbtCodec} never sees an NBT type.
 *
 * <p>
 * Type ids are the ones {@code NBTBase} uses: 1 byte, 4 long, 8 string. {@code hasKey(key, id)} is true only when the
 * key holds a value of exactly that type, which is what {@link KeyValue} requires: a foreign value under a GregScope
 * key reads as absent instead of throwing or silently converting.
 */
public final class NbtKeyValue implements KeyValue {

    private static final int TAG_BYTE = 1;
    private static final int TAG_LONG = 4;
    private static final int TAG_STRING = 8;

    private final NBTTagCompound tag;

    public NbtKeyValue(NBTTagCompound tag) {
        if (tag == null) {
            throw new IllegalArgumentException("tag");
        }
        this.tag = tag;
    }

    @Override
    public boolean hasByte(String key) {
        return tag.hasKey(key, TAG_BYTE);
    }

    @Override
    public byte getByte(String key) {
        return tag.getByte(key);
    }

    @Override
    public void putByte(String key, byte value) {
        tag.setByte(key, value);
    }

    @Override
    public boolean hasLong(String key) {
        return tag.hasKey(key, TAG_LONG);
    }

    @Override
    public long getLong(String key) {
        return tag.getLong(key);
    }

    @Override
    public void putLong(String key, long value) {
        tag.setLong(key, value);
    }

    @Override
    public boolean hasString(String key) {
        return tag.hasKey(key, TAG_STRING);
    }

    @Override
    public String getString(String key) {
        return tag.getString(key);
    }

    @Override
    public void putString(String key, String value) {
        tag.setString(key, value);
    }

    @Override
    public void remove(String key) {
        tag.removeTag(key);
    }
}
