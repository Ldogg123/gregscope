package io.github.ldogg123.gregscope.gametest;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;

/**
 * Really unloads and reloads the chunks holding a test cell, through the server's own chunk provider and region
 * storage, using public Forge/Minecraft API only.
 *
 * <p>
 * Horizon-QA keeps every test cell loaded with ForgeChunkManager tickets and only releases them after the whole run.
 * {@link #unload()} temporarily unforces every ticket on the chunks, queues them for unloading (moving the world spawn
 * far
 * away for that one call, so the spawn-area protection cannot veto it) and runs {@code unloadQueuedChunks()}: tile
 * entities are written to chunk NBT and marked for {@code onChunkUnload()}, which the world runs in its next entity
 * update. {@link #reload()} loads the chunks back from region storage (or the pending-write queue), which creates new
 * tile entities from that NBT, and re-forces the same tickets.
 *
 * <p>
 * While the chunks are unloaded, callers must not touch them through {@code World.getTileEntity}, {@code getBlock} or
 * Horizon-QA's GT helpers: those silently load them again. Use {@link #anyLoaded()} and the objects captured before the
 * unload.
 */
final class ChunkReload {

    private final GameTestHelper helper;
    private final WorldServer world;
    private final ChunkProviderServer provider;
    private final List<ChunkCoordIntPair> chunks = new ArrayList<>();
    /** Tickets removed per chunk (same order as {@link #chunks}); null while the chunks are loaded. */
    private List<List<Ticket>> tickets;

    private ChunkReload(GameTestHelper helper) {
        this.helper = helper;
        this.world = helper.getWorld();
        this.provider = world.theChunkProviderServer;
    }

    /**
     * Every chunk overlapping the test-local box {@code min..max} (a test cell may straddle a chunk border). Registers
     * a
     * cleanup that reloads and re-forces the chunks if the test ends while they are unloaded.
     */
    static ChunkReload of(GameTestHelper helper, TestPos min, TestPos max) {
        TestPos a = helper.absolute(min);
        TestPos b = helper.absolute(max);
        ChunkReload reload = new ChunkReload(helper);
        for (int cx = Math.min(a.x(), b.x()) >> 4; cx <= Math.max(a.x(), b.x()) >> 4; cx++) {
            for (int cz = Math.min(a.z(), b.z()) >> 4; cz <= Math.max(a.z(), b.z()) >> 4; cz++) {
                reload.chunks.add(new ChunkCoordIntPair(cx, cz));
            }
        }
        helper.afterTest(reload::restoreQuietly);
        return reload;
    }

    /** True only if every chunk is loaded. */
    boolean isLoaded() {
        for (ChunkCoordIntPair chunk : chunks) {
            if (!provider.chunkExists(chunk.chunkXPos, chunk.chunkZPos)) {
                return false;
            }
        }
        return true;
    }

    /** True if any chunk is loaded. */
    boolean anyLoaded() {
        for (ChunkCoordIntPair chunk : chunks) {
            if (provider.chunkExists(chunk.chunkXPos, chunk.chunkZPos)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "chunks " + chunks;
    }

    void unload() {
        helper.assertNull(tickets, this + " are already unloaded by this test");
        helper.assertTrue(isLoaded(), this + " are not loaded before the unload");
        List<List<Ticket>> removed = new ArrayList<>();
        for (ChunkCoordIntPair chunk : chunks) {
            List<Ticket> held = new ArrayList<>(
                ForgeChunkManager.getPersistentChunksFor(world)
                    .get(chunk));
            helper.assertFalse(held.isEmpty(), chunk + " is not force-loaded by Horizon-QA (unexpected test setup)");
            removed.add(held);
        }
        tickets = removed;
        for (int i = 0; i < chunks.size(); i++) {
            for (Ticket ticket : removed.get(i)) {
                ForgeChunkManager.unforceChunk(ticket, chunks.get(i));
            }
        }
        ChunkCoordinates spawn = world.getSpawnPoint();
        try {
            world.setSpawnLocation(spawn.posX + 1_000_000, spawn.posY, spawn.posZ + 1_000_000);
            for (ChunkCoordIntPair chunk : chunks) {
                provider.unloadChunksIfNotNearSpawn(chunk.chunkXPos, chunk.chunkZPos);
            }
        } finally {
            world.setSpawnLocation(spawn.posX, spawn.posY, spawn.posZ);
        }
        provider.unloadQueuedChunks();
        helper.assertFalse(anyLoaded(), this + " still loaded after unloadQueuedChunks()");
    }

    void reload() {
        helper.assertNotNull(tickets, this + " were not unloaded by this test");
        helper.assertFalse(anyLoaded(), this + " loaded again before reload() (something touched them)");
        restore();
        helper.assertTrue(isLoaded(), this + " not loaded after loadChunk()");
    }

    private void restore() {
        for (int i = 0; i < chunks.size(); i++) {
            ChunkCoordIntPair chunk = chunks.get(i);
            provider.loadChunk(chunk.chunkXPos, chunk.chunkZPos);
            for (Ticket ticket : tickets.get(i)) {
                ForgeChunkManager.forceChunk(ticket, chunk);
            }
        }
        tickets = null;
    }

    /** Keeps the chunks loaded and forced for the rest of the run if the test ended between unload and reload. */
    private void restoreQuietly() {
        if (tickets != null) {
            System.out.println("[GregScope gametest] restoring " + this + " after an interrupted reload test");
            restore();
        }
    }
}
