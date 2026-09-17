package io.github.ldogg123.gregscope.sensor;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.covers.CoverPlacer;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.render.TextureFactory;
import io.github.ldogg123.gregscope.GregScopeAssets;
import io.github.ldogg123.gregscope.probe.GregTechMachineProbe;

/**
 * Registers the Machine Sensor item and cover and decides where the cover may go (design-v0.2 §3.2). The only shipped
 * class that uses {@code GameRegistry} (for the item; §1.4 lifts "no items" for v0.2).
 */
public final class SensorCovers {

    private static final CoverPlacer PLACER = CoverPlacer.builder()
        .onlyPlaceIf(SensorCovers::isPlaceable)
        .build();

    /** Where sensor covers report (design-v0.2 §3.4); GS-107's registry installs itself here. */
    private static volatile SensorEvents events = SensorEvents.NONE;

    private SensorCovers() {}

    /** Never null: {@link SensorEvents#NONE} until a registry is installed. */
    public static SensorEvents events() {
        return events;
    }

    /**
     * Installs the listener the covers report to. Called by GS-107's registry on server start and cleared (with
     * {@code null}) on server stop, so single-player world switches leave no stale registry behind.
     */
    public static void setEvents(SensorEvents listener) {
        events = listener == null ? SensorEvents.NONE : listener;
    }

    /** preInit: {@code gregscope:machine_sensor}. */
    public static void registerItem(CreativeTabs tab) {
        ItemMachineSensor.INSTANCE.setCreativeTab(tab);
        GameRegistry.registerItem(ItemMachineSensor.INSTANCE, GregScopeAssets.REGISTRY_MACHINE_SENSOR);
    }

    /**
     * init (common code, after GT through {@code required-after:gregtech}). Building the icon container on a dedicated
     * server is safe: GT only queues it for icon registration, which happens on the client.
     */
    public static void registerCover() {
        IIconContainer overlayIcon = Textures.BlockIcons
            .custom(GregScopeAssets.DOMAIN, GregScopeAssets.BLOCK_ICON_SENSOR_OVERLAY);
        ITexture overlay = TextureFactory.of(overlayIcon);
        // The default placer stays GUI-clickable (no blocksCoverableGuiOpening), so the machine's GUI still opens and a
        // basic machine accepts the sensor on its main face.
        CoverRegistry
            .registerCover(sensorStack(), overlay, context -> new MachineSensorCover(context, overlay), PLACER);
    }

    /** A new stack of one Machine Sensor. */
    public static ItemStack sensorStack() {
        return new ItemStack(ItemMachineSensor.INSTANCE, 1, 0);
    }

    /**
     * The placement predicate: a GT basic machine or multiblock controller (the probe's supported set) that has no
     * Machine Sensor on any face yet. GT's own face rules (controller front, basic machine main face for placers that
     * are not GUI-clickable) are checked by GT after this predicate.
     *
     * <p>
     * The "one per machine" rule checks exactly {@link MachineSensorCover}, not {@link SensorCover}, so v0.3 meters on
     * the same block never block a Machine Sensor (design-v0.3 §5.1 A4).
     */
    public static boolean isPlaceable(ForgeDirection side, ItemStack stack, ICoverable coverable) {
        if (!(coverable instanceof IGregTechTileEntity)) {
            return false;
        }
        IMetaTileEntity mte = ((IGregTechTileEntity) coverable).getMetaTileEntity();
        if (!GregTechMachineProbe.isSupportedMte(mte)) {
            return false;
        }
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            if (coverable.getCoverAtSide(direction) instanceof MachineSensorCover) {
                return false;
            }
        }
        return true;
    }
}
