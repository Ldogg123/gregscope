package io.github.ldogg123.gregscope.gametest;

import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.FakePlayer;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Places GT machines the same way a player does, via GT's own {@code ItemMachines.placeBlockAt}. */
final class GtPlacement {

    private GtPlacement() {}

    static IGregTechTileEntity placeMachine(GameTestHelper helper, TestPos local, ItemStack machineStack) {
        helper.assertNotNull(machineStack, "GT machine item stack is not registered");
        TestPos abs = helper.absolute(local);
        helper.assertInstanceOf(ItemBlock.class, machineStack.getItem(), "GT machine item is not an ItemBlock");
        // GT's placement reads the owner's client preferences by UUID (null UUID -> NPE), so place as a fake player.
        FakePlayer player = helper.spawnFakePlayer("gregscope-gametest");
        boolean placed = ((ItemBlock) machineStack.getItem())
            .placeBlockAt(machineStack, player, helper.getWorld(), abs.x(), abs.y(), abs.z(), 1, 0.5F, 0.5F, 0.5F, 0);
        helper.assertTrue(placed, "GT refused to place " + machineStack.getDisplayName());
        TileEntity tile = helper.assertTileEntityPresent(local);
        IGregTechTileEntity holder = helper
            .assertInstanceOf(IGregTechTileEntity.class, tile, "placed tile is not a GT machine");
        helper.assertNotNull(holder.getMetaTileEntity(), "placed GT holder has no meta tile entity");
        return holder;
    }
}
