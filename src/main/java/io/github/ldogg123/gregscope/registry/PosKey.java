package io.github.ldogg123.gregscope.registry;

/**
 * The reverse-index key of the sensor registry: a block position and the cover side on it (design-v0.3 section 5.1
 * A1). Immutable. [pure]
 *
 * <p>
 * A1 asks for {@code packedPos(dim,x,y,z)<<3 | side}. A single {@code long} cannot hold that: x and z need 26 bits
 * each for the +-30,000,000 world limit, y needs 8 and the side 3, which is 63 bits before the dimension ID, and a
 * dimension ID is a full {@code int} (mod packs hand out negative and large IDs). The key is therefore a two-field
 * value: the dimension, and one {@code long} packing {@code (x, y, z, side)} exactly as A1 describes for the rest. The
 * packing is lossless inside the 1.7.10 world limits and {@link #packedPosition()} is {@code packed >>> 3}, so the
 * v0.3 code that A1 is written for sees the same numbers.
 *
 * <p>
 * Keys are only built on the registry's slow paths. The heartbeat fast path compares the primitive coordinates a LIVE
 * entry already holds, so it allocates nothing.
 */
public final class PosKey {

    /** Bits per horizontal coordinate: +-33,554,432 covers the 1.7.10 world limit of +-30,000,000. */
    private static final int XZ_BITS = 26;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1L;
    private static final int Y_BITS = 8;
    private static final long Y_MASK = (1L << Y_BITS) - 1L;
    private static final int SIDE_BITS = 3;
    private static final long SIDE_MASK = (1L << SIDE_BITS) - 1L;

    private final int dim;
    private final long packed;

    public PosKey(int dim, int x, int y, int z, int side) {
        this.dim = dim;
        this.packed = pack(x, y, z, side);
    }

    /** {@code packedPos(x,y,z)<<3 | side} (design-v0.3 A1); the dimension is the other half of the key. */
    public static long pack(int x, int y, int z, int side) {
        return ((x & XZ_MASK) << (XZ_BITS + Y_BITS + SIDE_BITS)) | ((z & XZ_MASK) << (Y_BITS + SIDE_BITS))
            | ((y & Y_MASK) << SIDE_BITS)
            | (side & SIDE_MASK);
    }

    public int dim() {
        return dim;
    }

    /** The packed {@code (x, y, z, side)}. */
    public long packed() {
        return packed;
    }

    /** The packed position without the side, i.e. A1's {@code packedPos}. */
    public long packedPosition() {
        return packed >>> SIDE_BITS;
    }

    public int side() {
        return (int) (packed & SIDE_MASK);
    }

    public int x() {
        return signExtend((int) ((packed >>> (XZ_BITS + Y_BITS + SIDE_BITS)) & XZ_MASK), XZ_BITS);
    }

    public int y() {
        return (int) ((packed >>> SIDE_BITS) & Y_MASK);
    }

    public int z() {
        return signExtend((int) ((packed >>> (Y_BITS + SIDE_BITS)) & XZ_MASK), XZ_BITS);
    }

    private static int signExtend(int value, int bits) {
        int shift = 32 - bits;
        return (value << shift) >> shift;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PosKey)) {
            return false;
        }
        PosKey other = (PosKey) o;
        return dim == other.dim && packed == other.packed;
    }

    @Override
    public int hashCode() {
        return 31 * dim + Long.hashCode(packed);
    }

    @Override
    public String toString() {
        return "dim " + dim + " (" + x() + "," + y() + "," + z() + ") side " + side();
    }
}
