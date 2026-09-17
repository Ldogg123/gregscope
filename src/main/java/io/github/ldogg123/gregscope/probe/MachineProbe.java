package io.github.ldogg123.gregscope.probe;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import io.github.ldogg123.gregscope.model.MachineSnapshot;

/** Reads a normalized snapshot from a machine tile entity. Implementations are stateless and read-only. */
public interface MachineProbe {

    /** Cheap type check for driver matching; no world lookups. */
    boolean supports(TileEntity tile);

    /** Server thread only. Returns null if the target is missing, unloaded, replaced, client-side or unsupported. */
    MachineSnapshot snapshot(TileEntity tile);

    /**
     * Server thread only. Snapshot of whatever supported machine is currently at the position, resolving the tile
     * entity on every call. Returns null if the world is null or client-side, if the position's chunk is not loaded
     * (it is never loaded for this), or if {@link #snapshot(TileEntity)} returns null for the tile there.
     */
    default MachineSnapshot snapshotAt(World world, int x, int y, int z) {
        if (world == null || world.isRemote || !world.blockExists(x, y, z)) {
            return null;
        }
        return snapshot(world.getTileEntity(x, y, z));
    }
}
