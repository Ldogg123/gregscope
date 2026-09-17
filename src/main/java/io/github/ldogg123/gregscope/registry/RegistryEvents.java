package io.github.ldogg123.gregscope.registry;

import java.util.UUID;

import io.github.ldogg123.gregscope.history.MinuteSlot;

/**
 * What the registry state machine hands to the rest of the mod (design-v0.2 sections 4.3, 6.1, 8.4). [pure]
 *
 * <p>
 * The core decides the transitions; the sampler (GS-108) and the I/O thread (GS-109) do the work the core cannot do
 * itself: assign and release sampler buckets, queue an async history load, write a closed minute and delete an expired
 * sensor's file. {@link #NONE} is installed until they exist, so the state machine is complete and testable on its
 * own. Every call happens on the server thread, inside the registry call that caused it.
 */
public interface RegistryEvents {

    RegistryEvents NONE = new RegistryEvents() {

        @Override
        public void sensorLive(SensorEntry entry) {}

        @Override
        public void sensorInactive(SensorEntry entry) {}

        @Override
        public void minuteClosed(SensorEntry entry, MinuteSlot slot) {}

        @Override
        public void sensorExpired(UUID id, int kind) {}

        @Override
        public String toString() {
            return "RegistryEvents.NONE";
        }
    };

    /** The entry just became LIVE: give it a sampler bucket and queue its history load. */
    void sensorLive(SensorEntry entry);

    /** The entry stopped being LIVE (UNLOADED or a tombstone): release its sampler bucket. */
    void sensorInactive(SensorEntry entry);

    /** A minute was closed and stored in the entry's ring: queue the 64-byte write. */
    void minuteClosed(SensorEntry entry, MinuteSlot slot);

    /** The entry expired or was purged and is gone from the registry: delete its history file. */
    void sensorExpired(UUID id, int kind);
}
