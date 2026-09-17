# GregScope snapshot schema v1

This is the reviewable data contract for the normalized machine snapshot produced by
`io.github.ldogg123.gregscope.probe.SnapshotBuilder` (pure) from readings taken by `GregTechMachineProbe` (the only class
that touches GT/Minecraft). It is verified against GT5-Unofficial **5.09.54.133** (GTNH 2.9.0-beta-3). Source pointers
below are `file:line` in that version.

## Purpose

A snapshot is a flat, deterministic `LinkedHashMap<String, Object>` describing one GT basic machine or multiblock
controller at one instant. It never contains GT objects, item/fluid stacks, NBT, chat components or `null` values. Values
are only `Integer`, `Long`, `Double`, `Boolean`, `String` and an unmodifiable `List<String>`. Keys appear in
`SnapshotKeys.ORDER` (the table order below), skipping absent optional keys.

`state` is intentionally coarse and stable; `statusId` carries the precise reason. `statusText` and `*Text` fields are
for humans only and must never be used for automation.

The probe returns **no snapshot** (Java `null`) when the target is missing, unloaded, replaced, client-side or
unsupported. `MachineProbe.snapshotAt(world, x, y, z)` looks up the tile entity at a position on every call and returns
no snapshot if the world is client-side or that position's chunk is not loaded (it never loads the chunk). The
OpenComputers component uses it, so it is bound to the position next to the Adapter rather than to one tile entity (see
[OpenComputers → Position binding](opencomputers.md#position-binding)). The `unavailable` state and
`machine_unavailable` status are reserved for a future hub.

## Always-present keys

| key | type | meaning / source |
|---|---|---|
| `schemaVersion` | Integer | Always `1`. |
| `kind` | String | `singleblock` (any `MTEBasicMachine`) or `multiblock` (any `MTEMultiBlockBase`). |
| `state` | String | Normalized state, see [States](#states). |
| `statusId` | String | Detailed status ID; GregScope-owned or a verbatim GT ID, see [Rules](#classification-rules). |
| `statusText` | String | Human-readable text: GT display string of the reason/result (GT-sourced rules), else English GregScope text (GregScope-owned IDs), else English state text, else the ID. |
| `name` | String | Server-locale machine name (`getLocalName()`), formatting codes stripped; falls back to `metaName`, then `machineClass`. |
| `metaName` | String | `IMetaTileEntity.getMetaName()`; `""` if GT returns null. Preferred programmatic identity. |
| `metaId` | Integer | `IGregTechTileEntity.getMetaTileID()`. |
| `machineClass` | String | Fully-qualified MTE class name (diagnostics). |
| `dimension` | Integer | `world.provider.dimensionId`. |
| `x`, `y`, `z` | Integer | Holder coordinates (`getYCoord()` is a `short`, widened). |
| `active` | Boolean | `isActive()`: GT's visible working state. |
| `allowedToWork` | Boolean | `isAllowedToWork()`: the soft-mallet/power switch. |
| `hasThingsToDo` | Boolean | `hasThingsToDo()` (true while a recipe is held, i.e. max progress > 0). |
| `wasShutdown` | Boolean | `wasShutdown()`: GT recorded a stop with a reason. |
| `progressTicks` | Integer | Raw `getProgress()`; may be negative (basic machines use `-100` as a power-fail cooldown). |
| `maxProgressTicks` | Integer | Raw `getMaxProgress()`. |
| `progress` | Double | `progressTicks / maxProgressTicks` clamped to `0.0..1.0`; `0.0` if `maxProgressTicks <= 0`. |
| `warnings` | List&lt;String&gt; | Possibly empty; order fixed: `maintenance`, `work_disabled`. |

## Optional keys

Optional keys are omitted (never `null`) when not applicable. Lua callers must check for presence.

| key | type | kind | present when / source / unit |
|---|---|---|---|
| `shutdownReasonId` | String | both | `wasShutdown`. Verbatim reason key (`none` for an unspecified/manual stop). |
| `shutdownCritical` | Boolean | both | `wasShutdown`. `ShutDownReason.wasCritical()`. |
| `euPerTick` | Long | both | `active` and readable (see below for multis without EU output). EU/t; **positive = consumption, negative = generation**. Basic electric: `mEUt`. Multi: see below. |
| `steamPerTick` | Long | single | `active` steam machine. Litres of steam per tick (`mEUt * 2`). |
| `energyStored` | Long | both | Basic electric: holder `getStoredEU()`. Multi: saturating sum over energy hatches (incl. exotic/laser) plus the controller's own buffer (`getStoredEU()`; `0` capacity for plain GT multis, used by TecTech and storage multis such as the LSC/PSS); only if ≥ 1 hatch or a non-zero controller capacity. EU. |
| `energyCapacity` | Long | both | As `energyStored`, using `getEUCapacity()`. EU. |
| `steamStored` | Long | single | Steam machine: `getStoredSteam() * 2`. Litres. |
| `steamCapacity` | Long | single | Steam machine: `getSteamCapacity() * 2`. Litres. |
| `formed` | Boolean | multi | `mMachine`. Not persisted; false for ~100 ticks after load. |
| `maintenanceIssues` | Integer | multi | `max(0, getIdealStatus() - getRepairStatus())`. |
| `maintenanceChecksEnabled` | Boolean | multi | `shouldCheckMaintenance()`. |
| `efficiency` | Double | multi | `mEfficiency / 10000.0`. **Not clamped**; can exceed `1.0`. Omitted for GT++ steam multis. |
| `pollutionEmissionFactor` | Double | multi | `getAveragePollutionPercentage() / 100.0`: fraction of pollution emitted (muffler average). |
| `recipesCompleted` | Long | multi | `recipesDone`; counts completed **parallels**, not recipe runs. |
| `controllerAgeTicks` | Long | multi | `getTotalRuntimeInTicks()`: ticks the controller has been loaded and ticking, **not** working time. |
| `ticksSinceLastWork` | Long | multi | `!active`: `max(0, controllerAgeTicks - getLastWorkingTick())`. |
| `recipeCheckResultId` | String | multi | Verbatim recipe-check ID, see [ID policy](#stable-id-policy). |
| `recipeCheckSuccessful` | Boolean | multi | `CheckRecipeResult.wasSuccessful()`. |
| `recipeCheckResultText` | String | multi | Sanitized display string; omitted if unavailable or empty. |
| `outputBlockedTicks` | Integer | single | `mOutputBlocked` (raw counter; see R9). |
| `stuttering` | Boolean | single | `isStuttering()` (not persisted across reloads). |

Multiblock `euPerTick` (only computed while active; omitted for GT++ steam multis, Large Boilers and the Heat Exchanger,
which report steam through `mEUt` and emit no EU):

- Power flow `eut` = `lEUt` for `MTEExtendedPowerMultiBlockBase`, `mEUt` otherwise; for TecTech `TTMultiblockBase`
  `lEUt` if non-zero else `mEUt` (see Deviations).
- `eut < 0` (consumer): `getInfoMap().get("energyUsage")` parsed as a long. This routes through GT's protected,
  override-aware `getActualEnergyUsage()` (efficiency- and, for TecTech, amperage-adjusted). Omitted if unparsable.
- `eut > 0` (generator): `-(eut * mEfficiency / 10000)` (GT WAILA formula), times `eAmpereFlow` for TecTech; overflow
  saturates. Omitted for TecTech multis with `mEfficiency <= 0` and for the Large Naquadah Reactor (see limitations).
- `eut == 0`: `0`.

## States

| state | meaning |
|---|---|
| `unavailable` | Reserved (future hub). The probe returns no snapshot instead. |
| `starting` | Multiblock has not run its first structure check since load. |
| `unformed` | Multiblock structure is incomplete. |
| `shutdown` | GT stopped the machine for a named or critical reason, or a crash disabled it. |
| `power_starved` | Basic machine holds a recipe but could not drain enough EU/steam. |
| `running` | GT reports the machine active. |
| `disabled` | Work is switched off (or stopped with an unspecified, non-critical reason). |
| `output_blocked` | Output cannot be emptied (items/fluids, or a bronze machine's steam vent). |
| `waiting` | Multiblock is enabled and formed, but its last recipe check failed for a non-routine reason; or an active basic machine is frozen by a machine error (R5b). |
| `idle` | Nothing to do. |

## GregScope-owned IDs

| statusId | used by |
|---|---|
| `machine_unavailable` | reserved |
| `startup_check` | R1 |
| `structure_incomplete` | R2 |
| `power_starved` / `steam_starved` | R5 (electric / steam) |
| `machine_error` | R5b |
| `running` | R6 |
| `disabled` | R7 |
| `steam_vent_blocked` | R8 |
| `item_output_full` | R9 (reuses GT's recipe-check ID); also passed through from GT in R10 |
| `fluid_output_full` | passed through from GT in R10 |
| `none` | R13; normalized "no reason" |
| `no_recipe` | GT's routine idle result (R12) |
| `simple_result` | GT's generic ID for simple reasons/results; never emitted as a status (normalized away) |

Warning IDs: `maintenance` (multiblock with `maintenanceIssues > 0`), `work_disabled` (state `running` while
`allowedToWork` is false).

## Classification rules

Evaluated strictly in order; first match wins. `multi` = multiblock, `basic` = singleblock,
`rid` = `recipeCheckResultId` (null → `none`).

| # | condition | state | statusId | text from | rationale |
|---|---|---|---|---|---|
| R1 | multi && startup check pending (`getmStartUpCheck() >= 0`) | starting | `startup_check` | GregScope | `onPostTick` only runs a multi once `mStartUpCheck < 0`; `mMachine` is not persisted (MTEMultiBlockBase.java:628). |
| R2 | multi && !formed | unformed | `structure_incomplete` | GregScope | Root cause; GT also stops it with non-critical `structure_incomplete` (MTEMultiBlockBase.java:637). |
| R3 | wasShutdown && (critical \|\| reason ≠ `none`) | shutdown | shutdown reason | reason | `getID()` is `simple_result` for all simple reasons; the key discriminates (SimpleShutDownReason.java:31). |
| R4 | multi && !allowedToWork && result persists on shutdown | shutdown | `rid` | result | `onTickFail` sets `crash` and disables work without a shutdown reason (MTEMultiBlockBase.java:675-679). |
| R5 | basic && allowedToWork && maxProgress > 0 && (stuttering \|\| progress < 0) | power_starved | `steam_starved` / `power_starved` | GregScope | Failed drain sets progress `-100` + stutter flag (MTEBasicMachine.java:632-633); only negative progress persists. |
| R5b | basic && active && machine errors | waiting | `machine_error` | GregScope | Industrial Apiary with bee errors (`hasErrors()`) stays active with progress frozen (MTEIndustrialApiary.java:569-584). |
| R6 | active | running | `running` | GregScope | Disabling does not abort an in-progress recipe; adds `work_disabled` warning. |
| R7 | !allowedToWork | disabled | `disabled` | GregScope | Includes non-critical `none` stops (power switch, turbines, drills call `stopMachine(NONE)`). |
| R8 | basic && steam vent blocked | output_blocked | `steam_vent_blocked` | GregScope | `MTEBasicMachineBronze.needsSteamVenting()` (MTEBasicMachineBronze.java:69). |
| R9 | basic && outputBlockedTicks > 0 && maxProgress ≤ 0 | output_blocked | `item_output_full` | GregScope | Counter runs until all outputs are empty, even during a new recipe (MTEBasicMachine.java:672-673). |
| R10 | multi && rid ∈ {`item_output_full`, `fluid_output_full`} | output_blocked | `rid` | result | GT recipe-check IDs (CheckRecipeResultRegistry.java:60,64). |
| R11 | multi && !(successful \|\| rid ∈ {`none`, `no_recipe`}) | waiting | `rid` | result | Unknown/future failure IDs must surface as waiting. Verified in-game: an EBF whose recipe needs more heat than its coils give reports GT's `insufficient_heat` (ResultInsufficientHeat.java:27). |
| R12 | multi | idle | `rid` | result | `no_recipe` is routine for empty inputs; success-typed ad-hoc IDs (e.g. `no_scrap`) are idle. |
| R13 | basic | idle | `none` | GregScope | |

GT never resets the recipe-check result on stop/disable, so R10–R12 only apply while work is allowed (R7 catches the
rest). A stale `item_output_full` on a disabled multi therefore reads `disabled`.

`statusText` = first non-empty of: the GT text for the rule's source (shutdown reason / recipe check), the GregScope
English text for a GregScope-owned `statusId`, the English state text, the `statusId`.

## Stable ID policy

- All GT-derived IDs are passed through **verbatim** as opaque, case-sensitive strings. They may contain dots and mixed
  case (`gtnhlanth.noaccel`, `EEC_nospawner`). GregScope never validates them against a fixed list; unknown future IDs
  work and classify as `waiting` when unsuccessful.
- Shutdown reasons and recipe-check results are **separate namespaces** (`shutdownReasonId` vs `recipeCheckResultId`);
  the same string can mean different things in each. `statusId` takes its value from whichever source the rule names.
- Normalization (`ReasonIds.normalize(key, id)`): non-empty key → key; else non-empty id other than `simple_result` →
  id; else `none`. No trimming or case changes. For recipe-check results the key is obtained via the public
  `writeToNBT(...).getString("key")` (SimpleCheckRecipeResult.java:49), not reflection.
- GregScope-owned IDs listed above are stable for schema v1.

## Known limitations

- No recipe identity, parallel count, input/output contents or history.
- A basic machine with an empty energy buffer and no held recipe reads `idle`, not `power_starved` (there is no held
  recipe, so nothing marks it as starved).
- Fluid-output blockage on basic machines and steam-furnace-style blockages read `idle` (GT exposes no persistent flag).
- `euPerTick` is `0` for multis that do not use `mEUt`/`lEUt` (e.g. Miner, Pump, wireless-energy multis).
- `progress` is meaningless for 1-tick-cycle machines (turbines, LESU, etc.).
- `recipesCompleted` counts parallels.
- `stuttering` and `formed` are not persisted by GT; right after a chunk load they read false.
- `statusText` is server-locale and may be English fallback text; some GT display strings are client-only and are
  dropped on dedicated servers (`ResultMissingItem` uses client `I18n`, ResultMissingItem.java:8,45).
- `euPerTick` is omitted for Large Boilers and the Heat Exchanger (steam producers: steam L/t ≈ `2 * mEUt *
  mEfficiency / 10000`); their `efficiency` is still reported.
- The Large Naquadah Reactor (TecTech) reports `getMaxEfficiency() == 0`, so its `mEfficiency` stays `0`: `euPerTick` is
  omitted and `efficiency` reads `0.0` although it produces EU. Any TecTech generator with `mEfficiency <= 0` (e.g. still
  warming up) also omits `euPerTick`.
- An Industrial Apiary disabled while stuttering stays active with progress frozen and reads `running` with a
  `work_disabled` warning. Machine errors are only read for the Industrial Apiary.
- Reading energy hatches, mufflers and the info map uses GT getters that prune invalid (null/dead) hatch references from
  the controller's own lists, exactly as GT's own tick does; no other state is modified.
- Lua table key order is unspecified after OpenComputers conversion; the Java map order is deterministic.

## Deviations from the handoff

| change | reason | source |
|---|---|---|
| `pollutionRatio` → `pollutionEmissionFactor` | It is the emitted fraction (muffler average), not a ratio of anything. | MTEMultiBlockBase.java:935 |
| `runtimeTicks` → `controllerAgeTicks` | `mTotalRunTime` increments every loaded tick, not only while working. | MTEMultiBlockBase.java:617 |
| `efficiency` no longer normalized to `0..1` | `mEfficiency / 10000` may exceed 1.0; clamping would hide data. | MTEMultiBlockBase.java:2608 |
| info-map `minEnergyTier` / `maxEnergyUsage` not exposed | Normal hatches only (ignores exotic/laser), and voltage sentinels. | MTEMultiBlockBase.java:2586-2605 |
| multiblock energy from hatches, not info map | `getInfoMap()` skips exotic/laser hatches; `getExoticAndNormalEnergyHatchList()` includes them (TT adds `eEnergyMulti`). | MTEMultiBlockBase.java:4134; TTMultiblockBase.java:1510 |
| multiblock `euPerTick` only from info map for consumers | `getActualEnergyUsage()` is protected; `energyUsage` in the info map is its only public, override-aware path. Generators use the WAILA formula. | MTEMultiBlockBase.java:1065, 2604, 2845-2846 |
| new `steamPerTick`/`steamStored`/`steamCapacity` in litres | Steam machines have no EU; GT stores steam at half the fluid amount. | MetaTileEntity.java:596 |
| new `starting` state (R1), `unformed` before `shutdown` | `mMachine` false for ~100 ticks after load; structure is the root cause of its shutdown. | MTEMultiBlockBase.java:628, 637 |
| `wasShutdown` alone no longer means `shutdown` | Manual power switch/turbine stops use non-critical `none`. | ShutDownReasonRegistry.java:106 |
| `running` before `disabled` / `output_blocked` | Disabled and stale-output-blocked machines keep running the current recipe. | MTEBasicMachine.java:672-673 |
| `power_or_steam_starved` split into `power_starved` / `steam_starved`; requires a held recipe | Stale stutter flag on idle steam machines. | MTEBasicMachine.java:632-633 |
| basic output blockage reports `item_output_full` (not a raw counter state) | Reuses GT's ID; counter is only trusted with no held recipe. | CheckRecipeResultRegistry.java:60 |
| crash / persisting results → `shutdown` (R4) | `onTickFail` disables work without a shutdown reason. | MTEMultiBlockBase.java:675-679; CheckRecipeResultRegistry.java:73 |
| `no_recipe` and successful results are idle | Routine for every idle multi; handoff treated only a fixed list as benign. | CheckRecipeResultRegistry.java:56 |
| new `work_disabled` warning, new `shutdownReasonId`/`shutdownCritical`/`recipeCheckSuccessful`/`maintenanceChecksEnabled`/`energy*`/`steam*` keys | Expose raw causes separately from `statusId`. | — |
| `unavailable` never emitted by the probe | Probe returns null; state kept for the future hub. | — |

### Deviations from the GS-002 design spec (found while verifying sources)

| change | reason | source |
|---|---|---|
| TecTech multis: power flow = `lEUt` if non-zero else `mEUt`; generators multiply by `eAmpereFlow` | `TTMultiblockBase` extends `MTEExtendedPowerMultiBlockBase` but stores its flow in `mEUt` unless the protected `useLongPower` flag is set (only a few TT multis set it), so the spec's `lEUt` would read 0 for most TT multis. Sign convention is the same as GT (`> 0` produces, `< 0` consumes). TT generator output is `flow * mEfficiency / maxEfficiency * eAmpereFlow`; `maxEfficiency` is assumed to be 10000 because `getMaxEfficiency(ItemStack)` needs the controller slot. | TTMultiblockBase.java:158, 197, 494-512, 1290-1300 |
| `name` falls back to `machineClass` when both `localName` and `metaName` are empty | The name is never blank. | — |
| multiblock `energyStored`/`energyCapacity` include the controller buffer (spec: hatches only) | TecTech `powerInput()` moves hatch EU into the controller every tick and drains from there, so hatches alone under-report; plain GT multis have `maxEUStore() == 0`, so nothing changes for them. | TTMultiblockBase.java:1225-1247, 1308-1312; MetaTileEntity.java:358 |
| `euPerTick` omitted for Large Boilers / Heat Exchanger, and for TecTech generators with `mEfficiency <= 0` (incl. the Large Naquadah Reactor) | Their positive `mEUt` is steam or a display value; the generator formula would report wrong EU. | MTELargeBoiler.java:375-381; MTEHeatExchanger.java:257, 273-274; MTELargeNaquadahReactor.java:221-244, 347-350 |
| new rule R5b and GregScope-owned ID `machine_error` (`waiting`) | An Industrial Apiary with bee errors stays active with frozen progress and would read `running` forever. | MTEIndustrialApiary.java:569-584, 859 |
