package io.github.ldogg123.gregscope.sensor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** GS-106: the cover status line (design-v0.2 §3.4 {@code getDescription}) and the attach owner rule (§3.4). */
class SensorDescriptionTest {

    private static final UUID ID = UUID.fromString("1a2b3c4d-0000-4000-8000-000000000001");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final UUID MACHINE_OWNER = UUID.fromString("00000000-0000-4000-8000-0000000000bb");

    @Test
    void idOnly() {
        SensorIdentity identity = new SensorIdentity(ID, "", null, null, 0L);
        assertEquals("GregScope sensor 1a2b3c4d", SensorDescription.text(identity, null, null));
        assertEquals("GregScope sensor 1a2b3c4d", SensorDescription.text(identity, "", "inactive"));
    }

    @Test
    void labelAndAvailability() {
        SensorIdentity identity = new SensorIdentity(ID, "Main EBF", PLAYER, "Ada", 0L);
        assertEquals("GregScope sensor 1a2b3c4d: Main EBF", SensorDescription.text(identity, null, null));
        assertEquals("GregScope sensor 1a2b3c4d: Main EBF (live)", SensorDescription.text(identity, "live", null));
    }

    @Test
    void inertShowsItsReason() {
        assertEquals("GregScope sensor (inactive)", SensorDescription.text(null, null, null));
        assertEquals("GregScope sensor (inactive)", SensorDescription.text(null, "live", ""));
        assertEquals(
            "GregScope sensor (unsupported data version)",
            SensorDescription.text(null, null, "unsupported data version"));
    }

    /**
     * GS-REV-1: the wording a cover uses where it cannot know its own state (off the server side, where the identity
     * is never synced) must not be the wording reserved for a foreign or corrupt blob.
     */
    @Test
    void theNeutralWordingClaimsNoState() {
        assertEquals("GregScope sensor", SensorDescription.NEUTRAL);
        assertNotEquals(
            SensorDescription.text(null, null, null),
            SensorDescription.NEUTRAL,
            "a healthy sensor must not read as an inert one");
    }

    @Test
    void attachPrefersThePlayerOverTheMachineOwner() {
        SensorIdentity identity = SensorIdentity
            .forAttach(ID, "Boiler", PLAYER, "Ada", MACHINE_OWNER, "Someone", 1_700_000_000L);
        assertEquals(PLAYER, identity.owner());
        assertEquals("Ada", identity.ownerName());
        assertEquals("Boiler", identity.label());
        assertEquals(1_700_000_000L, identity.createdEpochSec());
    }

    @Test
    void attachWithoutAPlayerUsesTheMachineOwner() {
        SensorIdentity identity = SensorIdentity.forAttach(ID, "", null, null, MACHINE_OWNER, "Owner", 5L);
        assertEquals(MACHINE_OWNER, identity.owner());
        assertEquals("Owner", identity.ownerName());
        assertEquals("", identity.label());
    }

    @Test
    void attachOnAnUnownedMachineIsUnowned() {
        SensorIdentity identity = SensorIdentity.forAttach(ID, null, null, null, null, "Player", 5L);
        assertNull(identity.owner());
        assertNull(identity.ownerName());
        assertEquals("", identity.label());
    }

    @Test
    void attachSanitizesTheAnvilName() {
        SensorIdentity identity = SensorIdentity
            .forAttach(ID, "  \u00A7cMain\u00A7r   EBF  ", PLAYER, "Ada", null, null, 0L);
        assertEquals("Main EBF", identity.label());
        assertEquals("GregScope sensor 1a2b3c4d: Main EBF", SensorDescription.text(identity, null, null));
    }
}
