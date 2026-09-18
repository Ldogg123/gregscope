package io.github.ldogg123.gregscope.hub;

/**
 * The write half of the Hub DTO codec seam (design-v0.2 section 2: "DTOs + codecs over ByteSink/ByteSource"). [pure]
 *
 * <p>
 * GS-114 implements it over the buffer ModularUI2 hands a {@code GenericSyncValue} / {@code GenericListSyncHandler},
 * exactly as {@code KeyValue} is the seam over {@code NBTTagCompound} for the sensor cover. Nothing in this package
 * has to name a game type to have a wire format, so {@link HubCodecs} is plain-JVM unit tested.
 *
 * <p>
 * The four primitives are the ones every 1.7.10 packet buffer already has. Booleans travel inside flag bytes and
 * absent numbers travel as sentinels, so the layouts are fixed-shape and a decoder never has to guess.
 */
public interface ByteSink {

    /** Writes the low 8 bits of {@code value}. */
    void writeByte(int value);

    void writeInt(int value);

    void writeLong(long value);

    /**
     * Writes a non-null string. The implementation chooses the framing (a length prefix plus UTF-8 for a packet
     * buffer); {@link HubCodecs} has already capped the length, so an implementation may also refuse anything
     * longer than {@link HubCodecs#MAX_STRING}.
     */
    void writeString(String value);
}
