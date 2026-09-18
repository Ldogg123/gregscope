package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.cleanroommc.modularui.api.RecipeViewerSettings;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.factory.TileEntityGuiFactory;
import com.cleanroommc.modularui.network.NetworkUtils;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.GenericListSyncHandler;
import com.cleanroommc.modularui.value.sync.GenericSyncValue;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.ModularSyncManager;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.value.sync.SyncHandler;
import com.cleanroommc.modularui.widget.WidgetTree;
import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.AfterBatch;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.relauncher.Side;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.hub.HubCodecs;
import io.github.ldogg123.gregscope.hub.HubDetail;
import io.github.ldogg123.gregscope.hub.HubHeader;
import io.github.ldogg123.gregscope.hub.HubPacketIo;
import io.github.ldogg123.gregscope.hub.HubPanel;
import io.github.ldogg123.gregscope.hub.HubRow;
import io.github.ldogg123.gregscope.hub.HubSession;
import io.github.ldogg123.gregscope.hub.HubViewLifecycle;
import io.github.ldogg123.gregscope.hub.HubViewModel;
import io.github.ldogg123.gregscope.hub.TelemetryHubs;
import io.github.ldogg123.gregscope.hub.TileTelemetryHub;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistry;
import io.github.ldogg123.gregscope.registry.SensorRegistryCore;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sensor.Labels;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;
import io.netty.buffer.Unpooled;

/**
 * GS-114 (design-v0.2 sections 9.2, 9.3, 5 and 14): the Telemetry Hub's ModularUI2 panel on a real dedicated server.
 *
 * <p>
 * <b>Why buildUI is called directly.</b> Erratum E9: {@code GuiManager.open} returns immediately for a
 * {@code FakePlayer}, and Horizon-QA has no player with a network connection, so no test can open the GUI the way a
 * player does. The design therefore says the tests call {@code buildUI} and exercise the named handlers, which is
 * exactly what {@link #open} does: it repeats {@code GuiManager.open}'s own sequence (create the panel, collect the
 * widget sync values, construct the container, open the sync manager) and leaves out only the packet. Every value a
 * test then writes arrives through {@code readOnServer}, which is the real server-side entry point of a C2S packet -
 * so the clamps below are the clamps a hostile client meets.
 *
 * <p>
 * <b>Batch name.</b> {@code gregscope.surface.hubgui} sorts after every existing GregScope batch, so no older test's
 * cell moves (the GS-109/GS-110 rule).
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "gregscope", "gtnhlib" })
public class HubGuiServerTests {

    private static final String BATCH = "gregscope.surface.hubgui";

    private static final TestPos HUB = at(1, 0, 1);
    private static final TestPos MACHINE = at(3, 0, 1);
    private static final TestPos SECOND = at(1, 0, 3);
    private static final TestPos THIRD = at(3, 0, 3);
    /** Far-away coordinates for registry-only filler sensors: no chunk there is loaded, and none is wanted. */
    private static final int FILLER_X = 6000;
    private static final int FILLER_Z = 6000;
    private static final ForgeDirection COVERED = ForgeDirection.UP;

    private static final int SYNC_VALUE = 0;

    /**
     * Every test here is synchronous: it drives {@code buildUI} and the named handlers inside one server tick and
     * observes none, which the Horizon-QA report confirms (ticks=0 for all of them). The default 100-tick timeout
     * would therefore only inflate the suite's worst-case budget, so they ask for a small one with margin.
     */
    private static final int SYNCHRONOUS = 20;

    /**
     * Characters a {@link HubPanel#WIDTH}-wide panel fits on one default-font line. Minecraft's font advances 6 px
     * for most ASCII, and the panel loses 6 px of padding on each side, so 248 / 6 rounds down to 41.
     */
    private static final int DETAIL_LINE_CHARS = 41;

    private HubGuiServerTests() {}

    @AfterBatch(BATCH)
    public static void cleanUpAfterHubGui() {
        SensorCleanup.detachAllAndPurge();
        TelemetryHubs.views()
            .clear();
    }

    // --- buildUI on a dedicated server (section 14 acceptance criterion 1) ---

    /**
     * {@code buildUI} runs on a dedicated server and registers exactly the seven handlers section 9.3 names, with the
     * types and the C2S rights that table gives them. It also checks the other half of "client-only code never loads
     * on a server": FML's {@code SideTransformer} has removed {@code createScreen} from the class the server loaded.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void buildUiOnDedicatedServer(GameTestHelper helper) {
        helper.assertTrue(
            FMLCommonHandler.instance()
                .getSide() == Side.SERVER,
            "not a dedicated server side");
        Ui ui = open(helper, "hubGuiBuild");

        helper.assertNotNull(ui.panel, "buildUI returned no panel");
        helper.assertEquals(HubPanel.PANEL_NAME, ui.panel.getName(), "panel name");

        // Section 9.3 pins these names on both sides of the wire; a rename is a protocol break.
        helper.assertEquals("gs_page", HubPanel.SYNC_PAGE, "gs_page");
        helper.assertEquals("gs_filter", HubPanel.SYNC_FILTER, "gs_filter");
        helper.assertEquals("gs_select", HubPanel.SYNC_SELECT, "gs_select");
        helper.assertEquals("gs_label", HubPanel.SYNC_LABEL, "gs_label");
        helper.assertEquals("gs_rows", HubPanel.SYNC_ROWS, "gs_rows");
        helper.assertEquals("gs_header", HubPanel.SYNC_HEADER, "gs_header");
        helper.assertEquals("gs_detail", HubPanel.SYNC_DETAIL, "gs_detail");

        IntSyncValue page = ui.intHandler(helper, HubPanel.SYNC_PAGE);
        IntSyncValue filter = ui.intHandler(helper, HubPanel.SYNC_FILTER);
        IntSyncValue select = ui.intHandler(helper, HubPanel.SYNC_SELECT);
        StringSyncValue label = ui.stringHandler(helper, HubPanel.SYNC_LABEL);
        SyncHandler<?> rows = helper.assertInstanceOf(
            GenericListSyncHandler.class,
            ui.handler(helper, HubPanel.SYNC_ROWS),
            "gs_rows is not a GenericListSyncHandler");
        GenericSyncValue<?, ?> header = ui.genericHandler(helper, HubPanel.SYNC_HEADER);
        GenericSyncValue<?, ?> detail = ui.genericHandler(helper, HubPanel.SYNC_DETAIL);

        // Section 9.3's C2S column, exactly: the four inputs accept a client packet, the three outputs do not.
        helper.assertTrue(page.isAllowC2S(), "gs_page does not accept C2S");
        helper.assertTrue(filter.isAllowC2S(), "gs_filter does not accept C2S");
        helper.assertTrue(select.isAllowC2S(), "gs_select does not accept C2S");
        helper.assertTrue(label.isAllowC2S(), "gs_label does not accept C2S");
        helper.assertFalse(rows.isAllowC2S(), "gs_rows accepts C2S");
        helper.assertFalse(header.isAllowC2S(), "gs_header accepts C2S");
        helper.assertFalse(detail.isAllowC2S(), "gs_detail accepts C2S");

        // The three outputs really serialize through the section 2 seam on a real PacketBuffer.
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try {
            header.write(buffer);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        HubHeader decoded = HubCodecs.decodeHeader(HubPacketIo.source(buffer));
        helper.assertEquals(
            ui.header(helper)
                .total(),
            decoded.total(),
            "gs_header did not survive its own wire format");
        helper.assertEquals(0, buffer.readableBytes(), "gs_header left bytes in the buffer");

        // createScreen is @SideOnly(CLIENT) (design-v0.2 section 1.4), so the server's class does not have it.
        for (Method method : TileTelemetryHub.class.getDeclaredMethods()) {
            helper.assertNotEquals(
                "createScreen",
                method.getName(),
                "createScreen survived on the dedicated server; is it still @SideOnly(CLIENT)?");
        }
        helper.succeed();
    }

    // --- the four C2S setters (section 9.3, section 14 acceptance criterion 3) ---

    /**
     * Section 9.3 {@code gs_page}: -5 becomes 0, an in-range value is stored as it is, and 999 becomes the last page.
     *
     * <p>
     * The fixture is deliberately more than one page long. With two sensors every clamp collapses to 0, which is also
     * {@code gs_page}'s initial value, so the whole test would pass against a setter that ignored its argument; the
     * fifteen extra registry entries make {@code pages} 3 and every assertion below discriminating.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void pageSetterClamps(GameTestHelper helper) {
        UUID owner = teamlessOwner("hubGuiPage");
        emptyRegistry(helper);
        liveSensor(helper, MACHINE, "0d01", owner, "hubGuiPage");
        liveSensor(helper, SECOND, "0d02", owner, "hubGuiPage");
        scopeFiller(helper, owner, "hubGuiPage", 15);
        publish(helper);
        Ui ui = open(helper, "hubGuiPage", owner);

        helper.assertEquals(
            17,
            ui.header(helper)
                .total(),
            "the Hub sees every sensor in its owner's scope");
        int pages = ui.header(helper)
            .pages();
        helper.assertEquals(3, pages, "seventeen sensors are three pages of eight");

        sendInt(ui.intHandler(helper, HubPanel.SYNC_PAGE), -5);
        helper.assertEquals(0, ui.page(helper), "page -5 was not clamped to 0");
        sendInt(ui.intHandler(helper, HubPanel.SYNC_PAGE), 1);
        helper.assertEquals(1, ui.page(helper), "an in-range page was not stored");
        sendInt(ui.intHandler(helper, HubPanel.SYNC_PAGE), 999);
        helper.assertEquals(pages - 1, ui.page(helper), "page 999 was not clamped to the last page");
        helper.assertEquals(
            pages - 1,
            ui.header(helper)
                .page(),
            "the header disagrees with gs_page");
        helper.assertEquals(
            HubViewModel.ROWS_PER_PAGE,
            ui.rows(helper)
                .size(),
            "the last page is not padded to 8 rows");
        helper
            .assertEquals(1, ids(ui.rows(helper)).size(), "the third page of seventeen rows holds exactly one sensor");
        helper.succeed();
    }

    /** Section 9.3 {@code gs_filter}: a value outside {0,1} becomes All. */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void filterSetterClamps(GameTestHelper helper) {
        UUID owner = teamlessOwner("hubGuiFilter");
        emptyRegistry(helper);
        liveSensor(helper, MACHINE, "0d11", owner, "hubGuiFilter");
        publish(helper);
        Ui ui = open(helper, "hubGuiFilter", owner);

        sendInt(ui.intHandler(helper, HubPanel.SYNC_FILTER), HubViewModel.FILTER_PROBLEMS);
        helper.assertEquals(HubViewModel.FILTER_PROBLEMS, ui.filter(helper), "gs_filter 1 was not accepted");
        sendInt(ui.intHandler(helper, HubPanel.SYNC_FILTER), 7);
        helper.assertEquals(HubViewModel.FILTER_ALL, ui.filter(helper), "gs_filter 7 was not clamped to All");
        sendInt(ui.intHandler(helper, HubPanel.SYNC_FILTER), -3);
        helper.assertEquals(HubViewModel.FILTER_ALL, ui.filter(helper), "gs_filter -3 was not clamped to All");
        helper.succeed();
    }

    /** Section 9.3 {@code gs_select}: a row index resolves to that row's UUID; anything else selects nothing. */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void selectionResolvesUuid(GameTestHelper helper) {
        UUID owner = teamlessOwner("hubGuiSelect");
        emptyRegistry(helper);
        UUID first = liveSensor(helper, MACHINE, "0d21", owner, "hubGuiSelect");
        publish(helper);
        Ui ui = open(helper, "hubGuiSelect", owner);

        helper.assertEquals(
            1,
            ui.header(helper)
                .total(),
            "the Hub sees the sensor");
        helper.assertEquals(
            first,
            ui.rows(helper)
                .get(0)
                .id(),
            "the only row is the sensor");
        helper.assertFalse(
            ui.detail(helper)
                .isPresent(),
            "something was selected before any click");

        sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 0);
        helper.assertEquals(
            first,
            ui.detail(helper)
                .id(),
            "row 0 did not resolve to the sensor UUID");
        helper.assertEquals(0, ui.selectedRow(helper), "gs_select does not read back the selected row");

        // Row 7 is a padding row here, and an index outside 0..7 is not a row at all: both select nothing.
        sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 7);
        helper.assertFalse(
            ui.detail(helper)
                .isPresent(),
            "an empty row was selected");
        sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 0);
        helper.assertEquals(
            first,
            ui.detail(helper)
                .id(),
            "row 0 did not resolve again");
        sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 4096);
        helper.assertFalse(
            ui.detail(helper)
                .isPresent(),
            "an out-of-range row index selected something");
        helper.assertEquals(
            HubViewModel.ROWS_PER_PAGE,
            ui.rows(helper)
                .size(),
            "the page is not padded to 8 rows");
        helper.succeed();
    }

    /** Section 9.3 {@code gs_label}: the text is sanitized and written to the <b>cover</b>, which owns the label. */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void labelSetterSanitizesAndWritesCover(GameTestHelper helper) {
        UUID owner = teamlessOwner("hubGuiLabel");
        emptyRegistry(helper);
        UUID id = liveSensor(helper, MACHINE, "0d31", owner, "hubGuiLabel");
        publish(helper);
        Ui ui = open(helper, "hubGuiLabel", owner);
        sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 0);
        helper.assertTrue(
            ui.detail(helper)
                .canEdit(),
            "the owner may not edit the label");

        String typed = "  EBF " + ((char) 167) + "cNorth  ";
        sendString(ui.stringHandler(helper, HubPanel.SYNC_LABEL), typed);
        String expected = Labels.sanitize(typed);
        helper.assertEquals(
            expected,
            cover(helper, MACHINE).identity()
                .label(),
            "the cover NBT kept the old label");
        helper.assertEquals(expected, entry(helper, id).label(), "the registry entry kept the old label");
        helper.assertEquals(expected, ui.label(helper), "gs_label does not read back the sanitized label");
        helper.assertFalse(expected.indexOf((char) 167) >= 0, "the sanitizer left a formatting code in " + expected);
        helper.succeed();
    }

    /**
     * Design-v0.2 section 5 at the <b>real</b> entry point. ModularUI2 registers {@code OpenGuiPacket} as a
     * client-to-server packet and {@code TileEntityGuiFactory.readGuiData} builds its {@code PosGuiData} from three
     * client-chosen varints, so a modified client can reach {@code buildUI} for any Hub without ever going through
     * {@code BlockTelemetryHub.onBlockActivated}. A stranger therefore gets a panel - ModularUI2 needs one - that
     * carries nothing: no owner, no count, no row, no detail, no reserved view, and a container that refuses the very
     * first interaction check. The {@code gs_label} write such a client could still send reaches neither the cover nor
     * the registry.
     *
     * <p>
     * This is the ticket's negative control: it is the test that fails if the gate moves back out of {@code buildUI}.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void strangerSeesNothingAndWritesNothing(GameTestHelper helper) {
        try (TestTeams teams = new TestTeams()) {
            UUID ownerId = teams.player("hubGuiLabelOwner");
            UUID strangerId = teams.player("hubGuiLabelStranger");
            helper.afterTest(
                () -> TelemetryHubs.views()
                    .closed(strangerId));
            emptyRegistry(helper);
            UUID id = liveSensor(helper, MACHINE, "0d41", ownerId, "hubGuiLabelOwner");
            publish(helper);

            // The Hub belongs to the sensor owner, so the sensor is in its scope; the viewer is nobody.
            Ui ui = open(helper, ownerId, "hubGuiLabelOwner", strangerId, "hubGuiLabelStranger", -1);
            // Stand the viewer on the Hub, so the only thing canInteractWith can still be refusing is the access.
            TestPos abs = helper.absolute(HUB);
            ui.viewerEntity.setPosition(abs.x() + 0.5D, abs.y(), abs.z() + 0.5D);

            helper.assertEquals(
                0,
                ui.header(helper)
                    .total(),
                "a stranger was told how many sensors the Hub sees");
            helper.assertTrue(
                ui.header(helper)
                    .owner() == null,
                "a stranger was told who owns the Hub");
            helper.assertEquals(0, ids(ui.rows(helper)).size(), "a stranger was sent rows: " + ids(ui.rows(helper)));
            helper.assertFalse(
                ui.detail(helper)
                    .isPresent(),
                "a stranger was sent a detail");
            helper.assertFalse(
                ui.container.canInteractWith(ui.viewerEntity),
                "the refused container did not close on the first interaction check");

            // The panel still registers the four C2S handlers, and all four refuse.
            ui.msm.onOpen();
            helper.assertFalse(
                TelemetryHubs.views()
                    .isOpen(strangerId),
                "a refused viewer reserved a slot of the section 9.1 cap");
            sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 0);
            helper.assertEquals(HubSession.NO_ROW, ui.selectedRow(helper), "a stranger selected a row");
            sendInt(ui.intHandler(helper, HubPanel.SYNC_PAGE), 3);
            helper.assertEquals(0, ui.page(helper), "a stranger paged");
            sendInt(ui.intHandler(helper, HubPanel.SYNC_FILTER), HubViewModel.FILTER_PROBLEMS);
            helper.assertEquals(HubViewModel.FILTER_ALL, ui.filter(helper), "a stranger set the filter");
            sendString(ui.stringHandler(helper, HubPanel.SYNC_LABEL), "stolen");
            helper.assertEquals(
                "",
                cover(helper, MACHINE).identity()
                    .label(),
                "a stranger wrote the cover label");
            helper.assertEquals("", entry(helper, id).label(), "a stranger wrote the registry label");
            helper.assertEquals("", ui.label(helper), "the refused value was not reset");
        }
        helper.succeed();
    }

    /**
     * Design-v0.2 sections 5 and 9.3: a viewer who may <b>open</b> the Hub but may not rename writes nothing. With
     * {@code permissions.renameRequiresOfficer=true} a plain team member is exactly that viewer - they see the Hub
     * owner's rows and {@code canEdit} is false. This is what pins the permission check inside
     * {@code HubSession.setLabel}, which the {@code buildUI} gate sits in front of but does not replace.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void labelSetterRejectsAViewerWhoMayNotRename(GameTestHelper helper) {
        helper.assertTrue(
            GregScopeTestHooks.overrideSettings(
                Settings.DEFAULTS.toBuilder()
                    .renameRequiresOfficer(true)
                    .build()),
            "GregScope test hooks are disabled");
        helper.afterTest(GregScopeTestHooks::clearSettingsOverride);
        try (TestTeams teams = new TestTeams()) {
            UUID teamOwner = teams.player("hubGuiOfficerOwner");
            UUID member = teams.player("hubGuiOfficerMember");
            Team team = teams.create("gsHubGuiOfficer", teamOwner);
            team.addMember(member);

            emptyRegistry(helper);
            UUID id = liveSensor(helper, MACHINE, "0da1", teamOwner, "hubGuiOfficerOwner");
            publish(helper);
            Ui ui = open(helper, teamOwner, "hubGuiOfficerOwner", member, "hubGuiOfficerMember", -1);

            helper.assertEquals(
                1,
                ui.header(helper)
                    .total(),
                "a team mate cannot see the Hub owner's sensor");
            sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 0);
            helper.assertEquals(
                id,
                ui.detail(helper)
                    .id(),
                "row 0 did not resolve to the sensor");
            helper.assertFalse(
                ui.detail(helper)
                    .canEdit(),
                "a plain member was told they may rename with renameRequiresOfficer=true");

            sendString(ui.stringHandler(helper, HubPanel.SYNC_LABEL), "stolen");
            helper.assertEquals(
                "",
                cover(helper, MACHINE).identity()
                    .label(),
                "a non-officer wrote the cover label");
            helper.assertEquals("", entry(helper, id).label(), "a non-officer wrote the registry label");
            helper.assertEquals("", ui.label(helper), "the refused value was not reset");
        }
        // Synchronous restore: a batch starts its tests one after another, but afterTest hooks run later, and a
        // lingering override would follow this test into the next one's fixture.
        GregScopeTestHooks.clearSettingsOverride();
        helper.succeed();
    }

    /** Section 9.3 {@code gs_label}: longer than {@code Labels.MAX_INPUT_UNITS} before sanitizing is refused. */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void labelSetterRejectsOversize(GameTestHelper helper) {
        UUID owner = teamlessOwner("hubGuiOversize");
        emptyRegistry(helper);
        UUID id = liveSensor(helper, MACHINE, "0d51", owner, "hubGuiOversize");
        publish(helper);
        Ui ui = open(helper, "hubGuiOversize", owner);
        sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 0);

        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i <= Labels.MAX_INPUT_UNITS; i++) {
            tooLong.append('x');
        }
        helper.assertTrue(tooLong.length() > Labels.MAX_INPUT_UNITS, "the fixture is not oversize");
        sendString(ui.stringHandler(helper, HubPanel.SYNC_LABEL), tooLong.toString());
        helper.assertEquals(
            "",
            cover(helper, MACHINE).identity()
                .label(),
            "an oversize label reached the cover");
        helper.assertEquals("", entry(helper, id).label(), "an oversize label reached the registry");

        // Exactly at the cap it is accepted, so the refusal above is about the length and nothing else.
        String atCap = tooLong.substring(0, Labels.MAX_INPUT_UNITS);
        sendString(ui.stringHandler(helper, HubPanel.SYNC_LABEL), atCap);
        helper.assertEquals(
            Labels.sanitize(atCap),
            cover(helper, MACHINE).identity()
                .label(),
            "a label at the cap was refused");
        helper.succeed();
    }

    /** Section 3.5 and section 9.3: a second write inside {@code hub.renameCooldownSeconds} is refused. */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void labelSetterRespectsCooldown(GameTestHelper helper) {
        helper.assertTrue(
            GregScopeTestHooks.overrideSettings(
                Settings.DEFAULTS.toBuilder()
                    .renameCooldownSeconds(300)
                    .build()),
            "GregScope test hooks are disabled");
        helper.afterTest(GregScopeTestHooks::clearSettingsOverride);
        UUID owner = teamlessOwner("hubGuiCooldown");
        emptyRegistry(helper);
        liveSensor(helper, MACHINE, "0d61", owner, "hubGuiCooldown");
        publish(helper);
        Ui ui = open(helper, "hubGuiCooldown", owner);
        sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 0);

        sendString(ui.stringHandler(helper, HubPanel.SYNC_LABEL), "first");
        helper.assertEquals(
            "first",
            cover(helper, MACHINE).identity()
                .label(),
            "the first write was refused");
        sendString(ui.stringHandler(helper, HubPanel.SYNC_LABEL), "second");
        helper.assertEquals(
            "first",
            cover(helper, MACHINE).identity()
                .label(),
            "the cooldown did not refuse the second write");
        helper.assertEquals("first", ui.label(helper), "the refused value was not reset");
        helper.succeed();
    }

    // --- scope and the rebuild throttle ---

    /** Section 5: the rows are the <b>Hub owner's</b> scope, whoever is looking; an outsider's sensor is not in it. */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void rowsScopedToHubOwnerTeam(GameTestHelper helper) {
        try (TestTeams teams = new TestTeams()) {
            UUID hubOwner = teams.player("hubGuiScopeOwner");
            UUID mate = teams.player("hubGuiScopeMate");
            UUID outsider = teams.player("hubGuiScopeOutsider");
            Team team = teams.create("gsHubGuiTeam", hubOwner);
            team.addMember(mate);

            emptyRegistry(helper);
            UUID own = liveSensor(helper, MACHINE, "0d71", hubOwner, "hubGuiScopeOwner");
            UUID mates = liveSensor(helper, SECOND, "0d72", mate, "hubGuiScopeMate");
            UUID theirs = liveSensor(helper, THIRD, "0d73", outsider, "hubGuiScopeOutsider");
            publish(helper);

            // An operator opens the Hub: section 5 says an op gains no extra visibility, so the scope is the owner's.
            Ui ui = open(helper, hubOwner, "hubGuiScopeOwner", outsider, "hubGuiScopeOutsider", 4);
            helper.assertEquals(
                2,
                ui.header(helper)
                    .total(),
                "the Hub shows something other than its owner's team");
            List<UUID> shown = ids(ui.rows(helper));
            helper.assertTrue(shown.contains(own), "the Hub owner's own sensor is missing: " + shown);
            helper.assertTrue(shown.contains(mates), "the team mate's sensor is missing: " + shown);
            helper.assertFalse(shown.contains(theirs), "an outsider's sensor is in the Hub: " + shown);
        }
        helper.succeed();
    }

    /**
     * Section 9.3's rebuild throttle, seen from the outside: while the frame sequence and the session inputs are
     * unchanged, the three getters hand back the very same objects. That is what stops ModularUI2 from sending three
     * DTOs per tick, because it compares a synced value with {@code Objects.equals}.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void gettersStableWithinSequence(GameTestHelper helper) {
        UUID owner = teamlessOwner("hubGuiStable");
        emptyRegistry(helper);
        liveSensor(helper, MACHINE, "0d81", owner, "hubGuiStable");
        publish(helper);
        Ui ui = open(helper, "hubGuiStable", owner);

        long sequence = GregScope.frame()
            .sequence();
        HubHeader header = ui.header(helper);
        List<HubRow> rows = ui.rows(helper);
        HubDetail detail = ui.detail(helper);
        for (int i = 0; i < 5; i++) {
            helper.assertSame(header, ui.header(helper), "gs_header rebuilt without a new frame");
            helper.assertSame(detail, ui.detail(helper), "gs_detail rebuilt without a new frame");
            // ModularUI2's list handler hands out a fresh unmodifiable wrapper every read, so the identity that
            // matters - and that its equals check compares - is the row objects inside it.
            List<HubRow> again = ui.rows(helper);
            helper.assertEquals(rows.size(), again.size(), "gs_rows changed length without a new frame");
            for (int row = 0; row < rows.size(); row++) {
                helper.assertSame(rows.get(row), again.get(row), "gs_rows row " + row + " rebuilt");
            }
        }
        helper.assertEquals(
            sequence,
            GregScope.frame()
                .sequence(),
            "reading the getters published a frame");

        // A new frame is what makes them change, so the throttle is not simply never rebuilding.
        publish(helper);
        helper.assertNotEquals(
            sequence,
            GregScope.frame()
                .sequence(),
            "the sampler published no new frame");
        helper.assertNotSame(header, ui.header(helper), "a new frame did not rebuild gs_header");

        // And the Hub column of the GS-113 carry-over reads the live counter rather than a constant.
        helper.assertEquals(
            (int) GregScope.history()
                .sensorsAbandoned(),
            ui.header(helper)
                .sensorsAbandoned(),
            "the header does not carry HistoryPersistence.sensorsAbandoned()");
        helper.succeed();
    }

    // --- the open-view register (the GS-113 carry-over) ---

    /**
     * Section 9.1's view register is driven by the panel's own lifecycle: a view is reserved when ModularUI2 opens the
     * sync manager and released when it disposes of it. Nothing reserves a slot before that, which is why the refused
     * right-clicks in {@code HubBlockTests} leave the register empty.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void viewRegisteredWhileThePanelIsOpen(GameTestHelper helper) {
        TelemetryHubs.views()
            .clear();
        helper.afterTest(
            () -> TelemetryHubs.views()
                .clear());
        UUID owner = teamlessOwner("hubGuiViews");
        emptyRegistry(helper);
        publish(helper);
        Ui ui = open(helper, "hubGuiViews", owner);

        helper.assertEquals(
            0,
            TelemetryHubs.views()
                .size(),
            "building the panel already reserved a view");
        ui.msm.onOpen();
        helper.assertTrue(
            TelemetryHubs.views()
                .isOpen(ui.viewer),
            "opening the panel did not reserve a view");
        helper.assertEquals(
            1,
            TelemetryHubs.views()
                .size(),
            "open views after one open");

        ui.msm.onClose();
        ui.msm.dispose();
        helper.assertFalse(
            TelemetryHubs.views()
                .isOpen(ui.viewer),
            "closing the panel did not release the view");
        helper.assertEquals(
            0,
            TelemetryHubs.views()
                .size(),
            "open views after the close");
        helper.succeed();
    }

    // --- the open-view cap and the view register at the real entry point ---

    /**
     * Design-v0.2 section 9.1, at the real entry point: {@code buildUI} refuses when the open-view cap is full, so a
     * client that sends its own {@code OpenGuiPacket} past the right-click can neither reserve a slot nor read a row.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void buildUiRefusesWhenTheViewCapIsFull(GameTestHelper helper) {
        helper.assertTrue(
            GregScopeTestHooks.overrideSettings(
                Settings.DEFAULTS.toBuilder()
                    .maxOpenHubViews(1)
                    .build()),
            "GregScope test hooks are disabled");
        helper.afterTest(GregScopeTestHooks::clearSettingsOverride);
        UUID owner = teamlessOwner("hubGuiCap");
        UUID other = teamlessOwner("hubGuiCapOther");
        TelemetryHubs.views()
            .clear();
        helper.afterTest(
            () -> TelemetryHubs.views()
                .clear());
        TelemetryHubs.views()
            .opened(other);

        emptyRegistry(helper);
        liveSensor(helper, MACHINE, "0db1", owner, "hubGuiCap");
        publish(helper);
        Ui refused = open(helper, HUB, owner, "hubGuiCap", owner, "hubGuiCap", -1);
        helper.assertEquals(
            0,
            refused.header(helper)
                .total(),
            "a viewer past the cap was sent the Hub's contents");
        helper.assertEquals(0, ids(refused.rows(helper)).size(), "a viewer past the cap was sent rows");
        refused.msm.onOpen();
        helper.assertFalse(
            TelemetryHubs.views()
                .isOpen(owner),
            "a viewer past the cap reserved a slot");
        helper.assertEquals(
            1,
            TelemetryHubs.views()
                .size(),
            "the refused open changed the view register");

        // With the slot free the same viewer is let in, so the refusal was about the cap and nothing else.
        TelemetryHubs.views()
            .closed(other);
        Ui allowed = open(helper, THIRD, owner, "hubGuiCap", owner, "hubGuiCap", -1);
        helper.assertEquals(
            1,
            allowed.header(helper)
                .total(),
            "the same viewer is still refused with the cap free");

        // Synchronous restore: afterTest hooks run later, and neither the cap nor a held slot may follow this test.
        TelemetryHubs.views()
            .clear();
        GregScopeTestHooks.clearSettingsOverride();
        helper.succeed();
    }

    /**
     * Design-v0.2 section 9.1: a player who leaves releases their slot. ModularUI2's panel close listener only runs
     * when the client sends a {@code CloseGuiPacket}; {@code ServerConfigurationManager.playerLoggedOut} closes no
     * container and ModularUI2's own {@code onPlayerLeave} only clears its network maps, so without
     * {@code HubViewLifecycle} an alt-F4 would hold the slot until the server stopped.
     *
     * <p>
     * The handler is called rather than posted: posting a {@code PlayerLoggedOutEvent} for a synthetic player would
     * run every other mod's logout code on the shared test server. That it really is subscribed, and that it is the
     * only {@code @SubscribeEvent} method on the class, is {@code IdleCostTests}' job.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void aDisconnectReleasesTheView(GameTestHelper helper) {
        UUID owner = teamlessOwner("hubGuiLogout");
        helper.afterTest(
            () -> TelemetryHubs.views()
                .closed(owner));
        emptyRegistry(helper);
        publish(helper);
        Ui ui = open(helper, "hubGuiLogout", owner);
        ui.msm.onOpen();
        helper.assertTrue(
            TelemetryHubs.views()
                .isOpen(owner),
            "opening the panel did not reserve a view");

        HubViewLifecycle.instance()
            .onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(ui.viewerEntity));
        helper.assertFalse(
            TelemetryHubs.views()
                .isOpen(owner),
            "a disconnect did not release the view");
        helper.succeed();
    }

    /**
     * Design-v0.2 section 9.1: a server-side force close releases the slot too. Minecraft answers a failed
     * {@code canInteractWith} with {@code EntityPlayerMP.closeScreen}, which reaches ModularUI2's empty
     * {@code onModularContainerClosed} and never its close listener, so the Hub releases the slot from
     * {@code canInteractWith} itself.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void aForcedCloseReleasesTheView(GameTestHelper helper) {
        UUID owner = teamlessOwner("hubGuiForce");
        helper.afterTest(
            () -> TelemetryHubs.views()
                .closed(owner));
        emptyRegistry(helper);
        publish(helper);
        Ui ui = open(helper, "hubGuiForce", owner);
        TestPos abs = helper.absolute(HUB);
        ui.viewerEntity.setPosition(abs.x() + 0.5D, abs.y(), abs.z() + 0.5D);
        ui.msm.onOpen();
        helper.assertTrue(
            TelemetryHubs.views()
                .isOpen(owner),
            "opening the panel did not reserve a view");
        helper.assertTrue(
            ui.container.canInteractWith(ui.viewerEntity),
            "the owner standing on the Hub cannot interact with it");
        helper.assertTrue(
            TelemetryHubs.views()
                .isOpen(owner),
            "a passing interaction check released the view");

        // What the vanilla per-tick check sees when the player walks away: more than 8 blocks.
        ui.viewerEntity.setPosition(abs.x() + 64.5D, abs.y(), abs.z() + 0.5D);
        helper.assertFalse(ui.container.canInteractWith(ui.viewerEntity), "the distance check did not refuse");
        helper.assertFalse(
            TelemetryHubs.views()
                .isOpen(owner),
            "a forced close did not release the view");
        helper.succeed();
    }

    /**
     * Design-v0.2 section 6.3: no C2S packet rebuilds the view model. A rebuild scans and sorts the whole scope and,
     * with a row selected, reads about 2,885 minute slots, and 1.7.10 drains up to 1,000 queued packets per
     * connection per tick on the server thread - so one rebuild per packet was a tick-time denial of service. The
     * setters only clamp and mark dirty; the rebuild happens in the getters, which ModularUI2 calls once per
     * container update.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void inputPacketsDoNotRebuild(GameTestHelper helper) {
        UUID owner = teamlessOwner("hubGuiBurst");
        emptyRegistry(helper);
        liveSensor(helper, MACHINE, "0d91", owner, "hubGuiBurst");
        liveSensor(helper, SECOND, "0d92", owner, "hubGuiBurst");
        scopeFiller(helper, owner, "hubGuiBurst", 15);
        publish(helper);
        Ui ui = openWithSession(helper, owner, "hubGuiBurst");

        ui.header(helper);
        int before = ui.session.rebuilds();
        helper.assertTrue(before > 0, "the view model never built at all");
        for (int i = 0; i < 64; i++) {
            sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), i % HubViewModel.ROWS_PER_PAGE);
            sendInt(ui.intHandler(helper, HubPanel.SYNC_PAGE), i % 3);
            sendInt(ui.intHandler(helper, HubPanel.SYNC_FILTER), i % 2);
        }
        helper.assertEquals(before, ui.session.rebuilds(), "a C2S packet rebuilt the view model");

        // And the next read does rebuild, so this is a throttle and not a model that stopped working.
        ui.header(helper);
        helper.assertEquals(before + 1, ui.session.rebuilds(), "the next container update did not rebuild");
        helper.succeed();
    }

    // --- helpers ---

    /** One built panel with its sync managers, the way {@code GuiManager.open} builds one, minus the packet. */
    private static final class Ui {

        final ModularPanel panel;
        final PanelSyncManager psm;
        final ModularSyncManager msm;
        final ModularContainer container;
        final EntityPlayer viewerEntity;
        final UUID viewer;
        /** Non-null only for {@link #openWithSession}, which builds the session itself to be able to read it. */
        final HubSession session;

        Ui(ModularPanel panel, PanelSyncManager psm, ModularSyncManager msm, ModularContainer container,
            EntityPlayer viewerEntity, UUID viewer, HubSession session) {
            this.panel = panel;
            this.psm = psm;
            this.msm = msm;
            this.container = container;
            this.viewerEntity = viewerEntity;
            this.viewer = viewer;
            this.session = session;
        }

        SyncHandler<?> handler(GameTestHelper helper, String name) {
            SyncHandler<?> found = psm.findSyncHandlerNullable(name);
            helper.assertNotNull(found, "no sync handler registered under " + name);
            return found;
        }

        IntSyncValue intHandler(GameTestHelper helper, String name) {
            return helper.assertInstanceOf(IntSyncValue.class, handler(helper, name), name + " is not an IntSyncValue");
        }

        StringSyncValue stringHandler(GameTestHelper helper, String name) {
            return helper
                .assertInstanceOf(StringSyncValue.class, handler(helper, name), name + " is not a StringSyncValue");
        }

        GenericSyncValue<?, ?> genericHandler(GameTestHelper helper, String name) {
            GenericSyncValue<?, ?> found = helper
                .assertInstanceOf(GenericSyncValue.class, handler(helper, name), name + " is not a GenericSyncValue");
            found.updateCacheFromSource(true);
            return found;
        }

        HubHeader header(GameTestHelper helper) {
            return helper.assertInstanceOf(
                HubHeader.class,
                genericHandler(helper, HubPanel.SYNC_HEADER).getValue(),
                "gs_header value");
        }

        HubDetail detail(GameTestHelper helper) {
            return helper.assertInstanceOf(
                HubDetail.class,
                genericHandler(helper, HubPanel.SYNC_DETAIL).getValue(),
                "gs_detail value");
        }

        List<HubRow> rows(GameTestHelper helper) {
            GenericListSyncHandler<?> found = helper.assertInstanceOf(
                GenericListSyncHandler.class,
                handler(helper, HubPanel.SYNC_ROWS),
                "gs_rows is not a GenericListSyncHandler");
            found.updateCacheFromSource(true);
            List<HubRow> out = new java.util.ArrayList<>();
            for (Object value : found.getValue()) {
                out.add(helper.assertInstanceOf(HubRow.class, value, "gs_rows element"));
            }
            return out;
        }

        int page(GameTestHelper helper) {
            return readInt(intHandler(helper, HubPanel.SYNC_PAGE));
        }

        int filter(GameTestHelper helper) {
            return readInt(intHandler(helper, HubPanel.SYNC_FILTER));
        }

        int selectedRow(GameTestHelper helper) {
            return readInt(intHandler(helper, HubPanel.SYNC_SELECT));
        }

        /**
         * What the client is about to be told the label is. A C2S packet puts the typed text straight into the
         * handler's cache, so the value only "resets" when the server's next {@code detectAndSendChanges} pulls the
         * accepted one from the getter - which is exactly what {@code updateCacheFromSource} does here.
         */
        String label(GameTestHelper helper) {
            StringSyncValue handler = stringHandler(helper, HubPanel.SYNC_LABEL);
            handler.updateCacheFromSource(true);
            return handler.getValue();
        }
    }

    /**
     * Repeats {@code GuiManager.open}'s own sequence up to the packet: {@code buildUI} on the Hub, the widget sync
     * values, a real {@code ModularContainer} and the sync managers. The panel is therefore built exactly as a real
     * open builds it, and every handler is initialised, which is what makes {@code readOnServer} work.
     */
    private static Ui open(GameTestHelper helper, TestPos hubPos, UUID hubOwner, String hubOwnerName, UUID viewerId,
        String viewerName, int permissionLevel) {
        CommandSenders.Real placer = CommandSenders.realPlayer(helper, hubOwnerName, hubOwner, -1);
        TileTelemetryHub hub = placeHub(helper, hubPos, placer);
        helper.assertEquals(hubOwner, hub.owner(), "the Hub did not take the intended owner");

        CommandSenders.Real viewer = CommandSenders.realPlayer(helper, viewerName, viewerId, permissionLevel);
        TestPos abs = helper.absolute(hubPos);
        PosGuiData data = new PosGuiData(viewer, abs.x(), abs.y(), abs.z());
        ModularSyncManager msm = new ModularSyncManager(false);
        PanelSyncManager psm = new PanelSyncManager(msm, true);
        UISettings settings = new UISettings(RecipeViewerSettings.DUMMY);
        ModularPanel panel = hub.buildUI(data, psm, settings);
        WidgetTree.collectSyncValues(psm, panel);
        ModularContainer container = TileEntityGuiFactory.INSTANCE.createContainer();
        container.construct(viewer, msm, settings, panel.getName(), data);
        return new Ui(panel, psm, msm, container, viewer, viewerId, null);
    }

    private static Ui open(GameTestHelper helper, UUID hubOwner, String hubOwnerName, UUID viewerId, String viewerName,
        int permissionLevel) {
        return open(helper, HUB, hubOwner, hubOwnerName, viewerId, viewerName, permissionLevel);
    }

    /** The common case: the Hub's owner is also the viewer, and is not an operator. */
    private static Ui open(GameTestHelper helper, String name, UUID owner) {
        return open(helper, owner, name, owner, name, -1);
    }

    /**
     * The same panel, but with the {@link HubSession} built here instead of inside {@code buildUI}, so a test can read
     * {@code rebuilds()}. Everything else is what {@code open} does; only {@code canInteractWith} and the view
     * listeners, which {@code buildUI} owns, are missing, and no test using this looks at them.
     */
    private static Ui openWithSession(GameTestHelper helper, UUID owner, String name) {
        CommandSenders.Real placer = CommandSenders.realPlayer(helper, name, owner, -1);
        TileTelemetryHub hub = placeHub(helper, HUB, placer);
        helper.assertEquals(owner, hub.owner(), "the Hub did not take the intended owner");

        TestPos abs = helper.absolute(HUB);
        PosGuiData data = new PosGuiData(placer, abs.x(), abs.y(), abs.z());
        ModularSyncManager msm = new ModularSyncManager(false);
        PanelSyncManager psm = new PanelSyncManager(msm, true);
        UISettings settings = new UISettings(RecipeViewerSettings.DUMMY);
        HubSession session = new HubSession(hub, placer, false);
        ModularPanel panel = HubPanel.build(session, psm);
        WidgetTree.collectSyncValues(psm, panel);
        ModularContainer container = TileEntityGuiFactory.INSTANCE.createContainer();
        container.construct(placer, msm, settings, panel.getName(), data);
        return new Ui(panel, psm, msm, container, placer, owner, session);
    }

    /**
     * Registers {@code count} more sensors in {@code owner}'s scope straight through the registry core, the way
     * {@code SamplerTests} does: a Hub row only needs a registry entry in scope, not a machine in this cell.
     */
    private static void scopeFiller(GameTestHelper helper, UUID owner, String ownerName, int count) {
        SensorRegistryCore core = registry(helper).core();
        int dim = helper.getWorld().provider.dimensionId;
        for (int i = 0; i < count; i++) {
            SensorIdentity identity = new SensorIdentity(
                UUID.nameUUIDFromBytes(
                    ("gregscope-hubgui-filler:" + ownerName + ":" + i)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "filler " + i,
                owner,
                ownerName,
                1_600_000_000L);
            SensorRegistryCore.Heartbeat result = core
                .heartbeat(identity, SensorKind.MACHINE, dim, FILLER_X + i, 64, FILLER_Z, COVERED.ordinal(), 0L);
            helper.assertEquals(SensorRegistryCore.Heartbeat.REGISTERED, result, "filler sensor " + i);
            helper.afterTest(() -> core.purge(identity.id()));
        }
    }

    /** A viewer and Hub owner with no sensors at all, for the tests that only need a panel. */
    private static Ui open(GameTestHelper helper, String name) {
        return open(helper, name, teamlessOwner(name));
    }

    /** A stable owner UUID that belongs to no GTNHLib team, so only the "same owner" row of section 5 applies. */
    private static UUID teamlessOwner(String tag) {
        return UUID.nameUUIDFromBytes(("gregscope-hubgui:" + tag).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Sends one {@code IntSyncValue} packet the way a client does: through {@code readOnServer}. */
    private static void sendInt(IntSyncValue handler, int value) {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        buffer.writeVarIntToBuffer(value);
        try {
            handler.readOnServer(SYNC_VALUE, buffer);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends one {@code StringSyncValue} packet the way a client does: through {@code readOnServer}. */
    private static void sendString(StringSyncValue handler, String value) {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        NetworkUtils.writeStringSafe(buffer, value);
        try {
            handler.readOnServer(SYNC_VALUE, buffer);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int readInt(IntSyncValue handler) {
        handler.updateCacheFromSource(true);
        return handler.getIntValue();
    }

    private static List<UUID> ids(List<HubRow> rows) {
        List<UUID> out = new java.util.ArrayList<>();
        for (HubRow row : rows) {
            if (!row.isEmpty()) {
                out.add(row.id());
            }
        }
        return out;
    }

    /** Publishes a fresh {@code TelemetryFrame}, which is what a Hub rebuild reads (errata E2: a warp does not). */
    private static void publish(GameTestHelper helper) {
        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");
    }

    private static TileTelemetryHub placeHub(GameTestHelper helper, TestPos local, EntityPlayer placer) {
        ItemStack stack = TelemetryHubs.hubStack();
        TestPos abs = helper.absolute(local);
        ItemBlock item = helper.assertInstanceOf(ItemBlock.class, stack.getItem(), "the Hub item is not an ItemBlock");
        helper.assertTrue(
            item.placeBlockAt(stack, placer, helper.getWorld(), abs.x(), abs.y(), abs.z(), 1, 0.5F, 0.5F, 0.5F, 0),
            "the Telemetry Hub was not placed at " + local);
        return helper.assertInstanceOf(
            TileTelemetryHub.class,
            helper.assertTileEntityPresent(local),
            "placed tile entity at " + local);
    }

    private static SensorRegistry registry(GameTestHelper helper) {
        SensorRegistry registry = GregScope.registry();
        helper.assertNotNull(registry, "GregScope has no registry on this server");
        return registry;
    }

    private static void emptyRegistry(GameTestHelper helper) {
        SensorRegistry registry = registry(helper);
        helper.assertTrue(GregScopeTestHooks.purgeAllNow() >= 0, "the registry could not be emptied");
        helper.assertEquals(
            0,
            registry.core()
                .size(),
            "the registry is not empty");
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

    /** A LIVE sensor with a chosen UUID and owner, exactly as {@code CommandTests} builds one (section 3.4). */
    private static UUID liveSensor(GameTestHelper helper, TestPos pos, String idPrefix, UUID owner, String ownerName) {
        UUID id = UUID.fromString(idPrefix + pad(pos) + "-0000-4000-8000-" + tail(pos));
        SensorIdentity identity = new SensorIdentity(id, "", owner, ownerName, 1_600_000_000L);
        IGregTechTileEntity holder = SensorFixtures.placeMachineWithSensorNbt(helper, pos, COVERED, identity);
        MachineSensorCover attached = helper
            .assertInstanceOf(MachineSensorCover.class, holder.getCoverAtSide(COVERED), "sensor cover at " + pos);
        helper.assertTrue(GregScopeTestHooks.heartbeatNow(attached), "GregScope test hooks are disabled");
        SensorEntry entry = entry(helper, id);
        helper.assertEquals(SensorState.LIVE, entry.state(), "the fixture sensor did not register");
        helper.assertEquals(owner, entry.owner(), "owner");
        return id;
    }

    private static String pad(TestPos pos) {
        return String.format("%01x%01x%01x%01x", pos.x() & 15, pos.y() & 15, pos.z() & 15, 1);
    }

    private static String tail(TestPos pos) {
        return String.format("%012x", (pos.x() * 100 + pos.z()) & 0xffff);
    }

    /**
     * GS-121 regression: a detail line must fit the panel, not merely be correct.
     *
     * <p>
     * A real client showed the detail block drawing "text over text". The model was right and the string was simply
     * too long: ModularUI2's text widget wraps at the panel width, but the lines pinned themselves to one line's
     * height, so the wrapped remainder drew on top of the next line. Nothing server-side could see it, because every
     * value in the string was correct. This asserts the thing that was actually wrong - the WIDTH - with the label
     * pushed to the longest a player can really set.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void detailLinesFitThePanel(GameTestHelper helper) {
        UUID owner = teamlessOwner("hubGuiFit");
        emptyRegistry(helper);
        UUID id = liveSensor(helper, MACHINE, "0d24", owner, "hubGuiFit");
        publish(helper);
        Ui ui = openWithSession(helper, owner, "hubGuiFit");

        // The longest label Labels.sanitize will keep, which is what a player can really put on a sensor.
        StringBuilder wide = new StringBuilder();
        while (wide.length() < Labels.MAX_CODE_POINTS) {
            wide.append('W');
        }
        sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 0);
        sendString(ui.stringHandler(helper, HubPanel.SYNC_LABEL), wide.toString());
        publish(helper);
        sendInt(ui.intHandler(helper, HubPanel.SYNC_SELECT), 0);

        // Without this the width assertions below would pass on an empty detail, which is how the first version of
        // this test passed against the very bug it was written for.
        helper.assertTrue(
            ui.session.detail()
                .isPresent(),
            "nothing was selected, so the lines below are empty");
        helper.assertEquals(
            id,
            ui.session.detail()
                .id(),
            "row 0 did not resolve to the fixture sensor");
        helper.assertEquals(
            Labels.MAX_CODE_POINTS,
            ui.session.detail()
                .displayName()
                .length(),
            "the wide label did not reach the detail, so this is not the worst case");

        String identityLine = HubPanel.detailIdentity(ui.session);
        String atLine = HubPanel.detailAt(ui.session);
        String stateLine = HubPanel.detailState(ui.session);
        helper.assertTrue(
            identityLine.length() <= DETAIL_LINE_CHARS,
            "the identity line is " + identityLine.length()
                + " characters, past the "
                + DETAIL_LINE_CHARS
                + " a "
                + HubPanel.WIDTH
                + "px panel fits, so it wraps and draws over the next line: "
                + identityLine);
        helper.assertTrue(
            stateLine.length() <= DETAIL_LINE_CHARS,
            "the state line is " + stateLine.length()
                + " characters, past the "
                + DETAIL_LINE_CHARS
                + " a "
                + HubPanel.WIDTH
                + "px panel fits, so it wraps and draws over the next line: "
                + stateLine);
        helper.assertTrue(
            atLine.length() <= DETAIL_LINE_CHARS,
            "the location line is " + atLine.length()
                + " characters, past the "
                + DETAIL_LINE_CHARS
                + " a "
                + HubPanel.WIDTH
                + "px panel fits, so it wraps and draws over the next line: "
                + atLine);
        Snapshots.log(
            "hubgui#fit",
            "identity " + identityLine.length() + " / at " + atLine.length() + " / state " + stateLine.length());
        helper.succeed();
    }

}
