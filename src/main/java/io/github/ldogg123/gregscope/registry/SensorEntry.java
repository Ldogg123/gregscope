package io.github.ldogg123.gregscope.registry;

import java.util.UUID;

import io.github.ldogg123.gregscope.history.MinuteAccumulator;
import io.github.ldogg123.gregscope.history.MinuteRing;
import io.github.ldogg123.gregscope.history.SecondRing;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.sampling.SensorCounters;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;

/**
 * One registry entry: what the registry knows about one sensor (design-v0.2 section 4.1). Mutable, server thread
 * only. [pure]
 *
 * <p>
 * Identity, location, owner, label, machine metadata and lifecycle are persisted (design-v0.2 section 8.3). The rings,
 * the accumulator, the counters, the last snapshot, the strike count, the bucket and {@code historyLoaded} live only
 * in RAM: they are allocated when the entry becomes LIVE and freed when it becomes a tombstone, which is what the
 * section 7.8 ceilings assume.
 *
 * <p>
 * The class is not named in design-v0.2 section 2, which lists the registry package as
 * {@code SensorState, RemovalCause, SensorRegistryCore, RunsTable}; the state machine needs a value to hold, so the
 * entry is its own [pure] class instead of an inner class, and unit tests can build one directly.
 */
public final class SensorEntry {

    /** Cap for {@code metaName}, {@code machineName} and {@code lastStatusId} (design-v0.2 section 4.1). */
    public static final int MAX_TEXT = 64;
    /** Validation failures in a row before the entry becomes MISSING (design-v0.2 section 4.3). */
    public static final int MAX_STRIKES = 3;
    /** {@link #lastGapReason()} while the last recorded second was observed rather than a gap. */
    public static final int NO_GAP = -1;

    private SensorIdentity identity;
    private final int kind;

    private int dim;
    private int x;
    private int y;
    private int z;
    private int side;

    private int metaId;
    private String metaName = "";
    private String machineName = "";
    private String lastStatusId = "";

    private SensorState state;
    private RemovalCause removalCause = RemovalCause.NONE;
    private long lastSeenEpochSec;
    private long stateSinceEpochSec;

    // RAM only.
    private int strikes;
    private int bucket = -1;
    private int bucketSlot = -1;
    private long sampleDueTick;
    private int lastGapReason = NO_GAP;
    private boolean historyLoaded;
    private SecondRing seconds;
    private MinuteRing minutes;
    private MinuteAccumulator accumulator;
    private SensorCounters counters;
    private MachineSnapshot lastSnapshot;
    private long lastSampleEpochSec;
    private long lastSampleTick = Long.MIN_VALUE;
    private long lastProbeWarnEpochSec = Long.MIN_VALUE;
    private long lastHeartbeatTick = Long.MIN_VALUE;

    public SensorEntry(SensorIdentity identity, int kind, int dim, int x, int y, int z, int side, SensorState state,
        long nowEpochSec) {
        if (identity == null) {
            throw new IllegalArgumentException("identity");
        }
        if (state == null) {
            throw new IllegalArgumentException("state");
        }
        this.identity = identity;
        this.kind = kind;
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
        this.state = state;
        this.lastSeenEpochSec = nowEpochSec;
        this.stateSinceEpochSec = nowEpochSec;
    }

    // --- identity and location ---

    public UUID id() {
        return identity.id();
    }

    public SensorIdentity identity() {
        return identity;
    }

    /** Replaces the identity; the UUID may change only through the registry's duplicate re-key. */
    public void setIdentity(SensorIdentity replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("identity");
        }
        this.identity = replacement;
    }

    /** One of the {@code SensorKind} codes; constant for the life of the entry. */
    public int kind() {
        return kind;
    }

    public String label() {
        return identity.label();
    }

    public UUID owner() {
        return identity.owner();
    }

    public String ownerName() {
        return identity.ownerName();
    }

    public long createdEpochSec() {
        return identity.createdEpochSec();
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

    public int side() {
        return side;
    }

    /** True if the entry sits at exactly this position and side; the heartbeat fast path's check. */
    public boolean isAt(int dim, int x, int y, int z, int side) {
        return this.dim == dim && this.x == x && this.y == y && this.z == z && this.side == side;
    }

    /** True if the entry sits at this block, whatever side the cover is on. */
    public boolean isAtBlock(int dim, int x, int y, int z) {
        return this.dim == dim && this.x == x && this.y == y && this.z == z;
    }

    public PosKey posKey() {
        return new PosKey(dim, x, y, z, side);
    }

    void moveTo(int dim, int x, int y, int z, int side) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
    }

    // --- machine metadata ---

    public int metaId() {
        return metaId;
    }

    public String metaName() {
        return metaName;
    }

    public String machineName() {
        return machineName;
    }

    public String lastStatusId() {
        return lastStatusId;
    }

    /**
     * Refreshes the machine metadata the Hub, the commands and OpenComputers show.
     *
     * @return true if anything changed, which makes the registry dirty
     */
    public boolean setMachineMetadata(int metaId, String metaName, String machineName, String lastStatusId) {
        String meta = cap(metaName);
        String machine = cap(machineName);
        String status = cap(lastStatusId);
        boolean changed = this.metaId != metaId || !this.metaName.equals(meta)
            || !this.machineName.equals(machine)
            || !this.lastStatusId.equals(status);
        this.metaId = metaId;
        this.metaName = meta;
        this.machineName = machine;
        this.lastStatusId = status;
        return changed;
    }

    static String cap(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_TEXT) {
            return text;
        }
        int end = MAX_TEXT;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }

    // --- lifecycle ---

    public SensorState state() {
        return state;
    }

    public RemovalCause removalCause() {
        return removalCause;
    }

    public long lastSeenEpochSec() {
        return lastSeenEpochSec;
    }

    public long stateSinceEpochSec() {
        return stateSinceEpochSec;
    }

    void setState(SensorState state, RemovalCause cause, long nowEpochSec) {
        this.state = state;
        this.removalCause = cause == null ? RemovalCause.NONE : cause;
        this.stateSinceEpochSec = nowEpochSec;
    }

    /** Used by the registry loader (GS-109) to restore a persisted state without moving {@code stateSince}. */
    public void restoreState(SensorState state, RemovalCause cause, long stateSinceEpochSec, long lastSeenEpochSec) {
        this.state = state;
        this.removalCause = cause == null ? RemovalCause.NONE : cause;
        this.stateSinceEpochSec = stateSinceEpochSec;
        this.lastSeenEpochSec = lastSeenEpochSec;
    }

    public void setLastSeenEpochSec(long epochSec) {
        this.lastSeenEpochSec = epochSec;
    }

    public int strikes() {
        return strikes;
    }

    /** @return the new strike count */
    int addStrike() {
        return ++strikes;
    }

    void clearStrikes() {
        strikes = 0;
    }

    /** The sampler bucket (design-v0.2 section 6.1), or -1 while the entry is not sampled. */
    public int bucket() {
        return bucket;
    }

    public void setBucket(int bucket) {
        this.bucket = bucket;
    }

    /**
     * The entry's slot inside its sampler bucket, or -1: what makes removal from a bucket an O(1) swap-remove
     * (design-v0.2 section 6.1). RAM only, owned by {@code SamplerSchedule}.
     */
    public int bucketSlot() {
        return bucketSlot;
    }

    public void setBucketSlot(int slot) {
        this.bucketSlot = slot;
    }

    /**
     * The sampler tick this entry was last due on. A sensor carried for a whole interval because of the per-tick
     * budget is given up on for that interval (design-v0.2 section 6.3). RAM only.
     */
    public long sampleDueTick() {
        return sampleDueTick;
    }

    public void setSampleDueTick(long tick) {
        this.sampleDueTick = tick;
    }

    /**
     * {@code GapReason.bit()} of the last gap second recorded for this entry, or {@link #NO_GAP} if the last recorded
     * second was an observed sample. RAM only; it is what {@code SensorView.lastGapReason} publishes.
     */
    public int lastGapReason() {
        return lastGapReason;
    }

    public void setLastGapReason(int bit) {
        this.lastGapReason = bit;
    }

    /** False until the async history load merged (design-v0.2 section 8.4). */
    public boolean historyLoaded() {
        return historyLoaded;
    }

    public void setHistoryLoaded(boolean loaded) {
        this.historyLoaded = loaded;
    }

    /** The last tick a cover heartbeat was seen; {@link Long#MIN_VALUE} if never. */
    public long lastHeartbeatTick() {
        return lastHeartbeatTick;
    }

    void setLastHeartbeatTick(long tick) {
        this.lastHeartbeatTick = tick;
    }

    // --- RAM history ---

    /** The 300-second ring, or null while the entry is not LIVE. */
    public SecondRing seconds() {
        return seconds;
    }

    /** The 24-hour minute ring, or null once the entry is a tombstone. */
    public MinuteRing minutes() {
        return minutes;
    }

    /** The open minute, or null once the entry is a tombstone. */
    public MinuteAccumulator accumulator() {
        return accumulator;
    }

    /** Process-lifetime counters, or null once the entry is a tombstone. */
    public SensorCounters counters() {
        return counters;
    }

    public MachineSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    public void setLastSnapshot(MachineSnapshot snapshot) {
        this.lastSnapshot = snapshot;
    }

    public long lastSampleEpochSec() {
        return lastSampleEpochSec;
    }

    public void setLastSampleEpochSec(long epochSec) {
        this.lastSampleEpochSec = epochSec;
    }

    /**
     * The sampler tick of the previous sample, {@link Long#MIN_VALUE} if there was none. The difference to the
     * current tick is the minute slot's {@code serverTicks} contribution (design-v0.2 section 7.4). RAM only.
     */
    public long lastSampleTick() {
        return lastSampleTick;
    }

    public void setLastSampleTick(long tick) {
        this.lastSampleTick = tick;
    }

    /**
     * When the last probe-error WARN was logged for this sensor; design-v0.2 section 6.2 allows one per sensor per
     * 10 minutes. RAM only.
     */
    public long lastProbeWarnEpochSec() {
        return lastProbeWarnEpochSec;
    }

    public void setLastProbeWarnEpochSec(long epochSec) {
        this.lastProbeWarnEpochSec = epochSec;
    }

    /**
     * Allocates what a LIVE entry needs. The minute ring, the accumulator and the counters survive an UNLOADED spell
     * (design-v0.2 section 4.2), so they are only created when they are missing.
     */
    void allocateLiveRings(int intervalTicks, int serverStartEpochMinute) {
        if (seconds == null) {
            seconds = new SecondRing();
        }
        allocateUnloadedRings(intervalTicks, serverStartEpochMinute);
    }

    /** What an UNLOADED entry keeps: the minute ring, the open minute and the counters, but no second ring. */
    void allocateUnloadedRings(int intervalTicks, int serverStartEpochMinute) {
        if (minutes == null) {
            minutes = new MinuteRing();
        }
        if (accumulator == null) {
            accumulator = new MinuteAccumulator(intervalTicks, serverStartEpochMinute);
        }
        if (counters == null) {
            counters = new SensorCounters();
        }
    }

    /** The second ring is dropped when the entry stops being LIVE; the minute history stays. */
    void freeSecondRing() {
        seconds = null;
    }

    /** A tombstone keeps no history in RAM (design-v0.2 section 4.2). */
    void freeRings() {
        seconds = null;
        minutes = null;
        accumulator = null;
        counters = null;
        lastSnapshot = null;
        historyLoaded = false;
        bucket = -1;
        bucketSlot = -1;
        strikes = 0;
    }

    @Override
    public String toString() {
        return "SensorEntry{" + id()
            + " kind "
            + kind
            + " "
            + state
            + (removalCause == RemovalCause.NONE ? "" : "/" + removalCause)
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
