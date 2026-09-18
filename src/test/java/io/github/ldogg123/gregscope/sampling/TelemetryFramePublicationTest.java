package io.github.ldogg123.gregscope.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistryCore;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-118 (design-v0.2 section 7.7): the frame really is safe to publish through a {@code static volatile} field and
 * read from another thread without a lock.
 *
 * <p>
 * <b>What is being tested, and why it is not trivially true.</b> The sampler mutates <em>live</em> {@code SensorEntry}
 * objects on the server thread and then publishes {@link SensorView} copies of them. Nothing stops a reader on an
 * OpenComputers computer thread or the v0.4 HTTP thread from holding a frame while the server thread keeps writing to
 * those same entries. The frame is only safe because (a) a {@code SensorView} <em>copies</em> the entry instead of
 * referring to it, (b) every field of the frame graph is final, so the JMM freezes them at the end of the
 * constructor, and (c) the field is {@code volatile}, so the reader that sees the reference also sees everything
 * written before it. Break any one of the three and a reader sees a frame whose parts come from different moments -
 * a torn read.
 *
 * <p>
 * The writer here is the server thread: per published frame it advances all four entries and then builds the frame,
 * so every field of every view in frame <i>n</i> must read as <i>n</i> and never as a mixture of <i>n</i> and
 * <i>n+1</i>. That single arithmetic relation is what makes a tear detectable at all.
 *
 * <p>
 * Three tests: a handshaking run in which the reader really validates each of the 10,000 published frames, a
 * free-running run in which both threads go flat out and the reader validates at least 10,000 reads, and one that
 * holds a single frame still while the writer rewrites everything it was built from.
 */
class TelemetryFramePublicationTest {

    private static final int FRAMES = 10_000;
    private static final int SENSORS = 4;
    private static final long BASE_EPOCH_SEC = 1_700_000_040L;
    private static final long TIMEOUT_MS = 60_000L;

    /** The publication point under test: the shape design-v0.2 section 7.7 requires of {@code TelemetrySampler}. */
    private static volatile TelemetryFrame published = TelemetryFrame.EMPTY;

    private List<SensorEntry> entries;
    private SamplerStatsView stats;
    private LimitsView limits;

    @BeforeEach
    void setUp() {
        published = TelemetryFrame.EMPTY;
        SensorRegistryCore core = new SensorRegistryCore(
            () -> Settings.DEFAULTS,
            () -> BASE_EPOCH_SEC * 1000L,
            new NoTeams());
        entries = new ArrayList<>();
        for (int i = 0; i < SENSORS; i++) {
            SensorIdentity identity = new SensorIdentity(
                new UUID(0L, i + 1L),
                "sensor " + i,
                null,
                null,
                BASE_EPOCH_SEC);
            core.heartbeat(identity, SensorKind.MACHINE, 0, i, 64, 0, 1, 100L);
            SensorEntry entry = core.entry(identity.id());
            assertNotNull(entry, "entry " + i + " did not register");
            assertNotNull(entry.counters(), "a LIVE entry must hold counters or a tear would be invisible here");
            entries.add(entry);
        }
        stats = new SamplerStatsView(new SamplerStats(), 0L, 0L);
        limits = new LimitsView(Settings.DEFAULTS);
    }

    /** One sampler step: advance every live entry to {@code sequence}, then publish the copies. */
    private void publish(long sequence) {
        for (SensorEntry entry : entries) {
            entry.setMachineMetadata(
                (int) (sequence % 1000),
                "meta-" + sequence,
                "machine-" + sequence,
                "status-" + sequence);
            entry.setLastSampleEpochSec(BASE_EPOCH_SEC + sequence);
            entry.setLastSeenEpochSec(BASE_EPOCH_SEC + sequence);
            entry.counters()
                .onSample(StateCodes.RUNNING, true, sequence, 20);
        }
        List<SensorView> views = new ArrayList<>(SENSORS);
        for (SensorEntry entry : entries) {
            views.add(new SensorView(entry));
        }
        published = new TelemetryFrame(sequence, sequence, BASE_EPOCH_SEC * 1000L + sequence, 20, views, stats, limits);
    }

    /**
     * Everything frame {@code n} must say about itself, checked in one go. Returns null when the frame is consistent,
     * or the first thing that was wrong.
     */
    private static String violation(TelemetryFrame frame) {
        if (frame == null) {
            return "the published field held null";
        }
        long sequence = frame.sequence();
        if (sequence == 0L) {
            return frame.sensors()
                .isEmpty() ? null : "the empty frame grew sensors";
        }
        if (frame.publishedNanos() != sequence) {
            return "publishedNanos " + frame.publishedNanos() + " does not belong to frame " + sequence;
        }
        if (frame.publishedEpochMillis() != BASE_EPOCH_SEC * 1000L + sequence) {
            return "publishedEpochMillis " + frame.publishedEpochMillis() + " does not belong to frame " + sequence;
        }
        if (frame.intervalTicks() != 20) {
            return "intervalTicks " + frame.intervalTicks();
        }
        List<SensorView> sensors = frame.sensors();
        if (sensors.size() != SENSORS) {
            return "frame " + sequence + " has " + sensors.size() + " sensors";
        }
        UUID previous = null;
        for (SensorView view : sensors) {
            if (previous != null && previous.compareTo(view.id()) >= 0) {
                return "frame " + sequence + " is not sorted by id";
            }
            previous = view.id();
            if (view.metaId() != (int) (sequence % 1000)) {
                return "frame " + sequence + " sensor " + view.id() + " carries metaId " + view.metaId();
            }
            if (!("meta-" + sequence).equals(view.metaName())) {
                return "frame " + sequence + " carries metaName " + view.metaName();
            }
            if (!("machine-" + sequence).equals(view.machineName())) {
                return "frame " + sequence + " carries machineName " + view.machineName();
            }
            if (!("status-" + sequence).equals(view.lastStatusId())) {
                return "frame " + sequence + " carries lastStatusId " + view.lastStatusId();
            }
            if (view.lastSampleEpochSec() != BASE_EPOCH_SEC + sequence) {
                return "frame " + sequence + " carries lastSampleEpochSec " + view.lastSampleEpochSec();
            }
            CountersView counters = view.counters();
            if (counters == null) {
                return "frame " + sequence + " lost its counters";
            }
            if (counters.samplesTotal() != sequence) {
                return "frame " + sequence + " carries samplesTotal " + counters.samplesTotal();
            }
            if (counters.gapSecondsTotal().length != GapReason.STORED_COUNT) {
                return "frame " + sequence + " carries a short gap array";
            }
        }
        return null;
    }

    @Test
    @Timeout(120)
    void aReaderValidatesEveryOneOfTenThousandPublishedFrames() throws InterruptedException {
        final AtomicLong acknowledged = new AtomicLong(0L);
        final AtomicLong validated = new AtomicLong(0L);
        final AtomicReference<String> failure = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            long last = 0L;
            while (last < FRAMES && failure.get() == null) {
                TelemetryFrame frame = published;
                long sequence = frame.sequence();
                if (sequence <= last) {
                    Thread.yield();
                    continue;
                }
                String bad = violation(frame);
                if (bad != null) {
                    failure.set(bad);
                    return;
                }
                if (sequence != last + 1) {
                    failure.set(
                        "the reader skipped from frame " + last + " to " + sequence + "; the handshake did not hold");
                    return;
                }
                validated.incrementAndGet();
                last = sequence;
                acknowledged.set(sequence);
            }
        }, "gregscope-frame-reader");
        reader.setDaemon(true);
        reader.start();

        long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
        for (long sequence = 1; sequence <= FRAMES && failure.get() == null; sequence++) {
            publish(sequence);
            while (acknowledged.get() < sequence && failure.get() == null) {
                if (System.nanoTime() > deadline) {
                    failure.set("the reader stopped acknowledging at frame " + acknowledged.get());
                    break;
                }
                Thread.yield();
            }
        }
        reader.join(TIMEOUT_MS);

        assertNull(failure.get(), "a reader saw a torn or wrong frame: " + failure.get());
        assertFalse(reader.isAlive(), "the reader thread did not finish");
        assertEquals(FRAMES, validated.get(), "the reader must validate every published frame");
    }

    @Test
    @Timeout(120)
    void aFreeRunningReaderNeverSeesATornOrMutatedFrame() throws InterruptedException {
        final AtomicLong reads = new AtomicLong(0L);
        final AtomicLong distinct = new AtomicLong(0L);
        final AtomicReference<String> failure = new AtomicReference<>();
        final AtomicReference<Thread> writerDone = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            long last = -1L;
            while (failure.get() == null && (writerDone.get() == null || reads.get() < FRAMES)) {
                TelemetryFrame frame = published;
                long sequence = frame.sequence();
                if (sequence < last) {
                    failure.set("a published sequence went backwards: " + last + " then " + sequence);
                    return;
                }
                if (sequence > last) {
                    distinct.incrementAndGet();
                    last = sequence;
                }
                String bad = violation(frame);
                if (bad != null) {
                    failure.set(bad);
                    return;
                }
                // The frame a reader holds must not change under it, however long it holds it.
                if (sequence != frame.sequence() || !bothReadsAgree(frame)) {
                    failure.set("frame " + sequence + " changed while it was being read");
                    return;
                }
                try {
                    frame.sensors()
                        .add(null);
                    failure.set("frame " + sequence + " handed out a writable sensor list");
                    return;
                } catch (UnsupportedOperationException expected) {
                    // what an unmodifiable list must do
                }
                reads.incrementAndGet();
            }
        }, "gregscope-frame-reader");
        reader.setDaemon(true);
        reader.start();

        for (long sequence = 1; sequence <= FRAMES && failure.get() == null; sequence++) {
            publish(sequence);
        }
        writerDone.set(Thread.currentThread());
        reader.join(TIMEOUT_MS);

        assertNull(failure.get(), "a reader saw a torn or mutated frame: " + failure.get());
        assertFalse(reader.isAlive(), "the reader thread did not finish");
        assertTrue(
            reads.get() >= FRAMES,
            "the reader only validated " + reads.get() + " frames; the test asks for " + FRAMES);
        assertTrue(
            distinct.get() >= 2,
            "the reader never saw the writer move (" + distinct.get() + " distinct frames), so nothing was raced");
        assertEquals(FRAMES, published.sequence(), "the last frame the writer published is the one still standing");
        assertNull(violation(published), "the frame left standing is inconsistent");
    }

    /** Reads every field of the frame twice and says whether the two reads agree. */
    private static boolean bothReadsAgree(TelemetryFrame frame) {
        long first = frame.publishedEpochMillis() ^ frame.publishedNanos() ^ frame.intervalTicks();
        for (SensorView view : frame.sensors()) {
            first ^= view.metaId() ^ view.lastSampleEpochSec()
                ^ view.counters()
                    .samplesTotal()
                ^ view.metaName()
                    .hashCode();
        }
        long second = frame.publishedEpochMillis() ^ frame.publishedNanos() ^ frame.intervalTicks();
        for (SensorView view : frame.sensors()) {
            second ^= view.metaId() ^ view.lastSampleEpochSec()
                ^ view.counters()
                    .samplesTotal()
                ^ view.metaName()
                    .hashCode();
        }
        return first == second;
    }

    @Test
    @Timeout(120)
    void aHeldFrameIsFrozenWhileTheWriterRewritesTheEntriesItCameFrom() throws InterruptedException {
        publish(1L);
        final TelemetryFrame held = published;
        assertNull(violation(held), "the held frame was already wrong");

        final AtomicReference<String> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            for (long sequence = 2; sequence <= FRAMES; sequence++) {
                publish(sequence);
            }
        }, "gregscope-frame-writer");
        writer.setDaemon(true);
        writer.start();

        long checks = 0;
        while (writer.isAlive() || checks < FRAMES) {
            String bad = violation(held);
            if (bad != null) {
                failure.set(bad);
                break;
            }
            checks++;
        }
        writer.join(TIMEOUT_MS);

        assertNull(
            failure.get(),
            "the frame a reader was holding changed while the server thread rewrote the entries it was copied "
                + "from: "
                + failure.get());
        assertFalse(writer.isAlive(), "the writer thread did not finish");
        assertTrue(checks >= FRAMES, "only " + checks + " checks were made");
        assertEquals(1L, held.sequence(), "the held frame is still frame 1");
        assertEquals(FRAMES, published.sequence(), "the writer got all the way through");
        // And the entries really did move, so the freeze was not the freeze of a writer that never wrote.
        assertEquals(
            (int) (FRAMES % 1000),
            entries.get(0)
                .metaId(),
            "the live entry did not advance, so nothing was proven");
        assertEquals(
            1,
            held.sensors()
                .get(0)
                .metaId(),
            "frame 1 must still carry frame 1's metadata");
    }

    @Test
    void theSensorListOfEveryPublishedFrameIsUnmodifiable() {
        publish(1L);
        final TelemetryFrame frame = published;
        assertThrows(
            UnsupportedOperationException.class,
            () -> frame.sensors()
                .clear());
        assertThrows(
            UnsupportedOperationException.class,
            () -> frame.sensors()
                .set(0, null));
        assertEquals(
            SENSORS,
            frame.sensors()
                .size());
        assertEquals(
            Collections.emptyList(),
            TelemetryFrame.EMPTY.sensors(),
            "the frame a reader sees before the first publish carries no sensors");
    }

    /** A resolver with no teams at all, so the caps never merge two owners. */
    private static final class NoTeams implements TeamResolver<Object> {

        @Override
        public Object teamOf(UUID player) {
            return null;
        }

        @Override
        public boolean isMember(Object team, UUID player) {
            return false;
        }

        @Override
        public boolean isOfficerOrOwner(Object team, UUID player) {
            return false;
        }
    }
}
