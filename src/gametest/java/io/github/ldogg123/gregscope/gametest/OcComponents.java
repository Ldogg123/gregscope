package io.github.ldogg123.gregscope.gametest;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;

import li.cil.oc.api.Driver;
import li.cil.oc.api.driver.SidedBlock;
import li.cil.oc.api.network.Component;
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
