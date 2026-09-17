package io.github.ldogg123.gregscope.sensor;

import java.lang.ref.WeakReference;
import java.util.UUID;

import javax.annotation.Nonnull;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fluids.Fluid;

import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.covers.Cover;
import io.github.ldogg123.gregscope.GregScope;

/**
 * The Machine Sensor cover (design-v0.2 §3): a read-only GT cover whose NBT carries the sensor identity.
 *
 * <p>
 * <b>Read-only towards the machine (§1.4).</b> Every {@code lets*} returns true (GT's defaults are false, which would
 * block energy, items, fluids and redstone on the covered face), it is not redstone sensitive, drives no redstone,
 * opens no cover GUI, refuses GT's copy-paste tool and refuses tick-rate changes. The only state it writes is its own.
 *
 * <p>
 * <b>Identity (§3.3).</b> The {@code d} compound of the cover NBT is the source of truth: it travels with a picked-up
 * machine and dies with a crowbar. {@link SensorNbtCodec} reads it over the {@link NbtKeyValue} seam. Data that is not
 * GregScope's, or comes from a newer format, leaves the cover <em>inert</em>: no identity, no registry, and the blob is
 * written back unchanged so a rollback keeps it.
 *
 * <p>
 * <b>Sides.</b> The constructor has no side effects, because GT builds covers during tile NBT load, before the world
 * is set, and on the client. Everything else that touches GregScope state is guarded by
 * {@code holder.isServerSide()}: {@code doCoverThings} is server-only in GT already, {@code onCoverRemoval} is not.
 * The identity never reaches a client: {@link #isDataNeededOnClient()} stays false, so the client's copy of this cover
 * only ever knows its tick rate.
 */
public class MachineSensorCover extends Cover implements SensorCover {

    /** §3.4: the cover heartbeat and GT's minimum tick rate for it. */
    public static final int TICK_RATE = 20;

    /** Null while the cover is inert (no GregScope data, or a data version this build does not support). */
    private SensorIdentity identity;
    /**
     * The {@code d} compound as it was read, kept so unknown keys survive a save (for example the v0.3 meters' own
     * keys, or a newer GregScope's). Null before anything was read.
     */
    private NBTTagCompound data;
    /** A non-compound {@code d} (a foreign blob, e.g. a reused numeric item ID), preserved verbatim. */
    private NBTBase foreignData;
    /** True for {@code gs > 1}: the compound must be written back untouched. */
    private boolean unsupportedVersion;
    /** Why this cover is inert, or null when it has an identity. */
    private String inertReason = SensorDescription.INACTIVE;
    /** Transient, set by the registry (GS-107) for {@link #getDescription()}; never saved. */
    private String availability;
    /**
     * The player who just attached this cover, held weakly until the first heartbeat consumes it, so a cap refusal can
     * tell them (design-v0.2 section 4.3 OVER_CAP row). Never saved and never a FakePlayer.
     */
    private WeakReference<EntityPlayer> attachingPlayer;

    public MachineSensorCover(CoverContext context, ITexture overlay) {
        super(context, overlay);
    }

    // --- identity ---

    @Override
    public SensorIdentity identity() {
        return identity;
    }

    @Override
    public int sensorKind() {
        return SensorKind.MACHINE;
    }

    /** Why the cover is inert ({@code inactive}, {@code unsupported data version}), or null when it has an identity. */
    public String inertReason() {
        return identity == null ? inertReason : null;
    }

    /** The registry's word for this sensor, shown in {@link #getDescription()}; null until one is set. */
    public String availability() {
        return availability;
    }

    /** Called by the registry (GS-107) on the server thread; not persisted. */
    @Override
    public void setAvailability(String availability) {
        this.availability = availability;
    }

    /**
     * Design-v0.2 section 4.3: this cover carries a UUID another sensor already uses, so the registry hands it a fresh
     * identity. Only the UUID changes; label, owner and creation time come from the copied NBT. Server thread only.
     */
    @Override
    public void rekey(SensorIdentity fresh) {
        ICoverable holder = getTile();
        if (fresh == null || identity == null || holder == null || !holder.isServerSide()) {
            return;
        }
        identity = fresh;
        holder.markDirty();
    }

    /**
     * The player who attached this cover, once, or {@code null}. The registry reads it on the first heartbeat so a
     * refused sensor can say so in chat; after that the cover forgets the player.
     */
    public EntityPlayer consumeAttachingPlayer() {
        WeakReference<EntityPlayer> reference = attachingPlayer;
        attachingPlayer = null;
        return reference == null ? null : reference.get();
    }

    /**
     * Reads the {@code d} compound (design-v0.2 §3.3).
     *
     * <p>
     * <b>A cover that already has an identity is never downgraded.</b> GT's own paste path
     * ({@code BehaviourCoverTool.java:88-99}) is gated only on the numeric cover id and calls
     * {@code ICoverable.updateAttachedCover}, which is {@code readFromNbt} on the <em>live</em> cover
     * ({@code CoverableTileEntity.java:498-502}); {@link #allowsCopyPasteTool()} only refuses the copy direction. A
     * blob that does not read as a GregScope sensor is therefore ignored here rather than allowed to erase a
     * registered sensor's UUID. A freshly built cover has no identity yet
     * ({@code CoverRegistry.buildCoverFromNbt} / {@code CoverPlacer.placeCover}), so world load, an item pickup and
     * the §3.3 rollback-preservation rule are untouched.
     */
    @Override
    protected void readDataFromNbt(NBTBase nbt) {
        if (nbt instanceof NBTTagCompound) {
            NBTTagCompound copy = (NBTTagCompound) nbt.copy();
            SensorNbtCodec.Result result = SensorNbtCodec.read(new NbtKeyValue(copy));
            if (result.status() != SensorNbtCodec.Status.VALID && identity != null) {
                return;
            }
            clearData();
            data = copy;
            if (result.status() == SensorNbtCodec.Status.VALID) {
                identity = result.identity();
                inertReason = null;
            } else {
                unsupportedVersion = result.status() == SensorNbtCodec.Status.UNSUPPORTED;
                inertReason = result.inertDescription();
            }
            return;
        }
        if (identity != null) {
            return;
        }
        clearData();
        if (nbt != null) {
            foreignData = nbt.copy();
        }
    }

    private void clearData() {
        identity = null;
        data = null;
        foreignData = null;
        unsupportedVersion = false;
        inertReason = SensorDescription.INACTIVE;
    }

    @Override
    protected @Nonnull NBTBase saveDataToNbt() {
        if (identity != null && !unsupportedVersion) {
            NBTTagCompound out = data != null ? (NBTTagCompound) data.copy() : new NBTTagCompound();
            SensorNbtCodec.write(identity, new NbtKeyValue(out));
            return out;
        }
        if (foreignData != null) {
            return foreignData.copy();
        }
        return data != null ? data.copy() : new NBTTagCompound();
    }

    /**
     * §3.4: a fresh UUID and creation time, the owner from the attaching player (or the machine's owner for a
     * FakePlayer or no player), and the stack's display name as the label, so an anvil rename sets it. Server side
     * only; registration happens on the next heartbeat. Existing data is never overwritten: a cover that already
     * carries an identity, or an unsupported blob, keeps it.
     */
    @Override
    public void onPlayerAttach(EntityPlayer player, ItemStack coverItem) {
        ICoverable holder = getTile();
        if (holder == null || !holder.isServerSide() || identity != null || unsupportedVersion) {
            return;
        }
        UUID playerUuid = null;
        String playerName = null;
        if (player != null && !(player instanceof FakePlayer)) {
            playerUuid = player.getUniqueID();
            playerName = player.getCommandSenderName();
            attachingPlayer = new WeakReference<>(player);
        }
        UUID holderOwner = null;
        String holderOwnerName = null;
        if (playerUuid == null && holder instanceof IGregTechTileEntity) {
            IGregTechTileEntity machine = (IGregTechTileEntity) holder;
            holderOwner = machine.getOwnerUuid();
            if (holderOwner != null) {
                holderOwnerName = machine.getOwnerName();
            }
        }
        String stackName = coverItem != null && coverItem.hasDisplayName() ? coverItem.getDisplayName() : "";
        identity = SensorIdentity.forAttach(
            UUID.randomUUID(),
            stackName,
            playerUuid,
            playerName,
            holderOwner,
            holderOwnerName,
            GregScope.clock()
                .epochSec());
        foreignData = null;
        inertReason = null;
        holder.markDirty();
    }

    // --- lifecycle ---

    /** §3.4: an O(1) heartbeat, no probe call. GT calls this on the server only, every {@link #TICK_RATE} ticks. */
    @Override
    public void doCoverThings(byte redstone, long tickTimer) {
        ICoverable holder = getTile();
        if (identity == null || holder == null || !holder.isServerSide()) {
            return;
        }
        SensorCovers.events()
            .heartbeat(this, holder, getSide());
    }

    @Override
    public void onCoverUnload() {
        ICoverable holder = getTile();
        if (identity == null || holder == null || !holder.isServerSide()) {
            return;
        }
        SensorCovers.events()
            .unloaded(this, holder, getSide());
    }

    /** Crowbar, screwdriver, facing-change drops and purge. GT also calls this on the client, hence the guard. */
    @Override
    public void onCoverRemoval() {
        ICoverable holder = getTile();
        if (identity == null || holder == null || !holder.isServerSide()) {
            return;
        }
        SensorCovers.events()
            .detached(this, holder, getSide());
    }

    /** A survival break writes the cover into the machine's drop first, so the identity lives on in the item. */
    @Override
    public void onBaseTEDestroyed() {
        ICoverable holder = getTile();
        if (identity == null || holder == null || !holder.isServerSide()) {
            return;
        }
        SensorCovers.events()
            .destroyedIntoItem(this, holder, getSide());
    }

    /**
     * §3.4, with one reality correction. GT's only caller is {@code CoverableTileEntity.getWailaBody}
     * ({@code CoverableTileEntity.java:531-553}), the client-side WAILA tooltip, and the identity is never synced
     * ({@link #isDataNeededOnClient()} is false and {@code writeDataToByteBuf} is not overridden, so
     * {@code GTPacketSendCoverData} carries only the tick rate). A client cover therefore has no identity and would
     * render every healthy sensor with the wording reserved for a foreign or corrupt blob. Off the server side it
     * names the cover and nothing else; the real status line waits for the v0.2.1 client label sync (§1.2).
     */
    @Override
    public String getDescription() {
        ICoverable holder = getTile();
        if (holder == null || !holder.isServerSide()) {
            return SensorDescription.NEUTRAL;
        }
        return SensorDescription.text(identity, availability, inertReason);
    }

    // --- rates ---

    @Override
    public int getMinimumTickRate() {
        return TICK_RATE;
    }

    @Override
    public boolean allowsTickRateAddition() {
        return false;
    }

    // --- read-only towards the machine (§1.4, §3.4) ---

    @Override
    public boolean letsEnergyIn() {
        return true;
    }

    @Override
    public boolean letsEnergyOut() {
        return true;
    }

    @Override
    public boolean letsFluidIn(Fluid fluid) {
        return true;
    }

    @Override
    public boolean letsFluidOut(Fluid fluid) {
        return true;
    }

    @Override
    public boolean letsItemsIn(int slot) {
        return true;
    }

    @Override
    public boolean letsItemsOut(int slot) {
        return true;
    }

    @Override
    public boolean letsRedstoneGoIn() {
        return true;
    }

    @Override
    public boolean letsRedstoneGoOut() {
        return true;
    }

    @Override
    public boolean isRedstoneSensitive(long timer) {
        return false;
    }

    @Override
    public boolean manipulatesSidedRedstoneOutput() {
        return false;
    }

    @Override
    public boolean allowsCopyPasteTool() {
        return false;
    }

    @Override
    public boolean hasCoverGUI() {
        return false;
    }

    @Override
    public String toString() {
        // Not getDescription(): a debug line should show the identity even off the server side.
        return "MachineSensorCover{" + getSide()
            + ", "
            + SensorDescription.text(identity, availability, inertReason)
            + "}";
    }
}
