# Machine Sensors

A Machine Sensor is a **GT cover**. It goes on a machine, and from then on GregScope records that machine.

## Where it can go

Any **GT basic machine** or **multiblock controller** — the machines GregScope can actually read. One sensor per
machine; a second is refused.

GT's own rules still apply on top: a multiblock controller only takes covers on its front face, and a basic machine
on its main face. If a placement is refused you get a chat line saying which rule refused it.

## A covered face keeps working

The sensor passes items, fluids, EU and redstone exactly as an uncovered face does, and the machine's GUI still
opens through it. Pipes, cables and conveyors on that face are unaffected. This is enforced by tests that move real
resources through a covered face.

??? note "One small side effect, smaller than it first looks"
    GT excludes a covered face from its rain checks, and those gate rain fire and thunderstorm explosions.

    This does **not** weatherproof a machine. GT tests five faces — the top and the four sides — and a machine
    counts as exposed if **any** of them is open to the sky. A sensor removes only its own face from that test, so
    a machine standing in the open still burns in a storm. It changes anything only on a machine already down to
    its last exposed face, and there it behaves exactly like any other GT cover.

## Identity: what survives what

Each sensor gets a UUID when placed, stored in the cover's own data. That id is what `/gregscope` prints, what the
Hub selects on, and what OpenComputers returns.

| What you do | What happens |
|---|---|
| Break the **machine** and put it back | **Keeps** its identity and history — GT writes the cover into the machine's drop, so the id travels in the item. |
| Take the cover off with a **crowbar or screwdriver** | You get a **blank** Machine Sensor. Placing it again starts a *new* sensor. |
| Restart the server | Keeps everything. |
| Let the chunk unload | Keeps everything; the history records a gap. |

The crowbar case is deliberate: a cover you detach is a blank part again, not a container carrying someone else's
history. The old sensor becomes a tombstone and its history is kept for a while (see
[`history.removedRetentionHours`](configuration.md#history)).

## Labels

Give a sensor a name so you can find it:

- Rename the **item** in an anvil before placing it.
- `/gregscope label <id> <text>`.
- Type in the Hub's detail panel.

Labels are trimmed to 32 characters and stripped of formatting codes. There is a short per-player cooldown so a
stuck key cannot spam the server.

## Lifecycle

A sensor is always in exactly one of these states, and `/gregscope list` filters on them:

| State | Meaning |
|---|---|
| `live` | The chunk is loaded and the machine is being sampled. |
| `unloaded` | The chunk is not loaded. The sensor still exists; history records a gap. |
| `over cap` | It exists but is not being sampled, because a [cap](configuration.md#limits) was reached. |
| `missing` | The chunk loaded, but the machine the sensor pointed at is gone. |
| `in item` | The cover was picked up and is sitting in an inventory. |
| `removed` | The sensor was destroyed. |

`missing`, `in item` and `removed` are **tombstones**: the sensor is gone but its history is kept so you can still
see what happened before it vanished.

!!! info "GregScope never loads a chunk"
    An unloaded sensor is left alone until something else loads its chunk. This is deliberate and enforced by
    tests — nothing GregScope does will keep your world spinning chunks you did not ask for.
