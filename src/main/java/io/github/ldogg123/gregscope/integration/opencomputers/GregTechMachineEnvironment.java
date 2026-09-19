package io.github.ldogg123.gregscope.integration.opencomputers;

import java.util.UUID;

import net.minecraft.world.World;

import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.history.GapRanges;
import io.github.ldogg123.gregscope.history.MinuteSlot;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.probe.MachineProbe;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sampling.SensorView;
import li.cil.oc.api.Network;
import li.cil.oc.api.driver.NamedBlock;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.ManagedEnvironment;

/**
 * Read-only OpenComputers environment exposing {@code getSnapshot()} for the GT machine at one position: the block the
 * Adapter touches. It holds the world and coordinates, not a tile entity, and resolves the machine on every call, so it
 * follows a machine whose chunk was unloaded and loaded again (a new tile entity) and reports whatever supported GT
 * machine is at that position now. Does not tick.
 *
 * <p>
 * This class must stay final and declare its callbacks directly: OpenComputers only invokes callbacks of a merged
 * (Adapter) component whose declaring class is exactly the environment's class.
 *
 * <p>
 * <b>GS-115 (design-v0.2 sections 10.1, 10.2 and 10.4).</b> {@code getSensor} and {@code getSensorHistory} are added
 * next to the v0.1 {@code getSnapshot}, whose output is unchanged. Neither is {@code direct}, so both run on the
 * server thread, where the registry and the history rings live, exactly as {@code getSnapshot} reads the live holder
 * there. Both are read-only: nothing here writes to the registry, a ring, a cover or a machine.
 *
 * <p>
 * <b>Adding callbacks does not change an Adapter's address (erratum E3).</b> OpenComputers keys a merged component's
 * saved address by the driver set of the block, not by the method list of an environment, so a world saved with v0.1
 * keeps its {@code component.address()} after the upgrade. {@code OcMachineSensorTests.addressStableAcrossChunkReload}
 * asserts it on a running server.
 */
public final class GregTechMachineEnvironment extends ManagedEnvironment implements NamedBlock {

    /** Component name used only when no other OC driver matches the block (e.g. OC's GregTech integration disabled). */
    public static final String COMPONENT_NAME = "gt_machine";

    /**
     * Below every OpenComputers GregTech driver (energy container -1, LSC 0, BEC 10). OC names a merged Adapter
     * component after the highest-priority environment, so existing names such as {@code gt_energycontainer},
     * {@code lsc} and {@code bec_*} stay unchanged and simply gain {@code getSnapshot}.
     */
    public static final int PRIORITY = -10;

    static final String UNAVAILABLE = "machine unavailable";

    /** The six real {@code ForgeDirection} faces a cover can sit on; {@code UNKNOWN} is never a cover side. */
    private static final int SIDES = 6;

    private final MachineProbe probe;
    private final World world;
    private final int x;
    private final int y;
    private final int z;

    GregTechMachineEnvironment(MachineProbe probe, World world, int x, int y, int z) {
        this.probe = probe;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
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

    @Callback(
        doc = "function():table -- Read-only GT machine telemetry snapshot (schema v1), or nil and an error message.")
    public Object[] getSnapshot(Context context, Arguments args) {
        // Detailed: a script asked, so it can pay for the ME stocking-bus lookup the sampler skips (GS-306).
        MachineSnapshot snapshot = probe.detailedSnapshotAt(world, x, y, z);
        return snapshot == null ? new Object[] { null, UNAVAILABLE } : new Object[] { snapshot.toMap() };
    }

    /**
     * Design-v0.2 section 10.2. The record of the Machine Sensor cover on this machine, or {@code nil, "no sensor"}.
     * No permission check: a computer that reaches this component is wired to an Adapter touching the machine itself,
     * which is the same physical access {@code getSnapshot} already grants.
     */
    @Callback(
        doc = "function():table -- Machine Sensor record (sensorRecordVersion 1) of this machine's sensor, or nil and an error message.")
    public Object[] getSensor(Context context, Arguments args) {
        SensorEntry entry = sensorEntry();
        if (entry == null) {
            return new Object[] { null, LuaTables.ERROR_NO_SENSOR };
        }
        long now = GregScope.clock()
            .epochSec();
        return new Object[] { LuaTables.sensorRecord(new SensorView(entry), entry.historyLoaded(), now) };
    }

    /**
     * Design-v0.2 sections 10.2 and 10.4. History of this machine's sensor at second or minute resolution.
     *
     * <p>
     * {@code resolution} is read with {@code optString} rather than {@code checkString}, so calling it with no
     * argument at all is the soft error {@code nil, "bad resolution"} instead of a Lua exception, and so is any other
     * <b>string</b> value. An argument of the wrong Lua <em>type</em> is not: {@code ArgumentsImpl.optString} falls
     * through to {@code checkString} for a defined argument, so OpenComputers raises its own
     * {@code bad argument #1 (string expected, got number)}, which is what every OC component does and what
     * {@code docs/opencomputers.md} documents. {@code count} is clamped rather than refused (section 10.4), and
     * {@code before} defaults to now.
     */
    @Callback(
        doc = "function(resolution:string[, count:number[, before:number]]):table -- History (historyVersion 1) of this machine's sensor; resolution is \"second\" or \"minute\".")
    public Object[] getSensorHistory(Context context, Arguments args) {
        String resolution = args.optString(0, "");
        if (!LuaTables.isResolution(resolution)) {
            return new Object[] { null, LuaTables.ERROR_BAD_RESOLUTION };
        }
        SensorEntry entry = sensorEntry();
        if (entry == null || entry.minutes() == null) {
            return new Object[] { null, LuaTables.ERROR_NO_SENSOR };
        }
        long now = GregScope.clock()
            .epochSec();
        int count = LuaTables.clampCount(resolution, args.optInteger(1, LuaTables.DEFAULT_COUNT));
        long before = args.optLong(2, now);
        SensorRegistry registry = GregScope.registry();
        GapRanges.Context gaps = registry.core()
            .gapContext(entry.id(), now);
        if (LuaTables.RESOLUTION_SECOND.equals(resolution)) {
            return new Object[] { LuaTables.secondHistory(entry.id(), entry.seconds(), count, before, gaps) };
        }
        if (!entry.historyLoaded()) {
            return new Object[] { null, LuaTables.ERROR_HISTORY_LOADING };
        }
        int expected = MinuteSlot.expectedSamples(
            GregScope.settings()
                .intervalTicks());
        return new Object[] { LuaTables.minuteHistory(entry.id(), entry.minutes(), count, before, expected, gaps) };
    }

    /**
     * The registry entry of the Machine Sensor on this machine, or null.
     *
     * <p>
     * The registry indexes a sensor by block position <em>and</em> cover side, and a machine may carry a sensor on
     * more than one face, so the six sides are asked in {@code ForgeDirection} order and a LIVE entry wins over an
     * UNLOADED one; ties go to the lowest side ordinal. Section 10.2 says "this machine's sensor" and names no
     * tie-break, and the choice has to be deterministic so two calls a tick apart do not answer about different
     * covers. Tombstones never appear here: the registry drops a removed sensor from its position index.
     */
    private SensorEntry sensorEntry() {
        SensorRegistry registry = GregScope.registry();
        if (registry == null || world == null || world.provider == null) {
            return null;
        }
        int dim = world.provider.dimensionId;
        SensorEntry best = null;
        for (int side = 0; side < SIDES; side++) {
            UUID id = registry.core()
                .idAt(dim, x, y, z, side);
            SensorEntry entry = id == null ? null
                : registry.core()
                    .entry(id);
            if (entry == null) {
                continue;
            }
            if (entry.state() == SensorState.LIVE) {
                return entry;
            }
            if (best == null) {
                best = entry;
            }
        }
        return best;
    }

}
