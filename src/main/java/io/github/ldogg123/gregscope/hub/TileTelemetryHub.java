package io.github.ldogg123.gregscope.hub;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
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
 * It stores an owner and nothing else: the Hub holds no telemetry, and {@link #buildUI} shows the registry entries in
 * its owner's scope. {@link #canUpdate()} is {@code false}, so Minecraft never puts it in a world's
 * tick list - this is the one shipped class design-v0.2 section 1.4 lifts "no tile entities" for, and
 * {@code ShippedClassesTest.theOnlyTileEntityIsTheHub} keeps that lift to this class and to {@code canUpdate} alone.
 *
 * <p>
 * <b>Unknown versions.</b> A compound whose {@code gsHub} is newer than {@link HubNbtCodec#FORMAT} is kept verbatim
 * and written back unchanged, the same rule the sensor cover follows for its own data (section 3.3): a world touched
 * by a newer GregScope survives a downgrade. Such a Hub parses no owner, so only an operator can open it, and the GUI
 * header shows "unsupported".
 *
 * <p>
 * <b>The GUI (GS-114).</b> This is the {@code IGuiHolder<PosGuiData>} ModularUI2's tile entity factory looks for.
 * {@link #buildUI} runs on <b>both</b> sides and is the only method that knows which one it is on; it is what the
 * gametest {@code HubGuiServerTests} calls directly, because erratum E9 says {@code GuiManager.open} returns
 * immediately for a FakePlayer. {@link #createScreen} is the one client-only member of the shipped jar
 * (design-v0.2 section 1.4), which is why {@code ShippedClassesTest} names this class in its client lift.
 */
public class TileTelemetryHub extends TileEntity implements IGuiHolder<PosGuiData> {

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

    /**
     * Design-v0.2 sections 9.2 and 9.3. Called on the server when a viewer opens the Hub and on the client when the
     * open packet arrives, so it must never reach anything that exists on one side only: the per-viewer
     * {@link HubSession} is the class that knows the difference, and {@link HubPanel} builds the same widget tree
     * from it either way.
     *
     * <p>
     * Three things are wired here rather than in the panel, because only this side of the seam knows the host:
     * <ul>
     * <li><b>The section 5 access check and the section 9.1 view cap.</b> This method is the real entry point, not the
     * right-click: ModularUI2 registers {@code OpenGuiPacket} as a <em>client-to-server</em> packet
     * ({@code NetworkHandler.java} {@code registerBoth}), and {@code TileEntityGuiFactory.readGuiData} builds its
     * {@code PosGuiData} from three varints the client chose, so a modified client can reach {@code buildUI} for any
     * Hub without ever going through {@code BlockTelemetryHub.onBlockActivated}. A viewer who fails either check gets
     * a {@link HubSession#isDenied() denied} session: no view model, no listeners, no row, and a
     * {@code canInteractWith} that refuses at once, so vanilla closes the container on the first player tick.
     * {@code onBlockActivated} keeps its own copy of the two checks because it is what tells the player which one
     * refused.</li>
     * <li>{@code canInteractWith}: ModularUI2's default tile entity check ({@code TileEntityGuiFactory.java:56-58},
     * copied rather than delegated so this Hub also refuses a tile that was replaced under the viewer), plus the
     * section 5 access re-check every {@link HubSession#ACCESS_RECHECK_TICKS} ticks. Losing access closes the GUI and
     * releases the view, because {@code EntityPlayerMP.closeScreen} does not reach ModularUI2's close listener.</li>
     * <li>The section 9.1 open-view register: {@code opened} and {@code closed} hang off ModularUI2's own panel
     * lifecycle, so a view is reserved exactly while a panel is really open. A right-click that is refused, or a
     * FakePlayer whose open returns immediately (erratum E9), reserves nothing.</li>
     * </ul>
     */
    @Override
    public ModularPanel buildUI(PosGuiData data, PanelSyncManager syncManager, UISettings settings) {
        final boolean client = syncManager.isClient();
        final boolean denied = !client && !mayOpen(data.getPlayer());
        final HubSession session = new HubSession(this, data.getPlayer(), client, denied);
        settings.canInteractWith(player -> canInteractWith(session, data, player));
        if (!client && !denied && session.viewer() != null) {
            final UUID viewer = session.viewer();
            syncManager.addOpenListener(
                player -> TelemetryHubs.views()
                    .opened(viewer));
            syncManager.addCloseListener(
                player -> TelemetryHubs.views()
                    .closed(viewer));
        }
        return HubPanel.build(session, syncManager);
    }

    /**
     * The two server-side gates a real open has to pass: design-v0.2 section 5 access, then the section 9.1 open-view
     * cap - the same pair {@code BlockTelemetryHub.onBlockActivated} makes before it asks ModularUI2 to open.
     */
    private boolean mayOpen(EntityPlayer player) {
        if (player == null || !canOpen(player)) {
            return false;
        }
        return TelemetryHubs.views()
            .canOpen(
                player.getUniqueID(),
                GregScope.settings()
                    .maxOpenHubViews());
    }

    private boolean canInteractWith(HubSession session, PosGuiData data, EntityPlayer player) {
        boolean allowed = player != null && player == data.getPlayer()
            && !isInvalid()
            && data.getTileEntity() == this
            && data.getSquaredDistance(player) <= 64.0D
            && (session.isClient() || session.stillAllowed(this, player));
        if (!allowed && !session.isClient() && session.viewer() != null) {
            // Minecraft answers this with closeScreen, which reaches ModularUI2's empty onModularContainerClosed and
            // never its close listener, so the slot has to be released here or it would be held until server stop.
            TelemetryHubs.views()
                .closed(session.viewer());
        }
        return allowed;
    }

    /**
     * Design-v0.2 section 9.3. The one {@code @SideOnly(CLIENT)} member of the shipped jar: FML removes it on a
     * dedicated server, which is what keeps {@code ModularScreen} - a class that draws - off a server's class path.
     */
    @SideOnly(Side.CLIENT)
    @Override
    public ModularScreen createScreen(PosGuiData data, ModularPanel mainPanel) {
        return new ModularScreen(GregScope.MODID, mainPanel);
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
