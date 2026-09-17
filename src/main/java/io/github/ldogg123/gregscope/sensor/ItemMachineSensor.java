package io.github.ldogg123.gregscope.sensor;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import io.github.ldogg123.gregscope.GregScopeAssets;

/**
 * The Machine Sensor item, {@code gregscope:machine_sensor} (design-v0.2 §3.2). It does nothing by itself: GT's
 * cover registry turns it into a {@link MachineSensorCover} when a player sneak-right-clicks a supported machine.
 *
 * <p>
 * The icon comes from {@link #setTextureName}, which Minecraft's default {@code registerIcons} reads on the client, so
 * this class references no client-only type. {@link #addInformation} is only called by client code; it uses
 * {@code StatCollector}, which exists on both sides.
 */
public final class ItemMachineSensor extends Item {

    public static final ItemMachineSensor INSTANCE = new ItemMachineSensor();

    private ItemMachineSensor() {
        setUnlocalizedName(GregScopeAssets.UNLOCALIZED_MACHINE_SENSOR);
        setTextureName(GregScopeAssets.iconName(GregScopeAssets.ITEM_ICON_MACHINE_SENSOR));
        setMaxStackSize(64);
        setHasSubtypes(false);
    }

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced) {
        for (String key : GregScopeAssets.sensorTooltipKeys()) {
            lines.add(StatCollector.translateToLocal(key));
        }
    }
}
