package io.github.ldogg123.gregscope.probe;

import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEBasicMachineBronze;
import gregtech.api.metatileentity.implementations.MTEExtendedPowerMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.util.shutdown.ShutDownReason;
import gregtech.common.tileentities.machines.basic.MTEIndustrialApiary;
import gregtech.common.tileentities.machines.multi.MTEHeatExchanger;
import gregtech.common.tileentities.machines.multi.MTELargeBoiler;
import gregtech.common.tileentities.machines.multi.MTELargeNaquadahReactor;
import gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.base.MTESteamMultiBlockBase;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.model.MachineKind;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.StatusIds;
import tectech.thing.metaTileEntity.multi.base.TTMultiblockBase;

/**
 * Read-only probe for GT5U 5.09.54.133 basic machines and multiblock controllers. The only class that touches GT or
 * Minecraft internals. Stateless; never caches holders or meta tile entities. Nothing is written, except that the GT
 * getters used for energy hatches, mufflers and the info map prune invalid (null/dead) hatch references from the
 * controller's own lists, exactly as GT's tick does.
 */
public final class GregTechMachineProbe implements MachineProbe {

    /** GT steam units are stored at half the fluid amount (MetaTileEntity.fill: aFluid.amount / 2). */
    private static final long LITRES_PER_STEAM_UNIT = 2L;

    private static final String APIARY_CLASS = "gregtech.common.tileentities.machines.basic.MTEIndustrialApiary";

    @Override
    public boolean supports(TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity)) {
            return false;
        }
        IGregTechTileEntity holder = (IGregTechTileEntity) tile;
        return holder.canAccessData() && isSupportedMte(holder.getMetaTileEntity());
    }

    @Override
    public MachineSnapshot snapshot(TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity)) {
            return null;
        }
        IGregTechTileEntity holder = (IGregTechTileEntity) tile;
        World world = holder.getWorld();
        if (world == null || world.isRemote || !holder.canAccessData() || holder.isDead()) {
            return null;
        }
        int x = holder.getXCoord();
        int y = holder.getYCoord();
        int z = holder.getZCoord();
        if (!world.blockExists(x, y, z) || world.getTileEntity(x, y, z) != tile) {
            return null;
        }
        IMetaTileEntity mte = holder.getMetaTileEntity();
        if (mte == null || mte.getBaseMetaTileEntity() != holder || !isSupportedMte(mte)) {
            return null;
        }

        MachineReadings r = new MachineReadings();
        readCommon(r, holder, mte, world, x, y, z);
        if (mte instanceof MTEBasicMachine) {
            readBasic(r, holder, (MTEBasicMachine) mte);
        } else {
            readMulti(r, holder, (MTEMultiBlockBase) mte);
        }
        return SnapshotBuilder.build(r);
    }

    /**
     * The machines GregScope reads: GT basic machines and multiblock controllers (hatches, casings, pipes and cables
     * are
     * neither). Public for the Machine Sensor placement rule (design-v0.2 §3.2); {@code null} is not supported.
     */
    public static boolean isSupportedMte(IMetaTileEntity mte) {
        return mte instanceof MTEBasicMachine || mte instanceof MTEMultiBlockBase;
    }

    private static void readCommon(MachineReadings r, IGregTechTileEntity holder, IMetaTileEntity mte, World world,
        int x, int y, int z) {
        String metaName = mte.getMetaName();
        if (metaName == null) {
            metaName = "";
        }
        String machineClass = mte.getClass()
            .getName();
        String localName = null;
        try {
            localName = Text.stripFormatting(mte.getLocalName());
        } catch (RuntimeException | LinkageError e) {
            GregScope.LOG.debug("GregScope: getLocalName failed for {}", machineClass, e);
        }
        r.kind(mte instanceof MTEBasicMachine ? MachineKind.SINGLEBLOCK : MachineKind.MULTIBLOCK)
            .name(Text.firstNonEmpty(localName, metaName, machineClass))
            .metaName(metaName)
            .metaId(holder.getMetaTileID())
            .machineClass(machineClass)
            .dimension(world.provider.dimensionId)
            .x(x)
            .y(y)
            .z(z)
            .active(holder.isActive())
            .allowedToWork(holder.isAllowedToWork())
            .hasThingsToDo(holder.hasThingsToDo())
            .wasShutdown(holder.wasShutdown())
            .progressTicks(holder.getProgress())
            .maxProgressTicks(holder.getMaxProgress());

        if (r.wasShutdown()) {
            ShutDownReason reason = holder.getLastShutDownReason();
            if (reason == null) {
                r.shutdownReasonId(StatusIds.NONE)
                    .shutdownCritical(false);
            } else {
                r.shutdownReasonId(ReasonIds.normalize(reason.getKey(), reason.getID()))
                    .shutdownCritical(reason.wasCritical())
                    .shutdownReasonText(displayString(reason));
            }
        }
    }

    private static void readBasic(MachineReadings r, IGregTechTileEntity holder, MTEBasicMachine m) {
        boolean steam = m.isSteampowered();
        r.steamPowered(steam)
            .stuttering(m.isStuttering())
            .outputBlockedTicks(m.mOutputBlocked)
            .steamVentBlocked(m instanceof MTEBasicMachineBronze && ((MTEBasicMachineBronze) m).needsSteamVenting());
        if (APIARY_CLASS.equals(
            m.getClass()
                .getName())) {
            try {
                // Bee errors (no flower, wrong climate...) freeze an active apiary without clearing isActive().
                r.machineErrors(Apiary.hasErrors(m));
            } catch (RuntimeException | LinkageError e) {
                GregScope.LOG.debug("GregScope: apiary error read failed", e);
            }
        }
        if (steam) {
            if (r.active()) {
                r.steamPerTick(m.mEUt * LITRES_PER_STEAM_UNIT);
            }
            r.steamStored(holder.getStoredSteam() * LITRES_PER_STEAM_UNIT)
                .steamCapacity(holder.getSteamCapacity() * LITRES_PER_STEAM_UNIT);
        } else {
            if (r.active()) {
                r.euPerTick((long) m.mEUt);
            }
            r.energyStored(holder.getStoredEU())
                .energyCapacity(holder.getEUCapacity());
        }
    }

    private static void readMulti(MachineReadings r, IGregTechTileEntity holder, MTEMultiBlockBase m) {
        boolean steamMulti = m instanceof MTESteamMultiBlockBase;
        // Large Boilers and the Heat Exchanger use a positive mEUt as a steam figure and emit no EU.
        boolean noEu = steamMulti || m instanceof MTELargeBoiler || m instanceof MTEHeatExchanger;
        r.startupPending(m.getmStartUpCheck() >= 0)
            .formed(m.mMachine)
            .maintenanceIssues(Math.max(0, m.getIdealStatus() - m.getRepairStatus()))
            .maintenanceChecksEnabled(m.shouldCheckMaintenance())
            .pollutionEmissionFactor(m.getAveragePollutionPercentage() / 100.0)
            .recipesCompleted(m.recipesDone)
            .controllerAgeTicks(m.getTotalRuntimeInTicks())
            .lastWorkingTick(m.getLastWorkingTick());
        if (!steamMulti) {
            r.efficiency(m.mEfficiency / 10000.0);
        }

        readRecipeCheck(r, m.getCheckRecipeResult());
        readEnergy(r, holder, m);
        if (!noEu && r.active()) {
            readMultiEuPerTick(r, m);
        }
    }

    private static void readRecipeCheck(MachineReadings r, CheckRecipeResult result) {
        if (result == null) {
            r.recipeCheckResultId(StatusIds.NONE)
                .recipeCheckSuccessful(false);
            return;
        }
        String safeId = null;
        try {
            safeId = result.getID();
            String key = StatusIds.SIMPLE_RESULT.equals(safeId) ? result.writeToNBT(new NBTTagCompound())
                .getString("key") : null;
            r.recipeCheckResultId(ReasonIds.normalize(key, safeId));
        } catch (RuntimeException | LinkageError e) {
            GregScope.LOG.debug("GregScope: recipe check key extraction failed", e);
            r.recipeCheckResultId(ReasonIds.normalize(null, safeId));
        }
        try {
            r.recipeCheckSuccessful(result.wasSuccessful())
                .recipeCheckPersistsOnShutdown(result.persistsOnShutdown());
        } catch (RuntimeException | LinkageError e) {
            GregScope.LOG.debug("GregScope: recipe check flags failed", e);
        }
        r.recipeCheckResultText(displayString(result));
    }

    private static void readEnergy(MachineReadings r, IGregTechTileEntity holder, MTEMultiBlockBase m) {
        try {
            // Controller buffer: 0 for plain GT multis; TecTech multis (and storage multis) keep their EU here.
            long stored = holder.getStoredEU();
            long capacity = holder.getEUCapacity();
            int counted = capacity > 0 ? 1 : 0;
            for (MTEHatch hatch : m.getExoticAndNormalEnergyHatchList()) {
                IGregTechTileEntity base = hatch == null ? null : hatch.getBaseMetaTileEntity();
                if (base == null) {
                    continue;
                }
                stored = Numbers.saturatingAdd(stored, base.getStoredEU());
                capacity = Numbers.saturatingAdd(capacity, base.getEUCapacity());
                counted++;
            }
            if (counted > 0) {
                r.energyStored(stored)
                    .energyCapacity(capacity);
            }
        } catch (RuntimeException e) {
            GregScope.LOG.debug("GregScope: multiblock energy read failed", e);
        }
    }

    private static void readMultiEuPerTick(MachineReadings r, MTEMultiBlockBase m) {
        try {
            long eut;
            long amperes = 1;
            if (m instanceof TTMultiblockBase) {
                // TT stores its power flow in mEUt unless the (protected) useLongPower flag is set.
                TTMultiblockBase tt = (TTMultiblockBase) m;
                eut = tt.lEUt != 0 ? tt.lEUt : tt.mEUt;
                amperes = tt.eAmpereFlow;
            } else if (m instanceof MTEExtendedPowerMultiBlockBase) {
                eut = ((MTEExtendedPowerMultiBlockBase<?>) m).lEUt;
            } else {
                eut = m.mEUt;
            }
            if (eut < 0) {
                Map<String, String> info = m.getInfoMap();
                r.euPerTick(info == null ? null : Numbers.parseLong(info.get("energyUsage")));
            } else if (eut > 0) {
                if ((m instanceof TTMultiblockBase && m.mEfficiency <= 0) || m instanceof MTELargeNaquadahReactor) {
                    // TT output scales with mEfficiency / maxEfficiency; the Large Naquadah Reactor reports
                    // maxEfficiency 0 and emits its real output outside that formula.
                    return;
                }
                r.euPerTick(Numbers.generatorEuPerTick(eut, m.mEfficiency, amperes));
            } else {
                r.euPerTick(0L);
            }
        } catch (RuntimeException e) {
            GregScope.LOG.debug("GregScope: EU/t read failed", e);
        }
    }

    private static String displayString(ShutDownReason reason) {
        try {
            return emptyToNull(Text.stripFormatting(reason.getDisplayString()));
        } catch (RuntimeException | LinkageError e) {
            GregScope.LOG.debug("GregScope: shutdown reason display string failed", e);
            return null;
        }
    }

    private static String displayString(CheckRecipeResult result) {
        try {
            return emptyToNull(Text.stripFormatting(result.getDisplayString()));
        } catch (RuntimeException | LinkageError e) {
            GregScope.LOG.debug("GregScope: recipe check display string failed", e);
            return null;
        }
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    /**
     * MTEIndustrialApiary implements Forestry interfaces, so resolving it (even for an instanceof) throws
     * NoClassDefFoundError without Forestry. Only touch it once the class name proves the instance is an apiary.
     */
    private static final class Apiary {

        static boolean hasErrors(MTEBasicMachine m) {
            return ((MTEIndustrialApiary) m).hasErrors();
        }
    }
}
