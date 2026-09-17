package io.github.ldogg123.gregscope.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.history.MinuteAccumulator;
import io.github.ldogg123.gregscope.history.MinuteRing;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.sampling.Clock;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * The sensor lifecycle state machine of design-v0.2 section 4: every transition of the section 4.3 table, the caps of
 * section 4.2, the OVER_CAP retry window, tombstone expiry and housekeeping. Server thread only. [pure]
 *
 * <p>
 * <b>Indexes.</b> {@code byId} maps the sensor UUID to its entry; {@code byPosition} is the reverse index, keyed on
 * the block position <em>and</em> the cover side (design-v0.3 section 5.1 A1), so a second sensor on another face of
 * the same block does not replace the first. For machine sensors (kind 0) the slow path additionally scans the six
 * sides of the block, because at most one Machine Sensor may exist per machine: another kind-0 UUID anywhere on that
 * block is stale and becomes REPLACED.
 *
 * <p>
 * <b>The heartbeat fast path</b> (a LIVE sensor of the same kind heartbeating at the position and side it is already
 * indexed at) does one {@code byId} lookup, compares primitives and stores the tick. It allocates nothing and never
 * touches the reverse index, the clock or the team resolver.
 *
 * <p>
 * <b>What the core does not do.</b> It never touches the world, never writes NBT and never does I/O. A duplicate is
 * re-keyed here and the caller writes the fresh identity into the cover; buckets, history loads, minute writes and
 * file deletion go out through {@link RegistryEvents}.
 */
public final class SensorRegistryCore {

    /** Design-v0.2 section 4.3: a refused sensor is re-checked at most every 1,200 ticks. */
    public static final int OVER_CAP_RETRY_TICKS = 1200;
    /** Design-v0.2 section 4.3: the refusal LRU holds this many UUIDs. */
    public static final int OVER_CAP_LRU_SIZE = 1024;
    /** Design-v0.2 section 4.3: housekeeping runs at server start and then every 72,000 ticks. */
    public static final int HOUSEKEEPING_INTERVAL_TICKS = 72_000;

    private static final int SIDES = 6;
    private static final long SECONDS_PER_HOUR = 3600L;
    private static final long SECONDS_PER_DAY = 86_400L;

    /** What a heartbeat did (design-v0.2 section 4.3). */
    public enum Heartbeat {

        /** Fast path: already LIVE at this position and side. */
        LIVE,
        /** A UUID the registry did not know became LIVE. */
        REGISTERED,
        /** A known entry became LIVE again, possibly at a new position (UNLOADED, MISSING, IN_ITEM or REMOVED). */
        RESUMED,
        /** The UUID was already in use elsewhere: the newcomer got a fresh identity, see {@link #rekeyedIdentity()}. */
        REKEYED,
        /**
         * Re-keyed as above, but a cap then refused the newcomer, so no entry exists for the fresh identity. The
         * caller still writes the fresh identity into the cover: keeping the colliding UUID would take the same
         * branch on every heartbeat, burn a UUID each time and never converge.
         */
        REKEYED_OVER_CAP,
        /** A cap refused the sensor; no entry exists for it. */
        OVER_CAP,
        /** A kind this version does not register (design-v0.3 kinds 1 and 2 in a v0.2 build). */
        UNSUPPORTED_KIND;

        /** True if the cover must store {@link SensorRegistryCore#rekeyedIdentity()}. */
        public boolean rekeyed() {
            return this == REKEYED || this == REKEYED_OVER_CAP;
        }

        /**
         * The availability word (design-v0.2 section 3.4) the cover carries after this outcome, or null when the
         * registry has nothing to say. It follows from the outcome alone, so the caller needs no second lookup of the
         * entry the heartbeat just resolved.
         */
        public String availability() {
            switch (this) {
                case LIVE:
                case REGISTERED:
                case RESUMED:
                case REKEYED:
                    return SensorState.LIVE.label();
                case OVER_CAP:
                case REKEYED_OVER_CAP:
                    return SensorState.OVER_CAP.label();
                default:
                    return null;
            }
        }
    }

    private final Supplier<Settings> settings;
    private final Clock clock;
    private final TeamResolver<?> teams;
    private final Supplier<UUID> ids;
    /** Which {@code SensorKind} codes this build registers; design-v0.3 turns kinds 1 and 2 on here. */
    private final IntPredicate supportedKinds;
    private final Map<UUID, SensorEntry> byId;
    private final Map<PosKey, UUID> byPosition;
    /** UUID -> tick of the last refusal; access-ordered, so the eldest untouched UUID is dropped first. */
    private final LinkedHashMap<UUID, Long> overCapRetry;
    private final int serverStartEpochMinute;

    private RegistryEvents events = RegistryEvents.NONE;
    private long duplicatesRekeyedTotal;
    private long quotaRefusedTotal;
    private boolean dirty;
    private SensorIdentity rekeyedIdentity;

    public SensorRegistryCore(Supplier<Settings> settings, Clock clock, TeamResolver<?> teams) {
        this(
            settings,
            clock,
            teams,
            UUID::randomUUID,
            SensorKind::isSupported,
            new HashMap<UUID, SensorEntry>(),
            new HashMap<PosKey, UUID>());
    }

    /**
     * Test seam: the maps are injected so a unit test can count lookups on the heartbeat fast path, and the set of
     * registered kinds so the design-v0.3 A1 rules can be exercised in a v0.2 build.
     */
    SensorRegistryCore(Supplier<Settings> settings, Clock clock, TeamResolver<?> teams, Supplier<UUID> ids,
        IntPredicate supportedKinds, Map<UUID, SensorEntry> byId, Map<PosKey, UUID> byPosition) {
        if (settings == null || clock == null || teams == null || ids == null || supportedKinds == null) {
            throw new IllegalArgumentException("settings, clock, teams, ids and supportedKinds are required");
        }
        this.settings = settings;
        this.clock = clock;
        this.teams = teams;
        this.ids = ids;
        this.supportedKinds = supportedKinds;
        this.byId = byId;
        this.byPosition = byPosition;
        this.overCapRetry = new LinkedHashMap<UUID, Long>(16, 0.75F, true) {

            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) {
                return size() > OVER_CAP_LRU_SIZE;
            }
        };
        this.serverStartEpochMinute = MinuteAccumulator.epochMinute(clock.epochSec());
    }

    public void setEvents(RegistryEvents listener) {
        this.events = listener == null ? RegistryEvents.NONE : listener;
    }

    /** The live settings: a test hook override is seen without restarting the registry. */
    public Settings settings() {
        return settings.get();
    }

    // --- queries ---

    public SensorEntry entry(UUID id) {
        return id == null ? null : byId.get(id);
    }

    /** The UUID indexed at this position and side, or null. */
    public UUID idAt(int dim, int x, int y, int z, int side) {
        return byPosition.get(new PosKey(dim, x, y, z, side));
    }

    /** Every entry, in no particular order; the view is unmodifiable but the entries are live objects. */
    public Collection<SensorEntry> entries() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }

    public int count(SensorState state) {
        int n = 0;
        for (SensorEntry entry : byId.values()) {
            if (entry.state() == state) {
                n++;
            }
        }
        return n;
    }

    /** LIVE + UNLOADED: what {@code limits.maxSensors} limits. */
    public int countedSensors() {
        int n = 0;
        for (SensorEntry entry : byId.values()) {
            if (entry.state()
                .countsTowardCaps()) {
                n++;
            }
        }
        return n;
    }

    public int tombstones() {
        int n = 0;
        for (SensorEntry entry : byId.values()) {
            if (entry.state()
                .isTombstone()) {
                n++;
            }
        }
        return n;
    }

    public long duplicatesRekeyedTotal() {
        return duplicatesRekeyedTotal;
    }

    public long quotaRefusedTotal() {
        return quotaRefusedTotal;
    }

    /** True while the persisted part of the registry changed since the last {@link #clearDirty()}. */
    public boolean dirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public void markDirty() {
        dirty = true;
    }

    /** Valid immediately after a {@link Heartbeat#REKEYED} result: the identity the cover must store. */
    public SensorIdentity rekeyedIdentity() {
        return rekeyedIdentity;
    }

    // --- heartbeat ---

    /**
     * The cover heartbeat of design-v0.2 sections 3.4 and 4.3.
     *
     * @param identity the cover's identity, which carries the sensor UUID
     * @param kind     the cover's {@code SensorKind}
     * @param tick     a monotonic server tick, used only for the OVER_CAP retry window
     */
    public Heartbeat heartbeat(SensorIdentity identity, int kind, int dim, int x, int y, int z, int side, long tick) {
        if (identity == null) {
            throw new IllegalArgumentException("identity");
        }
        SensorEntry entry = byId.get(identity.id());
        if (entry != null && entry.state() == SensorState.LIVE
            && entry.kind() == kind
            && entry.isAt(dim, x, y, z, side)) {
            entry.setLastHeartbeatTick(tick);
            return Heartbeat.LIVE;
        }
        return slowHeartbeat(entry, identity, kind, dim, x, y, z, side, tick);
    }

    private Heartbeat slowHeartbeat(SensorEntry entry, SensorIdentity identity, int kind, int dim, int x, int y, int z,
        int side, long tick) {
        rekeyedIdentity = null;
        if (!supportedKinds.test(kind)) {
            return Heartbeat.UNSUPPORTED_KIND;
        }
        if (entry != null) {
            boolean elsewhere = !entry.isAt(dim, x, y, z, side);
            boolean otherKind = entry.kind() != kind;
            if (otherKind || (elsewhere && entry.state()
                .countsTowardCaps())) {
                // Design-v0.2 section 4.3 duplicate row, plus design-v0.3 A1: a known UUID heartbeating with another
                // kind, or at another (position, side) while the original is still LIVE or UNLOADED, is a copy. The
                // original keeps its history; the newcomer gets a fresh UUID and is handled as unknown.
                return rekey(identity, kind, dim, x, y, z, side, tick);
            }
            return resume(entry, identity, kind, dim, x, y, z, side, tick);
        }
        return register(identity, kind, dim, x, y, z, side, tick);
    }

    private Heartbeat rekey(SensorIdentity identity, int kind, int dim, int x, int y, int z, int side, long tick) {
        UUID fresh = ids.get();
        while (fresh == null || byId.containsKey(fresh)) {
            fresh = ids.get();
        }
        SensorIdentity replacement = identity.withId(fresh);
        duplicatesRekeyedTotal++;
        Heartbeat outcome = register(replacement, kind, dim, x, y, z, side, tick);
        rekeyedIdentity = replacement;
        // The fresh identity is written into the cover either way (see REKEYED_OVER_CAP), but a caps refusal must not
        // read to the caller as a successful registration: there is no entry, and the player has to be told.
        return outcome == Heartbeat.OVER_CAP ? Heartbeat.REKEYED_OVER_CAP : Heartbeat.REKEYED;
    }

    private Heartbeat register(SensorIdentity identity, int kind, int dim, int x, int y, int z, int side, long tick) {
        if (!admit(identity, tick)) {
            return Heartbeat.OVER_CAP;
        }
        long now = clock.epochSec();
        replaceAt(dim, x, y, z, side, kind, identity.id(), now);
        SensorEntry entry = new SensorEntry(identity, kind, dim, x, y, z, side, SensorState.LIVE, now);
        byId.put(identity.id(), entry);
        byPosition.put(entry.posKey(), identity.id());
        entry.setLastHeartbeatTick(tick);
        entry.allocateLiveRings(settings().intervalTicks(), serverStartEpochMinute);
        dirty = true;
        events.sensorLive(entry);
        return Heartbeat.REGISTERED;
    }

    private Heartbeat resume(SensorEntry entry, SensorIdentity identity, int kind, int dim, int x, int y, int z,
        int side, long tick) {
        if (!entry.state()
            .countsTowardCaps() && !admit(identity, tick)) {
            // A tombstone that comes back is not counted yet, so the caps decide again.
            return Heartbeat.OVER_CAP;
        }
        long now = clock.epochSec();
        replaceAt(dim, x, y, z, side, kind, identity.id(), now);
        if (!entry.isAt(dim, x, y, z, side)) {
            unindex(entry);
            entry.moveTo(dim, x, y, z, side);
        }
        byPosition.put(entry.posKey(), identity.id());
        // The cover NBT is the source of truth for label, owner and creation time (design-v0.2 section 3.3).
        entry.setIdentity(identity);
        entry.setState(SensorState.LIVE, RemovalCause.NONE, now);
        entry.setLastSeenEpochSec(now);
        entry.setLastHeartbeatTick(tick);
        entry.clearStrikes();
        entry.allocateLiveRings(settings().intervalTicks(), serverStartEpochMinute);
        dirty = true;
        events.sensorLive(entry);
        return Heartbeat.RESUMED;
    }

    /**
     * The caps of design-v0.2 section 4.2 with the section 4.3 retry window: a refused UUID is only re-checked every
     * {@link #OVER_CAP_RETRY_TICKS} ticks, so a world full of refused sensors costs one LRU lookup per heartbeat.
     */
    private boolean admit(SensorIdentity identity, long tick) {
        UUID id = identity.id();
        Long refusedAt = overCapRetry.get(id);
        if (refusedAt != null && tick - refusedAt.longValue() < OVER_CAP_RETRY_TICKS) {
            return false;
        }
        if (!capsAllow(identity)) {
            overCapRetry.put(id, Long.valueOf(tick));
            quotaRefusedTotal++;
            return false;
        }
        overCapRetry.remove(id);
        return true;
    }

    private boolean capsAllow(SensorIdentity identity) {
        // The registry keeps one resolver for the whole run, so team merges, leaves and kicks must be seen: each cap
        // question starts from a cleared cache (design-v0.2 section 5 "cached for one rebuild").
        teams.clearCache();
        Settings active = settings();
        int max = active.maxSensors();
        int perTeam = active.maxSensorsPerTeam();
        UUID owner = identity.owner();
        int counted = 0;
        int team = 0;
        for (SensorEntry entry : byId.values()) {
            if (!entry.state()
                .countsTowardCaps()) {
                continue;
            }
            counted++;
            if (perTeam > 0 && sharesQuota(owner, entry.owner())) {
                team++;
            }
        }
        if (counted >= max) {
            return false;
        }
        return perTeam <= 0 || team < perTeam;
    }

    /**
     * Whether two owners share a per-team quota: the same player, the same team, or both unowned (design-v0.2 section
     * 4.2: all unowned sensors share one pseudo-owner, and an owner with no team is counted on its own).
     */
    private boolean sharesQuota(UUID a, UUID b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return teams.sameTeam(a, b);
    }

    /** Design-v0.2 section 4.3: whatever was indexed at this (position, side) is REPLACED. */
    private void replaceAt(int dim, int x, int y, int z, int side, int kind, UUID incoming, long now) {
        replaceOne(byPosition.get(new PosKey(dim, x, y, z, side)), incoming, kind, false, now);
        if (kind != SensorKind.MACHINE) {
            return;
        }
        // Design-v0.3 A1: only one Machine Sensor may exist per machine, so a kind-0 UUID on any other face of this
        // block is stale. Other kinds on the same block are left alone.
        for (int other = 0; other < SIDES; other++) {
            if (other == side) {
                continue;
            }
            replaceOne(byPosition.get(new PosKey(dim, x, y, z, other)), incoming, kind, true, now);
        }
    }

    private void replaceOne(UUID occupant, UUID incoming, int kind, boolean sameKindOnly, long now) {
        if (occupant == null || occupant.equals(incoming)) {
            return;
        }
        SensorEntry entry = byId.get(occupant);
        if (entry == null || entry.state()
            .isTombstone()) {
            return;
        }
        if (sameKindOnly && entry.kind() != kind) {
            return;
        }
        toTombstone(entry, SensorState.REMOVED, RemovalCause.REPLACED, GapReason.SENSOR_REMOVED, now);
    }

    // --- cover and sampler reports ---

    /**
     * Design-v0.2 section 4.3: the chunk or dimension went away, so the entry becomes UNLOADED and its open minute
     * closes with the reason given. Ignored unless a LIVE entry sits exactly there.
     *
     * @return true if the entry changed
     */
    public boolean unloaded(UUID id, int dim, int x, int y, int z, int side, GapReason reason) {
        SensorEntry entry = byId.get(id);
        if (entry == null || entry.state() != SensorState.LIVE || !entry.isAt(dim, x, y, z, side)) {
            return false;
        }
        long now = clock.epochSec();
        closeOpenMinute(entry, reason);
        // The registry observed the sensor right up to this moment, and an UNLOADED entry expires on lastSeen
        // (housekeeping below). Only a successful sample refreshes it otherwise, so with sampling.enabled=false the
        // stale window would be measured from registration and a sensor that heartbeated for a month would be
        // expired by its first unload. This is a slow path: the heartbeat fast path stays clock-free.
        entry.setLastSeenEpochSec(now);
        entry.setState(SensorState.UNLOADED, RemovalCause.NONE, now);
        // The listener is told while the entry still knows its sampler bucket, so GS-108's schedule can drop it with
        // an O(1) swap-remove; the bucket is cleared afterwards for a run with no sampler installed.
        events.sensorInactive(entry);
        entry.freeSecondRing();
        entry.setBucket(-1);
        entry.setBucketSlot(-1);
        entry.clearStrikes();
        dirty = true;
        return true;
    }

    /**
     * Design-v0.2 section 4.3: the cover was detached or went into a drop. The partial minute gets
     * {@code sensor_removed}, the RAM rings are freed and the entry becomes a tombstone. Ignored unless the entry sits
     * exactly there and still counts.
     *
     * @return true if the entry changed
     */
    public boolean removed(UUID id, int dim, int x, int y, int z, int side, RemovalCause cause) {
        SensorEntry entry = byId.get(id);
        if (entry == null || !entry.state()
            .countsTowardCaps() || !entry.isAt(dim, x, y, z, side)) {
            return false;
        }
        SensorState state = cause == RemovalCause.IN_ITEM ? SensorState.IN_ITEM : SensorState.REMOVED;
        toTombstone(entry, state, cause, GapReason.SENSOR_REMOVED, clock.epochSec());
        return true;
    }

    /**
     * Design-v0.2 section 4.3: a validation found the chunk loaded but no matching cover. Records a
     * {@code target_missing} second and, at {@link SensorEntry#MAX_STRIKES} in a row, turns the entry into a MISSING
     * tombstone.
     *
     * @return true if the entry became MISSING
     */
    public boolean strike(UUID id) {
        SensorEntry entry = byId.get(id);
        if (entry == null || entry.state() != SensorState.LIVE) {
            return false;
        }
        long now = clock.epochSec();
        if (entry.seconds() != null) {
            entry.seconds()
                .appendGap((int) now, GapReason.TARGET_MISSING);
        }
        if (entry.counters() != null) {
            entry.counters()
                .onGap(GapReason.TARGET_MISSING, 1L);
        }
        storeSlot(
            entry,
            entry.accumulator() == null ? null
                : entry.accumulator()
                    .gap(now, GapReason.TARGET_MISSING));
        entry.setLastGapReason(GapReason.TARGET_MISSING.bit());
        if (entry.addStrike() < SensorEntry.MAX_STRIKES) {
            return false;
        }
        // The partial minute is closed with the same reason the strike seconds carry, exactly as the detach and
        // in-item rows do. Without it the rings are freed with up to 59 s of folded history in them, and the machine's
        // last minute - the interesting one - would be a hole in the file instead of a partial minute.
        toTombstone(entry, SensorState.MISSING, RemovalCause.TARGET_MISSING, GapReason.TARGET_MISSING, now);
        return true;
    }

    /**
     * Stores a minute the sampler closed while folding a sample (design-v0.2 section 6.2) into the entry's ring and
     * hands it to {@link RegistryEvents#minuteClosed}, which is what GS-109 writes to disk. The core owns this step
     * so that every closed minute, wherever it came from, reaches the ring and the I/O queue the same way.
     */
    public void storeClosedMinute(SensorEntry entry, MinuteSlot slot) {
        storeSlot(entry, slot);
    }

    /** A validation found the sensor where it should be: the strike count resets. */
    public void validated(UUID id) {
        SensorEntry entry = byId.get(id);
        if (entry != null) {
            entry.clearStrikes();
        }
    }

    // --- expiry, purge, housekeeping ---

    /**
     * Design-v0.2 section 4.3: {@code /gregscope purge} expires an entry immediately. A LIVE sensor that heartbeats
     * again registers again with the same UUID and empty history.
     *
     * @return true if an entry was removed
     */
    public boolean purge(UUID id) {
        SensorEntry entry = byId.get(id);
        if (entry == null) {
            return false;
        }
        expire(entry);
        return true;
    }

    /** Purges every entry and forgets every refusal. @return how many entries were removed */
    public int purgeAll() {
        List<SensorEntry> all = new ArrayList<>(byId.values());
        for (SensorEntry entry : all) {
            expire(entry);
        }
        overCapRetry.clear();
        return all.size();
    }

    /**
     * Design-v0.2 section 4.3: expire tombstones past {@code history.removedRetentionHours}, UNLOADED entries past
     * {@code history.staleExpiryDays}, and the oldest tombstones beyond {@code limits.maxSensors}. O(entries), never
     * touches the world.
     *
     * @return how many entries were removed
     */
    public int housekeeping() {
        long now = clock.epochSec();
        Settings active = settings();
        long tombstoneAge = active.removedRetentionHours() * SECONDS_PER_HOUR;
        long staleAge = active.staleExpiryDays() * SECONDS_PER_DAY;
        List<SensorEntry> expired = new ArrayList<>();
        for (SensorEntry entry : byId.values()) {
            if (entry.state()
                .isTombstone()) {
                if (now - entry.stateSinceEpochSec() > tombstoneAge) {
                    expired.add(entry);
                }
            } else if (entry.state() == SensorState.UNLOADED && now - entry.lastSeenEpochSec() > staleAge) {
                expired.add(entry);
            }
        }
        for (SensorEntry entry : expired) {
            expire(entry);
        }
        int removed = expired.size();
        removed += evictOldestTombstones();
        return removed;
    }

    /** Design-v0.2 section 4.2: the number of tombstones is capped, and the oldest expires first. */
    private int evictOldestTombstones() {
        int max = settings().maxSensors();
        List<SensorEntry> tombstones = new ArrayList<>();
        for (SensorEntry entry : byId.values()) {
            if (entry.state()
                .isTombstone()) {
                tombstones.add(entry);
            }
        }
        if (tombstones.size() <= max) {
            return 0;
        }
        Collections.sort(tombstones, (a, b) -> Long.compare(a.stateSinceEpochSec(), b.stateSinceEpochSec()));
        int excess = tombstones.size() - max;
        for (int i = 0; i < excess; i++) {
            expire(tombstones.get(i));
        }
        return excess;
    }

    private void expire(SensorEntry entry) {
        if (entry.state() == SensorState.LIVE) {
            events.sensorInactive(entry);
        }
        byId.remove(entry.id());
        unindex(entry);
        entry.freeRings();
        dirty = true;
        events.sensorExpired(entry.id(), entry.kind());
    }

    // --- shared transitions ---

    private void toTombstone(SensorEntry entry, SensorState state, RemovalCause cause, GapReason reason, long now) {
        boolean wasLive = entry.state() == SensorState.LIVE;
        if (reason != null) {
            closeOpenMinute(entry, reason);
        }
        entry.setState(state, cause, now);
        if (wasLive) {
            // Before freeRings, so the entry still knows its sampler bucket (see unloaded()).
            events.sensorInactive(entry);
        }
        entry.freeRings();
        unindex(entry);
        dirty = true;
    }

    /** Drops the reverse-index mapping, but only while it still points at this entry. */
    private void unindex(SensorEntry entry) {
        PosKey key = entry.posKey();
        UUID indexed = byPosition.get(key);
        if (entry.id()
            .equals(indexed)) {
            byPosition.remove(key);
        }
    }

    private void closeOpenMinute(SensorEntry entry, GapReason reason) {
        entry.setLastGapReason(reason.bit());
        MinuteAccumulator accumulator = entry.accumulator();
        if (accumulator == null) {
            return;
        }
        storeSlot(entry, accumulator.closePartial(reason));
    }

    private void storeSlot(SensorEntry entry, MinuteSlot slot) {
        if (slot == null) {
            return;
        }
        MinuteRing ring = entry.minutes();
        if (ring == null) {
            return;
        }
        MinuteSlot stored = ring.put(slot);
        if (stored == null) {
            return;
        }
        if (entry.accumulator() != null) {
            entry.accumulator()
                .noteWritten(stored.epochMinute());
        }
        events.minuteClosed(entry, stored);
    }
}
