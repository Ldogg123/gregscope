# Testing

How GregScope v0.1 is verified, what each layer covers, and what is **not** covered yet.

GregScope is checked in two layers:

1. **Unit tests** (plain JVM, JUnit 5) for the pure snapshot core: classification, snapshot building and parsing.
2. **Horizon-QA in-game tests** on a real dedicated server with GT5-Unofficial 5.09.54.133 and OpenComputers
   1.12.61-GTNH: real machines, real OpenComputers drivers and Adapters, real chunk unloads, and the documented Lua
   example running on a real OpenOS computer.

Both run on every push and pull request in CI (`.github/workflows/build-and-test.yml`).

## Unit tests

Location: `src/test/java`. Run with `./gradlew test` (also part of `./gradlew build`). 82 tests in 5 classes:

| class | tests | covers |
|---|---|---|
| `ShippedClassesTest` | 4 | Reads every compiled class of the shipped mod (the `main` output on the test classpath) as bytes, without loading it, and checks its constant pool (every referenced class, member, descriptor and string literal) and super class. Fails on any reference to `net/minecraft/client/` or `cpw/mods/fml/client/` (slash or dot form), on `@SideOnly(CLIENT)`, and on periodic-work hooks: `SubscribeEvent`, `EventBus`, `TickEvent`, `FMLCommonHandler`, `MinecraftForge`, `GameRegistry`, `IWorldGenerator`, threads, timers, executors, members named `canUpdate`/`update`/`updateEntity`, or a `TileEntity` subclass. Also checks that the expected shipped classes were found and no test class was scanned, and that the reader sees an annotation descriptor and a string literal in a real class file. |
| `probe.StateClassifierTest` | 43 | Every classification rule R1-R13 in [schema v1](snapshot-schema-v1.md#classification-rules), rule precedence (for example R1 over R2, R3 over R4, R6 over R7), unknown and odd recipe-check IDs kept verbatim, null handling. |
| `probe.SnapshotBuilderTest` | 18 | Progress clamping, per-tick rates only while active, warnings, status text sources (GT text, fallbacks, GregScope-owned text), shutdown keys only when shut down, no basic/multiblock key leaking into the other kind, identity keys, no nulls and only allowed value types. |
| `probe.SnapshotParsingTest` | 9 | Defensive number parsing, saturating addition, generator `euPerTick`, reason ID normalization, formatting-code stripping. |
| `model.MachineSnapshotTest` | 8 | Canonical key order, required vs. optional keys (optional keys are omitted, never null), unmodifiable maps and warning lists. |

The unit tests use `TestReadings` fakes and do not load Minecraft, GT or OpenComputers classes (`ShippedClassesTest`
only reads class files as bytes). What GT actually returns is checked by the in-game tests.

**Negative controls (2026-09-17), reverted afterwards (files restored from a backup copy, `cmp` identical):**

- A method in `CommonProxy` annotated `@SideOnly(Side.CLIENT)` returning `net.minecraft.client.Minecraft.class.getName()`:
  `noClientClassReferences` failed and listed both `references net/minecraft/client/Minecraft` and
  `uses @SideOnly(CLIENT)`; the other 81 tests passed.
- `CommonProxy` registering itself on `FMLCommonHandler.instance().bus()` with a `@SubscribeEvent` `ServerTickEvent`
  method, plus `GregTechMachineEnvironment.canUpdate()` overridden to return `true`: `noPeriodicWorkHooks` failed and
  listed the `FMLCommonHandler`, `EventBus`, `SubscribeEvent` and `TickEvent` references and
  `GregTechMachineEnvironment declares or calls canUpdate`. The in-game `IdleCostTests` failed too (see below).

## Horizon-QA in-game tests

Location: `src/gametest` (Java in `src/gametest/java`, the OpenOS runner in `src/gametest/resources`).

### Dev-only source set

The `gametest` source set is configured in `addon.gradle`. It compiles against the mod and Horizon-QA 0.14.0 and is
added only to the `runServer`/`runClient` classpath. It is **not** part of `jar`, `reobfJar`, `sourcesJar` or any
publication, and Horizon-QA is a `runtimeOnlyNonPublishable` dependency. The build copies
`docs/examples/gregscope-snapshot.lua` byte for byte into the gametest resources, so the tested script is the
documented one.

To check a release jar, run `unzip -l build/libs/gregscope-<version>.jar`. It must list only GregScope's own classes
under `io/github/ldogg123/gregscope/` (no `gametest` package), `mcmod.info`, `LICENSE` and `META-INF/MANIFEST.MF`, and
no `horizonqa` or `.lua` entries.

### Running locally

Minecraft's server needs the EULA to be accepted before it starts. The first local `runServer` creates
`run/server/eula.txt` and stops. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA) and, if you accept it, set
`eula=true` in that file yourself. (`run/` is git-ignored; CI handles this in the shared GTNH workflow.)

From the repository root (Git Bash on Windows; `$(pwd -W)` gives a Windows path; use `$(pwd)` elsewhere):

```bash
./gradlew --no-daemon runServer \
  --mcJvmArgs=-Dhorizonqa.mode=ci \
  --mcJvmArgs=-Dhorizonqa.tests=gregscope \
  "--mcJvmArgs=-Dhorizonqa.reportDir=$(pwd -W)/build/horizonqa"
```

- `-Dhorizonqa.tests=gregscope` runs all GregScope tests. Select one class with `gregscope:ChunkReloadTests`.
- `ci` mode starts a dedicated server with a void world, runs the selected tests, writes
  `build/horizonqa/horizonqa-result.json` and a JUnit `TEST-horizonqa.xml`, and stops the server. A failed or timed-out
  required test gives a non-zero exit code.
- Snapshots each test inspects are printed to the server log with the prefix `[GregScope gametest]`.
- Do not run two Gradle invocations at the same time; they share `run/server`.

### Running in CI

`.github/workflows/build-and-test.yml` calls GTNH's shared `build-and-test` workflow with `horizonqa: true` and
`horizonqa-tests: gregscope`. It builds, runs the unit tests, then runs `runServer` in `ci` mode on a Linux dedicated
server under a **300 s** step timeout (server boot plus every test batch).

Batches run one after another; tests inside a batch run in parallel. Every wait is bounded. If every batch ran to its
timeout the suite would take 2100 ticks (105 s at 20 TPS), so a hang still ends with a Horizon-QA report inside the
CI budget. A normal run takes about 17 s of test time (see the last full run below).

### Test classes

| class | batch | what it does |
|---|---|---|
| `BasicMachineSnapshotTests` | `gregscope.basic` | Every test runs on an LV, MV and HV Electric Furnace (`MTEBasicMachine`, suffix `[lv]`/`[mv]`/`[hv]`): idle, running, disabled, power-starved and output-blocked; asserts the probe's `state`/`statusId` and related keys. Tier values are asserted exactly: `metaName`, `energyCapacity` 2048 / 8192 / 32768 EU (`V[tier] * 64`), and for the 4 EU/t, 128-tick smelting recipe `euPerTick` 4 / 16 / 64 and `maxProgressTicks` 128 / 64 / 32 (GT's `OverclockCalculator`: the ULV recipe counts as LV, one overclock per tier above). These formula values matched what the server reported. |
| `ElectricBlastFurnaceSnapshotTests` | `gregscope.ebf` | GT's own formed EBF template: starting, idle, running, disabled while idle and while running, maintenance warning, no-maintenance shutdown, broken structure, full output bus, power loss, and `waiting` (`recipeAboveCoilHeatIsWaiting`: recipe needs 2101 K, the template gives 2001 K; input and EV power present, work allowed). |
| `IdleCostTests` | `gregscope.idle` | Structural evidence for "no idle cost", not a benchmark. `noGregScopeListenersTileEntitiesOrGenerators`: no listener object of a GregScope class and no listener owned by the `gregscope` mod container on `MinecraftForge.EVENT_BUS`, `TERRAIN_GEN_BUS`, `ORE_GEN_BUS` or the FML bus (where 1.7.10 tick events arrive); no GregScope class in the tile entity registry, in `GameRegistry`'s static collections (world generators and others) or among any world's loaded tile entities. Each scan must find other mods' entries (observed: 217 listener objects, 543 tile entity classes, 113 loaded tile entities). `adapterNeverTicksGregScopeEnvironment`: a real Adapter next to an LV macerator, after 5 real ticks; the Adapter's `updatingBlocks` list is empty (asserted first) and the merged environment and GregScope's own environment report `canUpdate() == false`. Reads Forge/FML/OC internals by reflection (test code only). |
| `OpenComputersComponentTests` | `gregscope.oc` | GregScope's driver through OpenComputers' own driver registry, as an Adapter uses it: merging into `gt_energycontainer` (basic machine and controller), `getStoredEU` still present, soft error after the machine is destroyed, hatches left to OC's energy driver, LSC and BEC controllers keep their `lsc` / `bec_*` names. |
| `OpenComputersExampleScriptTests` | `gregscope.oc.openos`, `gregscope.oc.openos.ebf` | Boots a real OpenOS computer (creative case, real Adapter) and runs the unmodified example script. Output is compared exactly with the probe's snapshot: an idle LV macerator on Lua 5.3, Lua 5.2, Lua 5.4 and LuaJ, and a running EBF with maintenance and work-disabled warnings on Lua 5.3. Native architectures skip (not pass) if OC cannot load the native library on that OS/arch. |
| `AdapterBindingTests` | `gregscope.oc.adapter` | Real placed Adapter next to LV basic machines. Machine broken, then replaced by a different GT machine (twice) and by stone: component removed, energy-only component right after placement, component with `getSnapshot` and a new address a tick later reporting the new machine (the energy-only components are thrown away, not reused), an empty GT holder (the state GT's placement notifies neighbours in, reproduced without notification) gets OC's energy callbacks while GregScope's driver does not match it, removed component objects reading the position (Java calls). Adapter broken and placed again: no exception, detached component still reads the machine from Java, new Adapter's component has the same name, a new address and a working `getSnapshot`. |
| `ChunkReloadTests` | `gregscope.reload.*` | Really unloads and reloads the chunks of an LV macerator with a real Adapter, an idle formed EBF and a running, soft-disabled EBF. Asserts new tile entity objects, soft errors while unloaded without reloading the chunk, the Adapter component's address and name, the startup-check state, persisted keys and resumed recipe progress. `adapterInOtherChunkSeesMachineAfterItsChunkReloads` puts an Adapter and an LV macerator on opposite sides of a chunk border (empty 17-wide template `gregscope:empty_17x1x5`, border computed at run time) and unloads and reloads only the machine's chunk: the Adapter's chunk stays loaded, the Adapter keeps the same component object and address, `getSnapshot` soft-errors while unloaded without loading the chunk and reads the reloaded machine in the same tick as the reload; OC's own `getStoredEU` on that component returns `0` after the reload (stale tile). |

Helpers (`GtPlacement`, `OcComponents`, `OcFileSystem`, `Snapshots`, `StubContext`, `ChunkReload`) are test-only.
`ChunkReload.ofChunkAt` unloads a single chunk of a test cell.

**`ChunkReload` unload failures, two separate mechanisms.**

1. **Hodgepodge fresh-chunk protection (the CI failure).** Hodgepodge's `unloadQueuedChunks()` mixin skips, and leaves
   queued, any chunk that it or one of its eight neighbours was generated but not yet populated within the last
   600 server ticks (`ChunkGenScheduler.shouldProtectFromUnload`, read from the Hodgepodge 2.7.196 bytecode). Every CI
   run starts a new world, so the test cells are such chunks. GitHub CI on commit `f1676ce` (2026-09-17) failed 3
   `ChunkReloadTests` this way ("still loaded after unloadQueuedChunks()", 28 passed / 3 required failed), and a fresh
   local world on that commit's code (separate checkout) reproduced it 3/3. Calling `unloadQueuedChunks()` again in the same tick does not help, because the
   tick counter does not move: with the 64-call loop below and no other change, a fresh local world still failed all 4
   `ChunkReloadTests` ("still loaded after 64 unloadQueuedChunks() calls"). Local runs that reuse `run/server/world`
   find old test chunks, which most likely explains why earlier local runs were green, and why one local run right
   after the tests moved to a new grid row (new chunks) failed all four; that run was not re-examined. **Fix:**
   `HodgepodgeChunkGen` (test code, `gametestCompileOnly` Hodgepodge 2.7.196, only when the `hodgepodge` mod is loaded)
   clears Hodgepodge's generation tracking for each target chunk and its eight neighbours before the unload, as for old,
   populated chunks in a real base. With it, a fresh local world passed `ChunkReloadTests` 4/4 and the full suite 47/47,
   and the reused world passed 47/47 as well. **Not yet verified on GitHub CI** (no push was made).
2. **Unload queue size (reproduced only on purpose).** Each `unloadQueuedChunks()` call unloads at most 100 queued
   chunks, and a forced `WorldServer.saveAllChunks` just before the unload queued 381 chunks (the void world's spawn is
   far from the test grid), so one call skipped the test's chunks (2 tests failed). `ChunkReload.unload()` therefore
   calls `unloadQueuedChunks()` until the chunks are gone, at most 64 times; that passed 47/47 with the forced save in
   place. This case was not seen in a natural run.

If a test fails in the same tick as its unload, `ChunkReload`'s cleanup loads and re-forces the chunks at once, so old
and new tile entities of that failed test's cell may both update once (documented in the `ChunkReload` javadoc; it
adds side effects and log noise after the first failure, never a pass).

**Negative control (2026-09-17):** with the OpenComputers environment temporarily changed back to holding the tile
entity captured when it was created, `adapterInOtherChunkSeesMachineAfterItsChunkReloads`,
`basicMachineSurvivesChunkReload`, `ebfControllerRestartsStartupCheckAfterChunkReload` and
`replacedMachineGetsNewComponent` fail with `machine unavailable`; `adapterDetachAndReattach` and
`runningEbfKeepsRecipeProgressAcrossChunkReload` still pass (they do not call an environment across a tile entity
change). Before the fix, the cross-chunk test's component returned `machine unavailable` for all 20 ticks it was
polled after the reload.

**Negative control (2026-09-17), idle cost:** with `CommonProxy` temporarily subscribed to the FML bus
(`ServerTickEvent`) and `GregTechMachineEnvironment.canUpdate()` returning `true`, both `IdleCostTests` failed:
`noGregScopeListenersTileEntitiesOrGenerators` with "FMLCommonHandler.bus() listener belongs to GregScope:
io.github.ldogg123.gregscope.CommonProxy", `adapterNeverTicksGregScopeEnvironment` with "merged compound environment
canUpdate()". That run never reached the `updatingBlocks` check, so it was then moved in front of the `canUpdate()`
checks and exercised separately: with only `GregTechMachineEnvironment.canUpdate()` returning `true`,
`adapterNeverTicksGregScopeEnvironment` failed with "Adapter updatingBlocks size: expected <0> but found <1>" (logged
size 1); reverted, `cmp` identical.

### Last full run

Local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world** (`run/server/world` deleted first):
**47/47 passed**, 0 skipped, 0 issues; exit code 0; Gradle wall time 44 s, about 17 s from server `Done` to the
Horizon-QA result. A second full run reusing that world also passed 47/47 (45 s wall). GitHub CI has not run this
state. Slowest tests on the fresh world (ticks / seconds as Horizon-QA reports them):

| test | ticks | s |
|---|---|---|
| `exampleScriptRunsOnOpenOs[lua53]`, `[lua52]`, `[lua54]`, `[luaj]` (parallel) | 146 | 7.30 |
| `exampleScriptShowsRunningEbfWithWarnings` | 144 | 7.20 |
| `IdleCostTests.adapterNeverTicksGregScopeEnvironment` | 6 | 0.30 |
| `namedControllerKeepsOcComponentName[*]`, `basicMachineMerges…`, `destroyedMachineSoftErrors` | 5 | 0.25 |
| `ChunkReloadTests.adapterInOtherChunkSeesMachineAfterItsChunkReloads` | 4 | 0.20 |
| other `ChunkReloadTests` (each), `AdapterBindingTests.replacedMachineGetsNewComponent` | 3 | 0.15 |
| `ElectricBlastFurnaceSnapshotTests.brokenStructureIsUnformed`, `AdapterBindingTests.adapterDetachAndReattach` | 2 | 0.10 |

All other tests (including all 15 `BasicMachineSnapshotTests` cases, `recipeAboveCoilHeatIsWaiting` and
`noGregScopeListenersTileEntitiesOrGenerators`) finish in 0 ticks because they use Horizon-QA's time warp or need no
ticks. See the `ChunkReload` notes above for the unload failures on CI and on fresh worlds and their fix.

## GS-004 coverage

Every row and condition of [handoff §11 GS-004](handoff.md#gs-004--integration-validation-and-documentation). Test
names are `Class.method`.

### Machines and conditions

| machine | condition | covered by | notes |
|---|---|---|---|
| MV/HV basic machine | idle | `BasicMachineSnapshotTests.poweredIdle[mv]`, `[hv]` (and `[lv]`); also `OpenComputersComponentTests.basicMachineMergesIntoEnergyContainerComponent` (LV macerator, via OC) | MV and HV **Electric Furnaces** only; other MV/HV basic machine types are not exercised (they share `MTEBasicMachine`, and GregScope has no tier- or type-specific path for them). Exact `energyCapacity` per tier. OC Adapter tests use LV machines only. |
| MV/HV basic machine | running | `BasicMachineSnapshotTests.runningRecipe[mv]`, `[hv]` | Exact overclocked `euPerTick` (16 / 64) and `maxProgressTicks` (64 / 32). |
| MV/HV basic machine | disabled | `BasicMachineSnapshotTests.disabledWhileIdle[mv]`, `[hv]` | Disabled while running is covered only for the EBF. |
| MV/HV basic machine | starved of power | `BasicMachineSnapshotTests.drainedMidRecipeIsPowerStarved[mv]`, `[hv]` | Buffer emptied mid-recipe (stutter, `power_starved`, overclocked `maxProgressTicks` kept). The empty-buffer-without-recipe case reads `idle` by design (schema known limitations). Steam starvation: unit tests only (`StateClassifierTest.r5SteamStarved`). |
| MV/HV basic machine | output blocked | `BasicMachineSnapshotTests.foreignItemInOutputSlotIsOutputBlocked[mv]`, `[hv]` | Item output. Fluid-output blockage reads `idle` (documented limitation); steam vent blocked is unit-tested only (`r8SteamVentBlocked`). |
| Electric Blast Furnace | unformed | `ElectricBlastFurnaceSnapshotTests.brokenStructureIsUnformed`; `freshTemplateIsStarting` (startup check) | |
| Electric Blast Furnace | formed/idle | `ElectricBlastFurnaceSnapshotTests.idleAfterStartup` | |
| Electric Blast Furnace | running | `ElectricBlastFurnaceSnapshotTests.runningRecipe`; `OpenComputersExampleScriptTests.exampleScriptShowsRunningEbfWithWarnings` | |
| Electric Blast Furnace | maintenance warning | `ElectricBlastFurnaceSnapshotTests.maintenanceWarningWhileRunning`; `noMaintenanceShutsDown` (`no_repair` shutdown); `exampleScriptShowsRunningEbfWithWarnings` | |
| Electric Blast Furnace | output full | `ElectricBlastFurnaceSnapshotTests.fullOutputBusIsOutputBlocked` | Item output bus. A full output hatch (fluid) is unit-tested only (`r10FluidOutputFull`). |
| Electric Blast Furnace | power-loss shutdown | `ElectricBlastFurnaceSnapshotTests.powerLossMidRecipeShutsDown` | |
| Electric Blast Furnace | manually disabled | `ElectricBlastFurnaceSnapshotTests.disabledWhileIdle`, `disabledWhileRunningKeepsRunning`; `ChunkReloadTests.runningEbfKeepsRecipeProgressAcrossChunkReload` (disabled switch survives reload) | Disabled through GT's API (`disableWorking()`), not a player's soft-mallet click. |
| OC Adapter | attach | Real Adapter block: `ChunkReloadTests.basicMachineSurvivesChunkReload`, `AdapterBindingTests.*`, `OpenComputersExampleScriptTests.*`. Driver registry as an Adapter uses it: `OpenComputersComponentTests.*` | Attaching a new machine next to an existing Adapter: `AdapterBindingTests.replacedMachineGetsNewComponent` (energy-only component for one tick, then `getSnapshot`). |
| OC Adapter | detach | `AdapterBindingTests.adapterDetachAndReattach` (placed Adapter broken and placed again), `AdapterBindingTests.replacedMachineGetsNewComponent` (machine broken or replaced next to a placed Adapter), `OpenComputersComponentTests.destroyedMachineSoftErrors` (driver registry) | Blocks are removed with `World.setBlock`, not broken by a player. The tests check the components in the Adapter's network; OC removing the component from a **running computer** (`component_removed` signal, Lua proxy) is **not covered**. Only LV basic machines, not multiblock controllers, are replaced. |
| OC Adapter | chunk unload/reload | `ChunkReloadTests.basicMachineSurvivesChunkReload`, `ebfControllerRestartsStartupCheckAfterChunkReload`, `runningEbfKeepsRecipeProgressAcrossChunkReload`, `adapterInOtherChunkSeesMachineAfterItsChunkReloads` | Adapter and machine in the same chunk, and (basic machine only) an Adapter in a different chunk with only the machine's chunk reloaded. Reloading only the Adapter's chunk is not covered. Lua-visible `component_removed`/`component_added` signals are not covered. |
| OC Adapter | server restart | **Not covered as a restart.** `ChunkReloadTests` cover the tile-entity save/load path a restart uses, and CI starts a fresh dedicated server each run. | A real stop/start of the same world (static state rebuilt, chunk-loader tickets, OC computer state) is not tested; it would need a second server process, which Horizon-QA does not provide. |
| Dedicated server | startup smoke test | Every CI and local Horizon-QA run starts a dedicated server (`runServer`) with GregScope, GT and OC, and runs all tests on it. No client classes: `ShippedClassesTest.noClientClassReferences` (unit test) | "No client classes loaded server-side" is checked **statically for GregScope's own classes**: no shipped class references `net/minecraft/client/` or `cpw/mods/fml/client/` or uses `@SideOnly(CLIENT)` (negative control above). It does not record which classes the server JVM actually loads, and it says nothing about GT, OC or other mods. The dev server is GregScope's dev dependency set (pinned to the beta-3 manifest), not the full 2.9.0-beta-3 pack. |
| Electric Blast Furnace | waiting (not a GS-004 row; needed for §12) | `ElectricBlastFurnaceSnapshotTests.recipeAboveCoilHeatIsWaiting` | Observed: `state` `waiting`, `statusId` and `recipeCheckResultId` `insufficient_heat`, `recipeCheckSuccessful` false, formed, allowed to work, not shut down, not active, `maxProgressTicks` 0, `energyStored` 16896 (hatch full), no warnings, input still in the bus. |

### Documentation

| item | where |
|---|---|
| installation | [opencomputers.md → Install](opencomputers.md#install), [README](../README.md) |
| OC Adapter placement | [opencomputers.md → Adapter placement](opencomputers.md#adapter-placement) |
| schema v1 and state meanings | [snapshot-schema-v1.md](snapshot-schema-v1.md) (keys, states, classification rules) |
| sample Lua script | [examples/gregscope-snapshot.lua](examples/gregscope-snapshot.lua), run in-game by `OpenComputersExampleScriptTests` |
| known omissions: recipe name, parallels, history | [snapshot-schema-v1.md → Known limitations](snapshot-schema-v1.md#known-limitations) |

## Definition of done (handoff §12)

Checked only where there is evidence. Evidence types: **test** (automated), **inspection** (code/build config read),
**manual** (someone ran it by hand).

- [x] **Builds reproducibly with pinned beta-3 dependencies.** Inspection: GT5U `5.09.54.133` and OC `1.12.61-GTNH`
  pinned in `dependencies.gradle`, with transitive constraints to the beta-3 manifest. Test: `./gradlew build` passes
  locally and in CI from a clean checkout. Byte-identical jars across machines were not checked.
- [x] **Starts on a dedicated server.** Test: every Horizon-QA run (local and CI) starts a dedicated server with
  GregScope loaded. Limit: dev dependency set, not the full pack (see the unchecked item below).
- [x] **Adds no blocks, items, recipes, GregScope-owned saved data, mixins, or global tick handlers.** Inspection:
  `usesMixins = false`, no access transformer or core mod in `gradle.properties`; `src/main` registers only an
  OpenComputers driver in `FMLInitializationEvent` (no `GameRegistry`, no event bus subscribers, no `WorldSavedData`);
  the release jar holds only GregScope classes, `mcmod.info` and `LICENSE`. OC may keep node data in an Adapter's own
  NBT, as the handoff allows. Tests (partial): `ShippedClassesTest.noPeriodicWorkHooks` (no `GameRegistry`, event bus,
  tick event, world generator or `TileEntity` subclass in the shipped bytecode) and
  `IdleCostTests.noGregScopeListenersTileEntitiesOrGenerators` (no GregScope event listener, registered or loaded tile
  entity, or `GameRegistry` entry on the running server). Blocks, items, recipes and saved data are not checked by a
  test beyond the missing `GameRegistry` reference.
- [x] **Supports both a normal GT basic machine and a GT multiblock controller.** Test: `BasicMachineSnapshotTests`,
  `ElectricBlastFurnaceSnapshotTests`, `OpenComputersComponentTests.basicMachineMergesIntoEnergyContainerComponent`,
  `multiblockControllerMergesIntoEnergyContainerComponent`.
- [x] **Correctly distinguishes shutdown, disabled, unformed, stuttering, output-blocked, running, waiting, and idle in
  the documented test cases.** In-game tests cover shutdown (`noMaintenanceShutsDown`, `powerLossMidRecipeShutsDown`),
  disabled, unformed, stuttering (`drainedMidRecipeIsPowerStarved[*]`), output-blocked, running and idle, with basic
  machines at LV, MV and HV (Electric Furnaces), and `waiting` on a real EBF whose recipe needs more heat than its coils
  (`ElectricBlastFurnaceSnapshotTests.recipeAboveCoilHeatIsWaiting`, GT's `insufficient_heat`). Limits: `waiting` is
  tested in-game only for that multiblock case; the basic-machine `waiting` rule (R5b, `machine_error`, Industrial
  Apiary) and other recipe-check failure IDs are unit-tested only (`StateClassifierTest.r11*`, `r5b*`).
- [x] **Returns stable reason IDs and never requires consumers to parse localized text.** Test: in-game tests assert
  `statusId`, `shutdownReasonId` and `recipeCheckResultId` values; the example script automates on `state`/`statusId`;
  unit tests cover ID normalization and unknown IDs kept verbatim. Documentation:
  [stable ID policy](snapshot-schema-v1.md#stable-id-policy).
- [x] **Coexists with OC's `gt_energyContainer` driver.** Test: `OpenComputersComponentTests` (merged
  `gt_energycontainer` component with both `getSnapshot` and `getStoredEU`; hatches left to OC; `lsc`/`bec_*` names
  kept), `ChunkReloadTests.basicMachineSurvivesChunkReload` (real Adapter).
- [x] **Survives target removal and chunk reload without exceptions.** Test:
  `OpenComputersComponentTests.destroyedMachineSoftErrors`, `AdapterBindingTests.*` (machine or Adapter removed next to
  a placed Adapter), `ChunkReloadTests.*` (soft error, no exception, no chunk reloaded by the call; Adapter in the
  machine's chunk and, for a basic machine, in the neighbouring chunk). Limits: blocks removed with `World.setBlock`,
  not by a player; no running computer attached during removal.
- [x] **Includes a working Lua example and schema documentation.** Test:
  `OpenComputersExampleScriptTests.exampleScriptRunsOnOpenOs[*]`, `exampleScriptShowsRunningEbfWithWarnings`.
  Documentation: [snapshot-schema-v1.md](snapshot-schema-v1.md), [examples/gregscope-snapshot.lua](examples/gregscope-snapshot.lua).
- [x] **Has no measurable idle cost when nobody calls the component.** **Structural evidence, not a benchmark: nothing
  was timed or profiled.** The tests show GregScope has nothing that could run periodically: no event bus listener
  (including the FML bus that carries tick events), no tile entity, no world generator
  (`IdleCostTests.noGregScopeListenersTileEntitiesOrGenerators`); its OC environment and the merged Adapter environment
  report `canUpdate() == false` and a real Adapter's tick list stays empty
  (`IdleCostTests.adapterNeverTicksGregScopeEnvironment`); the shipped bytecode references no tick, event, thread or
  timer API and declares no update hook (`ShippedClassesTest.noPeriodicWorkHooks`). Negative controls for both are
  above. GregScope code runs only when a script calls `getSnapshot()` or when OC asks the driver about a neighbouring
  block (Adapter neighbour changes); the cost of those calls, and memory held by the environment objects, was not
  measured.

Not part of §12, but still open before a release:

- [ ] **Manual:** boot the release jar in the real GTNH 2.9.0-beta-3 server pack (not the dev dependency set), attach
  an Adapter to a machine and run the example script. Not done yet.
