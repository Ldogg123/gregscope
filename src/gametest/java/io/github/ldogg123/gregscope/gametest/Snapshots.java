package io.github.ldogg123.gregscope.gametest;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;

import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.probe.GregTechMachineProbe;

/**
 * Reads GregScope snapshots straight from the probe (no OpenComputers conversion) and prints every snapshot a test
 * inspects, so the report can quote the values real GT produced.
 */
final class Snapshots {

    private static final GregTechMachineProbe PROBE = new GregTechMachineProbe();

    private Snapshots() {}

    static Map<String, Object> probe(GameTestHelper helper, TestPos local, String label) {
        TestPos abs = helper.absolute(local);
        TileEntity tile = helper.getWorld()
            .getTileEntity(abs.x(), abs.y(), abs.z());
        MachineSnapshot snapshot = PROBE.snapshot(tile);
        helper.assertNotNull(snapshot, label + ": probe returned no snapshot for " + local);
        Map<String, Object> map = snapshot.toMap();
        log(label, map);
        return map;
    }

    static void log(String label, Object snapshot) {
        System.out.println("[GregScope gametest] " + label + " -> " + snapshot);
    }

    static void assertStatus(GameTestHelper helper, Map<String, Object> s, String state, String statusId) {
        helper.assertEquals(state, s.get("state"), "state in " + s);
        helper.assertEquals(statusId, s.get("statusId"), "statusId in " + s);
    }

    static boolean bool(GameTestHelper helper, Map<String, Object> s, String key) {
        return helper.assertInstanceOf(Boolean.class, s.get(key), key + " is not a boolean in " + s);
    }

    static long number(GameTestHelper helper, Map<String, Object> s, String key) {
        return helper.assertInstanceOf(Number.class, s.get(key), key + " is not a number in " + s)
            .longValue();
    }

    static double decimal(GameTestHelper helper, Map<String, Object> s, String key) {
        return helper.assertInstanceOf(Number.class, s.get(key), key + " is not a number in " + s)
            .doubleValue();
    }

    /** Warnings as a list whether they came from the probe (List) or through OpenComputers (Object[]). */
    static List<?> warnings(GameTestHelper helper, Map<String, Object> s) {
        Object value = s.get("warnings");
        if (value instanceof Object[]) {
            return Arrays.asList((Object[]) value);
        }
        if (value instanceof Collection) {
            return Collections.unmodifiableList(new java.util.ArrayList<Object>((Collection<?>) value));
        }
        helper.fail("warnings missing or of unexpected type in " + s);
        return Collections.emptyList();
    }

    static void assertPresent(GameTestHelper helper, Map<String, Object> s, String... keys) {
        for (String key : keys) {
            helper.assertTrue(s.containsKey(key), key + " missing from " + s);
        }
    }

    static void assertAbsent(GameTestHelper helper, Map<String, Object> s, String... keys) {
        for (String key : keys) {
            helper.assertFalse(s.containsKey(key), key + " unexpectedly present in " + s);
        }
    }
}
