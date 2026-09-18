# OpenComputers integration

GregScope adds read-only callbacks to GregTech machines connected to an OpenComputers **Adapter**.

- `getSnapshot()` (v0.1) returns the normalized [snapshot schema v1](snapshot-schema-v1.md) table.
- `getSensor()` and `getSensorHistory(...)` (v0.2) return the Machine Sensor cover's record and its recorded history.
  They answer only for a machine that carries a Machine Sensor cover.
- An Adapter against a **Telemetry Hub** (v0.2) carries a separate component, `gregscope_hub`, which reads every
  sensor in that Hub's scope wherever it is: `getInfo`, `listSensors`, `getLatest` and `getSensorHistory`.

Verified against OpenComputers **1.12.61-GTNH** and GT5-Unofficial **5.09.54.133** (GTNH 2.9.0-beta-3).

**v0.1 scripts keep working unchanged.** `getSnapshot` still takes no arguments and still returns the same schema v1
table, the component names and their priority are the same, and upgrading from v0.1 to v0.2 does **not** change any
Adapter component address.

## Install

1. Put the GregScope jar in `mods/` (on the server for multiplayer, or in the client for single player). GregScope
   requires `gregtech`, `OpenComputers`, `modularui2` and `gtnhlib`.
2. v0.1 added nothing to the world. **v0.2 adds two things the OpenComputers surface needs:** the Machine Sensor
   cover (for `getSensor` and `getSensorHistory`) and the Telemetry Hub block (for `gregscope_hub`). `getSnapshot`
   still needs neither.
3. Since v0.2 a client must run the same GregScope version as the server (the mod has blocks, items and a GUI now);
   the OpenComputers integration itself still runs only on the server and sends nothing to clients.

## Adapter placement

Place an OpenComputers Adapter directly next to the machine, and connect the Adapter to a computer (cable, or the
computer case touching it).

- **Basic machines** (any GT electric or steam single-block machine, `MTEBasicMachine`): touch the machine block.
- **Multiblocks** (`MTEMultiBlockBase`): touch the **controller**. Hatches, buses, casings and other parts are not
  supported. An Adapter next to a hatch still shows OC's own `gt_energycontainer` component, but without
  `getSnapshot`.

Only server-side machine state is read. Nothing is changed, and GregScope does no work until a script calls
`getSnapshot()`.

## Component name and discovery

An Adapter merges the environments of every OpenComputers driver that matches a block into **one** component, and
names it after the environment with the highest priority. GregScope's environment has the preferred name
`gt_machine` and priority **-10**, which is below all of OpenComputers' GregTech drivers:

| OC driver | matches | component name | priority |
|---|---|---|---|
| energy container | every GT machine | `gt_energycontainer` | -1 |
| Lapotronic Supercapacitor | LSC controller | `lsc` | 0 |
| BEC storage / IO node / diode | BEC multiblocks | `bec_storage`, `bec_io_node`, `bec_diode` | 10 |
| **GregScope** | GT basic machines and multiblock controllers | `gt_machine` | -10 |

The Horizon-QA game tests check these names in-game on a lone LSC controller and on each BEC controller type (storage,
IO node, diode): the component keeps its OC name and still has `getSnapshot`.

Consequences:

- In a normal GTNH pack, OC's energy-container driver matches every GT machine. A machine therefore keeps its
  existing component name (`gt_energycontainer`, `lsc`, `bec_*`) and simply **gains** `getSnapshot()`. Existing
  callbacks such as `getStoredEU()` are unchanged, and existing scripts keep working.
- `gt_machine` only appears if OpenComputers' own GregTech integration is disabled (the OC config `modBlacklist`
  contains `gregtech`).
- Do **not** rely on `component.gt_machine`. Discover machines by callback instead:

```lua
local component = require("component")

for address in component.list() do
  local proxy = component.proxy(address)
  if proxy.getSnapshot then
    -- a GT machine GregScope can read
  end
end
```

This intentionally differs from the original [handoff](handoff.md), which proposed priority `10` and
`component.gt_machine`. That would have renamed every Adapter-attached GT machine and broken existing
`gt_energycontainer`, `lsc` and `bec_*` scripts.

## Component addresses change once on install and removal

OpenComputers discards an Adapter component's saved node data when the set of environment classes behind it changes.
Installing **or** removing GregScope therefore gives every Adapter-attached GT machine a **new component address**
once. Scripts that hardcode addresses must look them up again. Discovery by callback (above) is unaffected.

**Upgrading GregScope does not.** The set of environment classes is what OpenComputers keys the saved address by, not
the list of callbacks on them, so adding `getSensor` and `getSensorHistory` in v0.2 leaves every address in place. A
Horizon-QA test (`OcMachineSensorTests.addressStableAcrossChunkReload`) asserts that on a running server: it reads a
merged component's address, really unloads and reloads the chunk of the machine and its Adapter, and checks that the
address, the component name and all three callbacks come back the same.

## Callback contract

```
getSnapshot() -> table
getSnapshot() -> nil, "machine unavailable"
```

- Takes no arguments and is read-only. It runs synchronously on the server thread (it is not a direct call), so each
  call takes at least one tick.
- On success it returns one table with the [schema v1](snapshot-schema-v1.md) keys. Optional keys are **absent** when
  not applicable, so always check for presence (`if snap.euPerTick then ... end`).
- `warnings` is a Lua sequence of strings (possibly empty).
- `statusText`, `shutdownReasonText` and `recipeCheckResultText` are for humans. Automate against
  `state`, `statusId`, `shutdownReasonId` and `recipeCheckResultId`.

### Soft error

If there is no supported GT machine at the component's position (the machine was broken or replaced by another block,
its chunk is unloaded, or the block there is not a GT basic machine or multiblock controller), the call returns
`nil, "machine unavailable"` instead of raising an error:

```lua
local snap, err = proxy.getSnapshot()
if not snap then
  print("unavailable: " .. tostring(err))
end
```

Apart from an unloaded chunk, a script rarely sees the soft error on a current component: when the machine next to an
Adapter is broken or replaced by another block, OpenComputers removes the component at once (see
[Position binding](#position-binding)).

### Position binding

`getSnapshot()` is bound to the **position next to the Adapter**, not to a particular machine object. Each call looks
up whatever is at that position now:

- If the machine's tile entity was replaced, for example because the machine's chunk was unloaded and loaded again,
  the call reads the new one.
- If a **different** supported GT machine is at that position now, the call reports that machine. Check `metaId` or
  `metaName` if a script must be sure which machine it is reading. In practice OpenComputers replaces the component
  when the machine next to the Adapter is replaced (see below), so this matters for components OpenComputers keeps,
  such as across a reload of only the machine's chunk. The tests exercise the different-machine case only by calling
  component objects OpenComputers had already removed directly from Java.
- Anything else there (air, another block, a hatch or casing, an unloaded chunk) gives `nil, "machine unavailable"`.

What OpenComputers does with the component itself when the block changes (observed in the `AdapterBindingTests` game
tests with a real Adapter and LV macerator, electric furnace and compressor; blocks are broken and replaced with
`World.setBlock` and GT's own item placement, not by a player):

- **Machine broken:** the Adapter removes the component in the same tick.
- **Different GT machine placed:** right after placement the Adapter exposes a component with only OpenComputers'
  energy callbacks (`getStoredEU` and so on, no `getSnapshot`); one tick later it is replaced by a component with
  `getSnapshot` for the new machine, with a **new address**. A script that discovered machines just before
  should run discovery again a tick later. Any address stored during that tick is already dead.
  **Why:** GT's placement notifies the Adapter while the new tile entity has no machine inside it yet.
  OpenComputers' energy driver matches that empty GT tile entity by type, but GregScope's driver only matches once a
  supported machine is present, so the Adapter builds a component from the energy driver alone. On GT's first tick
  the Adapter is notified again, the set of matching drivers now includes GregScope's, and OpenComputers rebuilds the
  component under a new address. So the energy-only tick and the extra address change come from **GregScope's driver
  matching rule**, not from an OpenComputers or GT defect. `AdapterBindingTests` checks both halves: the empty GT tile
  entity gets energy callbacks and no GregScope match, and the energy-only component is thrown away for the new one.
  It could be changed by letting GregScope's driver match any GT tile entity (the environment already accepts one).
  Trade-offs, from the OpenComputers source and not tested: hatches and casings would also get a `getSnapshot` that
  always returns `nil, "machine unavailable"`; and a GT machine swapped for another without an air-block update in
  between would leave the driver set unchanged, so OpenComputers would keep the old component, whose energy callbacks
  would read the removed tile entity (as in the cross-chunk case below). The current rule is kept for now.
- **Replaced by a non-GT block:** the component is removed.
- **Adapter broken:** nothing throws, and the component is gone with the Adapter. A new Adapter placed next to the
  machine gets a component with the same name and a **new address**.

The tests check the components reachable in the Adapter's network, not the `component_added`/`component_removed`
signals a running computer receives.

### Numbers

- On a Lua 5.3 CPU (OpenComputers' default architecture), integer-valued keys such as `energyStored`,
  `energyCapacity`, `euPerTick` and `recipesCompleted` arrive as exact 64-bit integers
  (`math.type(v) == "integer"`), even for the huge values of storage multiblocks. Print them with `tostring(v)` or
  `%d`; formatting them with `%f` converts them to floats and rounds them.
- On a Lua 5.2 or LuaJ CPU, all numbers are IEEE doubles, so integer values above 2^53 (9007199254740992) lose
  precision. Smaller values are exact.
- `progress` and `efficiency` are fractions (`efficiency` can exceed `1.0`).
- `euPerTick` is positive for consumption and negative for generation.

### Key order

GregScope builds the keys in a fixed order, but OpenComputers converts the map into a Lua table, and `pairs()` order is
unspecified. Sort keys yourself when printing or comparing snapshots.

## Machine Sensor callbacks (v0.2)

A machine that carries a Machine Sensor cover gains two more callbacks on the **same** merged
component as `getSnapshot`. Both are read-only, both run on the server thread (they are not direct calls), and both
answer `nil, "no sensor"` when the machine carries no sensor on any of its six faces. A machine with sensors on more
than one face answers about the LIVE one, and about the lowest `ForgeDirection` side when several are equal.

There is no permission check: a computer that can reach this component is wired to an Adapter touching the machine
itself, which is the same physical access `getSnapshot` already grants. The Telemetry Hub component (GS-116)
is the one that answers about sensors elsewhere, and that one is scoped by the Hub's owner.

### `getSensor()`

```
getSensor() -> table
getSensor() -> nil, "no sensor"
```

The sensor record, `sensorRecordVersion = 1`:

| key | type | notes |
|---|---|---|
| `sensorRecordVersion` | number | `1` |
| `id` | string | the sensor UUID |
| `shortId` | string | the first 8 hex digits of `id`, which is what the GUI and `/gregscope` show |
| `label` | string | the player-set label, `""` when there is none |
| `displayName` | string | the label, else the machine name plus `" #" .. shortId` |
| `owner` | string | owner UUID; **absent** for an unowned sensor |
| `ownerName` | string | cached owner name; **absent** for an unowned sensor |
| `availability` | string | `live`, `unloaded`, `over_cap`, `missing`, `in_item` or `removed` |
| `availabilitySince` | number | epoch seconds since the sensor has had this availability |
| `dimension`, `x`, `y`, `z` | number | the machine's position |
| `side` | string | the covered face: `down`, `up`, `north`, `south`, `west`, `east` |
| `kind` | string | `machine` in v0.2 |
| `metaName`, `metaId`, `machineName` | string / number / string | GT's identity of the machine |
| `state` | string | the last sampled machine state, or `unavailable` when the sensor never produced one |
| `statusId` | string | the matching status, or `machine_unavailable` |
| `lastSeen` | number | epoch seconds of the last heartbeat; **absent** when there is none |
| `lastSample` | number | epoch seconds of the last sample; **absent** when there is none |
| `ageSeconds` | number | how old `state` / `statusId` are; **absent** when there is no sample |
| `historyLoaded` | boolean | false while the minute history is still being read from disk |

`state` and `statusId` are the **last known** ones at any availability, with `ageSeconds` as the qualifier - the same
rule the Telemetry Hub's detail pane follows, so a computer and the GUI never disagree about a machine. Only a sensor
that produced no sample at all reads `unavailable` / `machine_unavailable`.

### `getSensorHistory(resolution [, count [, before]])`

```
getSensorHistory("minute")            -> table
getSensorHistory("second", 120)       -> table
getSensorHistory("minute", 60, from)  -> table   -- page one window further back
getSensorHistory(...)                 -> nil, "no sensor" | "bad resolution" | "history loading"
```

- `resolution` is `"second"` or `"minute"`. Any other **string**, and no argument at all, is
  `nil, "bad resolution"`. An argument of the wrong Lua type is not: OpenComputers checks the type before the
  callback sees it and raises the usual `bad argument #1 (string expected, got number)` Lua error, exactly as it does
  for every other OC component. The same holds for a non-number `count` or `before`. Nothing is read or written when
  that happens.
- `count` defaults to 60 and is **clamped**, never refused: 1..300 seconds, 1..240 minutes.
- `before` is an exclusive epoch second and defaults to now. The window is `[before - count * step, before)`, oldest
  row first. Minute windows are aligned to whole minutes, so the minute that is still open is never a row.
- To page back, pass the returned `from` as the next `before`.
- `nil, "history loading"` happens only for `"minute"`, while the sensor's 24 hours are still being read from disk.

```
{ historyVersion = 1, id = "...", resolution = "minute", step = 60, from = ..., to = ...,
  rows = { ... },
  gaps = { { from = ..., to = ..., reason = "server_offline" }, ... } }
```

**Minute rows** carry `t` (the minute's first epoch second), `samples`, `expected`, `coverage`, `lastState`,
`stateSeconds` (a table of state id to sample count), `maintenanceMax` and `serverTicks`, plus `euPerTickAvg`,
`euPerTickMin`, `euPerTickMax`, `energyStored`, `recipesCompleted` and `gaps` when that minute has them.
`stateSeconds` holds the observed **sample** counts, which is one per second at the shipped sampling interval; scale
by `60 / expected` for a coarser interval.

**Second rows** carry `t`, `state`, `active`, `allowedToWork`, `wasShutdown`, `progress` and `maintenanceIssues`, plus
`formed`, `euPerTick` and `energyStored` when the sample has them.

**Gaps are never zero-filled.** A minute or second with no observation is left out of `rows` and appears in `gaps`
instead, with the reason the server recorded (`chunk_unloaded`, `dimension_unloaded`, `target_missing`,
`sampling_skipped`, `probe_error`, `sensor_removed`) or the reason a reader derives for a stretch nobody recorded
(`server_offline` when no server run covers it, otherwise `chunk_unloaded` / `dimension_unloaded` if the registry had
the sensor unloaded, otherwise `unknown`). Adjacent ranges with the same reason are merged. A partially observed
minute is a row, not a gap; its own `gaps` list says what it missed.

**Absent values are missing keys**, exactly as in schema v1, so `rows` and `gaps` are always dense Lua sequences and
`ipairs` never stops early.

```lua
local sensor, err = proxy.getSensor()
if not sensor then print("no sensor: " .. tostring(err)) return end

local hist = assert(proxy.getSensorHistory("minute", 60))
for _, row in ipairs(hist.rows) do
  print(row.t, row.lastState, row.coverage, row.euPerTickAvg or "-")
end
for _, gap in ipairs(hist.gaps) do
  print("gap", gap.from, gap.to, gap.reason)
end
```

## Telemetry Hub component `gregscope_hub` (v0.2)

Put an OpenComputers **Adapter against a Telemetry Hub** and the Adapter carries a component named `gregscope_hub`
(`component.gregscope_hub` in OpenOS). It is the only way to read a sensor that is *not* next to the computer.

**What a computer may see is the Hub's scope, not the caller's** (design-v0.2 section 5): the Hub's owner's sensors
and the sensors of everyone in the Hub owner's team, across every dimension. There is no viewer and no permission
check beyond reaching the Hub: whoever can touch the block can read what the block shows, exactly as whoever can open
its GUI sees the same rows. A Hub placed by a machine (a fake player) is **unowned** and shows nothing at all. An id
that belongs to a sensor outside the scope is answered exactly like an id that does not exist, so a computer cannot
use these callbacks to find out that a sensor exists.

**None of the four callbacks is a direct call**; every one runs on the server thread and costs the computer one tick.
That is a deliberate deviation from the design, which marks three of them direct: the scope filter has to ask GTNHLib
for team membership and has to read the Hub's tile entity, and neither is safe to touch from a computer thread. The
data itself is still the immutable telemetry frame, which is republished once per sampling interval, so what a
computer reads is at most one interval old.

Everything here is **read-only**. There is no label writing and no purge over OpenComputers; use the Hub GUI or
`/gregscope`.

### `getInfo()`

```
getInfo() -> table
```

| key | type | notes |
|---|---|---|
| `apiVersion` | number | `2` in v0.2 (v0.1 was 1) |
| `schemaVersion` | number | `1`, the snapshot schema `getLatest` returns |
| `historyVersion` | number | `1` |
| `sensorRecordVersion` | number | `1` |
| `gregscopeVersion` | string | the mod version |
| `owner` | string | the Hub owner's UUID; **absent** for an unowned Hub |
| `ownerName` | string | the cached owner name; absent when there is none |
| `visible` | number | sensors in this Hub's scope |
| `live` | number | how many of them are LIVE |
| `maxSensors` | number | the server's `limits.maxSensors` |
| `intervalTicks` | number | the sampling interval |
| `secondsCapacity` | number | `300`, the second ring |
| `minutesCapacity` | number | `1440`, the minute ring (24 h) |
| `frameSequence` | number | the telemetry frame's sequence; `0` before the first one is published |
| `frameAgeSeconds` | number | how old that frame is, never negative and `0` when none was published |

### `listSensors([offset [, limit]])`

```
listSensors()          -> table   -- offset 0, limit 32
listSensors(64, 64)    -> table
```

```
{ total = 23, offset = 64, sensors = { <record>, <record>, ... } }
```

- `total` counts the whole scope, not the page.
- `limit` defaults to 32 and is **clamped** into 1..64, never refused. `offset` below 0 becomes 0; past the end it is
  echoed back with an empty `sensors`.
- The order is the sensor UUID order, so paging is stable between calls even while sensors come and go.
- Each entry is the same [sensor record](#getsensor) (`sensorRecordVersion = 1`) the machine component returns.

### `getLatest(idOrPrefix)`

```
getLatest("3fa2c1d0")                              -> record, snapshot
getLatest("3fa2c1d0-...-...")                      -> record, nil     -- no snapshot yet
getLatest(...)                                     -> nil, "sensor not found" | "ambiguous id"
```

- The argument is a sensor UUID or a prefix of **at least eight characters**, matched with dashes removed and
  case-insensitively, exactly as `/gregscope info` matches one. A shorter **string**, and no argument at all, is
  `nil, "sensor not found"`, because it is not an id this callback accepts; a prefix that matches two sensors in scope
  is `nil, "ambiguous id"`. An argument of the wrong Lua type raises OpenComputers' own
  `bad argument #1 (string expected, got number)` instead, as it does for every OC component - worth knowing because
  the two `getSensorHistory` callbacks take their arguments in a different order (the machine one starts with
  `resolution`, this one with `idOrPrefix`).
- The first value is the sensor record, the second the **exact schema v1 snapshot map** `getSnapshot` would return for
  that machine, or `nil` when the sensor has no snapshot at all (never sampled, or not a machine sensor).

### `getSensorHistory(idOrPrefix, resolution [, count [, before]])`

The same table and the same rules as the [machine component's `getSensorHistory`](#getsensorhistoryresolution--count--before),
with the sensor named by the first argument:

```
getSensorHistory("3fa2c1d0", "minute", 60)        -> table
getSensorHistory(...)  -> nil, "bad resolution" | "sensor not found" | "ambiguous id" | "history loading"
```

The resolution is checked first, so a bad one is reported even for an id that does not exist. A sensor in scope that
holds no history at all (a removed sensor waiting to be purged) answers `nil, "sensor not found"`. As above, the soft
errors are about string values: a wrong-typed argument is an ordinary OpenComputers `bad argument #N` Lua error.

```lua
local hub = component.gregscope_hub
local info = hub.getInfo()
print(info.visible .. " sensors, " .. info.live .. " live")

local page = hub.listSensors(0, 64)
for _, record in ipairs(page.sensors) do
  if record.availability ~= "live" then
    print(record.shortId, record.availability, record.displayName)
  end
end
```

## Reload and restart

GregScope keeps no saved data. Everything a snapshot reports is read again from GT on each call, so after a chunk
reload or a server restart it reflects whatever GT restored from its own save data.

- **While the machine's chunk is unloaded**, `getSnapshot()` on a component obtained earlier returns
  `nil, "machine unavailable"`. It never loads the chunk.
- **After the chunk loads again**, an Adapter in the same chunk re-attaches its drivers within a tick or two. The
  merged component keeps its **address** and name, because OpenComputers restores it from the Adapter's saved data as
  long as the set of drivers behind it is unchanged (see the section on addresses above).
- **Adapter in a different chunk from the machine.** If only the machine's chunk unloads and loads again, the
  Adapter is not notified and keeps its component: same object, same address. `getSnapshot()` on it returns
  `nil, "machine unavailable"` while the machine's chunk is unloaded and reads the reloaded machine as soon as the
  chunk is loaded again (same tick), because it is [bound to the position](#position-binding). Earlier GregScope builds
  held the machine's old tile entity and kept returning `nil, "machine unavailable"` in this case.
  OpenComputers' own energy callbacks on the same component are **not** fixed by this: `getStoredEU()` keeps reading
  the unloaded tile entity and returns `0` after the reload (observed with 64 EU stored in the reloaded machine),
  until the Adapter rebuilds the component (from OpenComputers' source, not tested: when its own chunk reloads, it is
  broken and placed again, or the set of OpenComputers drivers matching the block next to it changes, for example the
  machine is broken or replaced by a non-GT block or another GT machine; a plain neighbour update that leaves the same
  drivers does not rebuild it).
- GT restores the machine's identity, its on/off switch (including a disabled one) and a held recipe's progress. It does **not** save whether a
  multiblock is formed: for about **100 ticks** after loading, a multiblock controller reads `state = "starting"`,
  `statusId = "startup_check"` and `formed = false`, and `energyStored`/`energyCapacity` are absent because GT has not
  re-registered its hatches yet. A controller that was running still shows `active = true`, its `euPerTick` and its
  saved progress during that time. After GT's startup structure check it reports its real state again, and a held
  recipe continues from the saved progress (it does not advance during the startup check).
- From GT's source (not exercised by the tests): shutdown reason and a basic machine's progress are saved, while a
  basic machine's `stuttering` and `outputBlockedTicks` restart at `false`/`0`.

The Horizon-QA game tests (`ChunkReloadTests`) really unload and reload the chunks of an LV macerator with an Adapter,
of an idle formed Electric Blast Furnace and of a running, soft-disabled one, and, with the Adapter and an LV macerator
on opposite sides of a chunk border, only the machine's chunk. They assert every point above except the "from GT's
source" bullet and the part marked as from OpenComputers' source: the soft error while unloaded without reloading the chunk, the Adapter component's address and name
after reload, the cross-chunk Adapter keeping its component and reading the reloaded machine through it, OC's
`getStoredEU` returning `0` there, the startup-check state with `formed = false` and no
energy keys, `active` and `euPerTick` held during it, `allowedToWork = false` restored, progress unchanged 50 ticks
after the reload and resumed (not restarted, not advanced during the check) once the check is over. They also check that each
reloaded machine is a new tile entity with the same `metaId`, `metaName` and coordinates. A server restart loads tile
entities through the same save-and-load path (GT's machine data, the Adapter's saved component addresses). What the
tests do **not** exercise is the restart itself: static and global state of GT, OpenComputers and other mods being
rebuilt, the world and chunk-loader tickets being loaded at startup, and a computer's own saved state. The CI run
itself starts a dedicated server with GregScope and runs all game tests on it.

## Example

[`examples/gregscope-snapshot.lua`](examples/gregscope-snapshot.lua) lists every machine that has `getSnapshot`, prints
a short summary per machine, and can dump one full snapshot with sorted keys:

```
gregscope-snapshot             -- summary of all machines
gregscope-snapshot <address>   -- also dump the full snapshot of one machine (an address prefix is enough)
```

The game tests run this script unmodified on OpenOS with every CPU architecture in OpenComputers 1.12.61-GTNH (Lua 5.3,
Lua 5.2, Lua 5.4 and LuaJ) and check that it prints exactly what GregScope's probe reports. They cover an idle basic
machine and a running Electric Blast Furnace, whose summary looks like this:

```
22e37260 multiblock  Electric Blast Furnace
         state=running statusId=running progress=0.375 euPerTick=2133
         Running
         warnings: maintenance, work_disabled
```

[`examples/gregscope-hub.lua`](examples/gregscope-hub.lua) reads a Telemetry Hub through `gregscope_hub`: it prints
the Hub summary, lists the sensors that are a problem (anything not LIVE, or a LIVE sensor in one of the design's
problem states), and prints a 60-minute table of one sensor with its coverage, its running fraction and its average
EU/t, followed by the gap ranges the window is missing:

```
gregscope-hub                 -- summary, problems, and the table of the first problem
gregscope-hub <idPrefix>      -- the table of that sensor instead (at least eight characters)
```

```
GregScope Telemetry Hub
  gregscope 0.2.0  api 2  schema 1  history 1
  owner Steve  visible 2  live 2  max 256
  frame #29  age 1s  interval 20 ticks  capacity 300s/1440min
Problems (0):
  none
Last 60 min of 8eb54b67 (Basic Electric Furnace #8eb54b67)
  minute       coverage  running  EU/t avg
  gap 1789699200..1789702740  server_offline (59 min)
  gap 1789702740..1789702800  unknown (1 min)
  60 min: 0 observed, 60 missing
```

The game tests run this script unmodified too, on Lua 5.3 and LuaJ.

## Troubleshooting

| symptom | cause / fix |
|---|---|
| No component has `getSnapshot` | The Adapter must touch the basic machine or the multiblock **controller**, not a hatch or casing. Check that the Adapter is connected to the computer (`components` in the OpenOS shell) and that GregScope is installed on the **server**. Right after a machine is placed next to an Adapter, the component has only OC's energy callbacks for about one tick, because GregScope's driver only matches once GT has created the machine inside the new tile entity; look again a tick later, and expect a new address (see [Position binding](#position-binding)). |
| `component.gt_machine` is nil | Expected in a normal pack: the component is named `gt_energycontainer` (or `lsc` / `bec_*`). Use discovery by callback. |
| `getSnapshot` returns `nil, "machine unavailable"` | No supported GT machine at the position next to the Adapter: it was removed or replaced by another block, or its chunk is unloaded. Once the chunk is loaded again the same component reads the machine (also when the Adapter is in a different chunk). If the machine was broken or replaced, the old component is gone: run discovery again, a tick after placing the new machine. See [Position binding](#position-binding) and [Reload and restart](#reload-and-restart). |
| A script broke after installing or removing GregScope | Component addresses changed once (see above). Look the addresses up again. Upgrading v0.1 to v0.2 does **not** change any address. |
| `component.gregscope_hub` is nil | The Adapter must touch a **Telemetry Hub** block, and the Adapter must be connected to the computer. The component appears a tick or two after the Adapter is placed. |
| `listSensors` returns `total = 0` | The Hub is unowned (it was placed by a machine, not a player: break it and place it again), or no sensor in the world belongs to the Hub owner or to their team. |
| `getLatest` says `sensor not found` for an id that exists | The sensor is outside this Hub's scope, or the prefix is shorter than eight characters. A Hub only ever shows its own owner's scope, whoever is asking. |
| A multiblock shows `starting` / `startup_check` | GT runs its first structure check about 100 ticks after the chunk loads. Wait and read again. |
| A key is missing | Optional keys are omitted when not applicable; see the [schema](snapshot-schema-v1.md#optional-keys). |
| Large numbers look rounded | On Lua 5.3 the values are exact integers, so check that the script does not format them with `%f`. On Lua 5.2 / LuaJ CPUs numbers are doubles and lose precision above 2^53; see [Numbers](#numbers). |
