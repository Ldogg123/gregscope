package io.github.ldogg123.gregscope.sensor;

import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.tileentity.ICoverable;

/**
 * What a sensor cover reports to the registry (design-v0.2 §3.4, §4.3). GS-106 raises the events; GS-107's
 * {@code SensorRegistry} implements the interface and installs itself with {@link SensorCovers#setEvents}. Until then
 * {@link #NONE} is installed and every call is a no-op, so the cover behaves the same with or without a registry.
 *
 * <p>
 * Every method runs on the server thread, from the holder tile's own tick or unload, and only for a cover whose
 * {@link SensorCover#identity()} is non-null. The holder and the cover side are passed separately so the registry can
 * key its reverse index on {@code (position, side)} (design-v0.3 §5.1 A1) without asking the cover for its tile again;
 * the cover type is {@link SensorCover}, not the Machine Sensor class, so the v0.3 flow meters raise the same events.
 * The holder is alive when these run, but its chunk may already be leaving: an implementation must not load chunks or
 * touch other positions.
 */
public interface SensorEvents {

    /** Installed until GS-107 replaces it. */
    SensorEvents NONE = new SensorEvents() {

        @Override
        public void heartbeat(SensorCover cover, ICoverable holder, ForgeDirection side) {}

        @Override
        public void unloaded(SensorCover cover, ICoverable holder, ForgeDirection side) {}

        @Override
        public void detached(SensorCover cover, ICoverable holder, ForgeDirection side) {}

        @Override
        public void destroyedIntoItem(SensorCover cover, ICoverable holder, ForgeDirection side) {}

        @Override
        public String toString() {
            return "SensorEvents.NONE";
        }
    };

    /** {@code doCoverThings}, once per cover tick rate (20 ticks): the O(1) registry heartbeat. */
    void heartbeat(SensorCover cover, ICoverable holder, ForgeDirection side);

    /** The holder tile is unloading with the cover still on it: the entry becomes UNLOADED. */
    void unloaded(SensorCover cover, ICoverable holder, ForgeDirection side);

    /** The cover was taken off the machine (crowbar, facing change, purge): removal cause DETACHED. */
    void detached(SensorCover cover, ICoverable holder, ForgeDirection side);

    /** The machine was broken in survival and the cover went into its drop NBT: removal cause IN_ITEM. */
    void destroyedIntoItem(SensorCover cover, ICoverable holder, ForgeDirection side);
}
