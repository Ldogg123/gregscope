package io.github.ldogg123.gregscope.sensor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** GS-111 (design-v0.2 §3.5): the per-player rename cooldown both label surfaces share. */
class RenameCooldownTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    @Test
    void anUnknownPlayerMayWriteAtOnce() {
        RenameCooldown cooldown = new RenameCooldown();
        assertEquals(0, cooldown.remaining(ALICE, 1_000L, 5));
        assertEquals(0, cooldown.size());
    }

    @Test
    void aWriteStartsTheCooldownForThatPlayerOnly() {
        RenameCooldown cooldown = new RenameCooldown();
        cooldown.record(ALICE, 1_000L);
        assertEquals(5, cooldown.remaining(ALICE, 1_000L, 5));
        assertEquals(3, cooldown.remaining(ALICE, 1_002L, 5));
        assertEquals(0, cooldown.remaining(ALICE, 1_005L, 5));
        assertEquals(0, cooldown.remaining(ALICE, 1_600L, 5));
        assertEquals(0, cooldown.remaining(BOB, 1_000L, 5), "Bob has not written anything");
        assertEquals(1, cooldown.size());
    }

    @Test
    void theConsoleAndAZeroCooldownAreNeverLimited() {
        RenameCooldown cooldown = new RenameCooldown();
        cooldown.record(null, 1_000L);
        assertEquals(0, cooldown.size(), "the console is not tracked");
        assertEquals(0, cooldown.remaining(null, 1_000L, 5));
        cooldown.record(ALICE, 1_000L);
        assertEquals(0, cooldown.remaining(ALICE, 1_000L, 0), "renameCooldownSeconds=0 disables it");
        assertEquals(0, cooldown.remaining(ALICE, 1_000L, -1));
    }

    /** Design-v0.2 §6.2: the wall clock can go backwards; that must not lock a player out until it catches up. */
    @Test
    void aClockThatWentBackwardsCostsOneCooldownAtMost() {
        RenameCooldown cooldown = new RenameCooldown();
        cooldown.record(ALICE, 10_000L);
        assertEquals(5, cooldown.remaining(ALICE, 1_000L, 5));
        cooldown.record(ALICE, 1_000L);
        assertEquals(0, cooldown.remaining(ALICE, 1_005L, 5));
    }

    @Test
    void clearForgetsEveryone() {
        RenameCooldown cooldown = new RenameCooldown();
        cooldown.record(ALICE, 1_000L);
        cooldown.record(BOB, 1_000L);
        assertEquals(2, cooldown.size());
        cooldown.clear();
        assertEquals(0, cooldown.size());
        assertEquals(0, cooldown.remaining(ALICE, 1_000L, 5));
    }
}
