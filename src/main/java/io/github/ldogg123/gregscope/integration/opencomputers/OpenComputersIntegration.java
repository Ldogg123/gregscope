package io.github.ldogg123.gregscope.integration.opencomputers;

import io.github.ldogg123.gregscope.probe.GregTechMachineProbe;
import li.cil.oc.api.Driver;

/** Registers GregScope's OpenComputers drivers. */
public final class OpenComputersIntegration {

    private OpenComputersIntegration() {}

    /**
     * Must run during Forge init: OpenComputers accepts drivers until its own postInit locks the registry
     * (server/driver/Registry.scala: {@code locked}). {@code required-after:OpenComputers} guarantees OC's preInit has
     * installed the driver API before this runs.
     *
     * <p>
     * Two drivers, and they can never both match a block: one wants an {@code IGregTechTileEntity}, the other the
     * Telemetry Hub's own tile entity. {@code Driver.driverFor} merges every matching driver into one compound
     * component (server/driver/CompoundBlockDriver.scala), so a GT machine keeps its v0.1 component name and a Hub is
     * named after the only driver that matches it: {@code gregscope_hub} (design-v0.2 section 10.3).
     */
    public static void register() {
        Driver.add(new GregTechMachineDriver(new GregTechMachineProbe()));
        Driver.add(new HubDriver());
    }
}
