package io.github.ldogg123.gregscope.registry;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.common.covers.Cover;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeAssets;
import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.sampling.Clock;
import io.github.ldogg123.gregscope.sampling.TargetResolver;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorCover;
import io.github.ldogg123.gregscope.sensor.SensorEvents;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;

/**
 * The Minecraft adapter around {@link SensorRegistryCore} (design-v0.2 section 4): it turns cover events into
 * transitions, writes a re-keyed UUID back into the cover, tells the cover what the registry thinks of it, and
 * resolves a registered position back to a live cover. Server thread only.
 *
 * <p>
 * It is installed as the covers' {@code SensorEvents} listener on server start and removed on server stop, so a
 * single-player world switch cannot leave a stale registry behind.
 *
 * <p>
 * {@link #validate(UUID)} and {@link #validateAndResolve(UUID)} apply the design-v0.2 section 4.3 consequences of the
 * section 6.2 target resolution, which {@code TargetResolver} performs: dimension, {@code blockExists}, tile, then the
 * cover's interface, kind and id (design-v0.3 section 5.1 A2). GS-108's sampler calls
 * {@link #validateAndResolve(UUID)} and probes the tile entity it returns. Neither ever loads a chunk or initializes a
 * dimension: {@code DimensionManager.getWorld} returns null for a dimension that is not running, and
 * {@code World.blockExists} is a hash lookup.
 */
public final class SensorRegistry implements SensorEvents {

    private final SensorRegistryCore core;
    /** Reused, so resolving a target allocates nothing (design-v0.2 section 6.3). Server thread only. */
    private final TargetResolver resolver = new TargetResolver();

    public SensorRegistry(Supplier<Settings> settings, Clock clock, TeamResolver<?> teams) {
        this.core = new SensorRegistryCore(settings, clock, teams);
    }

    public SensorRegistryCore core() {
        return core;
    }

    // --- cover events (design-v0.2 section 3.4) ---

    @Override
    public void heartbeat(SensorCover cover, ICoverable holder, ForgeDirection side) {
        SensorIdentity identity = cover.identity();
        World world = holder.getWorld();
        if (identity == null || world == null || world.isRemote) {
            return;
        }
        SensorRegistryCore.Heartbeat result = core.heartbeat(
            identity,
            cover.sensorKind(),
            world.provider.dimensionId,
            holder.getXCoord(),
            holder.getYCoord(),
            holder.getZCoord(),
            side.ordinal(),
            serverTick());
        if (result.rekeyed()) {
            SensorIdentity fresh = core.rekeyedIdentity();
            GregScope.LOG.info(
                "Sensor {} heartbeats at a second position; the copy was re-keyed to {}",
                identity.shortId(),
                fresh.shortId());
            cover.rekey(fresh);
            holder.markDirty();
        }
        if (result == SensorRegistryCore.Heartbeat.OVER_CAP
            || result == SensorRegistryCore.Heartbeat.REKEYED_OVER_CAP) {
            tellPlayerOverCap(cover);
        }
        // The outcome alone says what the registry thinks of this sensor, so the fast path stays at the one map
        // lookup design-v0.2 section 14 asks for: no second core.entry() call here.
        cover.setAvailability(result.availability());
    }

    @Override
    public void unloaded(SensorCover cover, ICoverable holder, ForgeDirection side) {
        transition(cover, holder, side, null, GapReason.CHUNK_UNLOADED);
    }

    @Override
    public void detached(SensorCover cover, ICoverable holder, ForgeDirection side) {
        transition(cover, holder, side, RemovalCause.DETACHED, null);
    }

    @Override
    public void destroyedIntoItem(SensorCover cover, ICoverable holder, ForgeDirection side) {
        transition(cover, holder, side, RemovalCause.IN_ITEM, null);
    }

    private void transition(SensorCover cover, ICoverable holder, ForgeDirection side, RemovalCause cause,
        GapReason reason) {
        SensorIdentity identity = cover.identity();
        World world = holder.getWorld();
        if (identity == null || world == null || world.isRemote) {
            return;
        }
        int dim = world.provider.dimensionId;
        int x = holder.getXCoord();
        int y = holder.getYCoord();
        int z = holder.getZCoord();
        if (cause != null) {
            core.removed(identity.id(), dim, x, y, z, side.ordinal(), cause);
        } else {
            core.unloaded(identity.id(), dim, x, y, z, side.ordinal(), reason);
        }
        cover.setAvailability(availabilityOf(core.entry(identity.id())));
    }

    // --- validation (design-v0.2 section 6.2, with the section 4.3 consequences) ---

    /**
     * Checks that the sensor's cover is still where the registry thinks it is, and applies the section 4.3 result:
     * UNLOADED for a missing dimension or chunk, a {@code target_missing} strike for a missing or foreign cover, and a
     * strike reset otherwise.
     *
     * @return true if the sensor is LIVE and its cover was found
     */
    public boolean validate(UUID id) {
        return validateAndResolve(id) != null;
    }

    /**
     * The same as {@link #validate(UUID)}, but returning the machine's tile entity so the caller (GS-108's sampler)
     * can probe it without resolving the position a second time.
     *
     * @return the tile entity of a LIVE sensor whose cover was found, or null
     */
    public TileEntity validateAndResolve(UUID id) {
        SensorEntry entry = core.entry(id);
        if (entry == null || entry.state() != SensorState.LIVE) {
            return null;
        }
        TargetResolver.Outcome outcome = resolver.resolve(entry);
        switch (outcome) {
            case DIMENSION_UNLOADED:
                core.unloaded(
                    id,
                    entry.dim(),
                    entry.x(),
                    entry.y(),
                    entry.z(),
                    entry.side(),
                    GapReason.DIMENSION_UNLOADED);
                return null;
            case CHUNK_UNLOADED:
                core.unloaded(id, entry.dim(), entry.x(), entry.y(), entry.z(), entry.side(), GapReason.CHUNK_UNLOADED);
                return null;
            case MISSING:
                core.strike(id);
                return null;
            default:
                break;
        }
        core.validated(id);
        entry.setLastSeenEpochSec(
            GregScope.clock()
                .epochSec());
        return resolver.tile();
    }

    /** The live cover of a registered sensor, or null; used by the label writes of design-v0.2 section 3.5. */
    public MachineSensorCover liveCover(UUID id) {
        SensorEntry entry = core.entry(id);
        if (entry == null || entry.state() != SensorState.LIVE) {
            return null;
        }
        World world = DimensionManager.getWorld(entry.dim());
        if (world == null || !world.blockExists(entry.x(), entry.y(), entry.z())) {
            return null;
        }
        Cover cover = TargetResolver.coverAt(world, entry.x(), entry.y(), entry.z(), entry.side());
        return cover instanceof MachineSensorCover ? (MachineSensorCover) cover : null;
    }

    // --- housekeeping and purge ---

    /** Design-v0.2 section 4.3: expiry and the tombstone cap. Called at server start and by GS-108's tick handler. */
    public int housekeeping() {
        return core.housekeeping();
    }

    public boolean purge(UUID id) {
        return core.purge(id);
    }

    public int purgeAll() {
        return core.purgeAll();
    }

    // --- helpers ---

    private static String availabilityOf(SensorEntry entry) {
        return entry == null ? null
            : entry.state()
                .label();
    }

    private static void tellPlayerOverCap(SensorCover cover) {
        if (!(cover instanceof MachineSensorCover)) {
            return;
        }
        EntityPlayer player = ((MachineSensorCover) cover).consumeAttachingPlayer();
        if (player != null) {
            player.addChatMessage(new ChatComponentTranslation(GregScopeAssets.LANG_CHAT_SENSOR_OVER_CAP));
        }
    }

    /**
     * The tick the cover heartbeat itself runs on ({@code CoverableTileEntity.tickCoverAtSide}); used only for the
     * OVER_CAP retry window. 0 while there is no server, which only happens outside a running world.
     */
    private static long serverTick() {
        MinecraftServer server = MinecraftServer.getServer();
        return server == null ? 0L : server.getTickCounter();
    }
}
