package io.github.ldogg123.gregscope.probe;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import gregtech.api.interfaces.IDataCopyable;
import gregtech.api.metatileentity.implementations.MTEHatch;
import io.github.ldogg123.gregscope.buffers.BufferCollector;
import io.github.ldogg123.gregscope.buffers.BufferReading;

/**
 * Reads how much of a stocking bus's configured items the ME network actually holds. GS-306.
 *
 * <p>
 * <b>Loaded only when an ME bus is present.</b> This is the one class in GregScope that names AE2 types. Nothing
 * references it unless {@code BufferProbe} has already identified an ME bus by class name, and an ME bus cannot
 * exist without AE2, so on a pack without AE2 this class is never loaded and its missing imports never matter.
 * Same shape as the Forestry apiary guard v0.1 uses.
 *
 * <p>
 * <b>Why it reads the configuration the long way round.</b> The obvious field, {@code MTEHatchInputBusME.slots},
 * is {@code protected}, and the method that already resolves each slot against the network,
 * {@code updateInformationSlot}, <em>writes</em> to the hatch. Neither is available to a mod that promises never
 * to write to a machine and never to use reflection in shipped code. What is public and read-only is
 * {@code getCopiedData}, the data-stick export, which lists the configured stacks under {@code itemsToStock}.
 *
 * <p>
 * <b>Cost, and why this is not on the sampling path by default.</b> {@code getCopiedData} builds a fresh
 * {@code NBTTagCompound} and serialises every configured stack into it, and each stack then costs a network
 * lookup. That is far heavier than the rest of the buffer walk, so {@link BufferProbe} only calls it when the
 * caller asks for it - the Hub, a command or an OpenComputers call - and never from the per-tick sampler.
 */
final class MeItemProbe {

    /** A stocking bus holds at most this many configured slots; a longer list is a malformed data export. */
    private static final int MAX_SLOTS = 32;

    private MeItemProbe() {}

    /**
     * Adds what the network holds of each item this bus is configured to stock.
     *
     * @return true when the bus was read; false when it could not be, so the caller still flags it as ME-backed
     *         rather than silently reporting nothing
     */
    static boolean addStock(MTEHatch bus, BufferCollector into) {
        if (!(bus instanceof IDataCopyable)) {
            return false;
        }
        NBTTagCompound data;
        try {
            // Read-only: getCopiedData builds a fresh compound and never touches the hatch. Null is passed for the
            // player because the method does not use it; a GT that starts to would throw here and be caught.
            data = ((IDataCopyable) bus).getCopiedData(null);
        } catch (RuntimeException e) {
            return false;
        }
        if (data == null) {
            return false;
        }
        if (data.getBoolean("autoPull")) {
            // An auto-pulling bus has no fixed configuration - it takes whatever the recipe asks for - so there is
            // no list to price. Reporting "the whole network" would be a different and much larger claim.
            return false;
        }
        NBTTagList configured = data.getTagList("itemsToStock", 10);
        if (configured == null || configured.tagCount() == 0) {
            return false;
        }
        IMEMonitor<IAEItemStack> inventory = inventoryOf(bus);
        if (inventory == null) {
            return false;
        }
        BaseActionSource source = new BaseActionSource();
        int slots = Math.min(configured.tagCount(), MAX_SLOTS);
        boolean read = false;
        for (int i = 0; i < slots; i++) {
            ItemStack config = ItemStack.loadItemStackFromNBT(configured.getCompoundTagAt(i));
            if (config == null) {
                continue;
            }
            long held = networkAmount(inventory, config, source);
            if (held > 0L) {
                into.add(key(config), held, BufferReading.UNKNOWN_CAPACITY);
            }
            read = true;
        }
        return read;
    }

    /** A SIMULATE extraction of everything: takes nothing, and reports what the network could give. */
    private static long networkAmount(IMEMonitor<IAEItemStack> inventory, ItemStack config, BaseActionSource source) {
        try {
            IAEItemStack request = AEItemStack.create(config);
            if (request == null) {
                return 0L;
            }
            request.setStackSize(Long.MAX_VALUE);
            IAEItemStack result = inventory.extractItems(request, Actionable.SIMULATE, source);
            return result == null ? 0L : result.getStackSize();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static IMEMonitor<IAEItemStack> inventoryOf(MTEHatch bus) {
        try {
            return ((appeng.me.helpers.IGridProxyable) bus).getProxy()
                .getStorage()
                .getItemInventory();
        } catch (RuntimeException | appeng.me.GridAccessException e) {
            // A bus whose network is down or unformed simply has nothing to report.
            return null;
        }
    }

    /** The section 3 item identity: {@code i:<registryName>:<meta>}, never a numeric id. */
    private static String key(ItemStack stack) {
        Item item = stack.getItem();
        if (item == null) {
            return null;
        }
        String name = Item.itemRegistry.getNameForObject(item);
        return name == null ? null : "i:" + name + ":" + stack.getItemDamage();
    }
}
