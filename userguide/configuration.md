# Configuration

`config/gregscope.cfg`, read once at startup.

!!! warning "Changing any of these needs a server restart"
    The config is read in pre-init and not re-read.

## `sampling`

| Key | Default | Range | Meaning |
|---|---|---|---|
| `sampling.enabled` | `true` | | Sample LIVE sensors at all. |
| `sampling.intervalTicks` | `20` | 20, 40, 60 or 100 | Server ticks between two samples of one sensor. Only these four, because a minute has to divide evenly into samples. |
| `sampling.tickBudgetMicros` | `1000` | 100–5000 | Hard sampling budget per server tick, in microseconds. |

`tickBudgetMicros` is a **hard** cap. If a sampling cycle would exceed it, the rest is deferred to the next tick —
the tick is never made longer to finish sampling. `/gregscope stats` shows what it actually costs.

## `limits`

| Key | Default | Range | Meaning |
|---|---|---|---|
| `limits.maxSensors` | `256` | 16–1024 | Sensors sampled at most, server-wide. |
| `limits.maxSensorsPerTeam` | `64` | 0–1024 | Sensors sampled at most per owner team. |
| `limits.maxOpenHubViews` | `32` | 1–256 | Telemetry Hub screens open at once, server-wide. |

Sensors past a cap go to `over cap`: they keep their identity and history, they just stop being sampled until room
appears. You get a chat line when you place one past the cap.

## `history`

| Key | Default | Range | Meaning |
|---|---|---|---|
| `history.persist` | `true` | | Write minute history under `<world>/gregscope/history/`. The registry is saved either way, so labels and identities survive regardless. |
| `history.removedRetentionHours` | `24` | 1–168 | Hours a removed sensor's history is kept. |
| `history.staleExpiryDays` | `30` | 1–365 | Days an unloaded sensor is kept before it expires. |
| `history.ioQueueCapacity` | `4096` | 256–65536 | Pending disk writes. Further writes are dropped and counted rather than blocking the server thread. |

A non-zero dropped-write count in `/gregscope stats` means the disk is not keeping up — raise the queue, or turn
`history.persist` off if you do not need the history.

## `permissions`

| Key | Default | Range | Meaning |
|---|---|---|---|
| `permissions.opLevel` | `2` | 0–4 | Permission level that counts as operator for GregScope. |
| `permissions.renameRequiresOfficer` | `false` | | Require officer or team-owner rank to rename a team's sensors. |

!!! danger "`opLevel = 0` makes every player an operator"
    Which means every player can see every sensor on the server and purge anyone's data. Fine on a private
    server, a bad idea on a public one.

## `hub`

| Key | Default | Range | Meaning |
|---|---|---|---|
| `hub.renameCooldownSeconds` | `5` | 0–300 | Seconds a player waits between two label changes. |
