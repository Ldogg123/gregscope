package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.List;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.AfterBatch;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeAssets;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.command.GregScopeCommand;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sensor.Labels;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;

/**
 * GS-111 (design-v0.2 §11, §5, §3.5, §14): what {@code /gregscope} does on a real dedicated server.
 *
 * <p>
 * The command object is the shipped one and it is driven through {@code processCommand} with recording senders
 * ({@link CommandSenders}), so every assertion is about the shipped permission rules and the shipped output keys, not
 * about a stub. Sensors are placed from machine items whose NBT already carries a chosen identity
 * ({@link SensorFixtures}), because an in-game attach by a {@code FakePlayer} produces an <b>unowned</b> sensor
 * (§3.4) and the §5 rows are all about owners.
 *
 * <p>
 * <b>Batch names.</b> Horizon-QA hands out cells in batch order, and two tests that unload chunks must not end up in
 * neighbouring cells of one chunk (GS-109/GS-110 notes). These batches are therefore named {@code gregscope.surface.*},
 * which sorts after every existing GregScope batch, so no older test's cell moves; and the one chunk-reload test and
 * the one chunk-unload test have a batch each.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope", "gtnhlib" })
public class CommandTests {

    private static final String BATCH = "gregscope.surface.command";
    /** Takes the whole cell down and brings it back, so it runs alone (GS-110 notes). */
    private static final String RELOAD_BATCH = "gregscope.surface.command.reload";
    /** Takes the whole cell down and leaves it down, so it runs alone too. */
    private static final String UNLOAD_BATCH = "gregscope.surface.command.unload";

    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos SECOND = at(3, 0, 1);
    private static final TestPos THIRD = at(1, 0, 3);
    private static final ForgeDirection COVERED = ForgeDirection.UP;

    private static final GregScopeCommand COMMAND = new GregScopeCommand();

    private CommandTests() {}

    @AfterBatch(BATCH)
    public static void cleanUpAfterCommands() {
        SensorCleanup.detachAllAndPurge();
    }

    @AfterBatch(RELOAD_BATCH)
    public static void cleanUpAfterCommandReload() {
        SensorCleanup.detachAllAndPurge();
    }

    @AfterBatch(UNLOAD_BATCH)
    public static void cleanUpAfterCommandUnload() {
        SensorCleanup.detachAllAndPurge();
    }

    // --- stats (the statsCommandRuns test deferred from GS-108) ---

    /**
     * §6.3 and §11: {@code stats} answers with the seven documented lines, its histogram quantiles come from the
     * sampler, and its sensor counts are the §5 {@code canView} ones - the console sees the sensor, a stranger does
     * not.
     */
    @GameTest(batch = BATCH)
    public static void statsCommandRuns(GameTestHelper helper) {
        SensorRegistry registry = emptyRegistry(helper);
        UUID owner = UUID.fromString("00c0ffee-0000-4000-8000-000000000001");
        UUID id = liveSensor(helper, MACHINE, "00a1", owner, "cmdStatsOwner");
        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");

        CommandSenders.Console console = CommandSenders.console();
        run(console, console.chat, "stats");
        for (String key : new String[] { GregScopeAssets.LANG_CMD_STATS_SENSORS, GregScopeAssets.LANG_CMD_STATS_TIMING,
            GregScopeAssets.LANG_CMD_STATS_WORK, GregScopeAssets.LANG_CMD_STATS_REGISTRY,
            GregScopeAssets.LANG_CMD_STATS_IO, GregScopeAssets.LANG_CMD_STATS_MEMORY,
            GregScopeAssets.LANG_CMD_STATS_FRAME }) {
            helper.assertTrue(console.chat.has(key), key + " missing from: " + console.chat);
        }
        helper.assertEquals(7, console.chat.size(), "stats lines: " + console.chat);
        Object[] sensors = console.chat.args(GregScopeAssets.LANG_CMD_STATS_SENSORS);
        helper.assertEquals(
            registry.core()
                .count(SensorState.LIVE),
            number(sensors[0]),
            "the console's live count is the whole registry: " + console.chat);
        helper.assertEquals(1L, number(sensors[0]), "exactly the sensor this test placed");
        // The histogram is real: the sampler ran at least one cycle, so max microseconds is not negative and p99 is
        // not above the max.
        Object[] timing = console.chat.args(GregScopeAssets.LANG_CMD_STATS_TIMING);
        helper.assertTrue(number(timing[2]) >= number(timing[1]), "max < p99 in " + console.chat);
        helper.assertTrue(number(timing[1]) >= number(timing[0]), "p99 < p50 in " + console.chat);
        Object[] frame = console.chat.args(GregScopeAssets.LANG_CMD_STATS_FRAME);
        helper.assertTrue(number(frame[0]) > 0L, "no frame was ever published: " + console.chat);

        // §5: any player may ask, and sees only the sensors canView allows.
        CommandSenders.Player stranger = CommandSenders.player(helper, "cmdStatsStranger");
        run(stranger, stranger.chat, "stats");
        helper.assertEquals(7, stranger.chat.size(), "a non-op player gets the same seven lines");
        helper.assertEquals(
            0L,
            number(stranger.chat.args(GregScopeAssets.LANG_CMD_STATS_SENSORS)[0]),
            "a stranger must not be told about someone else's sensors: " + stranger.chat);
        CommandSenders.Player ownerSender = CommandSenders.player(helper, "cmdStatsOwner", owner, -1);
        run(ownerSender, ownerSender.chat, "stats");
        helper.assertEquals(
            1L,
            number(ownerSender.chat.args(GregScopeAssets.LANG_CMD_STATS_SENSORS)[0]),
            "the owner sees their own sensor: " + ownerSender.chat);
        helper.assertNotNull(id, "sensor id");
        helper.succeed();
    }

    /**
     * §11 says {@code stats} is for anyone at any time, and "any time" includes the first sampling interval of a
     * server run: the command is registered in {@code FMLServerStartingEvent}, before the server has ticked once, so
     * an RCON monitor or an admin at the console can ask before the sampler has published a frame. The frame is
     * {@code TelemetryFrame.EMPTY} then, and the seven lines still have to come out.
     */
    @GameTest(batch = BATCH)
    public static void statsRunsBeforeTheFirstFrameIsPublished(GameTestHelper helper) {
        emptyRegistry(helper);
        // Exactly what a restart leaves behind: the sampler resets the published frame to EMPTY when it stops.
        helper.assertTrue(GregScopeTestHooks.simulateRestart(), "GregScope test hooks are disabled");
        helper.assertEquals(
            0L,
            GregScope.frame()
                .sequence(),
            "the frame must be the unpublished one here");

        // The command runs before anything else is asserted about the frame, so that a frame which cannot answer
        // fails the way it would fail an operator rather than in a precondition of this test.
        CommandSenders.Console console = CommandSenders.console();
        run(console, console.chat, "stats");
        helper.assertNotNull(
            GregScope.frame()
                .stats(),
            "the empty frame must carry stats");
        helper.assertEquals(7, console.chat.size(), "stats lines before the first frame: " + console.chat);
        helper.assertTrue(
            console.chat.has(GregScopeAssets.LANG_CMD_STATS_TIMING),
            "the timing line is missing: " + console.chat);
        Object[] frame = console.chat.args(GregScopeAssets.LANG_CMD_STATS_FRAME);
        helper.assertEquals(0L, number(frame[0]), "the frame sequence must be 0: " + console.chat);
        helper.assertEquals("never", String.valueOf(frame[1]), "the frame age must read never: " + console.chat);

        // And the next interval publishes one, so the run carries on exactly as before.
        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "the sampler did not run");
        run(console, console.chat, "stats");
        helper.assertTrue(
            number(console.chat.args(GregScopeAssets.LANG_CMD_STATS_FRAME)[0]) > 0L,
            "no frame was published after an interval: " + console.chat);
        helper.succeed();
    }

    // --- list ---

    /** §5/§11: {@code list} shows the viewer's own sensors and their team's, and nobody else's. */
    @GameTest(batch = BATCH)
    public static void listFilteredByAccess(GameTestHelper helper) {
        emptyRegistry(helper);
        try (TestTeams teams = new TestTeams()) {
            UUID aliceId = teams.player("cmdAlice");
            UUID mateId = teams.player("cmdMate");
            UUID strangerId = teams.player("cmdStranger");
            Team team = teams.create("gsCmdTeam", aliceId);
            team.addMember(mateId);

            UUID own = liveSensor(helper, MACHINE, "0b01", aliceId, "cmdAlice");
            UUID mates = liveSensor(helper, SECOND, "0b02", mateId, "cmdMate");
            UUID theirs = liveSensor(helper, THIRD, "0b03", strangerId, "cmdStranger");

            CommandSenders.Player alice = CommandSenders.player(helper, "cmdAlice", aliceId, -1);
            run(alice, alice.chat, "list");
            String seen = alice.chat.flatten();
            helper.assertEquals(1, alice.chat.count(GregScopeAssets.LANG_CMD_LIST_HEADER), "one header: " + seen);
            helper.assertEquals(2, alice.chat.count(GregScopeAssets.LANG_CMD_LIST_ROW), "rows: " + seen);
            helper.assertTrue(seen.contains(Labels.shortId(own)), "own sensor missing: " + seen);
            helper.assertTrue(seen.contains(Labels.shortId(mates)), "team mate's sensor missing: " + seen);
            helper.assertFalse(seen.contains(Labels.shortId(theirs)), "a stranger's sensor leaked: " + seen);

            CommandSenders.Player stranger = CommandSenders.player(helper, "cmdStranger", strangerId, -1);
            run(stranger, stranger.chat, "list");
            String strangerSees = stranger.chat.flatten();
            helper.assertEquals(1, stranger.chat.count(GregScopeAssets.LANG_CMD_LIST_ROW), "rows: " + strangerSees);
            helper.assertTrue(strangerSees.contains(Labels.shortId(theirs)), "own sensor missing: " + strangerSees);
            helper.assertFalse(strangerSees.contains(Labels.shortId(own)), "Alice's sensor leaked: " + strangerSees);

            // The console counts as op (§5) and sees all three; the filters still apply.
            CommandSenders.Console console = CommandSenders.console();
            run(console, console.chat, "list");
            helper.assertEquals(3, console.chat.count(GregScopeAssets.LANG_CMD_LIST_ROW), "" + console.chat);
            run(console, console.chat, "list", "tombstones");
            helper.assertTrue(
                console.chat.has(GregScopeAssets.LANG_CMD_LIST_EMPTY),
                "no tombstones expected: " + console.chat);
            run(console, console.chat, "list", "live", "2");
            helper.assertEquals(
                1L,
                number(console.chat.args(GregScopeAssets.LANG_CMD_LIST_HEADER)[1]),
                "page 2 of one page must clamp to 1: " + console.chat);

            // An unowned sensor (the way a FakePlayer attach leaves it) is visible to ops only.
            UUID unowned = liveSensor(helper, at(3, 0, 3), "0b04", null, null);
            run(alice, alice.chat, "list");
            helper.assertFalse(
                alice.chat.flatten()
                    .contains(Labels.shortId(unowned)),
                "an unowned sensor must be op-only: " + alice.chat);
            run(console, console.chat, "list");
            helper.assertEquals(4, console.chat.count(GregScopeAssets.LANG_CMD_LIST_ROW), "" + console.chat);
        }
        helper.succeed();
    }

    // --- info and prefix ambiguity ---

    /** §11: an ambiguous prefix lists up to five matches instead of guessing; a full id resolves. */
    @GameTest(batch = BATCH)
    public static void ambiguousPrefixListsMatches(GameTestHelper helper) {
        emptyRegistry(helper);
        UUID first = liveSensor(helper, MACHINE, "00ab", null, null);
        UUID second = liveSensor(helper, SECOND, "00ab", null, null);
        helper.assertNotEquals(first, second, "the two fixtures must differ");

        CommandSenders.Console console = CommandSenders.console();
        run(console, console.chat, "info", "00ab");
        helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_AMBIGUOUS), "" + console.chat);
        helper.assertEquals(2, console.chat.count(GregScopeAssets.LANG_CMD_AMBIGUOUS_ROW), "" + console.chat);
        helper.assertFalse(console.chat.has(GregScopeAssets.LANG_CMD_INFO_HEADER), "info answered anyway");

        run(console, console.chat, "info", first.toString());
        helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_INFO_HEADER), "" + console.chat);
        helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_INFO_WHERE), "" + console.chat);
        helper.assertEquals(2, console.chat.count(GregScopeAssets.LANG_CMD_INFO_WINDOW), "5 min and 24 h lines");
        helper.assertEquals(
            Labels.shortId(first),
            console.chat.args(GregScopeAssets.LANG_CMD_INFO_HEADER)[0],
            "" + console.chat);
        helper.assertEquals("machine", console.chat.args(GregScopeAssets.LANG_CMD_INFO_HEADER)[1], "kind label");

        run(console, console.chat, "info", "ffff0000");
        helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_NOT_FOUND), "" + console.chat);
        run(console, console.chat, "info", "00a");
        helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_PREFIX_SHORT), "" + console.chat);
        run(console, console.chat, "wobble");
        helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_UNKNOWN), "" + console.chat);
        helper.succeed();
    }

    // --- label ---

    /** §3.5/§5: a stranger may not rename; the owner may, once per cooldown; the console may always. */
    @GameTest(batch = BATCH)
    public static void labelDeniedForStranger(GameTestHelper helper) {
        emptyRegistry(helper);
        UUID ownerId = UUID.fromString("00c0ffee-0000-4000-8000-000000000002");
        UUID id = liveSensor(helper, MACHINE, "0c01", ownerId, "cmdLabelOwner");
        SensorEntry entry = entry(helper, id);

        // A stranger cannot even see the sensor (§5 canView), so the prefix does not resolve for them. With the
        // default permissions.renameRequiresOfficer=false, canRename and canView allow exactly the same people, so
        // this - not a "denied" - is what a stranger's rename looks like. The denial case has its own test.
        CommandSenders.Player stranger = CommandSenders.player(helper, "cmdLabelStranger");
        run(stranger, stranger.chat, "label", "0c01", "Not", "mine");
        helper.assertTrue(stranger.chat.has(GregScopeAssets.LANG_CMD_NOT_FOUND), "" + stranger.chat);
        helper.assertFalse(stranger.chat.has(GregScopeAssets.LANG_CMD_LABEL_SET), "" + stranger.chat);
        helper.assertEquals("", entry.label(), "a stranger changed the label");

        CommandSenders.Player owner = CommandSenders.player(helper, "cmdLabelOwner", ownerId, -1);
        run(owner, owner.chat, "label", "0c01", "North", "Line");
        helper.assertTrue(owner.chat.has(GregScopeAssets.LANG_CMD_LABEL_SET), "" + owner.chat);
        helper.assertEquals("North Line", entry.label(), "registry label");
        helper.assertEquals(
            "North Line",
            cover(helper, MACHINE).identity()
                .label(),
            "cover label (the source of truth)");

        // §3.5: a per-player cooldown, charged only for a write that happened.
        run(owner, owner.chat, "label", "0c01", "Again");
        helper.assertTrue(owner.chat.has(GregScopeAssets.LANG_CMD_LABEL_COOLDOWN), "" + owner.chat);
        helper.assertEquals("North Line", entry.label(), "the cooldown must not write");

        // The console has no player UUID, so the cooldown does not apply to it; empty text clears the label.
        CommandSenders.Console console = CommandSenders.console();
        run(console, console.chat, "label", "0c01");
        helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_LABEL_CLEARED), "" + console.chat);
        helper.assertEquals("", entry.label(), "cleared in the registry");
        helper.assertEquals(
            "",
            cover(helper, MACHINE).identity()
                .label(),
            "cleared on the cover");
        helper.succeed();
    }

    /**
     * §5: {@code permissions.renameRequiresOfficer=true} is the one configuration where {@code canRename} is
     * narrower than {@code canView}, so it is the one that produces a real "you are not allowed to do that" for a
     * rename: a plain team member sees the sensor in {@code list} and is refused by {@code label}.
     */
    @GameTest(batch = BATCH)
    public static void labelDeniedForNonOfficerWhenConfigured(GameTestHelper helper) {
        emptyRegistry(helper);
        helper.assertTrue(
            GregScopeTestHooks.overrideSettings(
                Settings.DEFAULTS.toBuilder()
                    .renameRequiresOfficer(true)
                    .build()),
            "GregScope test hooks are disabled");
        helper.afterTest(GregScopeTestHooks::clearSettingsOverride);
        try (TestTeams teams = new TestTeams()) {
            UUID ownerId = teams.player("cmdOfficerOwner");
            UUID memberId = teams.player("cmdPlainMember");
            Team team = teams.create("gsCmdOfficerTeam", ownerId);
            team.addMember(memberId);
            UUID id = liveSensor(helper, MACHINE, "0c02", ownerId, "cmdOfficerOwner");
            SensorEntry entry = entry(helper, id);

            CommandSenders.Player member = CommandSenders.player(helper, "cmdPlainMember", memberId, -1);
            run(member, member.chat, "list");
            helper.assertEquals(1, member.chat.count(GregScopeAssets.LANG_CMD_LIST_ROW), "a member may see it");
            run(member, member.chat, "label", "0c02", "Mine", "now");
            helper.assertTrue(member.chat.has(GregScopeAssets.LANG_CMD_DENIED), "" + member.chat);
            helper.assertEquals("", entry.label(), "a plain member renamed with officer-only renaming on");

            team.addOfficer(memberId);
            run(member, member.chat, "label", "0c02", "Mine", "now");
            helper.assertTrue(member.chat.has(GregScopeAssets.LANG_CMD_LABEL_SET), "" + member.chat);
            helper.assertEquals("Mine now", entry.label(), "an officer may rename");
        }
        helper.succeed();
    }

    // --- purge ---

    /** §11: {@code purge} is op only, even for a sensor the player owns and can see. */
    @GameTest(batch = BATCH)
    public static void purgeRequiresOp(GameTestHelper helper) {
        SensorRegistry registry = emptyRegistry(helper);
        CommandSenders.Player owner = CommandSenders.player(helper, "cmdPurgeOwner");
        UUID id = liveSensor(helper, MACHINE, "0d01", owner.getUniqueID(), "cmdPurgeOwner");

        run(owner, owner.chat, "list");
        helper.assertEquals(1, owner.chat.count(GregScopeAssets.LANG_CMD_LIST_ROW), "the owner can see it");
        run(owner, owner.chat, "purge", "0d01");
        helper.assertTrue(owner.chat.has(GregScopeAssets.LANG_CMD_DENIED), "" + owner.chat);
        helper.assertNotNull(
            registry.core()
                .entry(id),
            "a non-op purged a sensor");

        // opLevel defaults to 2, so a player who holds level 2 may purge.
        CommandSenders.Player op = CommandSenders.player(helper, "cmdPurgeOp", 2);
        run(op, op.chat, "purge", "0d01");
        helper.assertTrue(op.chat.has(GregScopeAssets.LANG_CMD_PURGE_ONE), "" + op.chat);
        helper.assertNull(
            registry.core()
                .entry(id),
            "the entry survived an op purge");

        // Bulk purges are op only too, and say so when there is nothing to remove.
        CommandSenders.Player other = CommandSenders.player(helper, "cmdPurgeOther");
        run(other, other.chat, "purge", "--tombstones");
        helper.assertTrue(other.chat.has(GregScopeAssets.LANG_CMD_DENIED), "" + other.chat);
        CommandSenders.Console console = CommandSenders.console();
        run(console, console.chat, "purge", "--stale");
        helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_PURGE_NONE), "" + console.chat);
        helper.succeed();
    }

    // --- no log line per call ---

    /**
     * §11: "No INFO log line per call." Every subcommand, including the two that change something, is answered
     * without a single INFO line from the {@code gregscope} logger. The positive control is an INFO line written
     * through the same logger, which the same capture must see, so the assertion cannot pass vacuously.
     *
     * <p>
     * Deliberately INFO only: the I/O thread may log a WARN or an ERROR of its own at any moment (a dropped task, a
     * failed write), which has nothing to do with the call that happened to be running.
     */
    @GameTest(batch = BATCH)
    public static void noLogLinePerCall(GameTestHelper helper) {
        emptyRegistry(helper);
        UUID id = liveSensor(helper, MACHINE, "0e01", null, null);
        CommandSenders.Console console = CommandSenders.console();
        String infoPrefix = "INFO [" + GregScope.MODID + "]";
        try (LogCapture log = LogCapture.attach("", GregScope.MODID)) {
            GregScope.LOG.info("GS-111 positive control: this INFO line must be captured");
            helper.assertEquals(1, countInfo(log.lines(), infoPrefix), "positive control: " + log.lines());
            log.clear();

            run(console, console.chat, "stats");
            run(console, console.chat, "list");
            run(console, console.chat, "info", "0e01");
            run(console, console.chat, "label", "0e01", "Quiet");
            run(console, console.chat, "nonsense");
            run(console, console.chat, "purge", "0e01");
            List<String> lines = log.lines();
            StringBuilder logged = new StringBuilder();
            for (String line : lines) {
                if (line.startsWith(infoPrefix)) {
                    logged.append(line)
                        .append('\n');
                }
            }
            helper.assertEquals("", logged.toString(), "a /gregscope call wrote an INFO line");
        }
        helper.assertNull(
            registry(helper).core()
                .entry(id),
            "the purge at the end of the quiet run did nothing");
        helper.succeed();
    }

    private static int countInfo(List<String> lines, String prefix) {
        int n = 0;
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    // --- label and the chunk (its own batch each) ---

    /** §3.5/§14: a label written by the command lives in the cover NBT and survives a real chunk reload. */
    @GameTest(batch = RELOAD_BATCH)
    public static void labelPersistsAcrossChunkReload(GameTestHelper helper) {
        emptyRegistry(helper);
        UUID id = liveSensor(helper, MACHINE, "0f01", null, null);
        SensorEntry entry = entry(helper, id);
        CommandSenders.Console console = CommandSenders.console();
        run(console, console.chat, "label", "0f01", "EBF", "North");
        helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_LABEL_SET), "" + console.chat);
        helper.assertEquals("EBF North", entry.label(), "registry label before the reload");
        ChunkReload chunks = ChunkReload.ofChunkAt(helper, MACHINE);

        helper.startSequence()
            .thenExecute("unload", chunks::unload)
            .thenIdle(1)
            .thenExecute("reload and heartbeat", () -> {
                chunks.reload();
                TileEntity tile = helper.assertTileEntityPresent(MACHINE);
                IGregTechTileEntity reloaded = helper
                    .assertInstanceOf(IGregTechTileEntity.class, tile, "reloaded tile");
                MachineSensorCover after = helper.assertInstanceOf(
                    MachineSensorCover.class,
                    reloaded.getCoverAtSide(COVERED),
                    "sensor cover after the reload");
                helper.assertEquals(
                    "EBF North",
                    after.identity()
                        .label(),
                    "the label was not written to the cover NBT");
                helper.assertTrue(GregScopeTestHooks.heartbeatNow(after), "test hooks are disabled");
                helper.assertEquals("EBF North", entry.label(), "registry label after the reload");
                run(console, console.chat, "info", "0f01");
                helper.assertTrue(
                    console.chat.flatten()
                        .contains("EBF North"),
                    "info does not show the label: " + console.chat);
            })
            .thenSucceed();
    }

    /** §3.5/§14: a label write refuses an unloaded sensor, and the refusal does not load its chunk. */
    @GameTest(batch = UNLOAD_BATCH)
    public static void labelRefusedWhenUnloadedAndChunkStaysUnloaded(GameTestHelper helper) {
        emptyRegistry(helper);
        UUID id = liveSensor(helper, MACHINE, "0f02", null, null);
        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.LIVE, entry.state(), "state before the unload");
        ChunkReload chunks = ChunkReload.ofChunkAt(helper, MACHINE);
        CommandSenders.Console console = CommandSenders.console();

        helper.startSequence()
            .thenExecute("unload", chunks::unload)
            .thenIdle(1)
            .thenExecute("label refused, chunk stays unloaded", () -> {
                helper.assertEquals(SensorState.UNLOADED, entry.state(), "state after the unload");
                run(console, console.chat, "label", "0f02", "Should", "not", "stick");
                helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_LABEL_NOT_LOADED), "" + console.chat);
                helper.assertEquals("", entry.label(), "the label was written for an unloaded sensor");
                helper.assertFalse(chunks.anyLoaded(), "the label write loaded the chunk");
                // The read-only subcommands answer from RAM and must not load it either.
                run(console, console.chat, "info", "0f02");
                helper.assertTrue(console.chat.has(GregScopeAssets.LANG_CMD_INFO_HEADER), "" + console.chat);
                run(console, console.chat, "list", "unloaded");
                helper.assertEquals(1, console.chat.count(GregScopeAssets.LANG_CMD_LIST_ROW), "" + console.chat);
                helper.assertFalse(chunks.anyLoaded(), "a read-only subcommand loaded the chunk");
            })
            .thenExecute("reload", chunks::reload)
            .thenSucceed();
    }

    // --- helpers ---

    private static SensorRegistry registry(GameTestHelper helper) {
        SensorRegistry registry = GregScope.registry();
        helper.assertNotNull(registry, "GregScope has no registry; is the server running?");
        return registry;
    }

    /**
     * Empties the registry so a list or a count is exact. Horizon-QA keeps every finished cell loaded and its covers
     * keep heartbeating, but no server tick passes inside a synchronous test body, so nothing registers again while
     * the test runs (the same reason {@code SensorLifecycleTests.globalCapGivesOverCap} can do this).
     */
    private static SensorRegistry emptyRegistry(GameTestHelper helper) {
        SensorRegistry registry = registry(helper);
        helper.assertTrue(GregScopeTestHooks.purgeAllNow() >= 0, "the registry could not be emptied");
        helper.assertEquals(
            0,
            registry.core()
                .size(),
            "the registry is not empty");
        return registry;
    }

    private static SensorEntry entry(GameTestHelper helper, UUID id) {
        SensorEntry entry = registry(helper).core()
            .entry(id);
        helper.assertNotNull(entry, "no registry entry for sensor " + id);
        return entry;
    }

    private static MachineSensorCover cover(GameTestHelper helper, TestPos pos) {
        TileEntity tile = helper.assertTileEntityPresent(pos);
        IGregTechTileEntity holder = helper.assertInstanceOf(IGregTechTileEntity.class, tile, "GT machine");
        return helper
            .assertInstanceOf(MachineSensorCover.class, holder.getCoverAtSide(COVERED), "sensor cover at " + pos);
    }

    /**
     * A machine with a sensor cover whose UUID starts with {@code idPrefix} and whose owner is {@code owner},
     * registered LIVE by one heartbeat.
     */
    private static UUID liveSensor(GameTestHelper helper, TestPos pos, String idPrefix, UUID owner, String ownerName) {
        UUID id = UUID.fromString(idPrefix + pad(pos) + "-0000-4000-8000-" + tail(pos));
        SensorIdentity identity = new SensorIdentity(id, "", owner, ownerName, 1_600_000_000L);
        IGregTechTileEntity holder = SensorFixtures.placeMachineWithSensorNbt(helper, pos, COVERED, identity);
        MachineSensorCover attached = helper
            .assertInstanceOf(MachineSensorCover.class, holder.getCoverAtSide(COVERED), "sensor cover at " + pos);
        helper.assertEquals(
            id,
            attached.identity()
                .id(),
            "the placed cover carries another identity");
        helper.assertTrue(GregScopeTestHooks.heartbeatNow(attached), "GregScope test hooks are disabled");
        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.LIVE, entry.state(), "the fixture sensor did not register");
        helper.assertEquals(owner, entry.owner(), "owner");
        return id;
    }

    /** Four hex digits that make two fixtures at different positions differ. */
    private static String pad(TestPos pos) {
        return String.format("%01x%01x%01x%01x", pos.x() & 15, pos.y() & 15, pos.z() & 15, 1);
    }

    private static String tail(TestPos pos) {
        return String.format("%012x", (pos.x() * 100 + pos.z()) & 0xffff);
    }

    private static long number(Object arg) {
        return ((Number) arg).longValue();
    }

    private static void run(ICommandSender sender, CommandSenders.ChatLog log, String... args) {
        log.clear();
        COMMAND.processCommand(sender, args);
    }
}
