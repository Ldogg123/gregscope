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
    /**
     * Like {@link #snapshotAt}, but permitted to spend more time on readings a per-tick sampler cannot afford -
     * today, asking an ME network how much of a stocking bus's configured items it holds (GS-306).
     *
     * <p>
     * Only for a caller a human or a script is waiting on. The sampler must keep using {@link #snapshotAt}: the
     * extra work costs an NBT export and a network lookup per configured slot, per bus, and that does not belong
     * on a path that runs for every machine every sample.
     */
    default MachineSnapshot detailedSnapshotAt(World world, int x, int y, int z) {
        return snapshotAt(world, x, y, z);
    }

    default MachineSnapshot snapshotAt(World world, int x, int y, int z) {
        if (world == null || world.isRemote || !world.blockExists(x, y, z)) {
            return null;
        }
        return snapshot(world.getTileEntity(x, y, z));
    }
}
