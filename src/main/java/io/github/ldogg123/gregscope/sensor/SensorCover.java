package io.github.ldogg123.gregscope.sensor;

/**
 * A GT cover that carries a GregScope sensor identity (design-v0.3 §5.1 A2, landed in v0.2 with GS-105). [pure]
 *
 * <p>
 * The v0.2 Machine Sensor implements it with kind {@link SensorKind#MACHINE}; the v0.3 flow meters will implement it
 * with their own kinds. Code that resolves a registry entry to a live cover (the §6.2 chain) checks this interface,
 * the kind and the id, so a cover of another kind at the recorded side is a {@code target_missing} strike rather than
 * a match. Placement rules deliberately do <em>not</em> use this interface: "one Machine Sensor per machine" checks
 * exactly the Machine Sensor cover class, so meters and machine sensors never block each other (A4).
 */
public interface SensorCover {

    /** The identity read from the cover's NBT, or {@code null} while the cover is inert (no or unsupported data). */
    SensorIdentity identity();

    /** One of the {@link SensorKind} values; constant for a cover class. */
    int sensorKind();

    /**
     * Replaces the identity with one carrying a fresh UUID and writes it to the holder's NBT (design-v0.2 section 4.3
     * duplicate row). The registry calls this on the server thread when the UUID this cover carries is already in use
     * somewhere else, so the original sensor keeps its history and this copy starts its own.
     */
    void rekey(SensorIdentity fresh);

    /**
     * The registry's word for this sensor ({@code live}, {@code unloaded}, {@code over cap}, ...), shown in the
     * cover's description (design-v0.2 section 3.4). Transient and never saved; {@code null} clears it.
     */
    void setAvailability(String availability);
}
