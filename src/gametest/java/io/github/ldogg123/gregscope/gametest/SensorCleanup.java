package io.github.ldogg123.gregscope.gametest;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.tileentity.ICoverable;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sensor.SensorCover;

/**
 * Cleanup shared by the {@code @AfterBatch} hooks of every holder that places sensors (GS107-T3).
 *
 * <p>
 * Horizon-QA keeps a finished cell loaded until the whole run ends, so the machines a sensor test placed keep ticking
 * and their covers keep heartbeating into the registry. Every LIVE or UNLOADED entry counts against
 * {@code limits.maxSensorsPerTeam} (default 64), and every sensor a gametest places is unowned, so they all share one
 * quota. Left alone, the sensors of earlier batches eventually fill it and a later test's sensor is refused with no
 * entry at all - which surfaces as "no registry entry for sensor &lt;uuid&gt;" and points at the registry instead of
 * at the batch that exhausted the quota. This happened once already, in GS-108's first suite run.
 *
 * <p>
 * Purging alone would not help: a cover whose machine still ticks registers again on its next heartbeat. The covers
 * themselves are therefore detached first, through GT's own {@code detachCover} (which fires {@code onCoverRemoval}
 * and so reports a detach), and the registry is emptied afterwards.
 */
final class SensorCleanup {

    private SensorCleanup() {}

    /** Detaches every sensor cover the registry knows about and empties the registry. Never throws. */
    static void detachAllAndPurge() {
        SensorRegistry registry = GregScope.registry();
        if (registry == null) {
            return;
        }
        List<SensorEntry> entries = new ArrayList<>(
            registry.core()
                .entries());
        int detached = 0;
        for (SensorEntry entry : entries) {
            if (entry.state() != SensorState.LIVE) {
                continue;
            }
            if (detach(entry)) {
                detached++;
            }
        }
        int purged = GregScopeTestHooks.purgeAllNow();
        Snapshots.log(
            "cleanup after a sensor batch",
            "entries=" + entries.size() + " coversDetached=" + detached + " entriesPurged=" + purged);
    }

    private static boolean detach(SensorEntry entry) {
        World world = DimensionManager.getWorld(entry.dim());
        if (world == null || !world.blockExists(entry.x(), entry.y(), entry.z())) {
            return false;
        }
        TileEntity tile = world.getTileEntity(entry.x(), entry.y(), entry.z());
        if (!(tile instanceof ICoverable)) {
            return false;
        }
        ICoverable coverable = (ICoverable) tile;
        ForgeDirection side = ForgeDirection.getOrientation(entry.side());
        if (!(coverable.getCoverAtSide(side) instanceof SensorCover)) {
            return false;
        }
        // detachCover, not dropCover: the cover must stop heartbeating, but a cleanup should not litter the world
        // with item entities.
        coverable.detachCover(side);
        return true;
    }
}
