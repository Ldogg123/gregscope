package io.github.ldogg123.gregscope.integration.opencomputers;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.probe.MachineProbe;
import li.cil.oc.api.prefab.DriverSidedTileEntity;

/**
 * Adapter driver for GT basic machines and multiblock controllers. The Adapter does not catch driver exceptions, so
 * neither method ever throws.
 */
public final class GregTechMachineDriver extends DriverSidedTileEntity {

    private final MachineProbe probe;

    public GregTechMachineDriver(MachineProbe probe) {
        this.probe = probe;
    }

    @Override
    public Class<?> getTileEntityClass() {
        return IGregTechTileEntity.class;
    }

    @Override
    public boolean worksWith(World world, int x, int y, int z, ForgeDirection side) {
        try {
            return super.worksWith(world, x, y, z, side) && probe.supports(world.getTileEntity(x, y, z));
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * Returns a live environment for any GT holder, even one that stopped being supported since {@link #worksWith}: a
     * null environment would leave the Adapter's cached compound component without GregScope until its driver set
     * changes. The environment's callback soft-errors instead.
     */
    @Override
    public GregTechMachineEnvironment createEnvironment(World world, int x, int y, int z, ForgeDirection side) {
        TileEntity tile;
        try {
            tile = world.getTileEntity(x, y, z);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(tile instanceof IGregTechTileEntity)) {
            return null;
        }
        return new GregTechMachineEnvironment(probe, tile);
    }
}
