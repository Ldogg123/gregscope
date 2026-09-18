package io.github.ldogg123.gregscope.hub;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.access.AccessPolicy;
import io.github.ldogg123.gregscope.access.GtnhlibTeamResolver;
import io.github.ldogg123.gregscope.access.Viewer;
import io.github.ldogg123.gregscope.sensor.NbtKeyValue;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;

/**
 * The Telemetry Hub tile entity (design-v0.2 section 9.1), registered as {@code gregscope:telemetry_hub}.
 *
 * <p>
 * It stores an owner and nothing else: the Hub holds no telemetry, and shows the registry entries in its owner's scope
 * when the GUI arrives with GS-114. {@link #canUpdate()} is {@code false}, so Minecraft never puts it in a world's
 * tick list - this is the one shipped class design-v0.2 section 1.4 lifts "no tile entities" for, and
 * {@code ShippedClassesTest.theOnlyTileEntityIsTheHub} keeps that lift to this class and to {@code canUpdate} alone.
 *
 * <p>
 * <b>Unknown versions.</b> A compound whose {@code gsHub} is newer than {@link HubNbtCodec#FORMAT} is kept verbatim
 * and written back unchanged, the same rule the sensor cover follows for its own data (section 3.3): a world touched
 * by a newer GregScope survives a downgrade. Such a Hub parses no owner, so only an operator can open it, and GS-114's
 * GUI shows "unsupported".
 */
public class TileTelemetryHub extends TileEntity {

    /** MC's own tile entity keys, which {@link #writeToNBT} writes itself and a preserved compound must not replay. */
    private static final String[] VANILLA_KEYS = { "id", "x", "y", "z" };

    private UUID owner;
    private String ownerName = "";
    /** Non-null exactly when the stored data is of an unsupported version; then it is written back verbatim. */
    private NBTTagCompound preserved;
    private int version = HubNbtCodec.FORMAT;

    /**
     * Never true: the Hub does no periodic work at all (design-v0.2 section 6.3). Minecraft asks this once, when the
     * tile entity is added to the world, and only adds it to the tick list if it answers true.
     */
    @Override
    public boolean canUpdate() {
        return false;
    }

    /** The Hub owner, or {@code null} when it is unowned (placed by a FakePlayer) or its data is unsupported. */
    public UUID owner() {
        return owner;
    }

    /** The cached owner name; empty when there is no owner. Never null. */
    public String ownerName() {
        return ownerName;
    }

    /** True when the stored {@code gsHub} is newer than this build understands. */
    public boolean unsupported() {
        return preserved != null;
    }

    /** The {@code gsHub} version this Hub carries. */
    public int version() {
        return version;
    }

    /**
     * Sets the owner and marks the tile dirty (design-v0.2 section 9.1: {@code onBlockPlacedBy}). A Hub whose data is
     * of an unsupported version is never rewritten, so this does nothing there.
     *
     * @return true if the owner was stored
     */
    public boolean setOwner(UUID newOwner, String newOwnerName) {
        if (preserved != null) {
            return false;
        }
        owner = newOwner;
        ownerName = newOwner == null ? "" : SensorIdentity.capOwnerName(newOwnerName);
        markDirty();
        return true;
    }

    /**
     * Design-v0.2 section 5: the viewer is the Hub owner, in the same team as the Hub owner, or an operator holding
     * {@code permissions.opLevel}. The vanilla permission check is asked for this mod's command name, exactly as the
     * command adapter asks it, so operator levels follow the server's ops list.
     */
    public boolean canOpen(EntityPlayer player) {
        if (player == null) {
            return false;
        }
        final EntityPlayer viewer = player;
        return new AccessPolicy(GregScope.settings()).canOpenHub(
            Viewer.player(viewer.getUniqueID(), level -> viewer.canCommandSenderUseCommand(level, GregScope.MODID)),
            owner,
            new GtnhlibTeamResolver());
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        HubNbtCodec.Result result = HubNbtCodec.read(new NbtKeyValue(tag));
        version = result.version();
        if (result.status() == HubNbtCodec.Status.UNSUPPORTED) {
            preserved = (NBTTagCompound) tag.copy();
            owner = null;
            ownerName = "";
            return;
        }
        preserved = null;
        version = HubNbtCodec.FORMAT;
        owner = result.owner();
        ownerName = result.ownerName();
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (preserved != null) {
            // Verbatim, minus the four keys super.writeToNBT owns: this Hub's position is the current one.
            for (String name : preserved.func_150296_c()) {
                if (!isVanillaKey(name)) {
                    tag.setTag(
                        name,
                        preserved.getTag(name)
                            .copy());
                }
            }
            return;
        }
        HubNbtCodec.write(owner, ownerName, new NbtKeyValue(tag));
    }

    private static boolean isVanillaKey(String name) {
        for (String key : VANILLA_KEYS) {
            if (key.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
