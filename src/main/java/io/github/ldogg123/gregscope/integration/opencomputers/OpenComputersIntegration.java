package io.github.ldogg123.gregscope.integration.opencomputers;

import io.github.ldogg123.gregscope.probe.GregTechMachineProbe;
import li.cil.oc.api.Driver;

/** Registers GregScope's OpenComputers driver. */
public final class OpenComputersIntegration {

    private OpenComputersIntegration() {}

    /**
     * Must run during Forge init: OpenComputers accepts drivers until its own postInit locks the registry
     * (server/driver/Registry.scala: {@code locked}). {@code required-after:OpenComputers} guarantees OC's preInit has
     * installed the driver API before this runs.
     */
    public static void register() {
        Driver.add(new GregTechMachineDriver(new GregTechMachineProbe()));
    }
}
