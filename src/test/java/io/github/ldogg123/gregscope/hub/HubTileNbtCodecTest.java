package io.github.ldogg123.gregscope.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.sensor.KeyValue;

/**
 * GS-112 (design-v0.2 section 9.1, section 14): the pure part of the Telemetry Hub tile entity, its {@code gsHub}
 * record.
 */
class HubTileNbtCodecTest {

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
            map.put(key, Byte.valueOf(value));
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
            map.put(key, Long.valueOf(value));
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

    private static final UUID OWNER = UUID.fromString("3fa2c1d0-0000-4000-8000-00000000abcd");

    @Test
    void keysArePinned() {
        assertEquals(1, HubNbtCodec.FORMAT);
        assertEquals("gsHub", HubNbtCodec.GS_HUB);
        assertEquals("owM", HubNbtCodec.OWNER_MSB);
        assertEquals("owL", HubNbtCodec.OWNER_LSB);
        assertEquals("owN", HubNbtCodec.OWNER_NAME);
    }

    @Test
    void ownedHubRoundTrips() {
        MapKeyValue d = new MapKeyValue();
        HubNbtCodec.write(OWNER, "Steve", d);
        assertEquals(Byte.valueOf((byte) 1), d.map.get("gsHub"));
        HubNbtCodec.Result read = HubNbtCodec.read(d);
        assertEquals(HubNbtCodec.Status.VALID, read.status());
        assertEquals(OWNER, read.owner());
        assertEquals("Steve", read.ownerName());
        assertEquals(1, read.version());
    }

    @Test
    void unownedHubWritesNoOwnerKeysAndClearsOldOnes() {
        MapKeyValue d = new MapKeyValue();
        HubNbtCodec.write(OWNER, "Steve", d);
        HubNbtCodec.write(null, null, d);
        assertFalse(d.map.containsKey("owM"));
        assertFalse(d.map.containsKey("owL"));
        assertFalse(d.map.containsKey("owN"));
        HubNbtCodec.Result read = HubNbtCodec.read(d);
        assertEquals(HubNbtCodec.Status.VALID, read.status());
        assertNull(read.owner());
        assertEquals("", read.ownerName());
    }

    @Test
    void emptyCompoundAndNullAreAbsent() {
        HubNbtCodec.Result empty = HubNbtCodec.read(new MapKeyValue());
        assertEquals(HubNbtCodec.Status.ABSENT, empty.status());
        assertEquals(0, empty.version());
        assertNull(empty.owner());
        assertEquals("", empty.ownerName());
        assertEquals(
            HubNbtCodec.Status.ABSENT,
            HubNbtCodec.read(null)
                .status());
    }

    /** A foreign value under the marker key reads as absent instead of throwing or converting. */
    @Test
    void foreignTypeUnderTheMarkerIsAbsent() {
        MapKeyValue d = new MapKeyValue();
        d.putString("gsHub", "1");
        assertEquals(
            HubNbtCodec.Status.ABSENT,
            HubNbtCodec.read(d)
                .status());
    }

    /** Section 9.1: an unknown {@code gsHub} is never parsed, so the tile entity can write it back verbatim. */
    @Test
    void newerVersionIsUnsupportedAndParsesNothing() {
        MapKeyValue d = new MapKeyValue();
        HubNbtCodec.write(OWNER, "Steve", d);
        d.putByte("gsHub", (byte) 2);
        HubNbtCodec.Result read = HubNbtCodec.read(d);
        assertEquals(HubNbtCodec.Status.UNSUPPORTED, read.status());
        assertEquals(2, read.version());
        assertNull(read.owner());
        assertEquals("", read.ownerName());
        // Reading changed nothing: the caller still holds every key it had.
        assertEquals(Byte.valueOf((byte) 2), d.map.get("gsHub"));
        assertEquals(Long.valueOf(OWNER.getMostSignificantBits()), d.map.get("owM"));
    }

    /** The version byte is unsigned, so 200 is 200 and not -56. */
    @Test
    void versionIsUnsigned() {
        MapKeyValue d = new MapKeyValue();
        d.putByte("gsHub", (byte) 200);
        HubNbtCodec.Result read = HubNbtCodec.read(d);
        assertEquals(HubNbtCodec.Status.UNSUPPORTED, read.status());
        assertEquals(200, read.version());
    }

    /** A marker of 0 is no record this project wrote, so it reads as absent rather than as an owned Hub. */
    @Test
    void versionZeroIsAbsent() {
        MapKeyValue d = new MapKeyValue();
        d.putByte("gsHub", (byte) 0);
        d.putLong("owM", OWNER.getMostSignificantBits());
        d.putLong("owL", OWNER.getLeastSignificantBits());
        HubNbtCodec.Result read = HubNbtCodec.read(d);
        assertEquals(HubNbtCodec.Status.ABSENT, read.status());
        assertNull(read.owner());
    }

    /** Half an owner (one of the two longs) is no owner: the Hub is valid but unowned. */
    @Test
    void halfAnOwnerIsNoOwner() {
        MapKeyValue d = new MapKeyValue();
        d.putByte("gsHub", (byte) 1);
        d.putLong("owM", OWNER.getMostSignificantBits());
        HubNbtCodec.Result read = HubNbtCodec.read(d);
        assertEquals(HubNbtCodec.Status.VALID, read.status());
        assertNull(read.owner());
    }

    /** An owner with no cached name is still an owner. */
    @Test
    void ownerWithoutNameIsValid() {
        MapKeyValue d = new MapKeyValue();
        d.putByte("gsHub", (byte) 1);
        d.putLong("owM", OWNER.getMostSignificantBits());
        d.putLong("owL", OWNER.getLeastSignificantBits());
        HubNbtCodec.Result read = HubNbtCodec.read(d);
        assertEquals(HubNbtCodec.Status.VALID, read.status());
        assertEquals(OWNER, read.owner());
        assertEquals("", read.ownerName());
    }

    /** The owner name is capped exactly as a sensor's is (16 UTF-16 units), on write and on read. */
    @Test
    void ownerNameIsCapped() {
        MapKeyValue d = new MapKeyValue();
        HubNbtCodec.write(OWNER, "0123456789abcdefghij", d);
        assertEquals("0123456789abcdef", d.map.get("owN"));
        d.putString("owN", "0123456789abcdefghij");
        assertEquals(
            "0123456789abcdef",
            HubNbtCodec.read(d)
                .ownerName());
    }

    /** Writing never leaves a foreign key behind, and never touches keys it does not own. */
    @Test
    void writeLeavesForeignKeysAlone() {
        MapKeyValue d = new MapKeyValue();
        d.putString("someOtherMod", "hello");
        HubNbtCodec.write(OWNER, "Steve", d);
        assertEquals("hello", d.map.get("someOtherMod"));
        assertTrue(d.map.containsKey("gsHub"));
    }
}
