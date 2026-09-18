package io.github.ldogg123.gregscope.sensor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The per-player rename cooldown of design-v0.2 section 3.5 ({@code hub.renameCooldownSeconds}, default 5). [pure]
 *
 * <p>
 * Section 3.5 lists three ways to set a label and requires the cooldown for every write, so it lives next to the
 * label rules rather than inside one surface: {@code /gregscope label} (GS-111) and the Hub's label field (GS-114)
 * share one instance, owned by the registry adapter of this server run.
 *
 * <p>
 * Only <b>successful</b> writes are recorded, so a refusal (no permission, sensor not loaded, label too long) never
 * costs a player their next attempt. The console has no UUID; it is never limited. The map is bounded by the number
 * of players who have ever renamed something in this run and is dropped with the registry at server stop.
 */
public final class RenameCooldown {

    private final Map<UUID, Long> lastWriteEpochSec = new HashMap<>();

    /**
     * How many seconds {@code player} still has to wait.
     *
     * @param cooldownSeconds {@code hub.renameCooldownSeconds}; 0 or less disables the cooldown
     * @return 0 when the write may go ahead, otherwise the whole seconds left (at least 1)
     */
    public int remaining(UUID player, long nowEpochSec, int cooldownSeconds) {
        if (player == null || cooldownSeconds <= 0) {
            return 0;
        }
        Long last = lastWriteEpochSec.get(player);
        if (last == null) {
            return 0;
        }
        long elapsed = nowEpochSec - last.longValue();
        if (elapsed < 0L) {
            // The clock went backwards (section 6.2). Treat the stored stamp as "just now" rather than as a lock-out
            // that lasts until wall-clock time catches up.
            return cooldownSeconds;
        }
        long left = cooldownSeconds - elapsed;
        return left <= 0L ? 0 : (int) left;
    }

    /** Records a successful write, starting a fresh cooldown for that player. The console is not recorded. */
    public void record(UUID player, long nowEpochSec) {
        if (player != null) {
            lastWriteEpochSec.put(player, Long.valueOf(nowEpochSec));
        }
    }

    /** How many players are being tracked; for tests and for the size argument of section 7.8. */
    public int size() {
        return lastWriteEpochSec.size();
    }

    /** Forgets every recorded write. */
    public void clear() {
        lastWriteEpochSec.clear();
    }
}
