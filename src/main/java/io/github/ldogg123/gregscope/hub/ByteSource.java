package io.github.ldogg123.gregscope.hub;

/**
 * The read half of the Hub DTO codec seam (design-v0.2 section 2). [pure]
 *
 * <p>
 * Everything a source returns is untrusted: it came off the network. {@link HubCodecs} therefore re-checks every
 * string length against the section 9.3 caps after reading it and throws rather than trusting
 * {@link #readString(int)} to have enforced the cap. An implementation over a real packet buffer should still pass
 * the cap down (1.7.10's {@code readStringFromBuffer(int)} takes one), so a hostile packet cannot make the server
 * allocate first and fail afterwards; the codec's own check is what makes the rule testable without a buffer.
 */
public interface ByteSource {

    byte readByte();

    int readInt();

    long readLong();

    /**
     * Reads the next string.
     *
     * @param maxUnits the cap {@link HubCodecs} will enforce, in UTF-16 units; an implementation is encouraged to
     *                 refuse a longer string while reading rather than after
     */
    String readString(int maxUnits);
}
