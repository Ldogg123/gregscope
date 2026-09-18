package io.github.ldogg123.gregscope.integration.opencomputers;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import io.github.ldogg123.gregscope.hub.TileTelemetryHub;
import li.cil.oc.api.prefab.DriverSidedTileEntity;

/**
 * Adapter driver for the Telemetry Hub (design-v0.2 section 10.3). The Adapter does not catch driver exceptions, so
 * neither method ever throws.
 *
 * <p>
 * {@code DriverSidedTileEntity.worksWith} asks GTNHLib's {@code Capabilities.getCapability}, which casts the tile
 * entity to {@link #getTileEntityClass()} when it is assignable ({@code oc/li/cil/oc/util/CapabilityUtil.java:10-18},
 * {@code gtnhlib/.../capability/Capabilities.java:97-109}), so a plain class filter is enough and no capability has to
 * be published. Every side of the Hub answers the same, exactly as the GUI opens from every side.
 */
public final class HubDriver extends DriverSidedTileEntity {

    @Override
    public Class<?> getTileEntityClass() {
        return TileTelemetryHub.class;
    }

    @Override
    public boolean worksWith(World world, int x, int y, int z, ForgeDirection side) {
        try {
            return super.worksWith(world, x, y, z, side);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * Binds the environment to the Hub tile entity itself, the way OpenComputers' own tile entity drivers do
     * ({@code oc/li/cil/oc/integration/vanilla/DriverNoteBlock.scala:23-24}). A Hub that is broken or replaced makes
     * the Adapter drop this environment and build a new one for whatever is there now, and
     * {@link HubEnvironment} re-reads the tile from the world when the one it holds went invalid, so a chunk reload
     * that swaps the tile entity object keeps the component working.
     */
    @Override
    public HubEnvironment createEnvironment(World world, int x, int y, int z, ForgeDirection side) {
        TileEntity tile;
        try {
            tile = world.getTileEntity(x, y, z);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(tile instanceof TileTelemetryHub)) {
            return null;
        }
        return new HubEnvironment((TileTelemetryHub) tile);
    }
}
