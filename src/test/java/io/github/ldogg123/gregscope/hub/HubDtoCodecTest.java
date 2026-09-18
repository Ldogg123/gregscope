package io.github.ldogg123.gregscope.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.SensorState;

/**
 * GS-113 (design-v0.2 sections 9.2 and 9.3): the Hub DTO codecs round-trip over a real byte stream, cap what they
 * write and <b>reject</b> what they read.
 *
 * <p>
 * The {@link Bytes} helper is a genuine {@code DataOutputStream}/{@code DataInputStream} pair rather than a list of
 * typed values, so the layouts are exercised as bytes: a field written in the wrong order or read with the wrong
 * width shows up here. Its {@code readString} deliberately ignores the cap it is given, which is what lets the
 * oversize cases prove that {@link HubCodecs} does the rejecting itself rather than trusting the buffer.
 */
class HubDtoCodecTest {

    private static final UUID ID = UUID.fromString("3fa2c1d0-1111-4222-8333-444455556666");

    /** A {@link ByteSink} and {@link ByteSource} over one byte array. */
    static final class Bytes implements ByteSink, ByteSource {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(buffer);
        private DataInputStream in;

        @Override
        public void writeByte(int value) {
            try {
                out.writeByte(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void writeInt(int value) {
            try {
                out.writeInt(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void writeLong(long value) {
            try {
                out.writeLong(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void writeString(String value) {
            try {
                out.writeUTF(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        int size() {
            return buffer.size();
        }

        Bytes rewind() {
            in = new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()));
            return this;
        }

        @Override
        public byte readByte() {
            try {
                return in.readByte();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public int readInt() {
            try {
                return in.readInt();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public long readLong() {
            try {
                return in.readLong();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Deliberately ignores {@code maxUnits}: the codec is the one that has to refuse an oversize string. */
        @Override
        public String readString(int maxUnits) {
            try {
                return in.readUTF();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static HubRow fullRow() {
        return new HubRow(
            ID,
            0,
            HubCodecs.AVAILABILITY_LIVE,
            StateCodes.OUTPUT_BLOCKED,
            true,
            true,
            -1_920L,
            12,
            "EBF North");
    }

    private static HubWindow fullWindow() {
        long[] states = new long[StateCodes.COUNT];
        states[StateCodes.RUNNING] = 246L;
        states[StateCodes.IDLE] = 54L;
        return new HubWindow(states, 300L, 360L, 5, 240L, 1_920L, -80L, 2_133L, 17L, true, 3, 0x0A);
    }

    private static HubDetail fullDetail() {
        byte[] hourly = HubDetail.emptyHours();
        hourly[0] = 100;
        hourly[1] = 50;
        hourly[23] = 0;
        return HubDetail.builder()
            .id(ID)
            .kind(0)
            .availability(HubCodecs.AVAILABILITY_LIVE)
            .stateCode(StateCodes.RUNNING)
            .displayName("EBF North")
            .label("EBF North")
            .machineName("Electric Blast Furnace")
            .metaName("machine.ebf")
            .statusId("running")
            .statusText("Running perfectly")
            .metaId(1001)
            .position(0, 120, 64, -30, 5)
            .progressPermyriad(4_200)
            .maintenanceIssues(0)
            .euPerTick(1_920L)
            .energyStored(1_200_000L)
            .energyCapacity(1_600_000L)
            .ages(1, 1, 3_600)
            .canEdit(true)
            .historyLoaded(true)
            .hasHistory(true)
            .fiveMinutes(fullWindow())
            .day(fullWindow())
            .hourly(hourly)
            .build();
    }

    @Test
    void aRowRoundTrips() {
        HubRow row = fullRow();
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, row);

        HubRow back = HubCodecs.decodeRow(bytes.rewind());

        assertEquals(row, back);
        assertEquals(ID, back.id());
        assertEquals(StateCodes.OUTPUT_BLOCKED, back.stateCode());
        assertTrue(back.problem());
        assertTrue(back.warning());
        assertEquals(-1_920L, back.euPerTick());
        assertEquals(12, back.ageSeconds());
        assertEquals("EBF North", back.displayName());
    }

    @Test
    void thePaddingRowRoundTripsAsAnEmptyRow() {
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, HubRow.EMPTY);

        HubRow back = HubCodecs.decodeRow(bytes.rewind());

        assertEquals(HubRow.EMPTY, back);
        assertNull(back.id());
        assertTrue(back.isEmpty());
        assertEquals(HubCodecs.AVAILABILITY_NONE, back.availability());
        assertEquals(HubCodecs.NONE, back.euPerTick());
        assertEquals(HubCodecs.NO_AGE, back.ageSeconds());
    }

    @Test
    void aHeaderRoundTrips() {
        HubHeader header = new HubHeader(ID, "Steve", 23, 20, 4, 1, 1, 3, false, true, 410L, 7L, 2);
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, header);

        HubHeader back = HubCodecs.decodeHeader(bytes.rewind());

        assertEquals(header, back);
        assertEquals(23, back.total());
        assertEquals(20, back.live());
        assertEquals(4, back.shown());
        assertEquals(1, back.page());
        assertEquals(3, back.pages());
        assertEquals(410L, back.cycleMicrosP99());
        assertEquals(7L, back.samplingSkippedTotal());
        assertEquals(2, back.sensorsAbandoned());
    }

    @Test
    void anUnownedAndUnsupportedHeaderRoundTrips() {
        HubHeader header = new HubHeader(null, "", 0, 0, 0, 0, 0, 1, true, false, 0L, 0L, 0);
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, header);

        HubHeader back = HubCodecs.decodeHeader(bytes.rewind());

        assertEquals(header, back);
        assertNull(back.owner());
        assertTrue(back.unsupported());
        assertFalse(back.samplingEnabled());
    }

    @Test
    void aWindowRoundTrips() {
        HubWindow window = fullWindow();
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, window);

        HubWindow back = HubCodecs.decodeWindow(bytes.rewind());

        assertEquals(window, back);
        assertEquals(246L, back.stateSamples(StateCodes.RUNNING));
        assertEquals(300L, back.samples());
        assertEquals(360L, back.expectedSamples());
        assertEquals(1_920L, back.euPerTickAvg());
        assertEquals(-80L, back.euPerTickMin());
        assertEquals(17L, back.recipesCompleted());
        assertTrue(back.recipesCounterReset());
        assertEquals(0x0A, back.gapMask());
    }

    @Test
    void aDetailRoundTripsIncludingItsWindowsAndStrip() {
        HubDetail detail = fullDetail();
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, detail);

        HubDetail back = HubCodecs.decodeDetail(bytes.rewind());

        assertEquals(detail, back);
        assertEquals("Running perfectly", back.statusText());
        assertEquals(4_200, back.progressPermyriad());
        assertEquals(1_600_000L, back.energyCapacity());
        assertEquals(5, back.side());
        assertTrue(back.canEdit());
        assertEquals(detail.strip(), back.strip());
        assertEquals(
            '#',
            back.strip()
                .charAt(0));
        assertEquals(
            '=',
            back.strip()
                .charAt(1));
        assertEquals(
            '.',
            back.strip()
                .charAt(23));
    }

    @Test
    void aDetailWithNothingSelectedIsJustAFormatAndANullId() {
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, HubDetail.NONE);

        assertEquals(17, bytes.size(), "format byte plus two longs");
        HubDetail back = HubCodecs.decodeDetail(bytes.rewind());
        assertSame(HubDetail.NONE, back);
        assertFalse(back.isPresent());
    }

    @Test
    void anAbsentReadingSurvivesAsASentinelRatherThanAsZero() {
        HubDetail detail = HubDetail.builder()
            .id(ID)
            .availability(HubCodecs.AVAILABILITY_UNLOADED)
            .build();
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, detail);

        HubDetail back = HubCodecs.decodeDetail(bytes.rewind());

        assertEquals(HubCodecs.NONE, back.euPerTick());
        assertEquals(HubCodecs.NONE, back.energyStored());
        assertEquals(-1, back.progressPermyriad());
        assertEquals(-1, back.maintenanceIssues());
        assertEquals(HubCodecs.NO_AGE, back.sampleAgeSeconds());
        assertEquals(HubDetail.HOUR_GAP, back.hourly()[0]);
        assertEquals("????????????????????????", back.strip());
    }

    @Test
    void encodingCapsAnOverlongNameInsteadOfFailing() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            name.append('x');
        }
        HubRow row = new HubRow(
            ID,
            0,
            HubCodecs.AVAILABILITY_LIVE,
            0,
            false,
            false,
            HubCodecs.NONE,
            0,
            name.toString());

        assertEquals(
            HubCodecs.MAX_DISPLAY_NAME,
            row.displayName()
                .length());
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, row);
        assertEquals(
            HubCodecs.MAX_DISPLAY_NAME,
            HubCodecs.decodeRow(bytes.rewind())
                .displayName()
                .length());
    }

    @Test
    void decodingRejectsAnOversizeDisplayName() {
        Bytes bytes = new Bytes();
        bytes.writeByte(HubCodecs.FORMAT);
        HubCodecs.writeUuid(bytes, ID);
        bytes.writeByte(0);
        bytes.writeByte(HubCodecs.AVAILABILITY_LIVE);
        bytes.writeByte(StateCodes.RUNNING);
        bytes.writeByte(0);
        bytes.writeLong(HubCodecs.NONE);
        bytes.writeInt(0);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i <= HubCodecs.MAX_DISPLAY_NAME; i++) {
            name.append('x');
        }
        bytes.writeString(name.toString());

        IllegalArgumentException e = assertThrows(
            IllegalArgumentException.class,
            () -> HubCodecs.decodeRow(bytes.rewind()));
        assertTrue(
            e.getMessage()
                .contains("exceeds the cap 40"),
            e.getMessage());
    }

    @Test
    void decodingRejectsAnOversizeStatusText() {
        // The same prefix a real detail writes, then an overrun of exactly the statusText field.
        Bytes hostile = new Bytes();
        hostile.writeByte(HubCodecs.FORMAT);
        HubCodecs.writeUuid(hostile, ID);
        hostile.writeByte(0);
        hostile.writeByte(HubCodecs.AVAILABILITY_LIVE);
        hostile.writeByte(StateCodes.RUNNING);
        hostile.writeString("EBF North");
        hostile.writeString("EBF North");
        hostile.writeString("Electric Blast Furnace");
        hostile.writeString("machine.ebf");
        hostile.writeString("running");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i <= HubCodecs.MAX_STATUS_TEXT; i++) {
            text.append('y');
        }
        hostile.writeString(text.toString());

        IllegalArgumentException e = assertThrows(
            IllegalArgumentException.class,
            () -> HubCodecs.decodeDetail(hostile.rewind()));
        assertTrue(
            e.getMessage()
                .contains("exceeds the cap 128"),
            e.getMessage());
    }

    @Test
    void decodingRejectsAnotherFormat() {
        for (int format : new int[] { 0, 2, 127 }) {
            Bytes bytes = new Bytes();
            bytes.writeByte(format);
            assertThrows(IllegalArgumentException.class, () -> HubCodecs.decodeRow(bytes.rewind()));
            Bytes header = new Bytes();
            header.writeByte(format);
            assertThrows(IllegalArgumentException.class, () -> HubCodecs.decodeHeader(header.rewind()));
            Bytes window = new Bytes();
            window.writeByte(format);
            assertThrows(IllegalArgumentException.class, () -> HubCodecs.decodeWindow(window.rewind()));
            Bytes detail = new Bytes();
            detail.writeByte(format);
            assertThrows(IllegalArgumentException.class, () -> HubCodecs.decodeDetail(detail.rewind()));
        }
    }

    @Test
    void decodingRejectsUnknownCodes() {
        Bytes availability = new Bytes();
        availability.writeByte(HubCodecs.FORMAT);
        HubCodecs.writeUuid(availability, ID);
        availability.writeByte(0);
        availability.writeByte(9);
        assertThrows(IllegalArgumentException.class, () -> HubCodecs.decodeRow(availability.rewind()));

        Bytes state = new Bytes();
        state.writeByte(HubCodecs.FORMAT);
        HubCodecs.writeUuid(state, ID);
        state.writeByte(0);
        state.writeByte(HubCodecs.AVAILABILITY_LIVE);
        state.writeByte(StateCodes.COUNT);
        assertThrows(IllegalArgumentException.class, () -> HubCodecs.decodeRow(state.rewind()));
    }

    @Test
    void aFilterValueOffTheWireIsClamped() {
        HubHeader header = new HubHeader(
            ID,
            "Steve",
            1,
            1,
            1,
            HubViewModel.FILTER_PROBLEMS,
            0,
            1,
            false,
            true,
            0L,
            0L,
            0);
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, header);
        assertEquals(
            HubViewModel.FILTER_PROBLEMS,
            HubCodecs.decodeHeader(bytes.rewind())
                .filter());

        Bytes hostile = new Bytes();
        hostile.writeByte(HubCodecs.FORMAT);
        HubCodecs.writeUuid(hostile, ID);
        hostile.writeString("Steve");
        hostile.writeInt(1);
        hostile.writeInt(1);
        hostile.writeInt(1);
        hostile.writeByte(7);
        hostile.writeInt(0);
        hostile.writeInt(1);
        hostile.writeByte(2);
        hostile.writeLong(0L);
        hostile.writeLong(0L);
        hostile.writeInt(0);

        assertEquals(
            HubViewModel.FILTER_ALL,
            HubCodecs.decodeHeader(hostile.rewind())
                .filter());
    }

    @Test
    void theHourlyStripIsClampedAndGapsSurvive() {
        byte[] hourly = HubDetail.emptyHours();
        hourly[0] = 120;
        hourly[1] = -7;
        hourly[2] = 75;
        HubDetail detail = HubDetail.builder()
            .id(ID)
            .hourly(hourly)
            .build();

        assertEquals(100, detail.hourly()[0]);
        assertEquals(HubDetail.HOUR_GAP, detail.hourly()[1]);
        Bytes bytes = new Bytes();
        HubCodecs.encode(bytes, detail);
        HubDetail back = HubCodecs.decodeDetail(bytes.rewind());
        assertEquals(100, back.hourly()[0]);
        assertEquals(HubDetail.HOUR_GAP, back.hourly()[1]);
        assertEquals(
            "#?#",
            back.strip()
                .substring(0, 3));
    }

    @Test
    void theHourlyArrayIsNeverShared() {
        byte[] hourly = HubDetail.emptyHours();
        hourly[3] = 80;
        HubDetail detail = HubDetail.builder()
            .id(ID)
            .hourly(hourly)
            .build();

        hourly[3] = 0;
        byte[] read = detail.hourly();
        read[4] = 99;

        assertEquals(80, detail.hourly()[3]);
        assertEquals(HubDetail.HOUR_GAP, detail.hourly()[4]);
    }

    @Test
    void anHourlyArrayOfTheWrongLengthIsRefused() {
        assertThrows(
            IllegalArgumentException.class,
            () -> HubDetail.builder()
                .hourly(new byte[23]));
        assertThrows(
            IllegalArgumentException.class,
            () -> HubDetail.builder()
                .hourly(null));
    }

    @Test
    void theAvailabilityCodesArePinnedAndReversible() {
        assertEquals(0, HubCodecs.availabilityCode(SensorState.LIVE));
        assertEquals(1, HubCodecs.availabilityCode(SensorState.UNLOADED));
        assertEquals(2, HubCodecs.availabilityCode(SensorState.OVER_CAP));
        assertEquals(3, HubCodecs.availabilityCode(SensorState.MISSING));
        assertEquals(4, HubCodecs.availabilityCode(SensorState.IN_ITEM));
        assertEquals(5, HubCodecs.availabilityCode(SensorState.REMOVED));
        assertEquals(HubCodecs.AVAILABILITY_NONE, HubCodecs.availabilityCode(null));
        for (SensorState state : SensorState.values()) {
            assertEquals(state, HubCodecs.availabilityState(HubCodecs.availabilityCode(state)));
        }
        assertNull(HubCodecs.availabilityState(HubCodecs.AVAILABILITY_NONE));
        assertNull(HubCodecs.availabilityState(6));
        assertEquals("live", HubCodecs.availabilityId(0));
        assertEquals("over_cap", HubCodecs.availabilityId(2));
        assertEquals("in_item", HubCodecs.availabilityId(4));
        assertEquals("", HubCodecs.availabilityId(HubCodecs.AVAILABILITY_NONE));
    }

    @Test
    void cappingNeverSplitsASurrogatePair() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            text.appendCodePoint(0x1F600);
        }

        String capped = HubCodecs.cap(text.toString(), HubCodecs.MAX_DISPLAY_NAME);

        assertEquals(HubCodecs.MAX_DISPLAY_NAME, capped.length());
        assertFalse(Character.isHighSurrogate(capped.charAt(capped.length() - 1)));
        assertEquals("", HubCodecs.cap(null, 10));
        assertEquals("abc", HubCodecs.cap("abc", 10));

        StringBuilder odd = new StringBuilder("x");
        for (int i = 0; i < 25; i++) {
            odd.appendCodePoint(0x1F600);
        }
        String oddCapped = HubCodecs.cap(odd.toString(), HubCodecs.MAX_DISPLAY_NAME);
        assertEquals(HubCodecs.MAX_DISPLAY_NAME - 1, oddCapped.length());
        assertFalse(Character.isHighSurrogate(oddCapped.charAt(oddCapped.length() - 1)));
    }

    @Test
    void theStripSymbolTableIsPinned() {
        assertEquals('?', HubDetail.symbol(HubDetail.HOUR_GAP));
        assertEquals('.', HubDetail.symbol(0));
        assertEquals('.', HubDetail.symbol(24));
        assertEquals('-', HubDetail.symbol(25));
        assertEquals('-', HubDetail.symbol(49));
        assertEquals('=', HubDetail.symbol(50));
        assertEquals('=', HubDetail.symbol(74));
        assertEquals('#', HubDetail.symbol(75));
        assertEquals('#', HubDetail.symbol(100));
        for (int value = HubDetail.HOUR_GAP; value <= 100; value++) {
            char c = HubDetail.symbol(value);
            assertTrue(c < 128, "the strip must stay ASCII, got " + (int) c);
        }
    }

    @Test
    void aWindowNeedsOneColumnPerPinnedState() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new HubWindow(new long[StateCodes.COUNT - 1], 0L, 0L, 0, 0L, 0L, 0L, 0L, 0L, false, 0, 0));
        assertThrows(
            IllegalArgumentException.class,
            () -> new HubWindow(null, 0L, 0L, 0, 0L, 0L, 0L, 0L, 0L, false, 0, 0));
    }
}
