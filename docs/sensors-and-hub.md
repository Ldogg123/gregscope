# Machine Sensors and the Telemetry Hub (GS-120)

Everything a player or a server owner needs to run GregScope v0.2: what the two blocks do, who can see what, what
the limits are, every config key and command, and what happens if you remove the mod.

GregScope is **read-only towards your factory**. It watches machines and records what it saw. It never starts,
stops, configures or feeds a machine, and it never moves an item or a fluid. (v0.3's flow meters will move things,
by design and as their whole purpose, but they are a separate device and not part of v0.2.)

---

## 1. The Machine Sensor

A **GT cover**, crafted in an assembler at MV. Put it on any face of a GT machine and GregScope starts recording
that machine.

**Where it goes.** GregScope's placement rule accepts a GT basic machine or a multiblock **controller** - the same
set of machines the probe can read - and refuses a machine that already has a Machine Sensor on any face. GT's own
rules still apply on top: a multiblock controller only takes covers on its front, and a basic machine on its main
face. You get a chat line telling you which rule refused.

**A covered face keeps working.** The sensor lets items, fluids, EU and redstone through exactly as an uncovered
face does, and the machine's GUI still opens through it. Pipes, cables and conveyors on that face are unaffected.

> **One small side effect, smaller than it first looks.** GT's `isRainExposed()` tests five faces - UP and the four
> horizontals - and a machine counts as exposed if **any** of them is uncovered and open to the sky. A cover removes
> only its own face from that test. So a sensor does **not** make a machine weatherproof: put one on a Macerator
> standing in the open and it still catches fire and explodes in a thunderstorm, because the top and the other three
> sides are still exposed. The only case where it changes anything is a machine down to its last exposed face, and
> then it is identical to putting any other GT cover there - a conveyor or a plate does the same. Nothing GregScope
> can opt out of, and nothing you could not already do.

**Identity.** Each sensor gets a UUID when it is placed, stored in the cover's own NBT. That id is what
`/gregscope` prints, what the Hub selects on and what OpenComputers returns. Because it lives in the cover:

- **Breaking the machine** and putting it back keeps the sensor's identity and history: GT writes the cover into the
  machine's drop, so the id travels inside the dropped item.
- **Taking the cover off with a crowbar or a screwdriver does not.** GT drops a plain Machine Sensor with no data on
  it, so placing that item again starts a *new* sensor with a new id, and the old one becomes a tombstone whose
  history is kept for `history.removedRetentionHours`. This is deliberate: a cover you detach is a blank part again,
  not a container of someone else's history.
- The id survives a server restart, a chunk unload and a world backup.

**Labels.** Name a sensor so you can find it: rename the *item* in an anvil before placing it, use
`/gregscope label <id> <text>`, or type in the Hub's detail panel. Labels are trimmed to 32 characters and stripped
of formatting codes. There is a short per-player cooldown (`hub.renameCooldownSeconds`) so a stuck key cannot spam
the server.

## 2. The Telemetry Hub

A plain block, crafted in an assembler at EV. Right-click it for a GUI listing every sensor **its owner** can see,
worst first, with a detail panel, a filter, paging and a 24-hour summary.

The Hub shows **the Hub owner's** scope, not the viewer's. An operator opening someone else's Hub sees what that Hub
sees and nothing more - opening a Hub is never a way to widen your own access.

**The client mod is required.** Unlike v0.1, which was server-only, v0.2 has a GUI, so every player connecting to a
server running GregScope needs GregScope installed too, at the same version. A client without it, or with a
different version, is refused at the FML handshake with a clear message.

## 3. Who can see and change what

| Action | Who |
|---|---|
| See a sensor | its owner, anyone on the owner's team, and operators |
| Open a Hub | its owner, the owner's team, and operators |
| Rename a sensor | its owner and the owner's team; set `permissions.renameRequiresOfficer` to require officer or owner rank |
| Purge a sensor's data | operators |

"Team" means a GTNHLib team. An **unowned** sensor - one placed by something that is not a player, such as a
robot - is in nobody's scope and nobody's Hub; only operators see it. "Operator" means a player on the server's ops
list at level `permissions.opLevel` or above; setting that key to `0` makes every player an operator, which is
useful on a private server and a bad idea on a public one.

## 4. What a machine is holding

Since v0.3, a sensor also reports the machine's own **buffers** — what is in its hatches, tanks and slots right
now. This is read from the machine itself, so it is the same whoever filled it: a GT pipe, an EnderIO conduit, an
AE2 stocking hatch, or you by hand.

The Hub's detail panel shows it as one line:

```
in 12% FALLING
```

A machine at 12% is unremarkable. A machine at 12% **and falling** is about to stop, and that is the difference
worth knowing. The direction comes from the last five minutes.

`/gregscope info` shows the same thing with the contents spelled out, and OpenComputers' `getSnapshot` carries it
as `inputs`, `outputs`, `inputSaturation` and friends.

### What it can and cannot tell you

| | |
|---|---|
| "Is this EBF about to run out of oxygen?" | **Yes.** That is what the line is for. |
| "Is the output backing up?" | **Yes** — output saturation climbing toward 100%. |
| "What exactly is in there?" | **Yes**, the largest four, then a rollup. |
| "How much oxygen per second does it use?" | **No.** |
| "Who supplied it?" | **No.** |

The last two are worth being clear about, because they are the obvious next question. GregScope measures **levels,
not rates**. A level that dropped by 500 L might be 500 consumed, or 1,000 consumed while 500 arrived — those are
indistinguishable from outside. Metering the flow instead was designed, prototyped and then
[deliberately abandoned](flow-meters.md): a meter can only see what passes through the meter, so in a base fed by
AE2 and conduits it would have reported confident wrong numbers.

### ME hatches

A **fluid** hatch backed by an ME network reports what the **network** holds, not the trickle buffered in the
hatch. That is the number you want, and GT computes it for us.

Two consequences:

- Such an input shows **`n/a`** rather than a percentage, because "the network" has no capacity to be a fraction
  of. `n/a` and `0%` mean different things and the GUI keeps them apart.
- **ME item busses do not report amounts yet** — they are counted, so you can see the input is ME-backed, but the
  quantity is not available without querying AE2 directly. That is a known gap, not a bug.

### What it costs

The walk is measured, not assumed: **p50 0.3 µs, p99 0.7 µs** per machine on a formed EBF, against the 1 ms/tick
budget the whole sampler shares. For scale, reading the machine's state — which GregScope already did — costs about
3.4 µs. It also never writes to your machines; the obvious GT API for this quietly does, which is why GregScope
does not use it.

## 5. Sensor lifecycle

A sensor is always in exactly one of these states, and `/gregscope list` filters on them:

| State | Meaning |
|---|---|
| `live` | the chunk is loaded and the machine is being sampled |
| `unloaded` | the chunk is not loaded. The sensor still exists; history simply records a gap |
| `over cap` | the sensor exists but is not being sampled, because a cap was reached (section 6) |
| `missing` | the chunk loaded, but the machine the sensor pointed at is gone |
| `in item` | the cover was picked up and is sitting in an inventory |
| `removed` | the sensor was destroyed |

`missing`, `in item` and `removed` are **tombstones**: the sensor is gone but its history is kept for
`history.removedRetentionHours` hours so you can still look at what happened before it vanished. An `unloaded`
sensor that stays unloaded for `history.staleExpiryDays` days expires and is deleted.

**GregScope never loads a chunk.** An unloaded sensor is left alone until something else loads its chunk. This is
deliberate and is enforced by tests: nothing GregScope does will keep your world spinning chunks you did not ask
for.

## 6. Limits

Sampling costs server time, so it is bounded at both ends.

- `limits.maxSensors` caps how many sensors are sampled server-wide, and `limits.maxSensorsPerTeam` per team.
  Sensors past a cap go to `over cap`: they keep their identity and their history, they just stop being sampled
  until room appears. You get a chat line when you place one past the cap.
- `sampling.tickBudgetMicros` is a hard per-tick budget. If sampling would exceed it, the rest of the cycle is
  deferred to the next tick rather than making the tick longer. `/gregscope stats` shows the p50, p99 and worst
  cycle so you can see the real cost.
- `limits.maxOpenHubViews` caps how many Hub GUIs can be open at once across the server.

## 7. Configuration

`config/gregscope.cfg`, read once at startup. **Changing any of these needs a server restart.**

### `sampling`

| Key | Default | Range | Meaning |
|---|---|---|---|
| `sampling.enabled` | `true` | | Sample LIVE sensors at all. |
| `sampling.intervalTicks` | `20` | one of 20, 40, 60, 100 | Server ticks between two samples of one sensor. Only these four values are allowed, because a minute must divide evenly into samples. |
| `sampling.tickBudgetMicros` | `1000` | 100-5000 | Hard sampling budget per server tick, in microseconds. |

### `limits`

| Key | Default | Range | Meaning |
|---|---|---|---|
| `limits.maxSensors` | `256` | 16-1024 | Sensors sampled at most, server-wide. Extra sensors are `over cap`. |
| `limits.maxSensorsPerTeam` | `64` | 0-1024 | Sensors sampled at most per owner team. |
| `limits.maxOpenHubViews` | `32` | 1-256 | Telemetry Hub GUIs open at the same time, server-wide. |

### `history`

| Key | Default | Range | Meaning |
|---|---|---|---|
| `history.persist` | `true` | | Write minute history files under `<world>/gregscope/history/`. The registry is saved either way, so labels and identities survive regardless. |
| `history.removedRetentionHours` | `24` | 1-168 | Hours the history of a removed sensor is kept. |
| `history.staleExpiryDays` | `30` | 1-365 | Days an unloaded sensor is kept before it expires. |
| `history.ioQueueCapacity` | `4096` | 256-65536 | Pending disk writes. Further writes are dropped and counted rather than blocking the server thread; `/gregscope stats` reports the count, and a number above zero means the disk is not keeping up. |

### `permissions`

| Key | Default | Range | Meaning |
|---|---|---|---|
| `permissions.opLevel` | `2` | 0-4 | Permission level that counts as operator for GregScope. 1-4: players on the ops list with at least this level. **0: every player.** |
| `permissions.renameRequiresOfficer` | `false` | | When true, a team member must be an officer or the team owner to rename a sensor; when false any member may. |

### `hub`

| Key | Default | Range | Meaning |
|---|---|---|---|
| `hub.renameCooldownSeconds` | `5` | 0-300 | Seconds a player waits between two label changes. |

## 8. Commands

`/gregscope` is available to everyone; each subcommand checks its own permission, and you only ever see sensors you
are allowed to see.

```
/gregscope stats                                  sampling, I/O and size numbers
/gregscope list [live|unloaded|missing|tombstones|stale] [page]
/gregscope info <id>                              the full record and the 5 min / 24 h summaries
/gregscope label <id> [text]                      rename a loaded sensor; no text clears it
/gregscope purge <id>                             operators
/gregscope purge --tombstones | --stale           operators
```

`<id>` is a sensor UUID. You only need enough of it to be unambiguous (at least eight characters); dashes and case
are ignored. If a prefix matches more than one sensor you get the candidates back rather than a guess. A sensor you
are not allowed to see gives exactly the same "not found" answer as one that does not exist, so the command cannot
be used to probe for other players' machines.

## 9. Removing things

**Removing a sensor** keeps its history for `history.removedRetentionHours`, then deletes it. `/gregscope purge`
deletes it at once.

**Removing the mod** is safe, and the case is tested. Load the world once more without GregScope and Minecraft will
ask whether to continue without the missing block and item entries; say yes.

- Your **machines are untouched**. GregScope never changed them.
- Every Machine Sensor cover disappears with the mod, the way any cover from a removed mod does.
- `<world>/gregscope/` is left on disk exactly as it was. Delete it by hand if you want the space back, or keep it:
  reinstalling GregScope picks the registry and the history straight back up. The file format is documented in
  [history-format-v1.md](history-format-v1.md) so the data is readable without the mod at all.

## 10. Where to look next

- [opencomputers.md](opencomputers.md) - reading sensors and Hubs from OpenComputers, with example scripts.
- [history-format-v1.md](history-format-v1.md) - the byte layout of the history files.
- [metrics-model.md](metrics-model.md) - the metric names and labels the planned Prometheus exporter will use.
- [testing.md](testing.md) - how all of the above is verified.
