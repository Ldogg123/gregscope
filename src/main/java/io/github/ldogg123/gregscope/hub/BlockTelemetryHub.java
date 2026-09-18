package io.github.ldogg123.gregscope.hub;

import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeAssets;

/**
 * The Telemetry Hub block, {@code gregscope:telemetry_hub} (design-v0.2 section 9.1). A plain Forge block with a
 * non-ticking tile entity, deliberately not a GT meta tile entity: MTE ids are permanent in world saves, have no
 * allocator and crash on collision.
 *
 * <p>
 * Everything this block decides is decided on the server. {@code onBlockActivated} returns true on the client (so the
 * client plays the use animation and sends nothing else) and, on the server, checks design-v0.2 section 5 access and
 * then the section 9.1 open-view cap, telling the player which one refused. GS-114 opens the MUI2 panel at the point
 * marked below; until then a permitted right-click opens nothing, which is what the ticket allows, and nothing is
 * reserved in {@link HubViews} for a window that does not exist.
 *
 * <p>
 * <b>Textures.</b> Section 9.1 wants {@code telemetry_hub_front/side/top}. Per-side icons need
 * {@code registerBlockIcons(IIconRegister)} and {@code getIcon(int, int)}, which are {@code @SideOnly(CLIENT)} in
 * 1.7.10 ({@code net/minecraft/block/Block.java:1469} and {@code :651}), so this class would have to name a client
 * class. GS-112 does not lift that guard: all three PNGs are committed, and the block asks for the side texture with
 * {@code setBlockTextureName}, which Minecraft's own client-side {@code registerBlockIcons} reads. GS-114, which
 * already needs {@code @SideOnly(CLIENT)} for {@code createScreen}, wires the front and top icons.
 */
public final class BlockTelemetryHub extends Block implements ITileEntityProvider {

    public static final BlockTelemetryHub INSTANCE = new BlockTelemetryHub();

    /** Metadata values section 9.1 pins for the four horizontal facings. */
    public static final int FACING_NORTH = 2;
    public static final int FACING_SOUTH = 3;
    public static final int FACING_WEST = 4;
    public static final int FACING_EAST = 5;

    private BlockTelemetryHub() {
        super(Material.iron);
        setHardness(5.0F);
        setResistance(10.0F);
        setHarvestLevel("pickaxe", 2);
        setStepSound(Block.soundTypeMetal);
        setBlockName(GregScopeAssets.UNLOCALIZED_TELEMETRY_HUB);
        setBlockTextureName(GregScopeAssets.iconName(GregScopeAssets.BLOCK_ICON_HUB_SIDE));
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileTelemetryHub();
    }

    /**
     * Section 9.1: facing from the placer's yaw into metadata 2..5, and the owner from the placing player. A
     * {@code FakePlayer} (another mod's automation, a gametest) leaves the Hub unowned, exactly as a sensor cover
     * attached by one is unowned (section 3.4).
     */
    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        if (world.isRemote) {
            return;
        }
        world.setBlockMetadataWithNotify(x, y, z, facingFromYaw(placer), 2);
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileTelemetryHub)) {
            return;
        }
        TileTelemetryHub hub = (TileTelemetryHub) tile;
        if (placer instanceof EntityPlayer && !(placer instanceof FakePlayer)) {
            EntityPlayer player = (EntityPlayer) placer;
            hub.setOwner(player.getUniqueID(), player.getCommandSenderName());
        } else {
            hub.setOwner(null, null);
        }
    }

    /**
     * Section 9.1: on the client this only returns true; on the server it checks access, then the open-view cap, and
     * would then open the GUI.
     */
    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        if (player == null) {
            return true;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileTelemetryHub)) {
            return true;
        }
        TileTelemetryHub hub = (TileTelemetryHub) tile;
        if (!hub.canOpen(player)) {
            player.addChatMessage(new ChatComponentTranslation(GregScopeAssets.LANG_HUB_DENIED));
            return true;
        }
        UUID viewer = player.getUniqueID();
        if (!TelemetryHubs.views()
            .canOpen(
                viewer,
                GregScope.settings()
                    .maxOpenHubViews())) {
            player.addChatMessage(new ChatComponentTranslation(GregScopeAssets.LANG_HUB_BUSY));
            return true;
        }
        // GS-114 opens the MUI2 panel here and calls TelemetryHubs.views().opened(viewer).
        return true;
    }

    /** Vanilla's placement rule (as {@code BlockFurnace} uses it): yaw quadrant to metadata 2..5. */
    public static int facingFromYaw(EntityLivingBase placer) {
        if (placer == null) {
            return FACING_NORTH;
        }
        int quadrant = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        switch (quadrant) {
            case 1:
                return FACING_EAST;
            case 2:
                return FACING_SOUTH;
            case 3:
                return FACING_WEST;
            default:
                return FACING_NORTH;
        }
    }
}
