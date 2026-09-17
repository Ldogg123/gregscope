package io.github.ldogg123.gregscope.sampling;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.covers.Cover;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.sensor.SensorCover;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;

/**
 * The world half of the per-sample procedure of design-v0.2 section 6.2: turn a registry entry's recorded position
 * back into the live machine its cover sits on, or say why that failed. The only telemetry class that touches
 * {@code World} (design-v0.2 section 2). Server thread only.
 *
 * <p>
 * <b>It never loads anything.</b> {@code DimensionManager.getWorld} returns null for a dimension that is not running
 * and never starts one ({@code worldServerForDimension} would), and {@code World.blockExists} is a chunk-map hash
 * lookup that never generates or loads a chunk. {@code getTileEntity} is only reached once {@code blockExists} said
 * the chunk is there. Removing the {@code blockExists} guard is the design-v0.2 section 13.2 negative control.
 *
 * <p>
 * <b>Design-v0.3 section 5.1 A2.</b> The cover check is the {@link SensorCover} interface, then the kind, then the
 * id, so a cover of another kind at the recorded side is a {@code target_missing} strike rather than a match.
 *
 * <p>
 * One instance is reused per resolution to keep the sample path allocation-free; {@link #tile()} describes the last
 * {@link #resolve} and must be read before the next one.
 */
public final class TargetResolver {

    /** What the section 6.2 chain found. */
    public enum Outcome {
        /** The machine and its cover are there; {@link TargetResolver#tile()} holds the tile entity. */
        FOUND,
        /** The dimension is not running: UNLOADED with {@code dimension_unloaded}. */
        DIMENSION_UNLOADED,
        /** The chunk is not loaded: UNLOADED with {@code chunk_unloaded}. */
        CHUNK_UNLOADED,
        /** The chunk is loaded but the tile or the matching cover is gone: a {@code target_missing} strike. */
        MISSING
    }

    private TileEntity tile;

    /** The tile entity of the last {@link Outcome#FOUND}, or null. */
    public TileEntity tile() {
        return tile;
    }

    /** Runs the section 6.2 chain for one entry. Loads no chunk and starts no dimension. */
    public Outcome resolve(SensorEntry entry) {
        tile = null;
        World world = DimensionManager.getWorld(entry.dim());
        if (world == null) {
            return Outcome.DIMENSION_UNLOADED;
        }
        if (!world.blockExists(entry.x(), entry.y(), entry.z())) {
            return Outcome.CHUNK_UNLOADED;
        }
        Cover found = coverAt(world, entry.x(), entry.y(), entry.z(), entry.side());
        if (!(found instanceof SensorCover)) {
            return Outcome.MISSING;
        }
        SensorCover cover = (SensorCover) found;
        SensorIdentity identity = cover.identity();
        if (cover.sensorKind() != entry.kind() || identity == null
            || !identity.id()
                .equals(entry.id())) {
            return Outcome.MISSING;
        }
        tile = world.getTileEntity(entry.x(), entry.y(), entry.z());
        return Outcome.FOUND;
    }

    /**
     * The cover at that side of a live GT machine, or null if there is no live GT machine there. The chunk must
     * already be loaded; the caller checks that with {@code blockExists}.
     */
    public static Cover coverAt(World world, int x, int y, int z, int side) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof IGregTechTileEntity)) {
            return null;
        }
        IGregTechTileEntity holder = (IGregTechTileEntity) tile;
        if (holder.isDead()) {
            return null;
        }
        return holder.getCoverAtSide(ForgeDirection.getOrientation(side));
    }
}
