package io.github.ldogg123.gregscope.sensor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SensorNbtCodecTest {

    /** A typed in-memory stand-in for NBTTagCompound: a key holds exactly one value of one type. */
    static final class MapKeyValue implements KeyValue {

        final Map<String, Object> map = new HashMap<>();

        @Override
        public boolean hasByte(String key) {
            return map.get(key) instanceof Byte;
        }

        @Override
        public byte getByte(String key) {
            return (Byte) map.get(key);
        }

        @Override
        public void putByte(String key, byte value) {
            map.put(key, value);
        }

        @Override
        public boolean hasLong(String key) {
            return map.get(key) instanceof Long;
        }

        @Override
        public long getLong(String key) {
            return (Long) map.get(key);
        }

        @Override
        public void putLong(String key, long value) {
            map.put(key, value);
        }

        @Override
        public boolean hasString(String key) {
            return map.get(key) instanceof String;
        }

        @Override
        public String getString(String key) {
            return (String) map.get(key);
        }

        @Override
        public void putString(String key, String value) {
            map.put(key, value);
        }

        @Override
        public void remove(String key) {
            map.remove(key);
        }
    }

    private static final UUID ID = UUID.fromString("3fa2c1d0-1234-4abc-8def-0123456789ab");
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-00000000beef");

    @Test
    void roundTripOwned() {
        SensorIdentity identity = new SensorIdentity(ID, "EBF North", OWNER, "Steve", 1_759_107_600L);
        MapKeyValue d = new MapKeyValue();
        SensorNbtCodec.write(identity, d);

        assertEquals((byte) 1, d.map.get("gs"));
        assertEquals(ID.getMostSignificantBits(), d.map.get("idM"));
        assertEquals(ID.getLeastSignificantBits(), d.map.get("idL"));
        assertEquals("EBF North", d.map.get("lbl"));
        assertEquals(OWNER.getMostSignificantBits(), d.map.get("owM"));
        assertEquals(OWNER.getLeastSignificantBits(), d.map.get("owL"));
        assertEquals("Steve", d.map.get("owN"));
        assertEquals(1_759_107_600L, d.map.get("ct"));
        assertEquals(8, d.map.size());

        SensorNbtCodec.Result result = SensorNbtCodec.read(d);
        assertEquals(SensorNbtCodec.Status.VALID, result.status());
        assertEquals(identity, result.identity());
        assertEquals(1, result.version());
        assertNull(result.inertDescription());
    }

    @Test
    void unownedHasNoOwnerKeysAndEmptyLabelIsAbsent() {
        SensorIdentity identity = new SensorIdentity(ID, "", null, "ignored", 5L);
        MapKeyValue d = new MapKeyValue();
        d.putString("lbl", "old");
        d.putLong("owM", 1L);
        d.putLong("owL", 2L);
        d.putString("owN", "Alex");
        SensorNbtCodec.write(identity, d);

        assertFalse(d.map.containsKey("lbl"));
        assertFalse(d.map.containsKey("owM"));
        assertFalse(d.map.containsKey("owL"));
        assertFalse(d.map.containsKey("owN"));

        SensorIdentity read = SensorNbtCodec.read(d)
            .identity();
        assertNull(read.owner());
        assertNull(read.ownerName());
        assertFalse(read.isOwned());
        assertEquals("", read.label());
        assertEquals(identity, read);
    }

    @Test
    void missingMarkerOrCompoundIsInert() {
        assertEquals(
            SensorNbtCodec.Status.INERT,
            SensorNbtCodec.read(null)
                .status());

        MapKeyValue foreign = new MapKeyValue();
        foreign.putLong("idM", 1L);
        foreign.putLong("idL", 2L);
        foreign.putString("something", "else");
        SensorNbtCodec.Result result = SensorNbtCodec.read(foreign);
        assertEquals(SensorNbtCodec.Status.INERT, result.status());
        assertNull(result.identity());
        assertEquals("inactive", result.inertDescription());

        // A foreign value of another type under the marker key is not the marker.
        MapKeyValue wrongType = new MapKeyValue();
        wrongType.putString("gs", "1");
        wrongType.putLong("idM", 1L);
        wrongType.putLong("idL", 2L);
        assertEquals(
            SensorNbtCodec.Status.INERT,
            SensorNbtCodec.read(wrongType)
                .status());

        MapKeyValue zero = new MapKeyValue();
        zero.putByte("gs", (byte) 0);
        assertEquals(
            SensorNbtCodec.Status.INERT,
            SensorNbtCodec.read(zero)
                .status());
    }

    @Test
    void markerWithoutIdIsInert() {
        MapKeyValue d = new MapKeyValue();
        d.putByte("gs", (byte) 1);
        d.putLong("idM", 1L);
        assertEquals(
            SensorNbtCodec.Status.INERT,
            SensorNbtCodec.read(d)
                .status());
    }

    @Test
    void newerVersionIsUnsupportedAndLeftVerbatim() {
        for (byte gs : new byte[] { 2, 7, (byte) 200 }) {
            MapKeyValue d = new MapKeyValue();
            d.putByte("gs", gs);
            d.putLong("idM", 11L);
            d.putLong("idL", 22L);
            d.putString("lbl", "\u00A7afuture \u0000label");
            d.putString("newKey", "v2 only");
            Map<String, Object> before = new HashMap<>(d.map);

            SensorNbtCodec.Result result = SensorNbtCodec.read(d);
            assertEquals(SensorNbtCodec.Status.UNSUPPORTED, result.status(), "gs=" + gs);
            assertEquals(gs & 0xFF, result.version());
            assertNull(result.identity());
            assertEquals("unsupported data version", result.inertDescription());
            assertEquals(before, d.map, "read must not change an unsupported compound");
            for (Map.Entry<String, Object> e : before.entrySet()) {
                assertSame(e.getValue(), d.map.get(e.getKey()));
            }
        }
    }

    @Test
    void labelIsSanitizedOnRead() {
        MapKeyValue d = new MapKeyValue();
        d.putByte("gs", (byte) 1);
        d.putLong("idM", ID.getMostSignificantBits());
        d.putLong("idL", ID.getLeastSignificantBits());
        d.putString("lbl", "  \u00A7cHot\u00A7r   \uFEFFline\u0000  ");
        assertEquals(
            "Hot line",
            SensorNbtCodec.read(d)
                .identity()
                .label());
    }

    @Test
    void optionalKeysDefault() {
        MapKeyValue d = new MapKeyValue();
        d.putByte("gs", (byte) 1);
        d.putLong("idM", ID.getMostSignificantBits());
        d.putLong("idL", ID.getLeastSignificantBits());
        d.putLong("owM", OWNER.getMostSignificantBits());
        d.putLong("owL", OWNER.getLeastSignificantBits());
        SensorIdentity identity = SensorNbtCodec.read(d)
            .identity();
        assertEquals(ID, identity.id());
        assertEquals("", identity.label());
        assertEquals(OWNER, identity.owner());
        assertEquals("", identity.ownerName());
        assertEquals(0L, identity.createdEpochSec());

        // Half an owner UUID is unowned.
        d.remove("owL");
        assertNull(
            SensorNbtCodec.read(d)
                .identity()
                .owner());
    }

    @Test
    void ownerNameCappedAt16WithoutSplittingAPair() {
        assertEquals("abcdefghijklmnop", new SensorIdentity(ID, "", OWNER, "abcdefghijklmnopqrst", 0).ownerName());
        String pairAtCap = "abcdefghijklmno" + new String(Character.toChars(0x1F600));
        assertEquals("abcdefghijklmno", new SensorIdentity(ID, "", OWNER, pairAtCap, 0).ownerName());
        assertTrue(
            new SensorIdentity(ID, "", OWNER, null, 0).ownerName()
                .isEmpty());
    }

    @Test
    void identityCopies() {
        SensorIdentity identity = new SensorIdentity(ID, "a", OWNER, "Steve", 9L);
        assertEquals(
            "b",
            identity.withLabel("  b ")
                .label());
        UUID other = UUID.fromString("11111111-2222-4333-8444-555555555555");
        SensorIdentity rekeyed = identity.withId(other);
        assertEquals(other, rekeyed.id());
        assertEquals("a", rekeyed.label());
        assertEquals(OWNER, rekeyed.owner());
        assertEquals(9L, rekeyed.createdEpochSec());
        assertEquals("3fa2c1d0", identity.shortId());
    }
}
