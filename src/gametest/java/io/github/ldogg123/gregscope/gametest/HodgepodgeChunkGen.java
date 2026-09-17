package io.github.ldogg123.gregscope.gametest;

import java.util.List;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;

import com.mitchej123.hodgepodge.mixins.hooks.ChunkGenScheduler;
import com.mitchej123.hodgepodge.util.ChunkPosUtil;

import cpw.mods.fml.common.Loader;

/**
 * Hodgepodge (shipped with GTNH) refuses to unload a chunk while it or any of its eight neighbours was generated but
 * not
 * yet populated within the last 600 server ticks ({@code ChunkGenScheduler.shouldProtectFromUnload}, checked inside its
 * {@code unloadQueuedChunks()} mixin; a protected chunk is skipped and stays queued). In a freshly created test world
 * (every CI run) the test cells are exactly such chunks, so a real unload is silently skipped, and calling
 * {@code unloadQueuedChunks()} again in the same tick skips it again because the tick counter has not moved. Real bases
 * live in old, populated chunks where this protection never applies; clearing Hodgepodge's generation tracking for the
 * test chunks and their neighbours reproduces that situation. Uses Hodgepodge's public hook, test code only, and does
 * nothing when Hodgepodge is not loaded.
 */
final class HodgepodgeChunkGen {

    private HodgepodgeChunkGen() {}

    static void clearFreshGenerationProtection(WorldServer world, List<ChunkCoordIntPair> chunks) {
        if (!Loader.isModLoaded("hodgepodge")) {
            return;
        }
        Hooks.clear(world.provider.dimensionId, chunks);
    }

    /** Separate class so Hodgepodge types are only resolved when Hodgepodge is loaded. */
    private static final class Hooks {

        static void clear(int dimension, List<ChunkCoordIntPair> chunks) {
            ChunkGenScheduler scheduler = ChunkGenScheduler.getForDimension(dimension);
            if (scheduler == null) {
                return;
            }
            for (ChunkCoordIntPair chunk : chunks) {
                // The protection also looks at the eight neighbours of each chunk.
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        scheduler.onChunkUnload(ChunkPosUtil.toLong(chunk.chunkXPos + dx, chunk.chunkZPos + dz));
                    }
                }
            }
        }
    }
}
