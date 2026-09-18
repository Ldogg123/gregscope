package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.horizonqa.api.GameTestArguments;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.AfterBatch;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.api.annotation.MethodSource;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.Tags;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.hub.TelemetryHubs;
import io.github.ldogg123.gregscope.hub.TileTelemetryHub;
import io.github.ldogg123.gregscope.integration.opencomputers.LuaTables;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sensor.Labels;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.network.Component;
import li.cil.oc.server.machine.luac.NativeLua53Architecture;
import li.cil.oc.server.machine.luaj.LuaJLuaArchitecture;

/**
 * GS-116 (design-v0.2 sections 10.3 and 14): runs the documented example script
 * {@code docs/examples/gregscope-hub.lua} on a real OpenComputers computer booting the stock OpenOS floppy, unmodified,
 * and checks what it printed against the component it read.
 *
 * <p>
 * Gradle copies the repo file byte for byte into the game test resources (addon.gradle) and {@link OpenOsComputer}
 * writes it onto the computer's hard drive, so a change to the documented script that breaks it fails here. The AC
 * names Lua 5.3 and LuaJ, which is the matrix below; the two cases run in parallel in one batch, each in its own cell,
 * so every fixture owner is derived from the architecture and the test never empties the shared registry.
 *
 * <p>
 * <b>What is compared exactly, and what is compared structurally.</b> The summary block, the problem list and the
 * table's two heading lines are compared byte for byte against a Java mirror of the script's own {@code string.format}
 * calls, built from a {@code listSensors} call the test makes itself. The frame line carries a sequence number and an
 * age that move with the server, so it is matched against a pattern with the fixed parts asserted. The minute table's
 * body is checked as an invariant instead - every printed row plus every printed gap covers exactly the 60 minutes of
 * the window - because a minute boundary can pass while the computer is booting, which would change the window and one
 * gap by 60 seconds. The chosen sensor is the UNLOADED one, which nothing samples, so its table is the gap path the
 * design asks the example to handle.
 *
 * <p>
 * <b>Batch name.</b> {@code gregscope.surface.ochub.script} sorts after every existing GregScope batch, so no older
 * test's cell moves (the GS-109/GS-110 rule), and it is its own batch because an OpenOS boot costs far more ticks than
 * the {@code gregscope.surface.ochub} tests may.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope", "gtnhlib" })
public class HubExampleScriptTests {

    private static final String BATCH = "gregscope.surface.ochub.script";
    private static final String SCRIPT = "gregscope-hub.lua";
    /** The callback the runner waits to see before it starts the script. */
    private static final String AWAIT = "listSensors";

    private static final TestPos HUB = at(1, 0, 1);
    private static final TestPos ADAPTER = at(2, 0, 1);
    private static final TestPos CASE = at(3, 0, 1);
    private static final TestPos LIVE_MACHINE = at(1, 0, 3);
    private static final TestPos SECOND_MACHINE = at(2, 0, 3);
    private static final TestPos STRANGER_MACHINE = at(3, 0, 3);
    private static final ForgeDirection COVERED = ForgeDirection.UP;

    /** The window the example script asks for. */
    private static final int MINUTES = 60;

    /** design-v0.2 section 9.2: a LIVE sensor in one of these states is a problem, as the script's table says. */
    private static final Set<String> PROBLEM_STATES = new HashSet<>(
        Arrays.asList("shutdown", "power_starved", "output_blocked", "waiting", "unformed", "disabled"));

    private HubExampleScriptTests() {}

    @AfterBatch(BATCH)
    public static void cleanUpAfterHubScript() {
        SensorCleanup.detachAllAndPurge();
    }

    /** The two architectures section 14 names for this script, with the {@code _VERSION} OpenOS reports for each. */
    public static Object[] hubExampleScriptRunsOnOpenOs() {
        return new Object[] { GameTestArguments.named("lua53", NativeLua53Architecture.class.getName(), "Lua 5.3"),
            GameTestArguments.named("luaj", LuaJLuaArchitecture.class.getName(), "Luaj") };
    }

    @GameTest(batch = BATCH, timeoutTicks = OpenOsComputer.BATCH_TIMEOUT_TICKS)
    @MethodSource
    public static void hubExampleScriptRunsOnOpenOs(GameTestHelper helper, String architectureClass,
        String expectedVersion) {
        Class<? extends Architecture> architecture = OpenOsComputer.architectureClass(helper, architectureClass);
        OpenOsComputer.assumeArchitectureLoadable(helper, architecture);
        // Minecraft caps a stored owner name at 16 characters (SensorIdentity.capOwnerName), and the script prints
        // what the Hub stored, so the tag has to be short.
        String tag = expectedVersion.replace(" ", "")
            .replace(".", "");
        String label = "ochub#8[" + tag + "]";
        String ownerName = "ocHub" + tag;
        UUID hubOwner = owner(tag + ":owner");
        UUID stranger = owner(tag + ":stranger");

        TileTelemetryHub hub = placeHub(helper, hubOwner, ownerName);
        helper.assertEquals(hubOwner, hub.owner(), "the Hub did not take the intended owner");
        helper.assertEquals(ownerName, hub.ownerName(), "the Hub stored another owner name");
        UUID live = liveSensor(helper, LIVE_MACHINE, tag, hubOwner, ownerName);
        UUID second = liveSensor(helper, SECOND_MACHINE, tag, hubOwner, ownerName);
        UUID theirs = liveSensor(helper, STRANGER_MACHINE, tag, stranger, ownerName + "X");
        publish(helper);
        settle(helper);
        helper.assertTrue(entry(helper, live).historyLoaded(), "the minute history of a fixture sensor is not ready");
        helper.assertTrue(entry(helper, second).historyLoaded(), "the minute history of a fixture sensor is not ready");

        OpenOsComputer.run(
            helper,
            label,
            ADAPTER,
            CASE,
            architecture,
            expectedVersion,
            SCRIPT,
            AWAIT,
            run -> Labels.shortId(live),
            () -> {},
            run -> {
                Component component = OcComponents.findAdapterComponentWith(helper, adapter(helper), AWAIT);
                helper.assertNotNull(component, "the Adapter lost its gregscope_hub component");
                List<Map<String, Object>> scope = sensors(helper, component);
                helper.assertEquals(2, scope.size(), "the Hub's scope: " + scope);
                Set<String> shown = new HashSet<>();
                for (Map<String, Object> record : scope) {
                    shown.add(String.valueOf(record.get("id")));
                }
                helper.assertTrue(shown.contains(live.toString()), "a fixture sensor is not in scope: " + shown);
                helper.assertTrue(shown.contains(second.toString()), "a fixture sensor is not in scope: " + shown);

                // Section 5: the stranger's sensor is not in scope, so nothing about it may appear in the output.
                helper.assertFalse(
                    run.output.contains(Labels.shortId(theirs)) || run.output.contains(theirs.toString()),
                    "the script printed an out-of-scope sensor: " + run.output);

                assertScriptOutput(helper, run.output, ownerName, scope, chosen(helper, scope), "no argument");
                // The second run asks for one sensor by its short id, which must be the one it prints about.
                assertScriptOutput(helper, run.dump, ownerName, scope, record(helper, scope, live), "short id");
            });
    }

    // --- what the script is expected to print ---

    /**
     * Compares one run's output with the script's documented format: the four summary lines, the problem list and the
     * table heading exactly, the minute table's body as the section 10.4 window invariant.
     */
    private static void assertScriptOutput(GameTestHelper helper, String output, String ownerName,
        List<Map<String, Object>> scope, Map<String, Object> chosen, String what) {
        List<String> lines = new ArrayList<>(Arrays.asList(output.split("\n", -1)));
        helper.assertEquals("", lines.remove(lines.size() - 1), what + ": output must end with a newline");
        List<String> expected = expectedHead(helper, ownerName, scope, chosen);
        helper.assertTrue(lines.size() > expected.size(), what + ": the script printed no table at all:\n" + output);
        for (int i = 0; i < expected.size(); i++) {
            if (i == FRAME_LINE) {
                assertFrameLine(helper, lines.get(i), what);
                continue;
            }
            helper.assertEquals(expected.get(i), lines.get(i), what + ": output line " + i + " of\n" + output);
        }
        assertTableBody(helper, lines.subList(expected.size(), lines.size()), what, output);
    }

    /** Index of the one summary line whose numbers move with the server. */
    private static final int FRAME_LINE = 3;

    private static List<String> expectedHead(GameTestHelper helper, String ownerName, List<Map<String, Object>> scope,
        Map<String, Object> chosen) {
        List<Map<String, Object>> problems = new ArrayList<>();
        for (Map<String, Object> record : scope) {
            if (!"live".equals(record.get("availability")) || PROBLEM_STATES.contains(record.get("state"))) {
                problems.add(record);
            }
        }
        int live = 0;
        for (Map<String, Object> record : scope) {
            if ("live".equals(record.get("availability"))) {
                live++;
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("GregScope Telemetry Hub");
        lines.add(
            String.format(
                Locale.ROOT,
                "  gregscope %s  api %d  schema %d  history %d",
                Tags.VERSION,
                LuaTables.API_VERSION,
                LuaTables.SCHEMA_VERSION,
                LuaTables.HISTORY_VERSION));
        lines.add(
            String.format(
                Locale.ROOT,
                "  owner %s  visible %d  live %d  max %d",
                ownerName,
                scope.size(),
                live,
                GregScope.settings()
                    .maxSensors()));
        lines.add("(the frame line)");
        lines.add(String.format(Locale.ROOT, "Problems (%d):", problems.size()));
        if (problems.isEmpty()) {
            lines.add("  none");
        }
        for (Map<String, Object> record : problems) {
            lines.add(
                String.format(
                    Locale.ROOT,
                    "  %s  %-9s %-14s %s",
                    record.get("shortId"),
                    record.get("availability"),
                    record.get("state"),
                    record.get("displayName")));
        }
        lines.add(
            String.format(
                Locale.ROOT,
                "Last %d min of %s (%s)",
                MINUTES,
                chosen.get("shortId"),
                chosen.get("displayName")));
        lines.add("  minute       coverage  running  EU/t avg");
        return lines;
    }

    /** {@code  frame #123  age 0s  interval 20 ticks  capacity 300s/1440min} with the fixed parts asserted. */
    private static void assertFrameLine(GameTestHelper helper, String line, String what) {
        String pattern = "^ {2}frame #\\d+ {2}age \\d+s {2}interval " + GregScope.settings()
            .intervalTicks()
            + " ticks {2}capacity "
            + LuaTables.SECONDS_CAPACITY
            + "s/"
            + LuaTables.MINUTES_CAPACITY
            + "min$";
        helper.assertTrue(line.matches(pattern), what + ": frame line '" + line + "' does not match " + pattern);
    }

    /**
     * The minute table's body: some observed rows, some merged gap ranges and the summary line. Section 10.4's window
     * is exactly 60 minutes, so the two counts in the summary line must add up to it and must describe the lines that
     * were really printed.
     */
    private static void assertTableBody(GameTestHelper helper, List<String> lines, String what, String output) {
        helper.assertFalse(lines.isEmpty(), what + ": the table has no body:\n" + output);
        String summary = lines.get(lines.size() - 1);
        int rows = 0;
        long gapMinutes = 0L;
        for (String line : lines.subList(0, lines.size() - 1)) {
            if (line.startsWith("  gap ")) {
                // " gap <from>..<to> <reason> (<n> min)"
                String[] parts = line.trim()
                    .split("\\s+");
                helper.assertEquals(5, parts.length, what + ": gap line '" + line + "'");
                gapMinutes += Long.parseLong(parts[3].substring(1));
                helper.assertNotNull(GapReason.fromId(parts[2]), what + ": unknown gap reason in '" + line + "'");
            } else {
                rows++;
                helper.assertTrue(
                    line.matches("^ {2}\\d+ +\\d+% +\\d+% +\\S+$"),
                    what + ": row line '" + line + "' is not a minute row");
            }
        }
        helper.assertEquals(
            String.format(Locale.ROOT, "  %d min: %d observed, %d missing", MINUTES, rows, gapMinutes),
            summary,
            what + ": the summary line disagrees with the lines above it:\n" + output);
        helper.assertEquals((long) MINUTES, rows + gapMinutes, what + ": the window is not " + MINUTES + " minutes");
    }

    // --- fixtures ---

    /** Every record in the Hub's scope, in the order {@code listSensors} returns them. */
    private static List<Map<String, Object>> sensors(GameTestHelper helper, Component component) {
        Object[] result = OcComponents.invoke(helper, component, "listSensors", 0, 64);
        helper.assertTrue(
            result != null && result.length >= 1 && result[0] != null,
            "listSensors did not return a table: " + (result == null ? null : Arrays.asList(result)));
        Map<String, Object> page = OcComponents.asMap(helper, result[0]);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object raw : OcComponents.asList(helper, page.get("sensors"), "sensors")) {
            out.add(OcComponents.asMap(helper, raw));
        }
        return out;
    }

    /** The script's own choice when it is given no argument: the first problem, else the first sensor. */
    private static Map<String, Object> chosen(GameTestHelper helper, List<Map<String, Object>> scope) {
        for (Map<String, Object> record : scope) {
            if (!"live".equals(record.get("availability")) || PROBLEM_STATES.contains(record.get("state"))) {
                return record;
            }
        }
        helper.assertFalse(scope.isEmpty(), "the Hub's scope is empty");
        return scope.get(0);
    }

    private static Map<String, Object> record(GameTestHelper helper, List<Map<String, Object>> scope, UUID id) {
        for (Map<String, Object> record : scope) {
            if (id.toString()
                .equals(record.get("id"))) {
                return record;
            }
        }
        helper.fail("sensor " + id + " is not in the Hub's scope: " + scope);
        return null;
    }

    private static li.cil.oc.api.network.Environment adapter(GameTestHelper helper) {
        return helper.assertInstanceOf(
            li.cil.oc.api.network.Environment.class,
            helper.assertTileEntityPresent(ADAPTER),
            "the Adapter tile entity");
    }

    private static TileTelemetryHub placeHub(GameTestHelper helper, UUID hubOwner, String hubOwnerName) {
        ItemStack stack = TelemetryHubs.hubStack();
        TestPos abs = helper.absolute(HUB);
        ItemBlock item = helper.assertInstanceOf(ItemBlock.class, stack.getItem(), "the Hub item is not an ItemBlock");
        helper.assertTrue(
            item.placeBlockAt(
                stack,
                CommandSenders.realPlayer(helper, hubOwnerName, hubOwner, -1),
                helper.getWorld(),
                abs.x(),
                abs.y(),
                abs.z(),
                1,
                0.5F,
                0.5F,
                0.5F,
                0),
            "the Telemetry Hub was not placed at " + HUB);
        return helper.assertInstanceOf(
            TileTelemetryHub.class,
            helper.assertTileEntityPresent(HUB),
            "placed tile entity at " + HUB);
    }

    /** An owner UUID of this matrix case only: the two cases share a registry, so their scopes must not overlap. */
    private static UUID owner(String tag) {
        return UUID.nameUUIDFromBytes(("gregscope-hubscript:" + tag).getBytes(StandardCharsets.UTF_8));
    }

    /** A LIVE sensor with an id that belongs to this matrix case and this position only. */
    private static UUID liveSensor(GameTestHelper helper, TestPos pos, String tag, UUID sensorOwner, String ownerName) {
        UUID id = UUID.nameUUIDFromBytes(
            ("gregscope-hubscript-sensor:" + tag + ":" + pos.x() + "," + pos.z()).getBytes(StandardCharsets.UTF_8));
        SensorIdentity identity = new SensorIdentity(id, "", sensorOwner, ownerName, 1_600_000_000L);
        IGregTechTileEntity holder = SensorFixtures.placeMachineWithSensorNbt(helper, pos, COVERED, identity);
        MachineSensorCover attached = helper
            .assertInstanceOf(MachineSensorCover.class, holder.getCoverAtSide(COVERED), "sensor cover at " + pos);
        helper.assertTrue(GregScopeTestHooks.heartbeatNow(attached), "GregScope test hooks are disabled");
        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.LIVE, entry.state(), "the fixture sensor did not register");
        helper.assertEquals(sensorOwner, entry.owner(), "owner");
        return id;
    }

    /** Publishes a fresh {@code TelemetryFrame}, which is what the Hub component reads (errata E2: a warp does not). */
    private static void publish(GameTestHelper helper) {
        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");
    }

    /** Lets an async history load land and its answer be written, the way the sampler's per-interval drain does. */
    private static void settle(GameTestHelper helper) {
        helper.assertTrue(GregScopeTestHooks.flushIo(), "the I/O queue did not drain");
        helper.assertTrue(GregScopeTestHooks.applyHistoryLoadsNow() >= 0, "GregScope test hooks are disabled");
        helper.assertTrue(GregScopeTestHooks.flushIo(), "the I/O queue did not drain");
    }

    private static SensorRegistry registry(GameTestHelper helper) {
        SensorRegistry registry = GregScope.registry();
        helper.assertNotNull(registry, "GregScope has no registry; is the server running?");
        return registry;
    }

    private static SensorEntry entry(GameTestHelper helper, UUID id) {
        SensorEntry entry = registry(helper).core()
            .entry(id);
        helper.assertNotNull(entry, "no registry entry for sensor " + id);
        return entry;
    }
}
