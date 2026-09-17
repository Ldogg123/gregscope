# OpenComputers integration

GregScope adds one read-only callback, `getSnapshot()`, to GregTech machines connected to an OpenComputers **Adapter**.
It returns the normalized [snapshot schema v1](snapshot-schema-v1.md) table.

Verified against OpenComputers **1.12.61-GTNH** and GT5-Unofficial **5.09.54.133** (GTNH 2.9.0-beta-3).

## Install

1. Put the GregScope jar in `mods/` (on the server for multiplayer, or in the client for single player). GregScope
   requires `gregtech` and `OpenComputers`. It adds no blocks, items, recipes or config.
2. Clients do not need GregScope to join a server that runs it (`acceptableRemoteVersions = "*"`); the integration
   runs only on the server and sends nothing to clients.

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

If the machine is no longer there (broken, replaced by another block or machine, chunk unloaded, or no longer a
supported GT machine), the call returns `nil, "machine unavailable"` instead of raising an error:

```lua
local snap, err = proxy.getSnapshot()
if not snap then
  print("unavailable: " .. tostring(err))
end
```

The component itself usually disappears shortly afterwards, when the Adapter notices the block change.

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

## Reload and restart

GregScope keeps no saved data. Everything a snapshot reports is read again from GT on each call, so after a chunk
reload or a server restart it reflects whatever GT restored from its own save data.

- **While the machine's chunk is unloaded**, `getSnapshot()` on a component obtained earlier returns
  `nil, "machine unavailable"`. It never loads the chunk.
- **After the chunk loads again**, an Adapter in the same chunk re-attaches its drivers within a tick or two. The
  merged component keeps its **address** and name, because OpenComputers restores it from the Adapter's saved data as
  long as the set of drivers behind it is unchanged (see the section on addresses above).
- **Adapter in a different chunk from the machine (not verified in-game).** If only the machine's chunk unloads and
  loads again, OpenComputers' source suggests the Adapter keeps its component bound to the machine's old tile entity,
  so `getSnapshot()` may keep returning `nil, "machine unavailable"` until the Adapter's own chunk reloads or the
  Adapter is broken and placed again. Running discovery again finds the same component. Placing the Adapter in the
  same chunk as the machine avoids this.
- GT restores the machine's identity, its on/off switch (including a disabled one) and a held recipe's progress. It does **not** save whether a
  multiblock is formed: for about **100 ticks** after loading, a multiblock controller reads `state = "starting"`,
  `statusId = "startup_check"` and `formed = false`, and `energyStored`/`energyCapacity` are absent because GT has not
  re-registered its hatches yet. A controller that was running still shows `active = true`, its `euPerTick` and its
  saved progress during that time. After GT's startup structure check it reports its real state again, and a held
  recipe continues from the saved progress (it does not advance during the startup check).
- From GT's source (not exercised by the tests): shutdown reason and a basic machine's progress are saved, while a
  basic machine's `stuttering` and `outputBlockedTicks` restart at `false`/`0`.

The Horizon-QA game tests (`ChunkReloadTests`) really unload and reload the chunks of an LV macerator with an Adapter,
of an idle formed Electric Blast Furnace and of a running, soft-disabled one. They assert every point above except the
Adapter in a different chunk and the "from GT's source" bullet: the soft error while unloaded without reloading the
chunk, the Adapter component's address and name after reload, the startup-check state with `formed = false` and no
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

## Troubleshooting

| symptom | cause / fix |
|---|---|
| No component has `getSnapshot` | The Adapter must touch the basic machine or the multiblock **controller**, not a hatch or casing. Check that the Adapter is connected to the computer (`components` in the OpenOS shell) and that GregScope is installed on the **server**. |
| `component.gt_machine` is nil | Expected in a normal pack: the component is named `gt_energycontainer` (or `lsc` / `bec_*`). Use discovery by callback. |
| `getSnapshot` returns `nil, "machine unavailable"` | The machine was removed, replaced or its chunk is unloaded. Run discovery again once the chunk is loaded; after a chunk reload an Adapter in the same chunk keeps its component address. If the Adapter is in a **different chunk** from the machine and only the machine's chunk reloaded, discovery may find the same stale component (not verified in-game): break and re-place the Adapter, or move it into the machine's chunk. See [Reload and restart](#reload-and-restart). |
| A script broke after installing or removing GregScope | Component addresses changed once (see above). Look the addresses up again. |
| A multiblock shows `starting` / `startup_check` | GT runs its first structure check about 100 ticks after the chunk loads. Wait and read again. |
| A key is missing | Optional keys are omitted when not applicable; see the [schema](snapshot-schema-v1.md#optional-keys). |
| Large numbers look rounded | On Lua 5.3 the values are exact integers, so check that the script does not format them with `%f`. On Lua 5.2 / LuaJ CPUs numbers are doubles and lose precision above 2^53; see [Numbers](#numbers). |
