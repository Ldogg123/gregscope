package io.github.ldogg123.gregscope.integration.opencomputers;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.Tags;
import io.github.ldogg123.gregscope.access.AccessPolicy;
import io.github.ldogg123.gregscope.access.GtnhlibTeamResolver;
import io.github.ldogg123.gregscope.history.GapRanges;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.hub.TileTelemetryHub;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.sampling.SensorView;
import io.github.ldogg123.gregscope.sampling.TelemetryFrame;
import li.cil.oc.api.Network;
import li.cil.oc.api.driver.NamedBlock;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.ManagedEnvironment;

/**
 * The {@code gregscope_hub} OpenComputers component of design-v0.2 section 10.3: a read-only window on one Telemetry
 * Hub's scope. Reached by putting an OpenComputers Adapter against a Hub.
 *
 * <p>
 * This class must stay final and declare its callbacks directly, the same OpenComputers rule v0.1 follows: OC only
 * invokes callbacks whose declaring class is exactly the environment's class.
 *
 * <p>
 * <b>Scope (section 5).</b> A computer sees what the Hub sees, not what its owner sees: rows are filtered with
 * {@link AccessPolicy#inHubScope}, which takes no viewer at all, so physical access to the Hub is the whole
 * permission model. An unowned Hub shows nothing, and an id outside the scope is answered with
 * {@code nil, "sensor not found"} - it never reveals that the sensor exists.
 *
 * <p>
 * <b>Read-only.</b> Nothing here writes to a registry, a ring, a cover, a Hub or a machine; the Hub component has no
 * counterpart to the GUI's label field on purpose (section 1.4 keeps OpenComputers read-only).
 *
 * <p>
 * <b>Why no callback is {@code direct} (deviation from section 10.3, recorded in the implementation notes).</b>
 * Section 10.3 marks {@code getInfo}, {@code listSensors} and {@code getLatest} direct, and section 6.3 words that as
 * "direct callbacks read the frame only". A frame is immutable and published through a {@code volatile}, so reading
 * one from a computer thread is indeed safe - but none of the three can answer from the frame alone:
 * <ul>
 * <li>the scope filter needs {@code sameTeam}, and {@code GtnhlibTeamResolver} scans
 * {@code TeamManager.getTeamMap()}, a plain {@code java.util.HashMap} ({@code gtnhlib/.../teams/TeamManager.java:31}),
 * asking {@code Team.isMember} of an {@code ObjectOpenHashSet} ({@code Team.java:25,57-58}). Both are mutated on the
 * server thread by the team commands, and neither is safe to iterate from another thread;</li>
 * <li>the Hub's owner lives on a {@code TileEntity}, which is server-thread state as well.</li>
 * </ul>
 * The alternative - keeping the scope in a snapshot refreshed from OpenComputers' per-tick {@code update()} hook -
 * is periodic work that design-v0.2 section 1.4 does not lift and {@code ShippedClassesTest} guards against. Running
 * the three on the server thread costs a computer one tick of latency per call and nothing else, so that is what they
 * do. {@code OcHubTests.callbacksAreServerThreadOnly} pins it.
 */
public final class HubEnvironment extends ManagedEnvironment implements NamedBlock {

    /** Design-v0.2 section 10.3: the component name, frozen before the first public jar (section 17). */
    public static final String COMPONENT_NAME = "gregscope_hub";

    /** Section 10.3. Nothing else drives a Hub, so the merged component is always named after this environment. */
    public static final int PRIORITY = 0;

    /** The world and position of the Hub, so a replaced tile entity object can be found again. */
    private final World world;
    private final int x;
    private final int y;
    private final int z;

    private TileTelemetryHub tile;

    /** The last built scope and the two inputs it was built from; server thread only. */
    private HubScope scope = HubScope.EMPTY;
    private TelemetryFrame scopeFrame;
    private UUID scopeOwner;
    private boolean scopeBuilt;

    HubEnvironment(TileTelemetryHub tile) {
        this.tile = tile;
        this.world = tile.getWorldObj();
        this.x = tile.xCoord;
        this.y = tile.yCoord;
        this.z = tile.zCoord;
        setNode(
            Network.newNode(this, Visibility.Network)
                .withComponent(COMPONENT_NAME)
                .create());
    }

    @Override
    public String preferredName() {
        return COMPONENT_NAME;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    /**
     * Design-v0.2 section 10.3 {@code getInfo}: the Hub, its scope and the freshness of the data behind it. Always a
     * table, even for an unowned Hub or before the sampler published its first frame.
     */
    @Callback(
        doc = "function():table -- GregScope and Telemetry Hub information: versions, owner, scope size, limits and frame age.")
    public Object[] getInfo(Context context, Arguments args) {
        HubScope current = scope();
        return new Object[] { LuaTables.hubInfo(
            current,
            Tags.VERSION,
            GregScope.settings()
                .intervalTicks(),
            now()) };
    }

    /**
     * Design-v0.2 section 10.3 {@code listSensors}: one page of sensor records (section 10.1) in sensor UUID order.
     * Both arguments are clamped rather than refused, and an offset past the end is an empty page.
     */
    @Callback(
        doc = "function([offset:number[, limit:number]]):table -- Sensor records in this Hub's scope: {total, offset, sensors}. limit is clamped to 1..64.")
    public Object[] listSensors(Context context, Arguments args) {
        HubScope current = scope();
        int offset = args.optInteger(0, 0);
        int limit = args.optInteger(1, HubScope.DEFAULT_LIST_LIMIT);
        return new Object[] { LuaTables.sensorList(current, offset, limit, now()) };
    }

    /**
     * Design-v0.2 section 10.3 {@code getLatest}: the sensor record and, beside it, the exact schema v1 snapshot map
     * {@code getSnapshot} would return for the same machine - or the record and {@code nil} when the sensor has no
     * snapshot at all (it was never sampled, or it is a sensor kind that has none).
     */
    @Callback(
        doc = "function(idOrPrefix:string):table,table -- Sensor record and its last schema v1 snapshot, or nil and an error message.")
    public Object[] getLatest(Context context, Arguments args) {
        HubScope current = scope();
        HubScope.Lookup found = current.lookup(args.optString(0, ""));
        if (found.entry() == null) {
            return new Object[] { null, found.error() };
        }
        SensorView view = found.entry()
            .view();
        Map<String, Object> record = LuaTables.sensorRecord(
            view,
            found.entry()
                .historyLoaded(),
            now());
        MachineSnapshot snapshot = view.lastSnapshot();
        return new Object[] { record, snapshot == null ? null : snapshot.toMap() };
    }

    /**
     * Design-v0.2 sections 10.3 and 10.4: history of one sensor in this Hub's scope. The rings live on the server
     * thread, and so does this callback.
     *
     * <p>
     * The resolution is checked before the id, exactly as the machine component does, and it is read with
     * {@code optString} so calling the callback with too few arguments is the soft error {@code nil, "bad
     * resolution"} instead of a Lua exception. That soft error covers <b>string</b> values and missing arguments
     * only: {@code optString} falls through to {@code checkString} for an argument that is present, so a wrong-typed
     * one raises OpenComputers' own {@code bad argument #N (string expected, got number)} like any other OC
     * component. Worth knowing because this callback takes {@code idOrPrefix} first while the machine component's
     * takes {@code resolution} first. A sensor in scope that holds no minute ring at all - a tombstone
     * waiting to be purged - answers {@code nil, "sensor not found"}: section 10.4 has no other error for "known, but
     * there is nothing to read".
     */
    @Callback(
        doc = "function(idOrPrefix:string, resolution:string[, count:number[, before:number]]):table -- History (historyVersion 1) of one sensor in this Hub's scope.")
    public Object[] getSensorHistory(Context context, Arguments args) {
        String resolution = args.optString(1, "");
        if (!LuaTables.isResolution(resolution)) {
            return new Object[] { null, LuaTables.ERROR_BAD_RESOLUTION };
        }
        HubScope current = scope();
        HubScope.Lookup found = current.lookup(args.optString(0, ""));
        if (found.entry() == null) {
            return new Object[] { null, found.error() };
        }
        UUID id = found.entry()
            .view()
            .id();
        SensorRegistry registry = GregScope.registry();
        SensorEntry entry = registry == null ? null
            : registry.core()
                .entry(id);
        if (entry == null || entry.minutes() == null) {
            return new Object[] { null, LuaTables.ERROR_SENSOR_NOT_FOUND };
        }
        long now = now();
        int count = LuaTables.clampCount(resolution, args.optInteger(2, LuaTables.DEFAULT_COUNT));
        long before = args.optLong(3, now);
        GapRanges.Context gaps = registry.core()
            .gapContext(id, now);
        if (LuaTables.RESOLUTION_SECOND.equals(resolution)) {
            return new Object[] { LuaTables.secondHistory(id, entry.seconds(), count, before, gaps) };
        }
        if (!entry.historyLoaded()) {
            return new Object[] { null, LuaTables.ERROR_HISTORY_LOADING };
        }
        int expected = MinuteSlot.expectedSamples(
            GregScope.settings()
                .intervalTicks());
        return new Object[] { LuaTables.minuteHistory(id, entry.minutes(), count, before, expected, gaps) };
    }

    /**
     * The Hub's scope, rebuilt when the frame or the Hub owner changed since the last call. Server thread only.
     *
     * <p>
     * The cache is what design-v0.2 section 5 already allows a team lookup to be: valid "for one sampler-frame
     * sequence". A computer polling in a loop therefore costs one team scan per sampling interval, not one per call.
     */
    private HubScope scope() {
        TileTelemetryHub hub = hub();
        TelemetryFrame frame = GregScope.frame();
        UUID owner = hub == null ? null : hub.owner();
        if (scopeBuilt && scopeFrame == frame && Objects.equals(scopeOwner, owner)) {
            return scope;
        }
        scope = HubScope.of(
            frame,
            owner,
            hub == null ? "" : hub.ownerName(),
            new AccessPolicy(GregScope.settings()),
            new GtnhlibTeamResolver(),
            HubEnvironment::historyLoaded);
        scopeFrame = frame;
        scopeOwner = owner;
        scopeBuilt = true;
        return scope;
    }

    /**
     * The Hub this component belongs to, or null when there is none any more. The tile entity object is re-read from
     * the world when the one held here went invalid, which is what a chunk reload does to it; the block itself going
     * away makes the Adapter drop this environment anyway.
     */
    private TileTelemetryHub hub() {
        if (tile != null && !tile.isInvalid()) {
            return tile;
        }
        tile = null;
        if (world == null) {
            return null;
        }
        TileEntity found;
        try {
            found = world.getTileEntity(x, y, z);
        } catch (RuntimeException e) {
            return null;
        }
        if (found instanceof TileTelemetryHub) {
            tile = (TileTelemetryHub) found;
        }
        return tile;
    }

    /** Design-v0.2 section 8.4: whether a sensor's minute ring was read back from disk. Server thread only. */
    private static boolean historyLoaded(UUID id) {
        SensorRegistry registry = GregScope.registry();
        if (registry == null) {
            return false;
        }
        SensorEntry entry = registry.core()
            .entry(id);
        return entry != null && entry.historyLoaded();
    }

    private static long now() {
        return GregScope.clock()
            .epochSec();
    }
}
