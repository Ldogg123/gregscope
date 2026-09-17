package io.github.ldogg123.gregscope;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;

@Mod(
    modid = GregScope.MODID,
    version = Tags.VERSION,
    name = GregScope.NAME,
    acceptedMinecraftVersions = "[1.7.10]",
    // Server-side only: no blocks, items or packets, so clients without GregScope may join.
    acceptableRemoteVersions = "*",
    dependencies = "required-after:gregtech;required-after:OpenComputers")
public class GregScope {

    public static final String MODID = "gregscope";
    public static final String NAME = "GregScope";
    public static final Logger LOG = LogManager.getLogger(MODID);

    // v0.1 has no client-only code, so both sides share the common proxy.
    @SidedProxy(
        clientSide = "io.github.ldogg123.gregscope.CommonProxy",
        serverSide = "io.github.ldogg123.gregscope.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
