package io.github.ldogg123.gregscope.hub;

import java.nio.charset.Charset;

import net.minecraft.network.PacketBuffer;

import com.cleanroommc.modularui.utils.serialization.IByteBufDeserializer;
import com.cleanroommc.modularui.utils.serialization.IByteBufSerializer;

/**
 * The ModularUI2 half of the Hub DTO codec seam (design-v0.2 section 2): {@link ByteSink} and {@link ByteSource} over
 * the {@code PacketBuffer} a sync handler hands out, plus the six serializer pairs {@code HubPanel} registers.
 *
 * <p>
 * This is the only class that ties the wire format to a game type, which is what keeps {@link HubCodecs} plain-JVM
 * unit tested. It is the same shape as {@code NbtKeyValue} for the sensor cover's {@code KeyValue} seam.
 *
 * <p>
 * <b>String framing.</b> A string travels as a varint UTF-8 byte length followed by the bytes, which is Minecraft's
 * own framing minus the {@code IOException} its {@code PacketBuffer} helpers declare. The length is checked
 * <em>before</em> the bytes are read, against {@code maxUnits * 3} (the largest number of UTF-8 bytes that many
 * UTF-16 units can encode to), so a hostile packet cannot make the server allocate a large array first and fail
 * afterwards; {@link HubCodecs} then re-checks the decoded length in UTF-16 units, which is the cap section 9.3
 * really pins.
 */
public final class HubPacketIo {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** The most UTF-8 bytes one UTF-16 unit can produce (a surrogate pair is 2 units and 4 bytes). */
    private static final int MAX_BYTES_PER_UNIT = 3;

    private HubPacketIo() {}

    public static ByteSink sink(PacketBuffer buffer) {
        return new Sink(buffer);
    }

    public static ByteSource source(PacketBuffer buffer) {
        return new Source(buffer);
    }

    // --- the serializer pairs HubPanel registers (section 9.3 gs_rows, gs_header, gs_detail) ---

    public static final IByteBufSerializer<HubRow> ROW_OUT = new IByteBufSerializer<HubRow>() {

        @Override
        public void serialize(PacketBuffer buffer, HubRow value) {
            HubCodecs.encode(sink(buffer), value);
        }
    };

    public static final IByteBufDeserializer<HubRow> ROW_IN = new IByteBufDeserializer<HubRow>() {

        @Override
        public HubRow deserialize(PacketBuffer buffer) {
            return HubCodecs.decodeRow(source(buffer));
        }
    };

    public static final IByteBufSerializer<HubHeader> HEADER_OUT = new IByteBufSerializer<HubHeader>() {

        @Override
        public void serialize(PacketBuffer buffer, HubHeader value) {
            HubCodecs.encode(sink(buffer), value);
        }
    };

    public static final IByteBufDeserializer<HubHeader> HEADER_IN = new IByteBufDeserializer<HubHeader>() {

        @Override
        public HubHeader deserialize(PacketBuffer buffer) {
            return HubCodecs.decodeHeader(source(buffer));
        }
    };

    public static final IByteBufSerializer<HubDetail> DETAIL_OUT = new IByteBufSerializer<HubDetail>() {

        @Override
        public void serialize(PacketBuffer buffer, HubDetail value) {
            HubCodecs.encode(sink(buffer), value);
        }
    };

    public static final IByteBufDeserializer<HubDetail> DETAIL_IN = new IByteBufDeserializer<HubDetail>() {

        @Override
        public HubDetail deserialize(PacketBuffer buffer) {
            return HubCodecs.decodeDetail(source(buffer));
        }
    };

    private static final class Sink implements ByteSink {

        private final PacketBuffer buffer;

        Sink(PacketBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void writeByte(int value) {
            buffer.writeByte(value);
        }

        @Override
        public void writeInt(int value) {
            buffer.writeInt(value);
        }

        @Override
        public void writeLong(long value) {
            buffer.writeLong(value);
        }

        @Override
        public void writeString(String value) {
            if (value.length() > HubCodecs.MAX_STRING) {
                throw new IllegalArgumentException(
                    "string of " + value.length() + " units exceeds the Hub cap " + HubCodecs.MAX_STRING);
            }
            byte[] bytes = value.getBytes(UTF_8);
            buffer.writeVarIntToBuffer(bytes.length);
            buffer.writeBytes(bytes);
        }
    }

    private static final class Source implements ByteSource {

        private final PacketBuffer buffer;

        Source(PacketBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public byte readByte() {
            return buffer.readByte();
        }

        @Override
        public int readInt() {
            return buffer.readInt();
        }

        @Override
        public long readLong() {
            return buffer.readLong();
        }

        @Override
        public String readString(int maxUnits) {
            int length = buffer.readVarIntFromBuffer();
            if (length < 0 || length > maxUnits * MAX_BYTES_PER_UNIT) {
                throw new IllegalArgumentException("string of " + length + " bytes exceeds the cap " + maxUnits);
            }
            byte[] bytes = new byte[length];
            buffer.readBytes(bytes);
            return new String(bytes, UTF_8);
        }
    }
}
