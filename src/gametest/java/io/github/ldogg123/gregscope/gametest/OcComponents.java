package io.github.ldogg123.gregscope.gametest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;

import li.cil.oc.api.Driver;
import li.cil.oc.api.Items;
import li.cil.oc.api.detail.ItemInfo;
import li.cil.oc.api.driver.SidedBlock;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.network.Node;

/** Builds OpenComputers components the way an Adapter does and converts callback results for assertions. */
final class OcComponents {

    private OcComponents() {}

    /** The (compound) environment OC would attach to an Adapter next to the block at the test-local position. */
    static ManagedEnvironment adapterEnvironment(GameTestHelper helper, TestPos local) {
        TestPos abs = helper.absolute(local);
        World world = helper.getWorld();
        SidedBlock driver = Driver.driverFor(world, abs.x(), abs.y(), abs.z(), ForgeDirection.UNKNOWN);
        helper.assertNotNull(driver, "OpenComputers has no block driver for " + local);
        ManagedEnvironment env = driver.createEnvironment(world, abs.x(), abs.y(), abs.z(), ForgeDirection.UNKNOWN);
        helper.assertNotNull(env, "OpenComputers driver created no environment for " + local);
        return env;
    }

    /**
     * Places an OpenComputers block by its item name ({@code adapter}, {@code caseCreative}, ...) and returns its tile.
     */
    static TileEntity placeBlock(GameTestHelper helper, TestPos local, String name) {
        ItemInfo info = Items.get(name);
        helper.assertTrue(info != null && info.block() != null, "OpenComputers block not registered: " + name);
        TestPos abs = helper.absolute(local);
        World world = helper.getWorld();
        helper.assertTrue(world.setBlock(abs.x(), abs.y(), abs.z(), info.block(), 0, 3), "could not place " + name);
        return helper.assertTileEntityPresent(local);
    }

    /**
     * The merged component a placed Adapter built for a neighbouring GT machine: the one reachable from the Adapter's
     * node with both GregScope's {@code getSnapshot} and OC's {@code getStoredEU}. The per-driver nodes behind it are
     * reachable too, but only the merged one has both. Fails if there is none (yet) or more than one.
     */
    static Component adapterSnapshotComponent(GameTestHelper helper, Environment adapter) {
        Node node = adapter.node();
        helper.assertTrue(node != null && node.network() != null, "Adapter node not in a network yet");
        Component found = findAdapterSnapshotComponent(helper, adapter);
        helper.assertNotNull(found, "Adapter exposes no merged component with getSnapshot");
        return found;
    }

    /** Like {@link #adapterSnapshotComponent} but returns null if the Adapter currently exposes none. */
    static Component findAdapterSnapshotComponent(GameTestHelper helper, Environment adapter) {
        Node node = adapter.node();
        if (node == null || node.network() == null) {
            return null;
        }
        Component found = null;
        for (Node reachable : node.reachableNodes()) {
            if (reachable instanceof Component && ((Component) reachable).methods()
                .containsAll(Arrays.asList("getSnapshot", "getStoredEU"))) {
                helper.assertNull(found, "more than one merged component with getSnapshot on the Adapter");
                found = (Component) reachable;
            }
        }
        return found;
    }

    /** Asserts GregScope's soft error {@code nil, "machine unavailable"} and logs the raw result. */
    static void assertUnavailable(GameTestHelper helper, String label, Component component) {
        Object[] result = invoke(helper, component, "getSnapshot");
        Snapshots.log(label, result == null ? null : Arrays.asList(result));
        helper.assertTrue(result != null && result.length == 2, label + ": soft error result count");
        helper.assertNull(result[0], label + ": soft error first value");
        helper.assertEquals("machine unavailable", result[1], label + ": soft error message");
    }

    /** The snapshot map of a successful getSnapshot call; fails on a soft error. Logs the snapshot. */
    static Map<String, Object> snapshot(GameTestHelper helper, String label, Component component) {
        Object[] result = invoke(helper, component, "getSnapshot");
        helper.assertTrue(
            result != null && result.length == 1 && result[0] != null,
            label + ": getSnapshot did not return a snapshot: " + (result == null ? null : Arrays.asList(result)));
        Map<String, Object> s = asMap(helper, result[0]);
        Snapshots.log(label, s);
        return s;
    }

    static Component component(GameTestHelper helper, ManagedEnvironment env) {
        Node node = env.node();
        return helper.assertInstanceOf(Component.class, node, "environment node is not a component");
    }

    static Object[] invoke(GameTestHelper helper, Component component, String method) {
        try {
            return component.invoke(method, new StubContext());
        } catch (Exception e) {
            helper.fail(method + " threw " + e);
            return null;
        }
    }

    /** OC converts Java maps into Scala maps before returning them; normalise either form to a Java map. */
    static Map<String, Object> asMap(GameTestHelper helper, Object value) {
        Map<?, ?> raw;
        if (value instanceof Map) {
            raw = (Map<?, ?>) value;
        } else if (value instanceof scala.collection.Map) {
            raw = scala.collection.JavaConversions.mapAsJavaMap((scala.collection.Map<?, ?>) value);
        } else {
            helper.fail("expected a map but got " + (value == null ? "null" : value.getClass()));
            return null;
        }
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /** Numbers may come back boxed as Integer, Long or Double depending on OC's conversion. */
    static long asLong(GameTestHelper helper, Object value, String key) {
        Number n = helper.assertInstanceOf(Number.class, value, key + " is not a number: " + value);
        return n.longValue();
    }
}
