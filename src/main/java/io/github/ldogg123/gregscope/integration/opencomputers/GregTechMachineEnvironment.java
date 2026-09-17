package io.github.ldogg123.gregscope.integration.opencomputers;

import net.minecraft.tileentity.TileEntity;

import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.probe.MachineProbe;
import li.cil.oc.api.Network;
import li.cil.oc.api.driver.NamedBlock;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.ManagedEnvironment;

/**
 * Read-only OpenComputers environment exposing {@code getSnapshot()} for one GT machine. Holds only the tile reference;
 * the probe revalidates the target on every call. Does not tick.
 *
 * <p>
 * This class must stay final and declare its callbacks directly: OpenComputers only invokes callbacks of a merged
 * (Adapter) component whose declaring class is exactly the environment's class.
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

    private final MachineProbe probe;
    private final TileEntity tile;

    GregTechMachineEnvironment(MachineProbe probe, TileEntity tile) {
        this.probe = probe;
        this.tile = tile;
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
        MachineSnapshot snapshot = probe.snapshot(tile);
        return snapshot == null ? new Object[] { null, UNAVAILABLE } : new Object[] { snapshot.toMap() };
    }
}
