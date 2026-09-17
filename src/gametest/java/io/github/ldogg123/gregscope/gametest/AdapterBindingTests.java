package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.integration.opencomputers.GregTechMachineDriver;
import io.github.ldogg123.gregscope.probe.GregTechMachineProbe;
import li.cil.oc.api.Network;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Node;

/**
 * What the merged component of a real placed Adapter does when the machine next to it is replaced, and when the
 * Adapter itself is broken and placed again (GS-004 "OC Adapter attach, detach"). GregScope's environment is bound to
 * the position next to the Adapter, not to a tile entity.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope" })
public class AdapterBindingTests {

    private static final String BATCH = "gregscope.oc.adapter";
    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos ADAPTER = at(2, 0, 1);
    /** Upper bound for OpenComputers' network join and component rebuild (observed within 0-1 ticks). */
    private static final int OC_TIMEOUT_TICKS = 20;
    private static final long CHARGE_EU = 64;

    private AdapterBindingTests() {}

    /**
     * The machine next to a placed Adapter is broken and a different GT basic machine is placed at the same spot, then
     * swapped for a third one, then replaced by stone.
     *
     * <p>
     * Observed: OpenComputers never keeps the merged component across these replacements. Breaking the machine removes
     * it at once. Placing a machine gives a new component object with a new address, even when the old machine is
     * swapped out without an air-block notification (GT's own placement notifies the Adapter). Right after placement
     * the Adapter exposes a component with only OC's energy callbacks; the component with {@code getSnapshot} follows a
     * tick later, under yet another address. So a script sees the component disappear and a new one appear, and the new
     * one reports the new machine.
     *
     * <p>
     * Cause of the energy-only tick (GregScope's own driver gating, not an OC/GT defect): GT's placement notifies
     * neighbours while its new holder has no meta tile entity yet. OC's energy driver matches that bare holder by type,
     * but {@link GregTechMachineDriver#worksWith} requires a present, supported meta tile entity, so the Adapter builds
     * a component from the energy driver alone. On GT's first tick its block update notifies the Adapter again, the set
     * of matching drivers has grown, and OC rebuilds the component with a new address. The test checks the bare-holder
     * half directly (same block state, placed without notification) and asserts the rebuild. The detached component
     * objects stay bound to the position: called directly from Java (no computer can
     * reach them any more) they report whatever supported machine is there now, and soft-error next to stone.
     */
    @GameTest(batch = BATCH, timeoutTicks = 100)
    public static void replacedMachineGetsNewComponent(GameTestHelper helper) {
        GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_Macerator.get(1));
        TileEntity adapter = OcComponents.placeBlock(helper, ADAPTER, "adapter");
        Network.joinOrCreateNetwork(adapter);
        State st = new State();

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the macerator",
                OC_TIMEOUT_TICKS,
                () -> { st.first = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter); })
            .thenExecute("break the macerator", () -> {
                Map<String, Object> s = OcComponents.snapshot(helper, "adapter#1 macerator", st.first);
                helper.assertEquals(metaName(helper), s.get("metaName"), "macerator metaName");

                helper.destroyBlock(MACHINE);
                logAdapterComponents(helper, "adapter#1 right after breaking the macerator", adapter);
                helper.assertNull(
                    OcComponents.findAdapterSnapshotComponent(helper, (Environment) adapter),
                    "Adapter still exposes a getSnapshot component after the machine was broken");
                OcComponents.assertUnavailable(helper, "adapter#1 old component after break", st.first);
            })
            .thenExecute("bare GT holder, as GT's placement notifies neighbours", () -> assertBareHolderDrivers(helper))
            .thenExecute("place an electric furnace at the same position", () -> {
                GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_E_Furnace.get(1));
                st.expectedMetaName = metaName(helper);
                logAdapterComponents(helper, "adapter#1 right after placing the furnace", adapter);
                st.energyOnly = assertOnlyEnergyComponent(helper, "right after placing the furnace", adapter);
                st.startTick = helper.getWorld()
                    .getTotalWorldTime();
            })
            .thenWaitUntil("Adapter exposes the furnace", OC_TIMEOUT_TICKS, () -> {
                st.second = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter);
                Map<String, Object> s = OcComponents.snapshot(helper, "adapter#1 furnace component", st.second);
                helper.assertEquals(st.expectedMetaName, s.get("metaName"), "furnace metaName via the Adapter");
            })
            .thenExecute("swap the furnace for a compressor", () -> {
                logTicks(helper, "adapter#1 furnace placed -> component with getSnapshot", st.startTick);
                helper.assertNotSame(st.first, st.second, "component object after replacing the machine");
                helper.assertNotEquals(st.first.address(), st.second.address(), "component address after replacement");
                assertRebuiltFromEnergyOnly(helper, "furnace", adapter, st.energyOnly, st.second);
                // Detached old component, called from Java: bound to the position, so it reads the furnace now.
                Map<String, Object> old = OcComponents
                    .snapshot(helper, "adapter#1 detached macerator component (Java call)", st.first);
                helper.assertEquals(st.expectedMetaName, old.get("metaName"), "detached component metaName");

                // No air-block notification in between: flag 2 only updates clients.
                TestPos abs = helper.absolute(MACHINE);
                helper.getWorld()
                    .setBlock(abs.x(), abs.y(), abs.z(), Blocks.air, 0, 2);
                GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_Compressor.get(1));
                st.expectedMetaName = metaName(helper);
                logAdapterComponents(helper, "adapter#1 right after the swap", adapter);
                st.energyOnly = assertOnlyEnergyComponent(helper, "right after the swap", adapter);
                st.startTick = helper.getWorld()
                    .getTotalWorldTime();
            })
            .thenWaitUntil("Adapter exposes the compressor", OC_TIMEOUT_TICKS, () -> {
                st.third = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter);
                Map<String, Object> s = OcComponents.snapshot(helper, "adapter#1 compressor component", st.third);
                helper.assertEquals(st.expectedMetaName, s.get("metaName"), "compressor metaName via the Adapter");
            })
            .thenExecute("replace the compressor with stone", () -> {
                logTicks(helper, "adapter#1 compressor placed -> component with getSnapshot", st.startTick);
                helper.assertNotSame(st.second, st.third, "component object after the swap");
                helper.assertNotEquals(st.second.address(), st.third.address(), "component address after the swap");
                assertRebuiltFromEnergyOnly(helper, "compressor", adapter, st.energyOnly, st.third);
                Map<String, Object> old = OcComponents
                    .snapshot(helper, "adapter#1 detached furnace component (Java call)", st.second);
                helper.assertEquals(st.expectedMetaName, old.get("metaName"), "detached component metaName");

                helper.setBlock(MACHINE, Blocks.stone);
                logAdapterComponents(helper, "adapter#1 right after stone", adapter);
                helper.assertNull(
                    OcComponents.findAdapterSnapshotComponent(helper, (Environment) adapter),
                    "Adapter still exposes a getSnapshot component next to stone");
                OcComponents.assertUnavailable(helper, "adapter#1 compressor component after stone", st.third);
                OcComponents.assertUnavailable(helper, "adapter#1 macerator component after stone", st.first);
            })
            .thenSucceed();
    }

    /**
     * The placed Adapter is broken while the machine stays, then a new Adapter is placed at the same spot.
     *
     * <p>
     * Observed: breaking the Adapter throws nothing and removes the Adapter's node from its network. The old merged
     * component object is left in a network of its own (with its per-driver nodes), which no computer is part of.
     * Called directly from Java it still works: GregScope's position-bound environment reads the machine, and OC's
     * energy environment reads the still-loaded machine tile. A new Adapter gets a new merged component with a new
     * address (the broken Adapter's saved addresses are gone) and the same name.
     */
    @GameTest(batch = BATCH, timeoutTicks = 100)
    public static void adapterDetachAndReattach(GameTestHelper helper) {
        IGregTechTileEntity holder = GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_Macerator.get(1));
        helper.assertTrue(holder.increaseStoredEnergyUnits(CHARGE_EU, true), "could not charge the machine");
        TileEntity adapter = OcComponents.placeBlock(helper, ADAPTER, "adapter");
        Network.joinOrCreateNetwork(adapter);
        State st = new State();

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the machine",
                OC_TIMEOUT_TICKS,
                () -> { st.first = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter); })
            .thenExecute("break the Adapter", () -> {
                st.before = Snapshots.probe(helper, MACHINE, "adapter#2 machine");
                OcComponents.snapshot(helper, "adapter#2 component before detach", st.first);
                Node adapterNode = ((Environment) adapter).node();

                helper.destroyBlock(ADAPTER);
                helper.assertFalse(adapterTileAt(helper) instanceof Environment, "Adapter still present");
                helper.assertNull(adapterNode.network(), "broken Adapter's node still in a network");
                helper.assertNotNull(st.first.network(), "old merged component network (observed: kept)");
                helper.assertNull(
                    st.first.network()
                        .node(adapterNode.address()),
                    "old merged component still networked with the broken Adapter");

                // Java calls on the detached component: GregScope reads the machine at the position ...
                Map<String, Object> s = OcComponents
                    .snapshot(helper, "adapter#2 detached component (Java call)", st.first);
                helper.assertEquals(st.before.get("metaName"), s.get("metaName"), "detached component metaName");
                // ... and OC's energy environment still reads the machine tile, which is still loaded.
                Object[] eu = OcComponents.invoke(helper, st.first, "getStoredEU");
                helper.assertTrue(eu != null && eu.length == 1, "getStoredEU result count");
                helper.assertEquals(
                    CHARGE_EU,
                    OcComponents.asLong(helper, eu[0], "getStoredEU"),
                    "detached component getStoredEU");
                helper.assertInstanceOf(
                    IGregTechTileEntity.class,
                    helper.assertTileEntityPresent(MACHINE),
                    "machine gone after breaking the Adapter");
            })
            .thenIdle(1)
            .thenExecute("place a new Adapter", () -> {
                st.newAdapter = OcComponents.placeBlock(helper, ADAPTER, "adapter");
                helper.assertNotSame(adapter, st.newAdapter, "Adapter tile entity object");
                Network.joinOrCreateNetwork(st.newAdapter);
                st.startTick = helper.getWorld()
                    .getTotalWorldTime();
            })
            .thenWaitUntil(
                "new Adapter exposes the machine",
                OC_TIMEOUT_TICKS,
                () -> { st.second = OcComponents.adapterSnapshotComponent(helper, (Environment) st.newAdapter); })
            .thenExecute("new Adapter component", () -> {
                logTicks(helper, "adapter#2 new Adapter placed -> component with getSnapshot", st.startTick);
                Map<String, Object> s = OcComponents.snapshot(helper, "adapter#2 new Adapter component", st.second);
                Snapshots.assertStatus(helper, s, "idle", "none");
                helper.assertEquals(st.before.get("metaName"), s.get("metaName"), "metaName via the new Adapter");
                helper.assertEquals(
                    CHARGE_EU,
                    OcComponents.asLong(helper, s.get("energyStored"), "energyStored"),
                    "energyStored via the new Adapter");
                helper.assertNotSame(st.first, st.second, "merged component object of the new Adapter");
                helper.assertEquals(st.first.name(), st.second.name(), "component name");
                helper.assertNotEquals(st.first.address(), st.second.address(), "component address");
            })
            .thenSucceed();
    }

    /**
     * Observed right after GT places a machine next to an existing Adapter: the Adapter has rebuilt its component while
     * the new tile entity had no meta tile entity yet, so GregScope's driver did not match and only OC's energy
     * callbacks are there. Returns every reachable component with energy callbacks (observed: the merged
     * {@code gt_energycontainer} and OC's per-driver {@code gt_energyContainer} node). A tick later the Adapter
     * rebuilds
     * it again with {@code getSnapshot}.
     */
    private static List<Component> assertOnlyEnergyComponent(GameTestHelper helper, String label, TileEntity adapter) {
        Node node = ((Environment) adapter).node();
        List<Component> energy = new ArrayList<>();
        for (Node reachable : node.reachableNodes()) {
            if (reachable instanceof Component) {
                Component c = (Component) reachable;
                helper.assertFalse(
                    c.methods()
                        .contains("getSnapshot"),
                    label + ": getSnapshot already present");
                if (c.methods()
                    .contains("getStoredEU")) {
                    energy.add(c);
                }
            }
        }
        helper.assertFalse(energy.isEmpty(), label + ": no OC energy component");
        return energy;
    }

    /**
     * The state GT's {@code ItemMachines.placeBlockAt} notifies neighbours in: the machine block and its holder tile
     * entity exist, the meta tile entity does not. Reproduced with flag 2 (no neighbour notification) and removed again
     * the same way in the same tick. OC's driver lookup gives the energy callbacks only, and GregScope's driver does
     * not match.
     */
    private static void assertBareHolderDrivers(GameTestHelper helper) {
        TestPos abs = helper.absolute(MACHINE);
        World world = helper.getWorld();
        ItemStack stack = ItemList.Machine_LV_E_Furnace.get(1);
        int baseType = GregTechAPI.METATILEENTITIES[stack.getItemDamage()].getTileEntityBaseType();
        helper.assertTrue(
            world.setBlock(abs.x(), abs.y(), abs.z(), Block.getBlockFromItem(stack.getItem()), baseType, 2),
            "could not place the bare GT machine block");
        IGregTechTileEntity holder = helper.assertInstanceOf(
            IGregTechTileEntity.class,
            world.getTileEntity(abs.x(), abs.y(), abs.z()),
            "bare GT machine block has no GT holder");
        helper.assertFalse(holder.canAccessData(), "bare GT holder already has a meta tile entity");
        helper.assertFalse(
            new GregTechMachineDriver(new GregTechMachineProbe())
                .worksWith(world, abs.x(), abs.y(), abs.z(), ForgeDirection.UNKNOWN),
            "GregScope's driver matches the bare GT holder");
        Component bare = OcComponents.component(helper, OcComponents.adapterEnvironment(helper, MACHINE));
        Snapshots.log("adapter#1 OC environment for the bare GT holder", new TreeSet<>(bare.methods()));
        helper.assertTrue(
            bare.methods()
                .contains("getStoredEU"),
            "OC's energy driver does not match the bare GT holder");
        helper.assertFalse(
            bare.methods()
                .contains("getSnapshot"),
            "bare GT holder environment has getSnapshot");
        world.setBlock(abs.x(), abs.y(), abs.z(), Blocks.air, 0, 2);
    }

    /** The energy-only components were thrown away, not extended: the getSnapshot component is a new one. */
    private static void assertRebuiltFromEnergyOnly(GameTestHelper helper, String label, TileEntity adapter,
        List<Component> energyOnly, Component rebuilt) {
        for (Component old : energyOnly) {
            helper.assertNotSame(old, rebuilt, label + ": energy-only component object reused");
            helper.assertNotEquals(old.address(), rebuilt.address(), label + ": energy-only component address kept");
            for (Node reachable : ((Environment) adapter).node()
                .reachableNodes()) {
                helper.assertNotSame(old, reachable, label + ": energy-only component still reachable " + old.name());
            }
        }
    }

    /** Logs every component reachable from the Adapter with its name and callbacks. */
    private static void logAdapterComponents(GameTestHelper helper, String label, TileEntity adapter) {
        Node node = ((Environment) adapter).node();
        List<String> found = new ArrayList<>();
        if (node != null && node.network() != null) {
            for (Node reachable : node.reachableNodes()) {
                if (reachable instanceof Component) {
                    Component c = (Component) reachable;
                    found.add(c.name() + new TreeSet<>(c.methods()));
                }
            }
        }
        Snapshots.log(label + ": reachable components", found);
    }

    private static void logTicks(GameTestHelper helper, String label, long startTick) {
        long ticks = helper.getWorld()
            .getTotalWorldTime() - startTick;
        System.out.println("[GregScope gametest] " + label + ": " + ticks + " ticks");
    }

    private static TileEntity adapterTileAt(GameTestHelper helper) {
        TestPos abs = helper.absolute(ADAPTER);
        return helper.getWorld()
            .getTileEntity(abs.x(), abs.y(), abs.z());
    }

    private static String metaName(GameTestHelper helper) {
        return helper.gtnh()
            .gtTile(MACHINE)
            .getMetaTileEntity()
            .getMetaName();
    }

    /** Mutable values shared between sequence steps. */
    private static final class State {

        Component first;
        Component second;
        Component third;
        List<Component> energyOnly;
        String expectedMetaName;
        Map<String, Object> before;
        TileEntity newAdapter;
        long startTick;
    }
}
