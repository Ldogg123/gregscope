package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.ItemList;
import li.cil.oc.api.Items;
import li.cil.oc.api.Network;
import li.cil.oc.api.detail.ItemInfo;
import li.cil.oc.api.internal.Case;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Connector;
import li.cil.oc.api.network.Node;

/**
 * Runs the documented example script {@code docs/examples/gregscope-snapshot.lua} on a real OpenComputers computer
 * booting the stock OpenOS floppy, headless, and checks its printed output against GregScope's probe.
 *
 * <p>
 * Gradle copies the repo file byte for byte into the game test resources. Java writes it and a small runner
 * ({@code gregscope-gametest/autorun.lua}) onto the computer's hard drive through OC's filesystem callbacks, starts the
 * computer and polls every server tick for the runner's status marker, then reads the captured output back through
 * the same callbacks.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope" })
public class OpenComputersExampleScriptTests {

    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos ADAPTER = at(2, 0, 1);
    private static final TestPos CASE = at(3, 0, 1);

    private static final String RESOURCES = "/gregscope-gametest/";
    private static final String SCRIPT = "gregscope-snapshot.lua";
    private static final String RUNNER = "autorun.lua";
    private static final String OUT = "/gregscope-out.txt";
    private static final String ARGS = "/gregscope-args.txt";
    private static final String DUMP = "/gregscope-dump.txt";
    private static final String STATUS = "/gregscope-status.txt";
    private static final String STATUS_TMP = "/gregscope-status.tmp";
    private static final String EVENT_LOG = "/event.log"; // OpenOS event.onError log, on the tmpfs (/tmp)

    /** Creative case slots (OC InventorySlots.computer(3)): 3-4 memory, 5 HDD, 7 floppy, 8 CPU, 9 EEPROM. */
    private static final int SLOT_RAM_A = 3;
    private static final int SLOT_RAM_B = 4;
    private static final int SLOT_HDD = 5;
    private static final int SLOT_FLOPPY = 7;
    private static final int SLOT_CPU = 8;
    private static final int SLOT_EEPROM = 9;

    private static final int NETWORK_TIMEOUT_TICKS = 100;
    /**
     * Observed boot plus both script passes: about 140 ticks. Kept well under CI's 300 s runServer budget (server boot
     * plus every batch) so a hung computer still yields a Horizon-QA report instead of a killed process.
     */
    private static final int BOOT_TIMEOUT_TICKS = 600;

    private static final String INDENT = "         ";

    @GameTest(batch = "gregscope.oc.openos", timeoutTicks = 800)
    public static void exampleScriptRunsOnOpenOs(GameTestHelper helper) {
        byte[] script = resource(helper, SCRIPT);
        byte[] runner = resource(helper, RUNNER);

        // GT machine first, so the Adapter wraps it as soon as it joins the network.
        GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_Macerator.get(1));
        TileEntity adapter = placeOcBlock(helper, ADAPTER, "adapter");
        TileEntity caseTile = placeOcBlock(helper, CASE, "caseCreative");
        Case computerCase = helper.assertInstanceOf(Case.class, caseTile, "creative case tile is not an OC Case");
        insert(helper, computerCase, SLOT_RAM_A, "ram6");
        insert(helper, computerCase, SLOT_RAM_B, "ram6");
        insert(helper, computerCase, SLOT_HDD, "hdd3");
        insert(helper, computerCase, SLOT_FLOPPY, "openos");
        insert(helper, computerCase, SLOT_CPU, "cpu3");
        insert(helper, computerCase, SLOT_EEPROM, "luaBios");
        // Join now (as OC's /oc_spawnComputer does) instead of waiting for OC's next-tick scheduler.
        Network.joinOrCreateNetwork(adapter);
        Network.joinOrCreateNetwork(caseTile);

        Machine machine = computerCase.machine();
        helper.assertNotNull(machine, "creative case has no machine");
        Run run = new Run();
        helper.afterTest(() -> {
            // Stop the computer and empty the case so clearing the cell neither races the worker nor drops items.
            machine.stop();
            if (run.hdd != null) {
                // Leave the HDD empty: OC deletes an empty managed disk's save directory when the item is saved,
                // otherwise every local run leaves an orphaned directory under world/opencomputers.
                for (String path : new String[] { "/" + SCRIPT, "/" + RUNNER, ARGS, OUT, DUMP, STATUS, STATUS_TMP }) {
                    try {
                        run.hdd.remove(path);
                    } catch (RuntimeException e) {
                        System.out.println("[GregScope gametest] oc#5 cleanup could not remove " + path + ": " + e);
                    }
                }
            }
            for (int slot = 0; slot < computerCase.getSizeInventory(); slot++) {
                computerCase.setInventorySlotContents(slot, null);
            }
        });

        helper.startSequence()
            .thenWaitUntil("computer networked, powered, HDD and Adapter reachable", NETWORK_TIMEOUT_TICKS, () -> {
                Node node = machine.node();
                helper.assertTrue(node != null && node.network() != null, "machine node not in a network yet");
                double buffer = ((Connector) node).globalBuffer();
                helper.assertTrue(buffer > 0, "creative case has not filled the energy buffer yet: " + buffer);
                run.hdd = null;
                List<String> machines = new ArrayList<>();
                for (Node reachable : node.reachableNodes()) {
                    // Only components the computer can see: an Adapter's merged component hides the per-driver
                    // nodes behind it (Neighbors visibility), though they are still reachable on the network.
                    if (!(reachable instanceof Component) || !((Component) reachable).canBeSeenFrom(node)) {
                        continue;
                    }
                    Component component = (Component) reachable;
                    if ("filesystem".equals(component.name()) && !component.address()
                        .equals(machine.tmpAddress())) {
                        OcFileSystem fs = new OcFileSystem(component, node);
                        if (!fs.isReadOnly()) {
                            run.hdd = fs;
                        }
                    } else if (component.methods()
                        .contains("getSnapshot")) {
                            machines.add(component.name() + "@" + component.address());
                            run.machineAddress = component.address();
                        }
                }
                helper.assertNotNull(run.hdd, "writable HDD filesystem not visible to the computer");
                helper.assertEquals(
                    1L,
                    machines.size(),
                    "components with getSnapshot visible to the computer: " + machines);
            })
            .thenExecute(() -> {
                helper.assertFalse(run.hdd.exists("/init.lua"), "HDD must not carry init.lua (BIOS would boot it)");
                run.hdd.write("/" + SCRIPT, script);
                run.hdd.write("/" + RUNNER, runner);
                // Second run: `gregscope-snapshot <address prefix>` also dumps the full snapshot.
                run.hdd.write(
                    ARGS,
                    run.machineAddress.substring(0, 8)
                        .getBytes(StandardCharsets.UTF_8));
                helper.assertEquals(
                    new String(script, StandardCharsets.UTF_8),
                    run.hdd.readText("/" + SCRIPT),
                    "example script read back from the HDD differs");
                helper.assertFalse(run.hdd.exists(STATUS), "stale status marker on a fresh HDD");

                run.startTick = helper.getWorld()
                    .getTotalWorldTime();
                run.startNanos = System.nanoTime();
                boolean started = machine.start();
                helper.assertTrue(
                    started,
                    "machine.start() refused: lastError=" + machine.lastError()
                        + ", architecture="
                        + architecture(machine));
            })
            .thenWaitUntil("OpenOS booted and the runner published its status", BOOT_TIMEOUT_TICKS, () -> {
                if (run.hdd.exists(STATUS)) {
                    return;
                }
                if (!machine.isRunning()) {
                    // Not retryable: fail now with the crash reason instead of waiting for the timeout.
                    throw new IllegalStateException(
                        "computer stopped before the runner finished: lastError=" + machine.lastError()
                            + ", architecture="
                            + architecture(machine));
                }
                OcFileSystem tmp = tmpFileSystem(machine);
                if (tmp != null && tmp.exists(EVENT_LOG)) {
                    throw new IllegalStateException(
                        "OpenOS logged an uncaught error in /tmp/event.log:\n" + tmp.readText(EVENT_LOG));
                }
                helper.fail(
                    "status marker not yet written; uptime=" + String.format(Locale.ROOT, "%.1f", machine.upTime())
                        + "s");
            })
            .thenExecute(() -> {
                long ticks = helper.getWorld()
                    .getTotalWorldTime() - run.startTick;
                long millis = (System.nanoTime() - run.startNanos) / 1_000_000L;
                String status = run.hdd.readText(STATUS);
                String output = run.hdd.readText(OUT);
                String dump = run.hdd.exists(DUMP) ? run.hdd.readText(DUMP) : "<missing>";
                Snapshots.log(
                    "oc#5 OpenOS example script finished after " + ticks
                        + " ticks / "
                        + millis
                        + " ms (machine uptime "
                        + String.format(Locale.ROOT, "%.2f", machine.upTime())
                        + " s, architecture "
                        + architecture(machine)
                        + "); status",
                    status.trim()
                        .replace("\n", " | "));
                System.out.println("[GregScope gametest] oc#5 captured `gregscope-snapshot` output:\n" + output);
                System.out.println(
                    "[GregScope gametest] oc#5 captured `gregscope-snapshot " + run.machineAddress.substring(0, 8)
                        + "` output:\n"
                        + dump);

                helper.assertTrue(status.startsWith("ok\n"), "example script did not finish cleanly: " + status);
                OcFileSystem tmp = tmpFileSystem(machine);
                if (tmp != null && tmp.exists(EVENT_LOG)) {
                    helper.fail("OpenOS logged an error in /tmp/event.log:\n" + tmp.readText(EVENT_LOG));
                }

                Map<String, Object> probe = Snapshots.probe(helper, MACHINE, "oc#5 probe for the OpenOS machine");
                List<String> expected = expectedSummary(run.machineAddress, probe);
                helper.assertEquals(String.join("\n", expected) + "\n", output, "example script summary output");

                expected.add("Full snapshot of " + run.machineAddress + ":");
                expected.addAll(expectedDump(probe));
                helper.assertEquals(String.join("\n", expected) + "\n", dump, "example script dump output");
            })
            .thenSucceed();
    }

    /** The summary lines gregscope-snapshot.lua prints for one machine, formatted as its fmt() does. */
    private static List<String> expectedSummary(String address, Map<String, Object> s) {
        List<String> lines = new ArrayList<>();
        lines.add(
            String.format(Locale.ROOT, "%s %-11s %s", address.substring(0, 8), fmt(s.get("kind")), fmt(s.get("name"))));
        lines.add(
            String.format(
                Locale.ROOT,
                INDENT + "state=%s statusId=%s progress=%s euPerTick=%s",
                fmt(s.get("state")),
                fmt(s.get("statusId")),
                fmt(s.get("progress")),
                fmt(s.get("euPerTick"))));
        lines.add(INDENT + fmt(s.get("statusText")));
        Object warnings = s.get("warnings");
        if (warnings instanceof List && !((List<?>) warnings).isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Object warning : (List<?>) warnings) {
                parts.add(String.valueOf(warning));
            }
            lines.add(INDENT + "warnings: " + String.join(", ", parts));
        }
        return lines;
    }

    /**
     * The example's dump(): keys sorted (byte order), scalar values through fmt(), lists joined raw in brackets exactly
     * as {@code table.concat} does (fmt() is not applied to list elements).
     */
    private static List<String> expectedDump(Map<String, Object> s) {
        List<String> keys = new ArrayList<>(s.keySet());
        Collections.sort(keys);
        List<String> lines = new ArrayList<>();
        for (String key : keys) {
            Object value = s.get(key);
            String text;
            if (value instanceof List) {
                List<String> parts = new ArrayList<>();
                for (Object element : (List<?>) value) {
                    parts.add(concatElement(key, element));
                }
                text = "[" + String.join(", ", parts) + "]";
            } else {
                text = fmt(value);
            }
            lines.add(String.format(Locale.ROOT, "  %-26s %s", key, text));
        }
        return lines;
    }

    /**
     * What Lua's {@code table.concat} prints for one list element. Only strings and Java integer types are mirrored:
     * table.concat raises on booleans/nil, and float formatting differs between OC architectures (Lua 5.3 prints
     * {@code 1.0}, LuaJ prints {@code 1}), so any other element type fails loudly instead of guessing.
     */
    private static String concatElement(String key, Object element) {
        if (element instanceof String || element instanceof Integer
            || element instanceof Long
            || element instanceof Short
            || element instanceof Byte) {
            return String.valueOf(element);
        }
        throw new IllegalStateException(
            "list '" + key
                + "' holds a "
                + (element == null ? "null"
                    : element.getClass()
                        .getName())
                + " element; extend concatElement() to mirror table.concat for it");
    }

    /** Java mirror of the example's fmt(): integers exact, integral doubles as %.0f, other doubles as %.3f. */
    private static String fmt(Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.floor(d) && Math.abs(d) < 9007199254740992.0) {
                return String.format(Locale.ROOT, "%.0f", d);
            }
            return String.format(Locale.ROOT, "%.3f", d);
        }
        return String.valueOf(value);
    }

    private static TileEntity placeOcBlock(GameTestHelper helper, TestPos local, String name) {
        ItemInfo info = Items.get(name);
        helper.assertTrue(info != null && info.block() != null, "OpenComputers block not registered: " + name);
        TestPos abs = helper.absolute(local);
        World world = helper.getWorld();
        helper.assertTrue(world.setBlock(abs.x(), abs.y(), abs.z(), info.block(), 0, 3), "could not place " + name);
        return helper.assertTileEntityPresent(local);
    }

    private static void insert(GameTestHelper helper, IInventory inventory, int slot, String name) {
        ItemInfo info = Items.get(name);
        helper.assertNotNull(info, "OpenComputers item not registered: " + name);
        helper.assertNotNull(info.createItemStack(1), "OpenComputers item has no stack: " + name);
        inventory.setInventorySlotContents(slot, info.createItemStack(1));
    }

    private static OcFileSystem tmpFileSystem(Machine machine) {
        Node node = machine.node();
        if (node == null || node.network() == null || machine.tmpAddress() == null) {
            return null;
        }
        Node tmp = node.network()
            .node(machine.tmpAddress());
        return tmp instanceof Component ? new OcFileSystem((Component) tmp, node) : null;
    }

    private static String architecture(Machine machine) {
        return machine.architecture() == null ? "none"
            : machine.architecture()
                .getClass()
                .getName();
    }

    private static byte[] resource(GameTestHelper helper, String name) {
        try (InputStream in = OpenComputersExampleScriptTests.class.getResourceAsStream(RESOURCES + name)) {
            helper.assertNotNull(in, "game test resource missing: " + RESOURCES + name);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            helper.fail("could not read game test resource " + name + ": " + e);
            return null;
        }
    }

    /** Mutable state shared between sequence steps. */
    private static final class Run {

        OcFileSystem hdd;
        String machineAddress;
        long startTick;
        long startNanos;
    }

    private OpenComputersExampleScriptTests() {}
}
