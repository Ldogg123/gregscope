# Testing

How GregScope v0.1 is verified, what each layer covers, and what is **not** covered yet.

GregScope is checked in two layers:

1. **Unit tests** (plain JVM, JUnit 5) for the pure snapshot core: classification, snapshot building and parsing.
2. **Horizon-QA in-game tests** on a real dedicated server with GT5-Unofficial 5.09.54.133 and OpenComputers
   1.12.61-GTNH: real machines, real OpenComputers drivers and Adapters, real chunk unloads, and the documented Lua
   example running on a real OpenOS computer.

Both run on every push and pull request in CI (`.github/workflows/build-and-test.yml`).

## Unit tests

Location: `src/test/java`. Run with `./gradlew test` (also part of `./gradlew build`). 298 tests in 19 classes:

| class | tests | covers |
|---|---|---|
| `ShippedClassesTest` | 5 | Reads every compiled class of the shipped mod (the `main` output on the test classpath) as bytes, without loading it, and checks its constant pool (every referenced class, member, descriptor and string literal) and super class. Fails on any reference to `net/minecraft/client/` or `cpw/mods/fml/client/` (slash or dot form), on `@SideOnly(CLIENT)`, and on periodic-work hooks: `SubscribeEvent`, `EventBus`, `TickEvent`, `FMLCommonHandler`, `MinecraftForge`, `GameRegistry`, `IWorldGenerator`, threads, timers, executors, members named `canUpdate`/`update`/`updateEntity`, or a `TileEntity` subclass. Also checks that the expected shipped classes were found and no test class was scanned, and that the reader sees an annotation descriptor and a string literal in a real class file. GS-101 (v0.2): `clientProxyIsOnlyNamedByTheSidedProxy` fails if any shipped class other than `ClientProxy` references `io/github/ldogg123/gregscope/ClientProxy` as a class (only the `@SidedProxy` strings in `GregScope` may name it), if the `@SidedProxy` strings do not name `ClientProxy`/`CommonProxy`, or if `ClientProxy` does not set `gregscope.clientProxyLoaded`; the expected-class list now also holds `ClientProxy`, `GregScopeTestHooks`, `config/Settings` and `config/GregScopeConfig`. The client-reference and periodic-work checks are unchanged in GS-101; GS-105/GS-108/GS-112 lift parts of them deliberately (design-v0.2 §1.4). GS-103 only added `model/StateCodes`, `history/MinuteSlot`, `history/SecondRing`, `sensor/SensorNbtCodec` and `sampling/LogHistogram` to the expected-class list; no check changed. GS-104 only added `access/AccessPolicy` and `access/GtnhlibTeamResolver` to that list; no check changed. |
| `config.SettingsTest` | 44 | GS-101. The §12.3 keys, ranges and defaults (pinned in the test), comments with range and default; empty file gives the defaults with no warning; valid values (including `FALSE`/`True` and padded integers) read without warning; each out-of-range integer clamped to the nearer bound with exactly one warning (18 cases, including `Integer.MIN_VALUE`/`MAX_VALUE`); `intervalTicks` snapped to the nearest of 20/40/60/100, ties to the larger (16 cases); unparseable integers and booleans fall back to the default; many bad values give exactly one warning naming every key; unknown keys (such as the reserved `exporter`) ignored; `Settings.Builder` rejects values the file would not allow; `toBuilder`/`equals`/`hashCode`; the `[pure]` import rule over `Settings`, `ConfigKeys` and `LifecyclePhase` sources. |
| `probe.StateClassifierTest` | 43 | Every classification rule R1-R13 in [schema v1](snapshot-schema-v1.md#classification-rules), rule precedence (for example R1 over R2, R3 over R4, R6 over R7), unknown and odd recipe-check IDs kept verbatim, null handling. |
| `probe.SnapshotBuilderTest` | 18 | Progress clamping, per-tick rates only while active, warnings, status text sources (GT text, fallbacks, GregScope-owned text), shutdown keys only when shut down, no basic/multiblock key leaking into the other kind, identity keys, no nulls and only allowed value types. |
| `probe.SnapshotParsingTest` | 9 | Defensive number parsing, saturating addition, generator `euPerTick`, reason ID normalization, formatting-code stripping. |
| `model.MachineSnapshotTest` | 8 | Canonical key order, required vs. optional keys (optional keys are omitted, never null), unmodifiable maps and warning lists. |
| `sensor.LabelsTest` | 31 | GS-103, design-v0.2 §3.5. 28 parameterized `Labels.sanitize` cases, each also checked for idempotence, the 32-code-point cap and no split or lone surrogate: `§x` pairs (any next code point, including a surrogate pair) and a lone trailing `§`; C0/C1 controls, tab and newline (ISO controls, so removed rather than turned into spaces); U+FEFF, U+2028/2029; Cf (ZWJ, RLO, soft hyphen); private use in the BMP and plane 15; lone high, lone low and reversed surrogates; whitespace runs including NBSP and U+3000 collapsed and trimmed; truncation to 32 code points with a surrogate pair exactly at the cap, 33 pairs cut to 32, and a trailing space exposed by the cut; `null`/empty. Plus: raw input over 64 UTF-16 units rejected before sanitizing (65 ASCII, 33 pairs = 66 units, `§a` x33); `shortId`; display-name fallbacks (label, then snapshot name or meta name + ` #shortId`, then `#shortId`). |
| `sensor.SensorNbtCodecTest` | 9 | GS-103, §3.3, over a typed in-memory `KeyValue`. Round trip of an owned identity with the exact keys and types (`gs` byte 1, `idM/idL`, `lbl`, `owM/owL`, `owN`, `ct`); unowned and empty label written as absent keys (stale keys removed); `d` missing, `gs` missing, `gs` of the wrong type, `gs=0` and a marker without an id read as inert ("inactive"); `gs=2`, `7` and `200` (unsigned) read as unsupported ("unsupported data version") and the compound is left verbatim (same keys, same value objects); label sanitized on read; missing optional keys; half an owner UUID is unowned; owner name capped at 16 units without splitting a pair; `withLabel`/`withId`. |
| `history.MinuteAccumulatorTest` | 20 | GS-103, §6.2/§7.4. Full minute closed by the next minute's sample; `expectedSamples` for 20/40/60/100 and invalid intervals; `stateSamples` and `samples` saturate at 255; min/avg/max with negative EU, rounding half away from zero, absent EU not averaged as 0, `Long.MAX_VALUE` sums without overflow, `Long.MIN_VALUE` never stored (it is the "none" sentinel); `energyStoredLast` is the last present value; `maintenanceMax` saturates; recipes delta across minutes (the first sample only sets the baseline) and the reset flag when GT's counter decreases; `serverTicks` only while open, saturating at 65,535; partial minute with a stored reason; gap seconds set the mask only; server-start-minute flag; an older minute folds into the open one; a minute older than the newest written one is refused and counted (clock skew), while the same minute may reopen; the §7.4 merge rule (saturation, OR, weighted average, min/max, last state and energy from the newer piece, recipes summed); two accumulator pieces merged by `MinuteRing.put`. |
| `history.MinuteSlotCodecTest` | 12 | GS-103, §7.4. CRC-16/CCITT-FALSE check value `"123456789"` -> `0x29B1`; golden bytes (`fixtures/v1/minute_slot_valid.hex`) encode and decode exactly; encode/decode at an offset; the torn fixture and every single-bit corruption of the valid slot read as `null`, and a torn slot in a loaded ring is an `unknown` gap; an all-zero slot is empty; CRC-valid slots with a state code >= 10, stored bit 6, an undefined flag or a recipes delta below -1 are rejected, reserved bytes are not checked; the stale fixture (one day older, same ring index, CRC valid) reads as missing once a newer slot is newest, and `put` refuses it; `floorMod` ring index; `put` merges the same minute and the ring holds exactly the bytes to write; loaded slots never overwrite valid RAM slots, and a slot at the wrong index is ignored; `GapReason` ids, bits, masks and second codes pinned. |
| `history.GapRangesTest` | 13 | GS-103, §7.5. Fully observed window has no gaps; missing minutes outside every run are `server_offline` (also with no runs), a minute partly inside a run is not; missing minutes while the registry says UNLOADED since a time at or before the minute get `chunk_unloaded`/`dimension_unloaded`, earlier ones `unknown`; offline wins over unloaded; stored gap slots use their stored reasons (one range per reason; overlapping ranges of different reasons are kept) and an empty mask is `unknown`, merged with adjacent missing minutes; stored reasons are not re-attributed by runs; state 0 with samples is a gap, a partial minute with samples is not; run resolution (a clean run keeps its stop; `Run.stopOnLoad` closes an unclean run at the loaded `saved` but never before its start; the ongoing run ends at now; a non-ongoing stop 0 claims no coverage); an unclean run followed by downtime, a restart and a later save keeps the outage `server_offline`; the range builder merges adjacent and overlapping ranges of the same reason only and returns an unmodifiable sorted list; a realistic unload/stop/restart timeline. |
| `history.SecondRingTest` | 11 | GS-103, §7.3. Empty ring; head order oldest first; wrap after 2x300+37 appends keeps the newest 300; same-second duplicates all kept; a backwards clock step stays in append order; newest-N with an exclusive `before`, fewer than N available, and `[from, to)` windows; clamping of maintenance (0..255) and progress (0..1 to x10000, NaN to 0), b7 always set, EU/energy kept only with their flags; gap entries for each stored reason (code = bit+1, flags 0); golden bytes for a sample and a gap second (`fixtures/v1/second_sample.hex`, `second_gap.hex`), including decoding the sample. |
| `history.LayoutSizesTest` | 6 | GS-103, §7.3/§7.4/§7.8/§8.2. 28 B per second sample (field widths add up), 300 entries = 8,400 B; 64 B minute slot (field widths add up), CRC over 62 B, 1,440 slots = 92,160 B; history file 92,224 B; 103,560 B RAM per sensor; the §7.8 table at 256 and 1,024 sensors (2.15/8.6 MB, 23.6/94.4 MB, 0.77/3.1 MB, 26.5/106 MB, files 23.6/94.4 MB, registry <= 64/256 KB raw, 16,384/65,536 B written per minute); the startup INFO line text. |
| `history.SummariesTest` | 5 | GS-103. State fractions over observed samples only, coverage over the whole window (missing minutes lower coverage, not the fractions); coverage adds each stored slot's own `expectedSamples` (a window mixing 60/min and 12/min slots, both directions) and the current setting only for missing minutes or a stored 0; an empty window gives NaN fractions and 0 coverage; EU average weighted by `euSamples`, minutes without EU not averaged as 0, recipes summed, reset flag and maintenance carried; gap-only slots add reasons but no samples. |
| `sampling.SamplingPrimitivesTest` | 5 | GS-103. `LogHistogram`: bucket edges, monotonic upper bounds that map back to their bucket for all 960 used buckets, `Long.MAX_VALUE` in bucket 959; p50/p90/p99/max of 10,000 log-distributed values reported at or above the exact value and at most 1/16 above; `copyFrom`/`clear`. `SensorCounters`: samples by state, EU consumed/generated estimates (`abs(EU/t) x interval`), gap seconds per stored reason, getters return copies, saturation at `Long.MAX_VALUE`, derived reasons rejected. `Clock`/`FakeClock`: epoch seconds and minutes rounded down (also for negative millis), `Clock.SYSTEM`. |
| `model.StateCodesTest` | 4 | GS-103, §7.1. Every `MachineState` has its pinned code, looked up by state id so that reordering the enum cannot move a code; codes are distinct and round-trip; the named constants; unknown codes and `null`. |
| `PureSourcesTest` | 8 | GS-103, the §2 `[pure]` rule, in three layers. Every GS-103 and GS-104 pure class (and the v0.1 enum `model/MachineState`, which `StateCodes` uses) exists and is marked `[pure]`; every source file in `history/`, `sensor/`, `sampling/` and `access/`, subpackages included, is `[pure]` unless listed as an intended MC adapter (GS-104 lists `access/GtnhlibTeamResolver.java`; later tickets must add theirs on purpose). Source denylist: no `[pure]` file has an import or qualified name (outside comments) from `net.minecraft`, `net.minecraftforge`, `cpw.mods`, `gregtech`, `gtPlusPlus`, `tectech`, GT5U's other root packages (`bartworks`, `bwcrossmod`, `detrav`, `galacticgreg`, `ggfab`, `goodgenerator`, `gtneioreplugin`, `gtnhintergalactic`, `gtnhlanth`, `kekztech`, `kubatech`, `toxiceverglades`), `li.cil`, `com.gtnewhorizon(s)`, `com.cleanroommc`, `java.lang.reflect`, `org.spongepowered`, `org.lwjgl`, `io.netty` or `com.google`. Source allowlist: `[pure]` imports are `java.*` or `[pure]` GregScope classes only, and no `[pure]` file names a listed adapter by simple name in code. Bytecode allowlist: every class referenced by a compiled `[pure]` class (inner classes included; constant pool, descriptors, signatures) is `java/*` (not `java/lang/reflect`) or a GregScope class whose source is `[pure]`. Negative-control cases check each scanner (denylist, import/adapter-name scanner, and the bytecode reader on `GtnhlibTeamResolver`/`AccessPolicy`). |
| `access.AccessPolicyTest` | 43 | GS-104, design-v0.2 §5, over an in-memory `FakeTeams` resolver (team T: owner, officer, two members; team X: owner; two teamless players). A 23-row truth table with the expected values written out per row (not derived from the rule): viewer (self, team owner, officer, member, other team, teamless, op, console) x sensor owner (member, team owner, teamless, unowned) -> `canView`, `canRename` with `renameRequiresOfficer` false and true, `canOpenHub`, `canPurge`, `isOp`. A 14-row `inHubScope` table (same team either direction, other team, teamless, null Hub or sensor owner). Op no-escalation: an op may open another player's Hub but its scope stays the Hub owner's team (the op's own, strangers' and unowned sensors stay out), while commands let the op see everything. `permissions.opLevel` from `Settings` (default 2; 0 makes every player an op without asking the permission check, because vanilla's check is false for any player not on the ops list; 1 still requires the check; 4 rejects level 3; the console is op at any level; the permission check is asked for exactly the configured level). Owner and op decisions make no team lookup; `sameTeam` null handling and self without lookup; `Viewer` argument checks. |
| `access.NoErrorLoggingTeamLookupTest` | 4 | GS-104, errata E4, as a grep over every file in `src/main` (sources and resources, comments included): no `getTeamByPlayer`, no `getOrCreateTeam` (it calls `getTeamByPlayer` first, GTNHLib 0.11.46 `TeamManager.java`), no `getTeamId` (team IDs are never read, so never stored). Only `access/GtnhlibTeamResolver.java` names `gtnhlib.teams`, and it contains the `TeamManager.getTeamMap()` scan with `team.isMember(player)`. A negative-control case checks that the scanner finds each name in code, a comment and a string. |

The unit tests use `TestReadings` fakes and do not load Minecraft, GT or OpenComputers classes (`ShippedClassesTest`
only reads class files as bytes). What GT actually returns is checked by the in-game tests.

**Golden fixtures (GS-103).** `src/test/resources/fixtures/v1/` holds the byte layouts of design-v0.2 §7.3/§7.4 as
commented hex (`#` starts a comment; one field per line): `minute_slot_valid.hex`, `minute_slot_torn.hex` (byte 33
flipped, old CRC kept), `minute_slot_stale.hex` (one day older, CRC valid), `second_sample.hex` and `second_gap.hex`.
They were generated by `tools/gen_fixtures.py`, a separate Python 3 script (standard library only) that lays out each
field with `struct` and computes CRC-16/CCITT-FALSE bitwise (asserting the `0x29B1` check value), not by the Java
encoder, so a matching test is an independent check of the layout. Regenerate from the repository root with
`python tools/gen_fixtures.py src/test/resources/fixtures/v1`; the result must be byte-identical to the committed files
(`git diff --exit-code src/test/resources/fixtures/v1`). New byte fixtures go into that script, never through the Java
encoder. `.gsh` and `registry.dat` fixtures belong to GS-109.

**GS-103 negative controls (2026-09-17), reverted afterwards (files restored from a backup copy, `cmp` identical).**
In one run: the CRC initial value changed from `0xFFFF` to `0x0000`; `import net.minecraft.world.World;` added to the
`[pure]` `sampling/Clock.java`; the pinned codes of `waiting` and `idle` swapped in `StateCodes.code`; and
`GapRanges.missingReason` returning `unknown` instead of `server_offline`. Result: 13 of 245 tests failed, exactly the
expected ones: `MinuteSlotCodecTest.crcCheckValue`, `goldenBytesEncode`, `goldenBytesDecode`, `staleEpochIsIgnored`
(the fixtures' CRCs no longer match); `PureSourcesTest.pureClassesReferenceNoGameClasses`;
`StateCodesTest.everyStateHasItsPinnedCode`, `codesRoundTripAndAreDistinct`; and six `GapRangesTest` cases that expect
`server_offline`. Note that `MinuteSlot` round trips and the torn-slot test still passed with the wrong CRC, which is why
the golden fixtures come from an independent generator.

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
under `io/github/ldogg123/gregscope/` (no `gametest` package), `mcmod.info`, `LICENSE`, `META-INF/MANIFEST.MF` and,
from v0.2, `assets/gregscope/` resources (GS-101: `lang/en_US.lang`), and no `horizonqa`, `.lua`, `fixtures/` or
`.hex` entries (GS-103 checked: the jar has the new `history/`, `sensor/`, `sampling/` and `model/StateCodes` classes
and no test content; GS-104 checked: the jar has `access/AccessPolicy`, `GtnhlibTeamResolver`, `TeamResolver` and
`Viewer`, and no `gametest`, `horizonqa`, `.lua`, `fixtures`, `.hex` or test classes).

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
- The probe benchmark (`ProbeBenchmarkTests`, GS-102) is opt-in. Without `-Dgregscope.bench=true` its 4 tests are
  reported as **skipped** (a Horizon-QA assumption, not a failure). To run it, add
  `--mcJvmArgs=-Dgregscope.bench=true` and select `gregscope:ProbeBenchmarkTests`; see
  [Probe benchmark](#probe-benchmark-gs-102).
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
| `LifecycleTests` | `gregscope.lifecycle`, `gregscope.lifecycle.hooks` | GS-101. `serverStarts`: dedicated server side; GregScope's container version is `Tags.VERSION`; lifecycle phase is `SERVER_STARTED` (every handler up to `serverStarted` ran, none after); `gregtech`, `OpenComputers`, `modularui2` and `gtnhlib` are both requirements and load-after dependencies and are loaded; the dev runtime has GTNHLib `0.11.46` and ModularUI2 `2.3.88-1.7.10`, and the `gtnhlib@[0.11.46,)` requirement accepts 0.11.46 and rejects 0.11.45; `config/gregscope.cfg` exists with every §12.3 key and its comment, no `exporter` category, and the settings loaded in preInit equal `Settings.fromRaw` of the file; the creative tab is in `CreativeTabs.creativeTabArray`. `testHooksReachGametestJvm` (own batch, it changes process-wide settings): `System.getProperty("gregscope.testHooks")` is `"true"` (set by `addon.gradle` on `runServer`/`runClient`, so CI's plain `runServer` gets it), `GregScopeTestHooks.enabled()`, and a settings override is applied and cleared. |
| `SafetyTests` | `gregscope.safety` | GS-101. `clientProxyNotLoaded`: `gregscope.clientProxyLoaded` is unset and the injected proxy is exactly `CommonProxy`. `handshakeRequiresMatchingClient`: FML's own network checker for GregScope (the `NetworkModHolder` that `FMLHandshakeServerState` consults through `checkModList(client, Side.CLIENT)`) rejects a client mod list of every other loaded mod without GregScope, rejects vanilla, rejects a different GregScope version and accepts the same version. A real client connection is still **manual** (GS-121). |
| `ChunkReloadTests` | `gregscope.reload.*` | Really unloads and reloads the chunks of an LV macerator with a real Adapter, an idle formed EBF and a running, soft-disabled EBF. Asserts new tile entity objects, soft errors while unloaded without reloading the chunk, the Adapter component's address and name, the startup-check state, persisted keys and resumed recipe progress. `adapterInOtherChunkSeesMachineAfterItsChunkReloads` puts an Adapter and an LV macerator on opposite sides of a chunk border (empty 17-wide template `gregscope:empty_17x1x5`, border computed at run time) and unloads and reloads only the machine's chunk: the Adapter's chunk stays loaded, the Adapter keeps the same component object and address, `getSnapshot` soft-errors while unloaded without loading the chunk and reads the reloaded machine in the same tick as the reload; OC's own `getStoredEU` on that component returns `0` after the reload (stale tile). |
| `ProbeBenchmarkTests` | `gregscope.bench` | GS-102, **opt-in** (`-Dgregscope.bench=true`; otherwise every test skips through `helper.assumeTrue`). Four scenarios, each built in a real state that is asserted first: LV Electric Furnace idle (`idle`/`none`) and running (`running`), GT's formed EBF template running a 400-tick EV recipe (`running`), and a TecTech **Active Transformer** built block by block in an empty 5x3x5 cell (`gregscope:empty_5x3x5`), formed by TecTech's own startup check and switched back on (`formed`, `allowedToWork`). Each then calls `GregTechMachineProbe.snapshot(te)` 1,000 times to warm up and 10,000 timed times inside one tick and logs `[GregScope bench]` p50/p99/max µs. Asserts completion (no `null` snapshot), not a time. |
| `TeamAccessTests` | `gregscope.access` | GS-104, against real GTNHLib 0.11.46 teams. Teams are made for unique fake UUIDs with `TeamManager.getOrCreateTeam(name, uuid)` plus `addOfficer`/`addMember`, and removed again in the same call by the gametest helper `TestTeams` (a `TeamManager` subclass, the only way to reach its protected tables; nothing is left for GTNHLib to save). `sameTeamResolvesGtnhlibTeams`: `GtnhlibTeamResolver.teamOf` finds each member's team and nothing for a teamless or null UUID; `sameTeam` both directions, across teams, teamless and self; `isOfficerOrOwner` for owner, officer and member; `AccessPolicy` view/open/rename (default and officer-only)/Hub scope and op no-escalation over the real resolver. `randomUuidLookupLogsNoError`: a Log4j test appender (`LogCapture`, on the root and `gtnhlib` loggers, each event counted once) sees no "Unable to find team" line for resolver and policy lookups of a random UUID; positive controls in the same capture: `getOrCreateTeam` for a new player logs it once, and `TeamManager.getTeamByPlayer(random)` logs it once (errata E4 still true). `cacheLastsOneRebuildAndTeamChangesApplyNext`: a join stays invisible to a resolver's cached "no team" until `clearCache`, while membership checks read the live team; a leave and a `TeamManager.mergeTeams` show on the next resolver, and an old resolver keeps its cached consumed team. The merge uses teams without team data (`TestTeams.createWithoutData`), see the GT5U crash note in design-v0.2 Implementation notes GS-104. All three finish in 0 ticks. |

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

**Negative controls (2026-09-17), GS-101, reverted afterwards (backup copies restored, `cmp` identical):**

- `systemProperty("gregscope.testHooks", "true")` commented out in `addon.gradle` and `@SidedProxy(serverSide=...)` set
  to `ClientProxy`, run with `-Dhorizonqa.tests=gregscope:LifecycleTests,gregscope:SafetyTests`: 2 of 4 failed,
  `testHooksReachGametestJvm` with "gregscope.testHooks property: expected <true> but found <null>" and
  `clientProxyNotLoaded` with "gregscope.clientProxyLoaded".
- `acceptableRemoteVersions = "*"` put back on `@Mod`, with `run/server/config/gregscope.cfg` edited to
  `maxSensors=5`, `intervalTicks=30`, `persist=maybe`: `handshakeRequiresMatchingClient` failed with "client without
  GregScope accepted" (1 of 4). The server log had exactly one GregScope config WARN: "gregscope.cfg: 3 values were
  invalid or out of range and adjusted: sampling.intervalTicks=30 is not allowed, using 40; limits.maxSensors=5 is not
  allowed, using 16; history.persist="maybe" is not true or false, using default true", and the loaded settings had
  `intervalTicks=40`, `maxSensors=16`. Forge itself rewrote `persist=maybe` to `true` in the file; the out-of-range
  integers stayed as written.
- `CommonProxy.init` logging `ClientProxy.class.getName()`: `ShippedClassesTest.clientProxyIsOnlyNamedByTheSidedProxy`
  failed.

**GS-104 negative controls (2026-09-17), reverted afterwards (files restored from a backup copy, identical).**

- `AccessPolicy.canRename` ignoring `renameRequiresOfficer`, and `GtnhlibTeamResolver.teamOf` calling
  `TeamManager.getTeamByPlayer`, in one unit run (`--tests io.github.ldogg123.gregscope.access.*`): 3 of 47 failed,
  exactly the `truthTable` rows `M(level 0) on owner O` and `O(level 0) on owner TO`, and
  `NoErrorLoggingTeamLookupTest.noErrorLoggingLookupOrTeamIdAnywhereInMain`.
- `GtnhlibTeamResolver.teamOf` calling `TeamManager.getTeamByPlayer` first (the map scan skipped when it finds a team),
  full `gregscope` Horizon-QA run: 1 of 58 failed, `TeamAccessTests.randomUuidLookupLogsNoError` with "resolver lookups
  logged: [ERROR [gtnhlib] Unable to find team for player ... (null), ...]: expected <0> but found <2>"; 53 passed,
  4 skipped.

**Review-fix negative controls (2026-09-17), reverted afterwards (files restored from a backup copy, `cmp` identical).**
In one unit run: `AccessPolicy` given a same-package `private static final Class<?> ADAPTER = GtnhlibTeamResolver.class`
(no import) and `isOp` without the `opLevel <= 0` case; `Summaries` counting every stored minute with the current
`expectedSamplesPerMinute`; and an unmarked `history/sub/Unmarked.java`. Result: 5 of 298 failed, exactly
`PureSourcesTest.purePackagesHoldOnlyPureOrListedClasses` (`history/sub/Unmarked.java`),
`pureClassesImportOnlyJavaAndPureClassesAndNameNoAdapter` and `compiledPureClassesReferenceOnlyJavaAndPureClasses`
(both naming `GtnhlibTeamResolver`), `AccessPolicyTest.opLevelComesFromSettings` and
`SummariesTest.coverageUsesEachSlotsStoredExpectedSamples` (expected 156, was 60). The old denylist test
`pureClassesReferenceNoGameClasses` still passed, which is the gap the new layers close. Before that, the first run of
the bytecode check failed on the real sources: `StateCodes` referenced the unmarked v0.1 enum `MachineState`, which is
now marked `[pure]`.

### Probe benchmark (GS-102)

Design-v0.2 §6.3 gate: design target ≤ 50 µs per sample at p99; 50 < p99 ≤ 100 µs lowers the default `maxSensors` to
128; p99 > 100 µs pulls the lean probe into v0.2.0.

Setup: local, Windows 11, Intel Core i7-14700K, 64 GB RAM, dev dependency set, dedicated server from `runServer`
(OpenJDK 64-Bit Server VM 1.8.0_492, Azul), 2026-09-17, code as in GS-102. Command:

```bash
./gradlew --no-daemon runServer   --mcJvmArgs=-Dhorizonqa.mode=ci   --mcJvmArgs=-Dhorizonqa.tests=gregscope:ProbeBenchmarkTests   --mcJvmArgs=-Dgregscope.bench=true   "--mcJvmArgs=-Dhorizonqa.reportDir=$(pwd -W)/build/horizonqa-bench"
```

Each cell is 10,000 timed `snapshot` calls after 1,000 warm-up calls; nearest-rank percentiles, µs. Runs 1 and 2 used
the world left by earlier runs, run 3 the world freshly created by the full run below. The four scenarios of one run
share one JVM and, as logged, ran in the order EBF, transformer, idle furnace, running furnace, so the EBF got the
coldest JIT state.

| scenario | run 1 p50 / p99 / max | run 2 p50 / p99 / max | run 3 p50 / p99 / max |
|---|---|---|---|
| LV Electric Furnace, idle | 1.4 / 3.2 / 59.7 | 1.4 / 4.8 / 225.3 | 1.4 / 3.1 / 142.0 |
| LV Electric Furnace, running | 1.2 / 2.2 / 61.4 | 1.2 / 1.9 / 82.7 | 1.2 / 2.2 / 62.1 |
| formed EBF, running | 4.0 / 16.3 / 22,853.4 | 3.2 / 14.4 / 1,132.4 | 3.3 / 12.6 / 798.1 |
| TecTech Active Transformer, formed (observed `waiting`/`no_routing`) | 2.9 / 7.4 / 349.5 | 2.8 / 6.7 / 253.3 | 2.4 / 11.1 / 523.0 |

(An earlier run with the transformer still disabled, before the empty template was added, gave 3.3 / 10.6 / 759.7 for
the EBF, 2.1 / 7.5 / 213.6 for the transformer, 1.4 / 5.6 / 118.3 and 1.3 / 3.0 / 102.6 for the furnace.)

**Result:** worst p99 is **16.3 µs** (EBF, run 1), well under 50 µs. **Decision (§6.3): keep the defaults**
(`limits.maxSensors=256`, `sampling.tickBudgetMicros=1000`); no lean-probe subtask. At 256 sensors and a 20-tick
interval that is 12.8 samples per tick, about 0.2 ms per tick at the EBF's p99, before the history work GS-108 adds.

**Caveats.**
- The single worst call is not bounded by these numbers: one EBF call took 22.9 ms in run 1 (others: 0.8-1.1 ms). A
  pause that long in one call is most likely a GC or safepoint pause rather than probe work (p99 is 16 µs), but it was
  not investigated. The §6.3 budget check runs before each sample, so such a call can overrun the per-tick budget
  once; GS-108's budget-exceeded counter will show how often that happens in practice.
- Machines do not change between calls (all calls in one tick), a warm JIT is shared across scenarios, and this is a
  fast desktop CPU on Java 8. A busy production server, Java 17+ with the full pack, or a slower CPU will be slower;
  there is 3x headroom to the 50 µs target at p99.
- The TecTech controller is formed but not routing (no EU supplied), so it reports `waiting` with a recipe-check text;
  a routing transformer was not measured.

### Last full run

Review fixes after GS-104, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world and without
`config/gregscope.cfg`** (both moved away first): **54 passed, 4 skipped** (`ProbeBenchmarkTests`, opt-in), 0 failed,
0 timed out, 0 issues; Horizon-QA status `passed`, exit code 0; Gradle wall time 1 min 3 s. Per class: AdapterBindingTests 2,
BasicMachineSnapshotTests 15, ChunkReloadTests 4, ElectricBlastFurnaceSnapshotTests 11, IdleCostTests 2, LifecycleTests 2,
OpenComputersComponentTests 8, OpenComputersExampleScriptTests 5, SafetyTests 2, TeamAccessTests 3 passed. The regenerated
cfg carries the new `opLevel` comment (checked by `LifecycleTests.serverStarts`). No in-game test was added. GitHub CI
has not run this state.

GS-104, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world** (`run/server/world` moved
away first): **54 passed, 4 skipped** (`ProbeBenchmarkTests`, opt-in), 0 failed, 0 issues; Horizon-QA status `passed`,
exit code 0; Gradle wall time 48 s. New: `TeamAccessTests.sameTeamResolvesGtnhlibTeams`, `randomUuidLookupLogsNoError`
and `cacheLastsOneRebuildAndTeamChangesApplyNext`, each passed in 0 ticks. A run reusing the previous world gave the
same result. The "Unable to find team" ERROR lines in the log are expected: two from `getOrCreateTeam` in
`sameTeamResolvesGtnhlibTeams`, the two positive controls of `randomUuidLookupLogsNoError`, and one from GT's own team
lookup for a FakePlayer owner during `OpenComputersComponentTests` (v0.1 behaviour, not a GregScope
call). GitHub CI has not run this state.

GS-103, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world** (`run/server/world` moved
away first): **51 passed, 4 skipped** (`ProbeBenchmarkTests`, opt-in), 0 failed, 0 issues; Horizon-QA status `passed`,
exit code 0; Gradle wall time 45 s. A run reusing the previous world gave the same result. GS-103 adds no in-game tests
(its classes are pure and covered by the unit tests); the run confirms nothing regressed. GitHub CI has not run this
state.

GS-102, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world** (`run/server/world` moved
away first), without `-Dgregscope.bench`: **51 passed, 4 skipped** (the 4 `ProbeBenchmarkTests`, reason "probe
benchmark is opt-in"), 0 failed, 0 issues; Horizon-QA status `passed`, exit code 0; Gradle wall time 44 s. GitHub CI
has not run this state.

GS-101, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world and without
`config/gregscope.cfg`** (both moved away first, so the config was generated by the run): **51/51 passed** (the 47 v0.1
tests plus `LifecycleTests.serverStarts`, `LifecycleTests.testHooksReachGametestJvm`, `SafetyTests.clientProxyNotLoaded`
and `SafetyTests.handshakeRequiresMatchingClient`, each 0 ticks), 0 skipped, 0 issues; exit code 0; Gradle wall time
45 s. The only GregScope WARN was the test-hooks notice. A run reusing the previous world also passed 51/51. GitHub CI
has not run this state.

v0.1 run, kept for the per-test timings below:

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

- [x] **Manual:** boot the release jar in the real GTNH 2.9.0-beta-3 server pack (not the dev dependency set). Done
  2026-09-17 with `gregscope-95c928f.jar` on the official `GT_New_Horizons_2.9.0-beta-3_Server_Java_17-26` pack
  (Java 25, dedicated server): booted in 28 s, `gregscope` in FML's mod list, clean stop, and no `ERROR` line or
  exception that the same pack does not already log without GregScope (diffed against a baseline boot).
- [ ] **Manual:** in that real pack, attach an Adapter to a machine and run the example script on a computer. Not done
  in the real pack; the same script runs on a real OpenOS computer against a real Adapter in the dev dependency set
  (`OpenComputersExampleScriptTests`).
