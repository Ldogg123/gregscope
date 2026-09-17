package io.github.ldogg123.gregscope.sampling;

import java.util.UUID;

import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorState;

/**
 * One sensor's immutable row in a {@link TelemetryFrame} (design-v0.2 section 7.7). [pure]
 *
 * <p>
 * Every field is final and either a primitive, an immutable object ({@link UUID}, {@link String},
 * {@link MachineSnapshot}) or an immutable copy ({@link CountersView}), so the whole object can be read from an
 * OpenComputers computer thread or the v0.4 HTTP thread without any lock. Rings are deliberately <em>not</em> here:
 * history is read on the server thread only.
 *
 * <p>
 * <b>Design-v0.3 section 5.1 A6.</b> {@link #lastSnapshot()} is nullable and only ever set for kind 0; a v0.3 flow
 * meter leaves it null and adds its own reading next to it, and {@link #counters()} is the {@link CountersView}
 * interface rather than the machine counters class, so no reader has to know which kind it is looking at.
 */
public final class SensorView {

    /** {@link #lastGapReason()} when the last second was observed, not a gap. */
    public static final int NO_GAP = SensorEntry.NO_GAP;

    private final UUID id;
    private final int kind;
    private final String label;
    private final UUID owner;
    private final String ownerName;
    private final int dim;
    private final int x;
    private final int y;
    private final int z;
    private final byte side;
    private final SensorState state;
    private final long stateSinceEpochSec;
    private final long lastSeenEpochSec;
    private final long lastSampleEpochSec;
    private final int lastGapReason;
    private final String metaName;
    private final int metaId;
    private final String machineName;
    private final String lastStatusId;
    private final MachineSnapshot lastSnapshot;
    private final CountersView counters;

    /** Copies everything a reader may see from a live registry entry. Server thread only. */
    public SensorView(SensorEntry entry) {
        this.id = entry.id();
        this.kind = entry.kind();
        this.label = entry.label();
        this.owner = entry.owner();
        this.ownerName = entry.ownerName();
        this.dim = entry.dim();
        this.x = entry.x();
        this.y = entry.y();
        this.z = entry.z();
        this.side = (byte) entry.side();
        this.state = entry.state();
        this.stateSinceEpochSec = entry.stateSinceEpochSec();
        this.lastSeenEpochSec = entry.lastSeenEpochSec();
        this.lastSampleEpochSec = entry.lastSampleEpochSec();
        this.lastGapReason = entry.lastGapReason();
        this.metaName = entry.metaName();
        this.metaId = entry.metaId();
        this.machineName = entry.machineName();
        this.lastStatusId = entry.lastStatusId();
        this.lastSnapshot = entry.lastSnapshot();
        this.counters = entry.counters() == null ? null : new MachineCountersView(entry.counters());
    }

    public UUID id() {
        return id;
    }

    /** One of the {@code SensorKind} codes; v0.2 only ever publishes kind 0. */
    public int kind() {
        return kind;
    }

    /** Sanitized; empty when the sensor has no label. */
    public String label() {
        return label;
    }

    /** Owner UUID, or null for an unowned sensor. */
    public UUID owner() {
        return owner;
    }

    /** Cached owner name, or null for an unowned sensor. */
    public String ownerName() {
        return ownerName;
    }

    public int dim() {
        return dim;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    /** The {@code ForgeDirection} ordinal of the covered face. */
    public byte side() {
        return side;
    }

    public SensorState state() {
        return state;
    }

    public long stateSinceEpochSec() {
        return stateSinceEpochSec;
    }

    public long lastSeenEpochSec() {
        return lastSeenEpochSec;
    }

    /** When the last observed sample was taken, or 0 if there was none. */
    public long lastSampleEpochSec() {
        return lastSampleEpochSec;
    }

    /** {@link GapReason#bit()} of the last recorded gap second, or {@link #NO_GAP}. */
    public int lastGapReason() {
        return lastGapReason;
    }

    /** The last gap reason, or null if the last second was observed. */
    public GapReason lastGap() {
        return lastGapReason == NO_GAP ? null : GapReason.fromBit(lastGapReason);
    }

    public String metaName() {
        return metaName;
    }

    public int metaId() {
        return metaId;
    }

    public String machineName() {
        return machineName;
    }

    public String lastStatusId() {
        return lastStatusId;
    }

    /** The last machine snapshot, or null: never sampled, not loaded, or a kind that has no snapshot (A6). */
    public MachineSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    /** The per-sensor counters, or null while the entry holds none (a tombstone). */
    public CountersView counters() {
        return counters;
    }

    @Override
    public String toString() {
        return "SensorView{" + id
            + " kind "
            + kind
            + " "
            + state
            + " dim "
            + dim
            + " ("
            + x
            + ","
            + y
            + ","
            + z
            + ") side "
            + side
            + "}";
    }
}
