package io.github.ldogg123.gregscope;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import io.github.ldogg123.gregscope.integration.opencomputers.OpenComputersIntegration;

/** Server and common proxy; {@link ClientProxy} extends it on the client. */
public class CommonProxy {

    // OpenComputers accepts drivers only until its postInit locks the registry.
    public void init(FMLInitializationEvent event) {
        OpenComputersIntegration.register();
    }
}
