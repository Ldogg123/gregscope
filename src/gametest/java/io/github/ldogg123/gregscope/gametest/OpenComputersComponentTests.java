package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestArguments;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.api.annotation.MethodSource;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.integration.opencomputers.GregTechMachineDriver;
import io.github.ldogg123.gregscope.integration.opencomputers.GregTechMachineEnvironment;
import io.github.ldogg123.gregscope.probe.GregTechMachineProbe;
import kekztech.common.TileEntities;
import kekztech.common.tileentities.MTELapotronicSuperCapacitor;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.ManagedEnvironment;
import tectech.thing.CustomItemList;
import tectech.thing.metaTileEntity.multi.bec.MTEBECDiode;
import tectech.thing.metaTileEntity.multi.bec.MTEBECIONode;
import tectech.thing.metaTileEntity.multi.bec.MTEBECStorage;

/** Verifies GregScope's driver through OpenComputers' own driver registry, exactly as an Adapter would use it. */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope" })
public class OpenComputersComponentTests {

    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos EBF_CONTROLLER = at(1, 0, 0);
    private static final TestPos EBF_ENERGY_HATCH = at(0, 0, 0);

    @GameTest(batch = "gregscope.oc", timeoutTicks = 100)
    public static void basicMachineMergesIntoEnergyContainerComponent(GameTestHelper helper) {
        IGregTechTileEntity holder = GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_Macerator.get(1));
        int metaId = holder.getMetaTileID();
        String metaName = holder.getMetaTileEntity()
            .getMetaName();

        helper.startSequence()
            .thenIdle(5)
            .thenExecute(() -> {
                ManagedEnvironment env = OcComponents.adapterEnvironment(helper, MACHINE);
                Component component = OcComponents.component(helper, env);
                helper.assertEquals("gt_energycontainer", component.name(), "merged component name");

                Collection<String> methods = component.methods();
                helper.assertTrue(methods.contains("getSnapshot"), "methods lack getSnapshot: " + methods);
                helper.assertTrue(methods.contains("getStoredEU"), "methods lack getStoredEU: " + methods);

                Object[] result = OcComponents.invoke(helper, component, "getSnapshot");
                helper.assertNotNull(result, "getSnapshot returned null");
                helper.assertEquals(1L, result.length, "getSnapshot result count");
                Map<String, Object> snapshot = OcComponents.asMap(helper, result[0]);

                TestPos abs = helper.absolute(MACHINE);
                helper.assertEquals(1L, OcComponents.asLong(helper, snapshot.get("schemaVersion"), "schemaVersion"));
                helper.assertEquals("singleblock", snapshot.get("kind"), "kind in " + snapshot);
                helper.assertEquals("idle", snapshot.get("state"), "state in " + snapshot);
                helper.assertEquals("none", snapshot.get("statusId"), "statusId in " + snapshot);
                helper.assertEquals(metaName, snapshot.get("metaName"), "metaName in " + snapshot);
                helper.assertEquals((long) metaId, OcComponents.asLong(helper, snapshot.get("metaId"), "metaId"));
                helper.assertEquals((long) abs.x(), OcComponents.asLong(helper, snapshot.get("x"), "x"));
                helper.assertEquals((long) abs.y(), OcComponents.asLong(helper, snapshot.get("y"), "y"));
                helper.assertEquals((long) abs.z(), OcComponents.asLong(helper, snapshot.get("z"), "z"));

                Object[] stored = OcComponents.invoke(helper, component, "getStoredEU");
                helper.assertNotNull(stored, "getStoredEU returned null");
                helper.assertTrue(stored.length >= 1 && stored[0] != null, "getStoredEU returned no value");
            })
            .thenSucceed();
    }

    @GameTest(batch = "gregscope.oc", timeoutTicks = 100)
    public static void destroyedMachineSoftErrors(GameTestHelper helper) {
        GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_Macerator.get(1));

        helper.startSequence()
            .thenIdle(5)
            .thenExecute(() -> {
                ManagedEnvironment env = OcComponents.adapterEnvironment(helper, MACHINE);
                Component component = OcComponents.component(helper, env);
                Object[] before = OcComponents.invoke(helper, component, "getSnapshot");
                helper.assertTrue(before != null && before.length == 1 && before[0] != null, "live snapshot");

                helper.destroyBlock(MACHINE);
                TestPos abs = helper.absolute(MACHINE);
                helper.assertFalse(
                    helper.getWorld()
                        .getTileEntity(abs.x(), abs.y(), abs.z()) instanceof IGregTechTileEntity,
                    "GT machine still present after destroyBlock");

                // Same environment (as a cached Adapter component would be) after the block is gone.
                Object[] after = OcComponents.invoke(helper, component, "getSnapshot");
                Snapshots.log("oc#2 getSnapshot after destroy", after == null ? null : Arrays.asList(after));
                helper.assertNotNull(after, "getSnapshot returned null");
                helper.assertEquals(2L, after.length, "soft error result count");
                helper.assertNull(after[0], "soft error first value");
                helper.assertEquals("machine unavailable", after[1], "soft error message");
            })
            .thenSucceed();
    }

    @GameTest(template = ElectricBlastFurnaceSnapshotTests.TEMPLATE, batch = "gregscope.oc", timeoutTicks = 100)
    public static void multiblockControllerMergesIntoEnergyContainerComponent(GameTestHelper helper) {
        ManagedEnvironment env = OcComponents.adapterEnvironment(helper, EBF_CONTROLLER);
        Component component = OcComponents.component(helper, env);
        helper.assertEquals("gt_energycontainer", component.name(), "merged component name");
        Collection<String> methods = component.methods();
        helper.assertTrue(methods.contains("getSnapshot"), "methods lack getSnapshot: " + methods);

        Object[] result = OcComponents.invoke(helper, component, "getSnapshot");
        helper.assertTrue(result != null && result.length == 1, "getSnapshot result count");
        Map<String, Object> snapshot = OcComponents.asMap(helper, result[0]);
        Snapshots.log("oc#3 EBF controller via OC", snapshot);
        helper.assertEquals("multiblock", snapshot.get("kind"), "kind in " + snapshot);
        helper.assertEquals(
            (long) helper.gtnh()
                .gtTile(EBF_CONTROLLER)
                .getMetaTileID(),
            OcComponents.asLong(helper, snapshot.get("metaId"), "metaId"));
        helper.succeed();
    }

    @GameTest(template = ElectricBlastFurnaceSnapshotTests.TEMPLATE, batch = "gregscope.oc", timeoutTicks = 100)
    public static void hatchIsLeftToOpenComputersEnergyDriver(GameTestHelper helper) {
        GregTechMachineDriver ours = new GregTechMachineDriver(new GregTechMachineProbe());
        World world = helper.getWorld();
        TestPos hatch = helper.absolute(EBF_ENERGY_HATCH);
        TestPos controller = helper.absolute(EBF_CONTROLLER);
        helper.assertFalse(
            ours.worksWith(world, hatch.x(), hatch.y(), hatch.z(), ForgeDirection.UNKNOWN),
            "GregScope driver claims the energy hatch");
        helper.assertTrue(
            ours.worksWith(world, controller.x(), controller.y(), controller.z(), ForgeDirection.UNKNOWN),
            "GregScope driver rejects the controller (control check)");

        ManagedEnvironment env = OcComponents.adapterEnvironment(helper, EBF_ENERGY_HATCH);
        Component component = OcComponents.component(helper, env);
        Collection<String> methods = component.methods();
        Snapshots.log("oc#4 energy hatch component " + component.name(), methods);
        helper.assertEquals("gt_energycontainer", component.name(), "hatch component name");
        helper.assertTrue(methods.contains("getStoredEU"), "OC energy driver missing on hatch: " + methods);
        helper.assertFalse(methods.contains("getSnapshot"), "hatch component exposes getSnapshot: " + methods);
        helper.succeed();
    }

    /**
     * Controllers with a dedicated, higher-priority OpenComputers driver. Each row: expected component name, one
     * callback only that OC driver provides, and the controller's MTE class. OC's drivers for these match by
     * {@code instanceof} alone, so a lone (unformed) controller is enough.
     */
    public static Object[] namedControllerKeepsOcComponentName() {
        return new Object[] {
            GameTestArguments.named("lsc", "lsc", "getStoredEUString", MTELapotronicSuperCapacitor.class.getName()),
            GameTestArguments.named("bec_storage", "bec_storage", "getFieldStrength", MTEBECStorage.class.getName()),
            GameTestArguments.named("bec_io_node", "bec_io_node", "getAvailableNanites", MTEBECIONode.class.getName()),
            GameTestArguments
                .named("bec_diode", "bec_diode", "getCondensateFilterCount", MTEBECDiode.class.getName()) };
    }

    @GameTest(batch = "gregscope.oc", timeoutTicks = 100)
    @MethodSource
    public static void namedControllerKeepsOcComponentName(GameTestHelper helper, String expectedName,
        String driverCallback, String mteClass) {
        IGregTechTileEntity holder = GtPlacement.placeMachine(helper, MACHINE, controllerStack(helper, expectedName));
        helper.assertEquals(
            mteClass,
            holder.getMetaTileEntity()
                .getClass()
                .getName(),
            "placed controller MTE class");
        int metaId = holder.getMetaTileID();

        helper.startSequence()
            .thenIdle(5)
            .thenExecute(() -> {
                ManagedEnvironment env = OcComponents.adapterEnvironment(helper, MACHINE);
                Component component = OcComponents.component(helper, env);
                Collection<String> methods = component.methods();
                Snapshots.log("oc#6 " + expectedName + " component " + component.name(), methods);
                // GregScope's environment (priority GregTechMachineEnvironment.PRIORITY) must never win the name.
                helper.assertEquals(
                    expectedName,
                    component.name(),
                    "merged component name (GregScope priority " + GregTechMachineEnvironment.PRIORITY + ")");
                helper.assertTrue(methods.contains(driverCallback), "OC driver callback missing: " + methods);
                helper.assertTrue(methods.contains("getSnapshot"), "methods lack getSnapshot: " + methods);
                helper.assertTrue(methods.contains("getStoredEU"), "methods lack getStoredEU: " + methods);
                // Only getSnapshot is invoked: OC's BEC callbacks are not designed for a lone, unformed controller.

                Object[] result = OcComponents.invoke(helper, component, "getSnapshot");
                helper.assertTrue(result != null && result.length == 1, "getSnapshot result count");
                Map<String, Object> snapshot = OcComponents.asMap(helper, result[0]);
                Snapshots.log("oc#6 " + expectedName + " getSnapshot", snapshot);
                helper.assertEquals(1L, OcComponents.asLong(helper, snapshot.get("schemaVersion"), "schemaVersion"));
                helper.assertEquals("multiblock", snapshot.get("kind"), "kind in " + snapshot);
                helper.assertEquals(mteClass, snapshot.get("machineClass"), "machineClass in " + snapshot);
                helper.assertEquals((long) metaId, OcComponents.asLong(helper, snapshot.get("metaId"), "metaId"));
                // A lone controller 5 ticks after placement has not run GT's 100-tick startup structure check yet.
                helper.assertEquals("starting", snapshot.get("state"), "state in " + snapshot);
                helper.assertEquals("startup_check", snapshot.get("statusId"), "statusId in " + snapshot);
            })
            .thenSucceed();
    }

    private static ItemStack controllerStack(GameTestHelper helper, String componentName) {
        switch (componentName) {
            case "lsc":
                // kekztech registers the LSC without an ItemList constant.
                return TileEntities.lsc.getStackForm(1);
            case "bec_storage":
                return CustomItemList.Machine_Multi_BECStorage.get(1);
            case "bec_io_node":
                return CustomItemList.Machine_Multi_BECIONode.get(1);
            case "bec_diode":
                return CustomItemList.Machine_Multi_BECDiode.get(1);
            default:
                helper.fail("no controller stack for " + componentName);
                return null;
        }
    }

    private OpenComputersComponentTests() {}
}
