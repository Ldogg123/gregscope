package io.github.ldogg123.gregscope.gametest;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.util.GTUtility;
import io.github.ldogg123.gregscope.sensor.SensorCovers;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorNbtCodec;

/**
 * Places a GT machine whose item NBT already carries an exact GregScope sensor identity, so a test can choose the
 * sensor UUID and, above all, its <b>owner</b>: a cover attached in-game by a {@code FakePlayer} takes the machine's
 * GT owner (design-v0.2 §3.4), which is unowned in a test cell, and the §5 permission rows need owned sensors.
 *
 * <p>
 * Shared by {@link SensorLifecycleTests} (GS-107) and {@link CommandTests} (GS-111).
 */
final class SensorFixtures {

    private SensorFixtures() {}

    /** A GT LV electric furnace placed from an item whose NBT carries {@code identity} on {@code side}. */
    static IGregTechTileEntity placeMachineWithSensorNbt(GameTestHelper helper, TestPos pos, ForgeDirection side,
        SensorIdentity identity) {
        NBTTagCompound data = new NBTTagCompound();
        data.setByte(SensorNbtCodec.GS, (byte) SensorNbtCodec.FORMAT);
        data.setLong(
            SensorNbtCodec.ID_MSB,
            identity.id()
                .getMostSignificantBits());
        data.setLong(
            SensorNbtCodec.ID_LSB,
            identity.id()
                .getLeastSignificantBits());
        data.setLong(SensorNbtCodec.CREATED, identity.createdEpochSec());
        if (!identity.label()
            .isEmpty()) {
            data.setString(SensorNbtCodec.LABEL, identity.label());
        }
        if (identity.owner() != null) {
            data.setLong(
                SensorNbtCodec.OWNER_MSB,
                identity.owner()
                    .getMostSignificantBits());
            data.setLong(
                SensorNbtCodec.OWNER_LSB,
                identity.owner()
                    .getLeastSignificantBits());
            data.setString(SensorNbtCodec.OWNER_NAME, identity.ownerName());
        }
        NBTTagCompound entry = new NBTTagCompound();
        entry.setByte("s", (byte) side.ordinal());
        entry.setInteger("id", GTUtility.stackToInt(SensorCovers.sensorStack()));
        entry.setInteger("tra", 0);
        entry.setTag("d", data);
        NBTTagList covers = new NBTTagList();
        covers.appendTag(entry);
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setTag(GTValues.NBT.COVERS, covers);
        ItemStack stack = ItemList.Machine_LV_E_Furnace.get(1L);
        stack.setTagCompound(nbt);
        return GtPlacement.placeMachine(helper, pos, stack);
    }
}
