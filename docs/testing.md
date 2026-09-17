# Testing

How GregScope v0.1 is verified, what each layer covers, and what is **not** covered yet.

GregScope is checked in two layers:

1. **Unit tests** (plain JVM, JUnit 5) for the pure snapshot core: classification, snapshot building and parsing.
2. **Horizon-QA in-game tests** on a real dedicated server with GT5-Unofficial 5.09.54.133 and OpenComputers
   1.12.61-GTNH: real machines, real OpenComputers drivers and Adapters, real chunk unloads, and the documented Lua
   example running on a real OpenOS computer.

Both run on every push and pull request in CI (`.github/workflows/build-and-test.yml`).

## Unit tests

Location: `src/test/java`. Run with `./gradlew test` (also part of `./gradlew build`). 78 tests in 4 classes:

| class | tests | covers |
|---|---|---|
| `probe.StateClassifierTest` | 43 | Every classification rule R1-R13 in [schema v1](snapshot-schema-v1.md#classification-rules), rule precedence (for example R1 over R2, R3 over R4, R6 over R7), unknown and odd recipe-check IDs kept verbatim, null handling. |
| `probe.SnapshotBuilderTest` | 18 | Progress clamping, per-tick rates only while active, warnings, status text sources (GT text, fallbacks, GregScope-owned text), shutdown keys only when shut down, no basic/multiblock key leaking into the other kind, identity keys, no nulls and only allowed value types. |
| `probe.SnapshotParsingTest` | 9 | Defensive number parsing, saturating addition, generator `euPerTick`, reason ID normalization, formatting-code stripping. |
| `model.MachineSnapshotTest` | 8 | Canonical key order, required vs. optional keys (optional keys are omitted, never null), unmodifiable maps and warning lists. |

The unit tests use `TestReadings` fakes and do not load Minecraft, GT or OpenComputers classes. What GT actually
returns is checked by the in-game tests.

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
timeout the suite would take 1800 ticks (90 s at 20 TPS), so a hang still ends with a Horizon-QA report inside the
CI budget. A normal run takes about 16 s of test time (see the last full run below).

### Test classes

| class | batch | what it does |
|---|---|---|
| `BasicMachineSnapshotTests` | `gregscope.basic` | LV Electric Furnace (`MTEBasicMachine`) driven through idle, running, disabled, power-starved and output-blocked; asserts the probe's `state`/`statusId` and related keys. |
| `ElectricBlastFurnaceSnapshotTests` | `gregscope.ebf` | GT's own formed EBF template: starting, idle, running, disabled while idle and while running, maintenance warning, no-maintenance shutdown, broken structure, full output bus, power loss. |
| `OpenComputersComponentTests` | `gregscope.oc` | GregScope's driver through OpenComputers' own driver registry, as an Adapter uses it: merging into `gt_energycontainer` (basic machine and controller), `getStoredEU` still present, soft error after the machine is destroyed, hatches left to OC's energy driver, LSC and BEC controllers keep their `lsc` / `bec_*` names. |
| `OpenComputersExampleScriptTests` | `gregscope.oc.openos`, `gregscope.oc.openos.ebf` | Boots a real OpenOS computer (creative case, real Adapter) and runs the unmodified example script. Output is compared exactly with the probe's snapshot: an idle LV macerator on Lua 5.3, Lua 5.2, Lua 5.4 and LuaJ, and a running EBF with maintenance and work-disabled warnings on Lua 5.3. Native architectures skip (not pass) if OC cannot load the native library on that OS/arch. |
| `ChunkReloadTests` | `gregscope.reload.*` | Really unloads and reloads the chunks of an LV macerator with a real Adapter, an idle formed EBF and a running, soft-disabled EBF. Asserts new tile entity objects, soft errors while unloaded without reloading the chunk, the Adapter component's address and name, the startup-check state, persisted keys and resumed recipe progress. |

Helpers (`GtPlacement`, `OcComponents`, `OcFileSystem`, `Snapshots`, `StubContext`, `ChunkReload`) are test-only.

### Last full run

Local, Windows, 2026-09-17, selector `gregscope`: **31/31 passed**, 0 skipped, 0 issues; exit code 0; Gradle wall time
47 s, about 16 s from server `Done` to shutdown. Slowest tests (ticks / seconds as Horizon-QA reports them):

| test | ticks | s |
|---|---|---|
| `exampleScriptRunsOnOpenOs[luaj]` | 154 | 7.70 |
| `exampleScriptRunsOnOpenOs[lua53]`, `[lua52]`, `[lua54]` (parallel) | 153 | 7.65 |
| `exampleScriptShowsRunningEbfWithWarnings` | 140 | 7.00 |
| `namedControllerKeepsOcComponentName[*]`, `basicMachineMerges…`, `destroyedMachineSoftErrors` | 5 | 0.25 |
| `ChunkReloadTests` (each) | 3 | 0.15 |

All other tests finish in 0-2 ticks because they use Horizon-QA's time warp.

## GS-004 coverage

Every row and condition of [handoff §11 GS-004](handoff.md#gs-004--integration-validation-and-documentation). Test
names are `Class.method`.

### Machines and conditions

| machine | condition | covered by | notes |
|---|---|---|---|
| MV/HV basic machine | idle | `BasicMachineSnapshotTests.poweredIdle`; also `OpenComputersComponentTests.basicMachineMergesIntoEnergyContainerComponent` (via OC) | **Tier differs:** tested on an **LV** Electric Furnace / LV Macerator, not MV/HV. GregScope has no tier-dependent code path, but MV/HV machines were not exercised. |
| MV/HV basic machine | running | `BasicMachineSnapshotTests.runningRecipe` | LV, see above. |
| MV/HV basic machine | disabled | `BasicMachineSnapshotTests.disabledWhileIdle` | LV. Disabled while running is covered only for the EBF. |
| MV/HV basic machine | starved of power | `BasicMachineSnapshotTests.drainedMidRecipeIsPowerStarved` | LV. Buffer emptied mid-recipe (stutter, `power_starved`). The empty-buffer-without-recipe case reads `idle` by design (schema known limitations). Steam starvation: unit tests only (`StateClassifierTest.r5SteamStarved`). |
| MV/HV basic machine | output blocked | `BasicMachineSnapshotTests.foreignItemInOutputSlotIsOutputBlocked` | LV, item output. Fluid-output blockage reads `idle` (documented limitation); steam vent blocked is unit-tested only (`r8SteamVentBlocked`). |
| Electric Blast Furnace | unformed | `ElectricBlastFurnaceSnapshotTests.brokenStructureIsUnformed`; `freshTemplateIsStarting` (startup check) | |
| Electric Blast Furnace | formed/idle | `ElectricBlastFurnaceSnapshotTests.idleAfterStartup` | |
| Electric Blast Furnace | running | `ElectricBlastFurnaceSnapshotTests.runningRecipe`; `OpenComputersExampleScriptTests.exampleScriptShowsRunningEbfWithWarnings` | |
| Electric Blast Furnace | maintenance warning | `ElectricBlastFurnaceSnapshotTests.maintenanceWarningWhileRunning`; `noMaintenanceShutsDown` (`no_repair` shutdown); `exampleScriptShowsRunningEbfWithWarnings` | |
| Electric Blast Furnace | output full | `ElectricBlastFurnaceSnapshotTests.fullOutputBusIsOutputBlocked` | Item output bus. A full output hatch (fluid) is unit-tested only (`r10FluidOutputFull`). |
| Electric Blast Furnace | power-loss shutdown | `ElectricBlastFurnaceSnapshotTests.powerLossMidRecipeShutsDown` | |
| Electric Blast Furnace | manually disabled | `ElectricBlastFurnaceSnapshotTests.disabledWhileIdle`, `disabledWhileRunningKeepsRunning`; `ChunkReloadTests.runningEbfKeepsRecipeProgressAcrossChunkReload` (disabled switch survives reload) | Disabled through GT's API (`disableWorking()`), not a player's soft-mallet click. |
| OC Adapter | attach | Real Adapter block: `ChunkReloadTests.basicMachineSurvivesChunkReload`, `OpenComputersExampleScriptTests.*`. Driver registry as an Adapter uses it: `OpenComputersComponentTests.*` | |
| OC Adapter | detach | `OpenComputersComponentTests.destroyedMachineSoftErrors` (machine removed, component returns `nil, "machine unavailable"`) | **Partly covered.** The machine is destroyed, not the Adapter, and the environment comes from OC's driver registry, not a placed Adapter. Breaking the Adapter itself and OC removing the component from a running computer are **not covered**. |
| OC Adapter | chunk unload/reload | `ChunkReloadTests.basicMachineSurvivesChunkReload`, `ebfControllerRestartsStartupCheckAfterChunkReload`, `runningEbfKeepsRecipeProgressAcrossChunkReload` | Adapter and machine in the **same chunk** only. An Adapter in a different chunk from the machine is **not covered**; OC's source suggests the component stays bound to the old tile until the Adapter reloads (see [opencomputers.md](opencomputers.md#reload-and-restart)). Lua-visible `component_removed`/`component_added` signals are not covered. |
| OC Adapter | server restart | **Not covered as a restart.** `ChunkReloadTests` cover the tile-entity save/load path a restart uses, and CI starts a fresh dedicated server each run. | A real stop/start of the same world (static state rebuilt, chunk-loader tickets, OC computer state) is not tested; it would need a second server process, which Horizon-QA does not provide. |
| Dedicated server | startup smoke test | Every CI and local Horizon-QA run starts a dedicated server (`runServer`) with GregScope, GT and OC, and runs all tests on it. | **"No client classes loaded server-side" is not asserted.** GregScope has no client-only code and a common proxy on both sides, and the server starts without errors, but no test checks which classes were loaded. The dev server is GregScope's dev dependency set (pinned to the beta-3 manifest), not the full 2.9.0-beta-3 pack. |

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
  NBT, as the handoff allows. Not tested automatically.
- [x] **Supports both a normal GT basic machine and a GT multiblock controller.** Test: `BasicMachineSnapshotTests`,
  `ElectricBlastFurnaceSnapshotTests`, `OpenComputersComponentTests.basicMachineMergesIntoEnergyContainerComponent`,
  `multiblockControllerMergesIntoEnergyContainerComponent`.
- [ ] **Correctly distinguishes shutdown, disabled, unformed, stuttering, output-blocked, running, waiting, and idle in
  the documented test cases.** In-game tests cover shutdown (`noMaintenanceShutsDown`, `powerLossMidRecipeShutsDown`),
  disabled, unformed, stuttering (`drainedMidRecipeIsPowerStarved`), output-blocked, running and idle. **`waiting` has
  no in-game test**: it is covered only by unit tests (`StateClassifierTest.r11*`, `r5b*`), because no GS-004 scenario
  produces it. Basic machines were tested at LV, not MV/HV.
- [x] **Returns stable reason IDs and never requires consumers to parse localized text.** Test: in-game tests assert
  `statusId`, `shutdownReasonId` and `recipeCheckResultId` values; the example script automates on `state`/`statusId`;
  unit tests cover ID normalization and unknown IDs kept verbatim. Documentation:
  [stable ID policy](snapshot-schema-v1.md#stable-id-policy).
- [x] **Coexists with OC's `gt_energyContainer` driver.** Test: `OpenComputersComponentTests` (merged
  `gt_energycontainer` component with both `getSnapshot` and `getStoredEU`; hatches left to OC; `lsc`/`bec_*` names
  kept), `ChunkReloadTests.basicMachineSurvivesChunkReload` (real Adapter).
- [x] **Survives target removal and chunk reload without exceptions.** Test:
  `OpenComputersComponentTests.destroyedMachineSoftErrors`, `ChunkReloadTests.*` (soft error, no exception, no chunk
  reloaded by the call). Limits: removal is tested through the driver registry, not a placed Adapter; chunk reload only
  with the Adapter in the machine's chunk.
- [x] **Includes a working Lua example and schema documentation.** Test:
  `OpenComputersExampleScriptTests.exampleScriptRunsOnOpenOs[*]`, `exampleScriptShowsRunningEbfWithWarnings`.
  Documentation: [snapshot-schema-v1.md](snapshot-schema-v1.md), [examples/gregscope-snapshot.lua](examples/gregscope-snapshot.lua).
- [ ] **Has no measurable idle cost when nobody calls the component.** **Not measured.** By inspection GregScope does
  no periodic work: no tick handlers, and its OC environment does not override `canUpdate()` (OC's prefab default is
  `false`), so it only runs when a script calls `getSnapshot()` or an Adapter checks its neighbours. No profiling or
  tick-time comparison has been done.

Not part of §12, but still open before a release:

- [ ] **Manual:** boot the release jar in the real GTNH 2.9.0-beta-3 server pack (not the dev dependency set), attach
  an Adapter to a machine and run the example script. Not done yet.
