package io.github.ldogg123.gregscope.probe;

import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;

import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import io.github.ldogg123.gregscope.buffers.BufferCollector;
import io.github.ldogg123.gregscope.buffers.BufferReading;
import io.github.ldogg123.gregscope.buffers.BufferSet;

/**
 * Reads what a machine is holding, design-v0.3-buffers. The only class besides {@link GregTechMachineProbe} that
 * touches GT types.
 *
 * <p>
 * <b>Why it does not call {@code getStoredFluids()}.</b> That is the obvious API and GregScope cannot use it: it
 * calls {@code setHatchRecipeMap} for every input hatch, which assigns {@code hatch.mRecipeMap}
 * ({@code MTEMultiBlockBase.java:2440-2444}) - a <em>write</em> to the machine, and GregScope's whole promise is
 * that it never writes to one. It also allocates an {@code ArrayList} and a {@code HashMap} per call, which a
 * sampler on a 1 ms/tick budget should not do. So this walks the public hatch lists
 * ({@code MTEMultiBlockBase.java:229-232}) and reads each one through getters that only read.
 *
 * <p>
 * <b>ME hatches report what the network holds.</b> Every hatch is read through {@code getTankInfo}, not through the
 * tank getters, because that is the call an ME hatch answers honestly: {@code MTEHatchInputME.getTankInfo} resolves
 * each configured slot against the network with {@code extractItems(request, Actionable.SIMULATE, ...)} and reports
 * the network's contents. SIMULATE takes nothing, so the read stays read-only, and GregScope needs no AE2
 * dependency because it never names an AE2 type. Such a hatch reports {@code Integer.MAX_VALUE} as its capacity,
 * which is recorded as unmeasurable rather than as a real capacity - an ME-backed input is not a buffer that can be
 * full, and calling it 0% full would be worse than saying nothing.
 *
 * <p>
 * <b>ME item busses are still only flagged.</b> {@code MTEHatchInputBusME.getStackInSlot} returns null for stocked
 * slots outside recipe processing, and the method that holds the network amount writes to the hatch, so it cannot be
 * called. Reading item stock needs a direct ME network query and an AE2 dependency: design-v0.3-buffers GS-306.
 */
public final class BufferProbe {

    /** Class names checked by name, so GregScope still loads on a pack without the ME hatches. */
    private static final String[] ME_HATCHES = { "gregtech.common.tileentities.machines.MTEHatchInputME",
        "gregtech.common.tileentities.machines.MTEHatchInputBusME",
        "gregtech.common.tileentities.machines.MTEHatchCraftingInputME" };

    private BufferProbe() {}

    /** Reads a multiblock's inputs. The collector is the caller's, reused across machines. */
    public static BufferSet readMultiInputs(MTEMultiBlockBase m, BufferCollector into) {
        into.reset();
        readFluidHatches(m.mInputHatches, into);
        readItemBusses(m.mInputBusses, into);
        return into.build();
    }

    /** Reads a multiblock's outputs. An output never comes from an ME network, so nothing is flagged here. */
    public static BufferSet readMultiOutputs(MTEMultiBlockBase m, BufferCollector into) {
        into.reset();
        readFluidHatches(m.mOutputHatches, into);
        readItemBusses(m.mOutputBusses, into);
        return into.build();
    }

    /** Reads a basic machine's own tank and input slots. */
    public static BufferSet readBasicInputs(MTEBasicMachine m, BufferCollector into) {
        into.reset();
        addFluid(into, m.getFillableStack(), m.getCapacity());
        int first = m.getInputSlot();
        for (int i = 0; i < m.mInputSlotCount; i++) {
            addItem(into, safeStack(m, first + i));
        }
        return into.build();
    }

    /** Reads a basic machine's drainable tank and output slots. */
    public static BufferSet readBasicOutputs(MTEBasicMachine m, BufferCollector into) {
        into.reset();
        addFluid(into, m.getDrainableStack(), m.getCapacity());
        int first = m.getOutputSlot();
        for (int i = 0; i < m.mOutputItems.length; i++) {
            addItem(into, safeStack(m, first + i));
        }
        return into.build();
    }

    private static void readFluidHatches(List<? extends MTEHatch> hatches, BufferCollector into) {
        if (hatches == null) {
            return;
        }
        for (int i = 0; i < hatches.size(); i++) {
            MTEHatch hatch = hatches.get(i);
            if (hatch == null || !hatch.isValid()) {
                continue;
            }
            if (isMeBacked(hatch)) {
                into.addMeBacked();
            }
            readTanks(hatch, into);
        }
    }

    private static void readItemBusses(List<? extends MTEHatch> busses, BufferCollector into) {
        if (busses == null) {
            return;
        }
        for (int i = 0; i < busses.size(); i++) {
            MTEHatch bus = busses.get(i);
            if (bus == null || !bus.isValid()) {
                continue;
            }
            if (isMeBacked(bus)) {
                into.addMeBacked();
                continue;
            }
            if (!(bus instanceof MTEHatchInputBus) && !(bus instanceof MTEHatchOutputBus)) {
                continue;
            }
            for (int slot = 0; slot < bus.getSizeInventory(); slot++) {
                addItem(into, safeStack(bus, slot));
            }
        }
    }

    /**
     * A stack read defensively. A bus from another mod may throw on an out-of-range slot, and one misbehaving
     * neighbour must not stop a machine being sampled at all.
     */
    private static ItemStack safeStack(Object holder, int slot) {
        try {
            if (holder instanceof MTEHatch) {
                return ((MTEHatch) holder).getStackInSlot(slot);
            }
            return ((MTEBasicMachine) holder).getStackInSlot(slot);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads a hatch through {@code getTankInfo}, which is the call that makes an ME hatch tell the truth.
     *
     * <p>
     * A plain hatch answers with its own tank either way, so this is not a special case bolted on for AE2 - it is
     * simply the accessor that asks the holder what it has, rather than assuming the holder is a tank.
     * {@code MTEHatchInputME.getTankInfo} resolves each configured slot against the ME network with
     * {@code extractItems(request, Actionable.SIMULATE, ...)} and reports what the <em>network</em> holds, which is
     * the number a player actually wants. SIMULATE takes nothing, so the read stays read-only.
     */
    private static void readTanks(MTEHatch hatch, BufferCollector into) {
        FluidTankInfo[] tanks;
        try {
            tanks = hatch.getTankInfo(ForgeDirection.UNKNOWN);
        } catch (RuntimeException e) {
            // A hatch whose network is unreachable must not stop the machine being sampled.
            return;
        }
        if (tanks == null) {
            return;
        }
        for (int i = 0; i < tanks.length; i++) {
            FluidTankInfo tank = tanks[i];
            if (tank != null) {
                addFluid(into, tank.fluid, tank.capacity);
            }
        }
    }

    private static void addFluid(BufferCollector into, FluidStack stack, long capacity) {
        // An ME hatch reports Integer.MAX_VALUE for "the network, however big that is". Treating that as a real
        // capacity would make saturation read ~0% for a machine that is in fact perfectly supplied, so it is
        // recorded as unmeasurable instead: an ME-backed input is not a buffer that can be full.
        long room = capacity >= Integer.MAX_VALUE ? BufferReading.UNKNOWN_CAPACITY : capacity;
        if (stack == null) {
            into.add(null, 0L, room);
            return;
        }
        Fluid fluid = stack.getFluid();
        into.add(fluid == null ? null : "f:" + fluid.getName(), stack.amount, room);
    }

    private static void addItem(BufferCollector into, ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) {
            return;
        }
        Item item = stack.getItem();
        if (item == null) {
            return;
        }
        String name = Item.itemRegistry.getNameForObject(item);
        if (name == null) {
            return;
        }
        // A slot reports no capacity of its own: section 3 keeps that as "unmeasurable" rather than inventing one.
        into.add("i:" + name + ":" + stack.getItemDamage(), stack.stackSize, BufferReading.UNKNOWN_CAPACITY);
    }

    private static boolean isMeBacked(Object hatch) {
        String name = hatch.getClass()
            .getName();
        for (int i = 0; i < ME_HATCHES.length; i++) {
            if (ME_HATCHES[i].equals(name)) {
                return true;
            }
        }
        return false;
    }
}
