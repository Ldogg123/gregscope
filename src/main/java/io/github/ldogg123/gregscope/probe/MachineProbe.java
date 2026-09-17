package io.github.ldogg123.gregscope.probe;

import net.minecraft.tileentity.TileEntity;

import io.github.ldogg123.gregscope.model.MachineSnapshot;

/** Reads a normalized snapshot from a machine tile entity. Implementations are stateless and read-only. */
public interface MachineProbe {

    /** Cheap type check for driver matching; no world lookups. */
    boolean supports(TileEntity tile);

    /** Server thread only. Returns null if the target is missing, unloaded, replaced, client-side or unsupported. */
    MachineSnapshot snapshot(TileEntity tile);
}
