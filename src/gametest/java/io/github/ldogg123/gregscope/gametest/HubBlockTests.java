package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.FakePlayer;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import cpw.mods.fml.common.registry.GameRegistry;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeAssets;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.hub.BlockTelemetryHub;
import io.github.ldogg123.gregscope.hub.HubNbtCodec;
import io.github.ldogg123.gregscope.hub.TelemetryHubs;
import io.github.ldogg123.gregscope.hub.TileTelemetryHub;

/**
 * GS-112 (design-v0.2 section 9.1, section 5, section 14): the Telemetry Hub block and tile entity on a real dedicated
 * server.
 *
 * <p>
 * Everything goes through the shipped objects: the block is placed with its own {@code ItemBlock.placeBlockAt} (which
 * is what a player's right-click calls, and what runs {@code onBlockPlacedBy}), and a right-click is
 * {@code BlockTelemetryHub.onBlockActivated} itself. The owner rows of section 5 need a <b>real</b> player, because a
 * {@code FakePlayer} is "no attributable player" by design and leaves the Hub unowned - which is its own assertion
 * here ({@link CommandSenders.Real} against {@code helper.spawnFakePlayer}).
 *
 * <p>
 * <b>No GUI yet.</b> GS-114 owns {@code HubPanel}, so a permitted right-click opens nothing. The server-side checks
 * are real and are what these tests assert: the refusal messages, and that a refused viewer never reaches the open
 * path (the view register stays empty, and the player's container is still their own inventory).
 *
 * <p>
 * <b>Batch names.</b> {@code gregscope.surface.hub*} sorts after every existing GregScope batch, so no older test's
 * cell moves (the GS-109/GS-110 rule), and the one chunk-reload test has a batch of its own.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregscope" })
public class HubBlockTests {

    private static final String BATCH = "gregscope.surface.hub";
    /** Takes the whole cell down and brings it back, so it runs alone (GS-110 notes). */
    private static final String RELOAD_BATCH = "gregscope.surface.hub.reload";

    private static final TestPos HUB = at(1, 0, 1);
    private static final TestPos SECOND_HUB = at(3, 0, 1);

    private static final UUID OWNER_UUID = UUID.fromString("0b112112-0000-4000-8000-00000000c0de");

    private HubBlockTests() {}

    // --- registration (design-v0.2 section 9.1 and the section 17 frozen names) ---

    /** The block, its ItemBlock and its tile entity are registered under the names section 17 freezes. */
    @GameTest(batch = BATCH)
    public static void hubBlockAndTileAreRegistered(GameTestHelper helper) {
        Block registered = GameRegistry.findBlock(GregScope.MODID, GregScopeAssets.REGISTRY_TELEMETRY_HUB);
        helper.assertSame(BlockTelemetryHub.INSTANCE, registered, "gregscope:telemetry_hub block");
        Item item = GameRegistry.findItem(GregScope.MODID, GregScopeAssets.REGISTRY_TELEMETRY_HUB);
        helper.assertInstanceOf(ItemBlock.class, item, "gregscope:telemetry_hub is not an ItemBlock");
        helper.assertSame(item, Item.getItemFromBlock(BlockTelemetryHub.INSTANCE), "the block's own item");

        helper.assertEquals("tile.gregscope.telemetry_hub", registered.getUnlocalizedName(), "unlocalized name");
        // Dedicated servers load mod lang too (section 12.2).
        helper.assertEquals(
            "Telemetry Hub",
            TelemetryHubs.hubStack()
                .getDisplayName(),
            "display name");
        helper.assertEquals(
            "Telemetry Hub",
            StatCollector.translateToLocal(GregScopeAssets.LANG_TILE_TELEMETRY_HUB),
            "tile lang key");

        // The tile entity really is registered under gregscope:telemetry_hub: Minecraft can build one from that id.
        NBTTagCompound saved = new NBTTagCompound();
        saved.setString("id", GregScopeAssets.TILE_ENTITY_TELEMETRY_HUB);
        TileEntity loaded = TileEntity.createAndLoadEntity(saved);
        helper.assertInstanceOf(TileTelemetryHub.class, loaded, "TileEntity id gregscope:telemetry_hub");
        helper.assertFalse(loaded.canUpdate(), "the Hub tile entity says it ticks");
        helper.succeed();
    }

    // --- placement and ownership (section 9.1) ---

    /** Section 9.1: a real player becomes the owner, and the facing lands in metadata 2..5. */
    @GameTest(batch = BATCH)
    public static void ownerSetOnPlace(GameTestHelper helper) {
        CommandSenders.Real player = CommandSenders.realPlayer(helper, "hubOwner", OWNER_UUID, -1);
        player.rotationYaw = 0.0F;
        TileTelemetryHub hub = placeHub(helper, HUB, player);

        helper.assertEquals(OWNER_UUID, hub.owner(), "the placing player is not the owner");
        helper.assertEquals("hubOwner", hub.ownerName(), "owner name");
        helper.assertFalse(hub.unsupported(), "a freshly placed Hub reads as unsupported");
        helper.assertEquals(HubNbtCodec.FORMAT, hub.version(), "gsHub version");
        helper.assertFalse(hub.canUpdate(), "the Hub ticks");

        TestPos abs = helper.absolute(HUB);
        int meta = helper.getWorld()
            .getBlockMetadata(abs.x(), abs.y(), abs.z());
        helper.assertTrue(meta >= 2 && meta <= 5, "facing metadata out of 2..5: " + meta);
        helper.assertEquals(BlockTelemetryHub.facingFromYaw(player), meta, "facing does not follow the placer's yaw");

        // What canUpdate() == false really buys: World.addTileEntity and World.setTileEntity only add a tile whose
        // canUpdate() is true (World.java:4405-4412 and :2834-2841), so a placed Hub is in no world's tick list.
        for (Object loaded : helper.getWorld().loadedTileEntityList) {
            helper.assertNotSame(hub, loaded, "the Hub joined the world's tile entity tick list");
        }
        helper.succeed();
    }

    /** Section 9.1: a FakePlayer is no attributable player, so the Hub it places is unowned. */
    @GameTest(batch = BATCH)
    public static void fakePlayerLeavesTheHubUnowned(GameTestHelper helper) {
        FakePlayer robot = helper.spawnFakePlayer("gregscope-hub-robot");
        TileTelemetryHub hub = placeHub(helper, HUB, robot);
        helper.assertNull(hub.owner(), "a FakePlayer became the Hub owner");
        helper.assertEquals("", hub.ownerName(), "owner name of an unowned Hub");
        helper.succeed();
    }

    // --- NBT (section 9.1) ---

    /** The three {@code gsHub} keys round-trip through the real tile entity, and an unknown version is preserved. */
    @GameTest(batch = BATCH)
    public static void nbtRoundTripAndUnknownVersionPreserved(GameTestHelper helper) {
        CommandSenders.Real player = CommandSenders.realPlayer(helper, "hubNbt", OWNER_UUID, -1);
        TileTelemetryHub hub = placeHub(helper, HUB, player);

        NBTTagCompound written = new NBTTagCompound();
        hub.writeToNBT(written);
        helper.assertEquals(
            (long) HubNbtCodec.FORMAT,
            (long) written.getByte(HubNbtCodec.GS_HUB),
            "gsHub in the written NBT");
        helper.assertEquals(
            OWNER_UUID.getMostSignificantBits(),
            written.getLong(HubNbtCodec.OWNER_MSB),
            "owM in the written NBT");
        TileTelemetryHub reloaded = new TileTelemetryHub();
        reloaded.readFromNBT(written);
        helper.assertEquals(OWNER_UUID, reloaded.owner(), "owner after a round trip");
        helper.assertEquals("hubNbt", reloaded.ownerName(), "owner name after a round trip");

        // A newer format: nothing is parsed, everything is written back, and the owner is never overwritten.
        NBTTagCompound future = (NBTTagCompound) written.copy();
        future.setByte(HubNbtCodec.GS_HUB, (byte) 2);
        future.setString("gsFuture", "a key this build knows nothing about");
        TileTelemetryHub unsupported = new TileTelemetryHub();
        unsupported.readFromNBT(future);
        helper.assertTrue(unsupported.unsupported(), "gsHub 2 did not read as unsupported");
        helper.assertEquals(2, unsupported.version(), "version of an unsupported Hub");
        helper.assertNull(unsupported.owner(), "an unsupported Hub parsed an owner");
        helper.assertFalse(unsupported.setOwner(OWNER_UUID, "someoneElse"), "an unsupported Hub took an owner");

        NBTTagCompound out = new NBTTagCompound();
        unsupported.writeToNBT(out);
        helper.assertEquals((long) 2, (long) out.getByte(HubNbtCodec.GS_HUB), "gsHub was rewritten");
        helper.assertEquals(
            "a key this build knows nothing about",
            out.getString("gsFuture"),
            "the unknown key was dropped");
        helper.assertEquals(
            OWNER_UUID.getMostSignificantBits(),
            out.getLong(HubNbtCodec.OWNER_MSB),
            "the preserved owner keys were dropped");
        helper.assertEquals(
            GregScopeAssets.TILE_ENTITY_TELEMETRY_HUB,
            out.getString("id"),
            "the tile entity id is not the registered one");
        helper.succeed();
    }

    /** Section 9.1 and section 14: the record survives a real chunk unload and reload. */
    @GameTest(batch = RELOAD_BATCH)
    public static void nbtRoundTripAcrossChunkReload(GameTestHelper helper) {
        CommandSenders.Real player = CommandSenders.realPlayer(helper, "hubReload", OWNER_UUID, -1);
        TileTelemetryHub owned = placeHub(helper, HUB, player);
        helper.assertEquals(OWNER_UUID, owned.owner(), "owner before the reload");

        // A second Hub in the same cell, carrying data from a newer GregScope.
        TileTelemetryHub future = placeHub(helper, SECOND_HUB, helper.spawnFakePlayer("gregscope-hub-future"));
        NBTTagCompound futureTag = new NBTTagCompound();
        future.writeToNBT(futureTag);
        futureTag.setByte(HubNbtCodec.GS_HUB, (byte) 2);
        futureTag.setString("gsFuture", "keep me");
        future.readFromNBT(futureTag);
        future.markDirty();
        helper.assertTrue(future.unsupported(), "the second Hub is not unsupported");

        ChunkReload chunks = ChunkReload.of(helper, HUB, SECOND_HUB);
        helper.startSequence()
            .thenExecute("unload", chunks::unload)
            .thenIdle(1)
            .thenExecute("reload and read back", () -> {
                chunks.reload();
                TileTelemetryHub after = helper.assertInstanceOf(
                    TileTelemetryHub.class,
                    helper.assertTileEntityPresent(HUB),
                    "Hub tile entity after the reload");
                helper.assertNotSame(owned, after, "the tile entity was never really unloaded");
                helper.assertEquals(OWNER_UUID, after.owner(), "owner after the reload");
                helper.assertEquals("hubReload", after.ownerName(), "owner name after the reload");
                helper.assertFalse(after.unsupported(), "an owned Hub read back as unsupported");

                TileTelemetryHub futureAfter = helper.assertInstanceOf(
                    TileTelemetryHub.class,
                    helper.assertTileEntityPresent(SECOND_HUB),
                    "second Hub tile entity after the reload");
                helper.assertTrue(futureAfter.unsupported(), "gsHub 2 did not survive the reload");
                helper.assertEquals(2, futureAfter.version(), "version after the reload");
                NBTTagCompound out = new NBTTagCompound();
                futureAfter.writeToNBT(out);
                helper.assertEquals("keep me", out.getString("gsFuture"), "the unknown key did not survive the reload");
            })
            .thenSucceed();
    }

    // --- opening (section 5 and the section 9.1 view cap) ---

    /** Section 5: a stranger is told so and reaches nothing; the owner is not refused. */
    @GameTest(batch = BATCH)
    public static void strangerDenied(GameTestHelper helper) {
        clearViews(helper);
        CommandSenders.Real owner = CommandSenders.realPlayer(helper, "hubOwner2", OWNER_UUID, -1);
        placeHub(helper, HUB, owner);
        CommandSenders.Real stranger = CommandSenders.realPlayer(helper, "hubStranger", -1);

        helper.assertTrue(activate(helper, HUB, stranger), "onBlockActivated did not consume the click");
        helper.assertTrue(stranger.chat.has(GregScopeAssets.LANG_HUB_DENIED), "" + stranger.chat);
        helper.assertEquals(1, stranger.chat.size(), "" + stranger.chat);
        helper
            .assertSame(stranger.inventoryContainer, stranger.openContainer, "a denied activation opened a container");
        helper.assertEquals(
            0,
            TelemetryHubs.views()
                .size(),
            "a denied activation reserved a view");

        // The owner is allowed: no refusal, and still nothing open (GS-114 brings the panel).
        helper.assertTrue(activate(helper, HUB, owner), "onBlockActivated did not consume the owner's click");
        helper.assertEquals(0, owner.chat.size(), "the owner was told something: " + owner.chat);
        helper.assertSame(
            owner.inventoryContainer,
            owner.openContainer,
            "GS-112 opened a container; is this still the no-GUI state?");
        helper.succeed();
    }

    /** Section 5: an unowned Hub (placed by a FakePlayer) is open to operators only. */
    @GameTest(batch = BATCH)
    public static void unownedHubIsOperatorsOnly(GameTestHelper helper) {
        clearViews(helper);
        placeHub(helper, HUB, helper.spawnFakePlayer("gregscope-hub-robot2"));
        CommandSenders.Real stranger = CommandSenders.realPlayer(helper, "hubNobody", -1);
        CommandSenders.Real op = CommandSenders.realPlayer(helper, "hubOp", 2);

        activate(helper, HUB, stranger);
        helper.assertTrue(stranger.chat.has(GregScopeAssets.LANG_HUB_DENIED), "" + stranger.chat);
        activate(helper, HUB, op);
        helper.assertEquals(0, op.chat.size(), "an operator was refused: " + op.chat);
        helper.succeed();
    }

    /** Section 9.1: the open-view cap refuses a further viewer, with its own message. */
    @GameTest(batch = BATCH)
    public static void viewCapEnforced(GameTestHelper helper) {
        clearViews(helper);
        helper.assertTrue(
            GregScopeTestHooks.overrideSettings(
                Settings.DEFAULTS.toBuilder()
                    .maxOpenHubViews(1)
                    .build()),
            "GregScope test hooks are disabled");
        helper.afterTest(GregScopeTestHooks::clearSettingsOverride);
        CommandSenders.Real owner = CommandSenders.realPlayer(helper, "hubOwner3", OWNER_UUID, -1);
        placeHub(helper, HUB, owner);

        // Somebody else already holds the only view (GS-114's GUI is what will do this in production).
        TelemetryHubs.views()
            .opened(UUID.fromString("0b112112-0000-4000-8000-0000000000ff"));
        helper.assertTrue(activate(helper, HUB, owner), "onBlockActivated did not consume the click");
        helper.assertTrue(owner.chat.has(GregScopeAssets.LANG_HUB_BUSY), "" + owner.chat);
        helper.assertFalse(
            owner.chat.has(GregScopeAssets.LANG_HUB_DENIED),
            "the owner was refused by access: " + owner.chat);
        helper.assertEquals(
            1,
            TelemetryHubs.views()
                .size(),
            "the refused click changed the view register");

        // With the view closed again the same click is allowed.
        owner.chat.clear();
        TelemetryHubs.views()
            .clear();
        activate(helper, HUB, owner);
        helper.assertEquals(0, owner.chat.size(), "the owner was still refused: " + owner.chat);
        helper.succeed();
    }

    // --- helpers ---

    private static void clearViews(GameTestHelper helper) {
        TelemetryHubs.views()
            .clear();
        helper.afterTest(
            () -> TelemetryHubs.views()
                .clear());
    }

    /** Places a Telemetry Hub the way a player does, through its own ItemBlock, so onBlockPlacedBy runs. */
    private static TileTelemetryHub placeHub(GameTestHelper helper, TestPos local, EntityPlayer placer) {
        ItemStack stack = TelemetryHubs.hubStack();
        TestPos abs = helper.absolute(local);
        ItemBlock item = helper.assertInstanceOf(ItemBlock.class, stack.getItem(), "the Hub item is not an ItemBlock");
        boolean placed = item
            .placeBlockAt(stack, placer, helper.getWorld(), abs.x(), abs.y(), abs.z(), 1, 0.5F, 0.5F, 0.5F, 0);
        helper.assertTrue(placed, "the Telemetry Hub was not placed at " + local);
        return helper.assertInstanceOf(
            TileTelemetryHub.class,
            helper.assertTileEntityPresent(local),
            "placed tile entity at " + local);
    }

    /** One right-click on the Hub's top face, exactly as {@code ItemInWorldManager} delivers it. */
    private static boolean activate(GameTestHelper helper, TestPos local, EntityPlayer player) {
        TestPos abs = helper.absolute(local);
        return BlockTelemetryHub.INSTANCE
            .onBlockActivated(helper.getWorld(), abs.x(), abs.y(), abs.z(), player, 1, 0.5F, 1.0F, 0.5F);
    }
}
