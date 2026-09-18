package io.github.ldogg123.gregscope.hub;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.registry.GameRegistry;
import io.github.ldogg123.gregscope.GregScopeAssets;

/**
 * Registers the Telemetry Hub block and its tile entity, and owns the server-wide open-view bookkeeping
 * (design-v0.2 section 9.1). One of the two shipped classes that may touch {@code GameRegistry}: section 1.4 lifts
 * "no blocks" and "no tile entities" for v0.2, and {@code ShippedClassesTest} keeps the lift to this class and to the
 * two calls below.
 *
 * <p>
 * The registry names are frozen before the first public jar (section 17): {@code gregscope:telemetry_hub} for the
 * block (with the default {@code ItemBlock}) and for the tile entity. Any later rename has to go through
 * {@code FMLMissingMappingsEvent}.
 */
public final class TelemetryHubs {

    private static final HubViews VIEWS = new HubViews();

    private TelemetryHubs() {}

    /**
     * preInit: the block with Minecraft's default {@code ItemBlock}, and the non-ticking tile entity.
     *
     * <p>
     * Deliberately not called {@code register*}: {@code ShippedClassesTest} reads constant pools and cannot tell a
     * declared method name from a called one, so a method of that name here would put {@code registerBlock} into
     * <em>every caller's</em> constant pool and make the lift wider than this class.
     */
    public static void install(CreativeTabs tab) {
        BlockTelemetryHub.INSTANCE.setCreativeTab(tab);
        GameRegistry.registerBlock(BlockTelemetryHub.INSTANCE, GregScopeAssets.REGISTRY_TELEMETRY_HUB);
        GameRegistry.registerTileEntity(TileTelemetryHub.class, GregScopeAssets.TILE_ENTITY_TELEMETRY_HUB);
    }

    /**
     * The open Hub views of this server (design-v0.2 section 9.1 view cap). Server thread only; never null, and
     * emptied by {@link #reset()} when the server stops.
     */
    public static HubViews views() {
        return VIEWS;
    }

    /** Server stop: forget every open view, so a single-player world switch starts at zero. */
    public static void reset() {
        VIEWS.clear();
    }

    /** A new stack of one Telemetry Hub. */
    public static ItemStack hubStack() {
        return new ItemStack(BlockTelemetryHub.INSTANCE, 1, 0);
    }
}
