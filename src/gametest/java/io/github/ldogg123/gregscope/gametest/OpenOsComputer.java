package io.github.ldogg123.gregscope.gametest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;

import li.cil.oc.api.Items;
import li.cil.oc.api.Network;
import li.cil.oc.api.detail.ItemInfo;
import li.cil.oc.api.driver.item.MutableProcessor;
import li.cil.oc.api.internal.Case;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Connector;
import li.cil.oc.api.network.Node;
import li.cil.oc.server.machine.luac.LuaStateFactory;
import li.cil.oc.server.machine.luac.NativeLua52Architecture;
import li.cil.oc.server.machine.luac.NativeLua53Architecture;
import li.cil.oc.server.machine.luac.NativeLua54Architecture;
import li.cil.oc.server.machine.luaj.LuaJLuaArchitecture;

/**
 * Runs one of the documented example scripts on a real OpenComputers computer booting the stock OpenOS floppy,
 * headless, and hands the captured output back to the test.
 *
 * <p>
 * Gradle copies the repo files byte for byte into the game test resources (addon.gradle), so what runs here is what
 * {@code docs/examples/} ships. Java writes the script and a small runner
 * ({@code gregscope-gametest/autorun.lua}) onto the computer's hard drive through OpenComputers' filesystem callbacks,
 * starts the computer and polls every server tick for the runner's status marker, then reads the captured output back
 * through the same callbacks.
 *
 * <p>
 * Extracted from {@code OpenComputersExampleScriptTests} by GS-116, which needs the same computer for
 * {@code docs/examples/gregscope-hub.lua}: the script name, the callback the runner waits for and the argument of the
 * second run are the only things that differ between the two.
 */
final class OpenOsComputer {

    static final String RESOURCES = "/gregscope-gametest/";
    static final String RUNNER = "autorun.lua";
    static final String SCRIPT_NAME_FILE = "/gregscope-script.txt";
    static final String AWAIT_FILE = "/gregscope-await.txt";
    static final String OUT = "/gregscope-out.txt";
    static final String ARGS = "/gregscope-args.txt";
    static final String DUMP = "/gregscope-dump.txt";
    static final String STATUS = "/gregscope-status.txt";
    static final String STATUS_TMP = "/gregscope-status.tmp";
    /** OpenOS {@code event.onError} log, on the tmpfs (/tmp). */
    static final String EVENT_LOG = "/event.log";

    /** Creative case slots (OC InventorySlots.computer(3)): 3-4 memory, 5 HDD, 7 floppy, 8 CPU, 9 EEPROM. */
    private static final int SLOT_RAM_A = 3;
    private static final int SLOT_RAM_B = 4;
    private static final int SLOT_HDD = 5;
    private static final int SLOT_FLOPPY = 7;
    private static final int SLOT_CPU = 8;
    private static final int SLOT_EEPROM = 9;

    private static final int NETWORK_TIMEOUT_TICKS = 100;
    /**
     * Observed boot plus both script passes: 137-153 ticks locally, 157 ticks on GitHub CI. About 2x that, so a hung
     * computer fails fast. The OpenOS batches run one after the other, so a regression that hangs every computer costs
     * every batch timeout ({@link #BATCH_TIMEOUT_TICKS} each). The worst case for the whole gregscope suite (every
     * batch hitting its timeout) is kept in docs/testing.md, the single source for the CI budget; it must leave room
     * for server boot (about 30 s on CI) inside CI's runServer budget, so a hang still yields a Horizon-QA report
     * instead of a killed process. Update it there when adding a batch or changing a timeout.
     */
    private static final int BOOT_TIMEOUT_TICKS = 300;
    /** Network join ({@link #NETWORK_TIMEOUT_TICKS}) plus boot ({@link #BOOT_TIMEOUT_TICKS}) plus slack. */
    static final int BATCH_TIMEOUT_TICKS = 450;

    private OpenOsComputer() {}

    /**
     * Every CPU architecture shipped in OpenComputers 1.12.61-GTNH, with the {@code _VERSION} OpenOS reports for it.
     */
    static Class<? extends Architecture> architectureClass(GameTestHelper helper, String name) {
        try {
            Class<?> type = Class.forName(name);
            helper.assertTrue(Architecture.class.isAssignableFrom(type), name + " is not an OC architecture");
            @SuppressWarnings("unchecked")
            Class<? extends Architecture> cast = (Class<? extends Architecture>) type;
            return cast;
        } catch (ClassNotFoundException e) {
            helper.fail("OpenComputers architecture class missing: " + name);
            return null;
        }
    }

    /** Native architectures need OC's bundled JNLua library for this OS/CPU; LuaJ is pure Java. */
    static void assumeArchitectureLoadable(GameTestHelper helper, Class<? extends Architecture> architecture) {
        helper.assumeTrue(
            architectureLoadable(architecture),
            "OpenComputers has no native library for " + architecture
                .getSimpleName() + " on " + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
    }

    private static boolean architectureLoadable(Class<? extends Architecture> architecture) {
        if (architecture == NativeLua52Architecture.class) {
            return LuaStateFactory.Lua52$.MODULE$.isAvailable();
        }
        if (architecture == NativeLua53Architecture.class) {
            return LuaStateFactory.Lua53$.MODULE$.isAvailable();
        }
        if (architecture == NativeLua54Architecture.class) {
            return LuaStateFactory.Lua54$.MODULE$.isAvailable();
        }
        return architecture == LuaJLuaArchitecture.class;
    }

    /**
     * Builds an OpenOS computer (creative case, an Adapter at {@code adapterPos} next to the block under test), runs
     * {@code script} once with no arguments and, when {@code secondRunArgument} supplies one, a second time with it,
     * then hands the captured output to {@code verifier}.
     *
     * @param script            file name under {@code docs/examples/}, as copied into the game test resources
     * @param awaitMethod       a callback name the runner waits to see on some component before starting the script
     * @param architecture      CPU architecture selected through the CPU item's NBT, or null for the CPU's default
     * @param beforeStart       runs in the same tick as {@code machine.start()}, just before it
     * @param secondRunArgument the argument of the second run, or null for one run only
     */
    static void run(GameTestHelper helper, String label, TestPos adapterPos, TestPos casePos,
        Class<? extends Architecture> architecture, String expectedVersion, String script, String awaitMethod,
        Argument secondRunArgument, Runnable beforeStart, Verifier verifier) {
        byte[] scriptBytes = resource(helper, script);
        byte[] runner = resource(helper, RUNNER);

        TileEntity adapter = OcComponents.placeBlock(helper, adapterPos, "adapter");
        TileEntity caseTile = OcComponents.placeBlock(helper, casePos, "caseCreative");
        Case computerCase = helper.assertInstanceOf(Case.class, caseTile, "creative case tile is not an OC Case");
        insert(helper, computerCase, SLOT_RAM_A, "ram6");
        insert(helper, computerCase, SLOT_RAM_B, "ram6");
        insert(helper, computerCase, SLOT_HDD, "hdd3");
        insert(helper, computerCase, SLOT_FLOPPY, "openos");
        ItemStack cpu = Items.get("cpu3")
            .createItemStack(1);
        if (architecture != null) {
            MutableProcessor processor = helper.assertInstanceOf(
                MutableProcessor.class,
                li.cil.oc.api.Driver.driverFor(cpu),
                "cpu3 driver cannot switch architectures");
            processor.setArchitecture(cpu, architecture);
        }
        computerCase.setInventorySlotContents(SLOT_CPU, cpu);
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
                String[] paths = { "/" + script, "/" + RUNNER, SCRIPT_NAME_FILE, AWAIT_FILE, ARGS, OUT, DUMP, STATUS,
                    STATUS_TMP };
                for (String path : paths) {
                    try {
                        run.hdd.remove(path);
                    } catch (RuntimeException e) {
                        System.out
                            .println("[GregScope gametest] " + label + " cleanup could not remove " + path + ": " + e);
                    }
                }
            }
            for (int slot = 0; slot < computerCase.getSizeInventory(); slot++) {
                computerCase.setInventorySlotContents(slot, null);
            }
        });

        helper.startSequence()
            .thenWaitUntil("computer networked, powered, HDD and component reachable", NETWORK_TIMEOUT_TICKS, () -> {
                Node node = machine.node();
                helper.assertTrue(node != null && node.network() != null, "machine node not in a network yet");
                double buffer = ((Connector) node).globalBuffer();
                helper.assertTrue(buffer > 0, "creative case has not filled the energy buffer yet: " + buffer);
                run.hdd = null;
                List<String> components = new ArrayList<>();
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
                        .contains(awaitMethod)) {
                            components.add(component.name() + "@" + component.address());
                            run.componentAddress = component.address();
                            run.componentName = component.name();
                        }
                }
                helper.assertNotNull(run.hdd, "writable HDD filesystem not visible to the computer");
                helper.assertEquals(
                    1L,
                    (long) components.size(),
                    "components with " + awaitMethod + " visible to the computer: " + components);
            })
            .thenExecute(() -> {
                helper.assertFalse(run.hdd.exists("/init.lua"), "HDD must not carry init.lua (BIOS would boot it)");
                run.hdd.write("/" + script, scriptBytes);
                run.hdd.write("/" + RUNNER, runner);
                run.hdd.write(SCRIPT_NAME_FILE, script.getBytes(StandardCharsets.UTF_8));
                run.hdd.write(AWAIT_FILE, awaitMethod.getBytes(StandardCharsets.UTF_8));
                String argument = secondRunArgument == null ? null : secondRunArgument.of(run);
                if (argument != null) {
                    run.hdd.write(ARGS, argument.getBytes(StandardCharsets.UTF_8));
                    run.argument = argument;
                }
                helper.assertEquals(
                    new String(scriptBytes, StandardCharsets.UTF_8),
                    run.hdd.readText("/" + script),
                    "example script read back from the HDD differs");
                helper.assertFalse(run.hdd.exists(STATUS), "stale status marker on a fresh HDD");

                beforeStart.run();
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
                run.output = run.hdd.readText(OUT);
                run.dump = run.hdd.exists(DUMP) ? run.hdd.readText(DUMP) : "<missing>";
                Snapshots.log(
                    label + " OpenOS "
                        + script
                        + " finished after "
                        + ticks
                        + " ticks / "
                        + millis
                        + " ms (machine uptime "
                        + String.format(Locale.ROOT, "%.2f", machine.upTime())
                        + " s, architecture "
                        + architecture(machine)
                        + "); status",
                    status.trim()
                        .replace("\n", " | "));
                System.out
                    .println("[GregScope gametest] " + label + " captured `" + script + "` output:\n" + run.output);
                if (run.argument != null) {
                    System.out.println(
                        "[GregScope gametest] " + label
                            + " captured `"
                            + script
                            + " "
                            + run.argument
                            + "` output:\n"
                            + run.dump);
                }

                helper.assertTrue(status.startsWith("ok\n"), "example script did not finish cleanly: " + status);
                OcFileSystem tmp = tmpFileSystem(machine);
                if (tmp != null && tmp.exists(EVENT_LOG)) {
                    helper.fail("OpenOS logged an error in /tmp/event.log:\n" + tmp.readText(EVENT_LOG));
                }
                // Status marker: "ok", detail, _VERSION as OpenOS' sandbox reports it.
                String[] statusLines = status.split("\n", -1);
                helper.assertTrue(statusLines.length >= 3, "status marker lacks the _VERSION line: " + status);
                helper.assertEquals(expectedVersion, statusLines[2], "_VERSION reported by the computer");
                if (architecture != null) {
                    helper.assertEquals(
                        architecture.getName(),
                        architecture(machine),
                        "architecture the computer actually ran");
                }

                verifier.verify(run);
            })
            .thenSucceed();
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
        try (InputStream in = OpenOsComputer.class.getResourceAsStream(RESOURCES + name)) {
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

    /** The argument of the second run, chosen once the component under test is known (for example its address). */
    interface Argument {

        String of(Run run);
    }

    /** Checks the captured output once the runner finished; runs on the server thread in the final sequence step. */
    interface Verifier {

        void verify(Run run);
    }

    /** Mutable state a Horizon-QA sequence carries between its steps. */
    static final class Run {

        OcFileSystem hdd;
        /** Address of the single component carrying the awaited callback. */
        String componentAddress;
        String componentName;
        String argument;
        long startTick;
        long startNanos;
        /** Output of the run with no arguments. */
        String output;
        /** Output of the run with {@link #argument}, or {@code "<missing>"}. */
        String dump;
    }
}
