package io.github.ldogg123.gregscope.probe;

import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
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
 * <b>ME hatches report a flag, never a number.</b> GT's own javadoc on {@code getStoredFluidsForColor} says it
 * "cannot retrieve the ME input amount correctly". A stocking hatch buffers a little locally and pulls the rest
 * from the network on demand, so its readable contents are not the supply the machine has. Reporting that buffer as
 * a level would be a confident wrong number for exactly the AE2 setups this feature is meant to serve, so those
 * inputs are counted with {@link BufferCollector#addMeBacked()} and the UI says "ME".
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
                continue;
            }
            if (hatch instanceof MTEHatchInput) {
                MTEHatchInput tank = (MTEHatchInput) hatch;
                addFluid(into, tank.getFluid(), tank.getCapacity());
            } else if (hatch instanceof MTEHatchOutput) {
                MTEHatchOutput tank = (MTEHatchOutput) hatch;
                addFluid(into, tank.getFluid(), tank.getCapacity());
            }
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

    private static void addFluid(BufferCollector into, FluidStack stack, long capacity) {
        if (stack == null) {
            into.add(null, 0L, capacity);
            return;
        }
        Fluid fluid = stack.getFluid();
        into.add(fluid == null ? null : "f:" + fluid.getName(), stack.amount, capacity);
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
