package io.github.ldogg123.gregscope;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import io.github.ldogg123.gregscope.integration.opencomputers.OpenComputersIntegration;

public class CommonProxy {

    // OpenComputers accepts drivers only until its postInit locks the registry.
    public void init(FMLInitializationEvent event) {
        OpenComputersIntegration.register();
    }
}
