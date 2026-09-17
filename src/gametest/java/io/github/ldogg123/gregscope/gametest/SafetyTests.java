package io.github.ldogg123.gregscope.gametest;

import java.util.HashMap;
import java.util.Map;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.NetworkModHolder;
import cpw.mods.fml.relauncher.Side;
import io.github.ldogg123.gregscope.CommonProxy;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.Tags;

/** Dedicated-server safety of the v0.2 skeleton (GS-101; GS-119 adds the GUI class-loading checks). */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope" })
public class SafetyTests {

    private static final String BATCH = "gregscope.safety";

    private SafetyTests() {}

    /** The dedicated server uses CommonProxy and never loads ClientProxy (whose static initializer sets the marker). */
    @GameTest(batch = BATCH)
    public static void clientProxyNotLoaded(GameTestHelper helper) {
        helper.assertTrue(
            FMLCommonHandler.instance()
                .getSide() == Side.SERVER,
            "not a server side");
        // Literal on purpose: referencing ClientProxy.LOADED_PROPERTY would not load the class either (a compile-time
        // constant), but a literal keeps this test free of any ClientProxy reference.
        helper.assertNull(System.getProperty("gregscope.clientProxyLoaded"), "gregscope.clientProxyLoaded");
        helper.assertNotNull(GregScope.proxy, "proxy not injected");
        helper.assertEquals(CommonProxy.class, GregScope.proxy.getClass(), "proxy class");
        helper.succeed();
    }

    /**
     * Automated half of the GS-101 manual check "a client without GregScope is rejected": asks FML's own network
     * checker for GregScope (the one {@code FMLHandshakeServerState} consults through
     * {@code FMLNetworkHandler.checkModList(client, Side.CLIENT)}) about client mod lists. A real client connection
     * stays manual.
     */
    @GameTest(batch = BATCH)
    public static void handshakeRequiresMatchingClient(GameTestHelper helper) {
        ModContainer container = Loader.instance()
            .getIndexedModList()
            .get(GregScope.MODID);
        helper.assertNotNull(container, "no GregScope mod container");
        NetworkModHolder holder = NetworkRegistry.INSTANCE.registry()
            .get(container);
        helper.assertNotNull(holder, "GregScope has no FML network holder");

        Map<String, String> client = new HashMap<>();
        for (ModContainer mod : Loader.instance()
            .getActiveModList()) {
            if (!GregScope.MODID.equals(mod.getModId())) {
                client.put(mod.getModId(), mod.getVersion());
            }
        }
        helper.assertFalse(holder.check(client, Side.CLIENT), "client without GregScope accepted");
        helper.assertFalse(holder.acceptsVanilla(Side.CLIENT), "vanilla client accepted");
        client.put(GregScope.MODID, Tags.VERSION + "-other");
        helper.assertFalse(holder.check(client, Side.CLIENT), "client with a different GregScope version accepted");
        client.put(GregScope.MODID, Tags.VERSION);
        helper.assertTrue(holder.check(client, Side.CLIENT), "client with the same GregScope version rejected");
        Snapshots.log("safety#handshake", "checker accepts only " + GregScope.MODID + "=" + Tags.VERSION);
        helper.succeed();
    }
}
