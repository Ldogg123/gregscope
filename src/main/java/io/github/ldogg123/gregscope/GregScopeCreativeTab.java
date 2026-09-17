package io.github.ldogg123.gregscope;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Items;
import net.minecraft.item.Item;

/**
 * The {@code gregscope} creative tab (label key {@code itemGroup.gregscope}). Constructing a tab is safe on a dedicated
 * server: the constructor only registers it in {@code CreativeTabs.creativeTabArray}.
 */
public final class GregScopeCreativeTab extends CreativeTabs {

    public static final String LABEL = GregScope.MODID;

    GregScopeCreativeTab() {
        super(LABEL);
    }

    /**
     * Called only by client code. It references no client class, so it needs no {@code @SideOnly}. A vanilla
     * comparator stands in until the Machine Sensor item exists (GS-105).
     */
    @Override
    public Item getTabIconItem() {
        return Items.comparator;
    }
}
