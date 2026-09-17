# Testing

How GregScope v0.1 is verified, what each layer covers, and what is **not** covered yet.

GregScope is checked in two layers:

1. **Unit tests** (plain JVM, JUnit 5) for the pure snapshot core: classification, snapshot building and parsing.
2. **Horizon-QA in-game tests** on a real dedicated server with GT5-Unofficial 5.09.54.133 and OpenComputers
   1.12.61-GTNH: real machines, real OpenComputers drivers and Adapters, real chunk unloads, and the documented Lua
   example running on a real OpenOS computer.

Both run on every push and pull request in CI (`.github/workflows/build-and-test.yml`).

## Unit tests

Location: `src/test/java`. Run with `./gradlew test` (also part of `./gradlew build`). 398 tests in 27 classes:

| class | tests | covers |
|---|---|---|
| `ShippedClassesTest` | 7 | Reads every compiled class of the shipped mod (the `main` output on the test classpath) as bytes, without loading it, and checks its constant pool (every referenced class, member, descriptor and string literal) and super class. Fails on any reference to `net/minecraft/client/` or `cpw/mods/fml/client/` (slash or dot form), on `@SideOnly(CLIENT)`, and on periodic-work hooks: `SubscribeEvent`, `EventBus`, `TickEvent`, `FMLCommonHandler`, `MinecraftForge`, `GameRegistry`, `IWorldGenerator`, threads, timers, executors, members named `canUpdate`/`update`/`updateEntity`, or a `TileEntity` subclass. Also checks that the expected shipped classes were found and no test class was scanned, and that the reader sees an annotation descriptor and a string literal in a real class file. GS-101 (v0.2): `clientProxyIsOnlyNamedByTheSidedProxy` fails if any shipped class other than `ClientProxy` references `io/github/ldogg123/gregscope/ClientProxy` as a class (only the `@SidedProxy` strings in `GregScope` may name it), if the `@SidedProxy` strings do not name `ClientProxy`/`CommonProxy`, or if `ClientProxy` does not set `gregscope.clientProxyLoaded`; the expected-class list now also holds `ClientProxy`, `GregScopeTestHooks`, `config/Settings` and `config/GregScopeConfig`. The client-reference and periodic-work checks are unchanged in GS-101; GS-105/GS-108/GS-112 lift parts of them deliberately (design-v0.2 §1.4). GS-103 only added `model/StateCodes`, `history/MinuteSlot`, `history/SecondRing`, `sensor/SensorNbtCodec` and `sampling/LogHistogram` to the expected-class list; no check changed. GS-104 only added `access/AccessPolicy` and `access/GtnhlibTeamResolver` to that list; no check changed. GS-107 likewise only added `registry/SensorRegistryCore`, `registry/SensorEntry`, `registry/SensorState` and `registry/SensorRegistry` to that list; no check changed. GS-105 lifts the `GameRegistry` part of the periodic-work check deliberately and narrowly (design-v0.2 §1.4, "no items" lifted): only `sensor/SensorCovers` may reference `cpw/mods/fml/common/registry/GameRegistry`, and the new `gameRegistryIsUsedOnlyToRegisterTheSensorItem` requires that it does, that it calls `registerItem`, and that it references no `register*` name other than `registerItem` and GT's `registerCover`; the member names `registerWorldGenerator`, `registerTileEntity`, `registerTileEntityWithAlternatives` and `registerBlock` are now forbidden in every shipped class (GS-112 lifts tile entity and block registration for the Hub). The client-reference check is unchanged: the item sets its icon with `setTextureName`, and neither the item nor the cover overrides a client-only method with `@SideOnly(CLIENT)` or references a client class. The expected-class list adds `GregScopeAssets` and the `sensor/` classes `ItemMachineSensor`, `MachineSensorCover`, `SensorCovers`, `SensorCover` and `SensorKind`. GS-106 changed no check either: it only adds `sensor/NbtKeyValue`, `sensor/SensorEvents` and `sensor/SensorDescription` to the expected-class list. The cover reads and writes NBT, reports to a listener and builds its description without touching a client class, a tick hook or `GameRegistry`. GS-108 lifts the tick part of the periodic-work check, deliberately and narrowly (design-v0.2 §1.4, "no global tick handler" lifted for exactly one handler): only `sampling/TelemetrySampler` may reference `SubscribeEvent`, `EventBus`, `TickEvent` and `FMLCommonHandler`, and the new `theOnlyTickHandlerIsTheSampler` requires that it really references all four (so the skip cannot pass vacuously), that it names `TickEvent$ServerTickEvent` and `TickEvent$Phase`, and that it uses none of the other forbidden references (threads, timers, executors, `MinecraftForge`, `GameRegistry`, world generators). Every other shipped class is still forbidden all of them. That there is exactly **one** `@SubscribeEvent` method is checked on the running server by `IdleCostTests.exactlyOneServerTickHandler`, because this reader sees the constant pool, not the method table. The expected-class list adds `sampling/SamplerSchedule`, `SampleFolder`, `TelemetryFrame`, `SensorView`, `TargetResolver` and `TelemetrySampler`. |
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
| `PureSourcesTest` | 8 | GS-103, the §2 `[pure]` rule, in three layers. Every GS-103 and GS-104 pure class (and the v0.1 enum `model/MachineState`, which `StateCodes` uses) exists and is marked `[pure]`; every source file in `history/`, `sensor/`, `sampling/` and `access/`, subpackages included, is `[pure]` unless listed as an intended MC adapter (GS-104 lists `access/GtnhlibTeamResolver.java`; later tickets must add theirs on purpose). Source denylist: no `[pure]` file has an import or qualified name (outside comments) from `net.minecraft`, `net.minecraftforge`, `cpw.mods`, `gregtech`, `gtPlusPlus`, `tectech`, GT5U's other root packages (`bartworks`, `bwcrossmod`, `detrav`, `galacticgreg`, `ggfab`, `goodgenerator`, `gtneioreplugin`, `gtnhintergalactic`, `gtnhlanth`, `kekztech`, `kubatech`, `toxiceverglades`), `li.cil`, `com.gtnewhorizon(s)`, `com.cleanroommc`, `java.lang.reflect`, `org.spongepowered`, `org.lwjgl`, `io.netty` or `com.google`. Source allowlist: `[pure]` imports are `java.*` or `[pure]` GregScope classes only, and no `[pure]` file names a listed adapter by simple name in code. Bytecode allowlist: every class referenced by a compiled `[pure]` class (inner classes included; constant pool, descriptors, signatures) is `java/*` (not `java/lang/reflect`) or a GregScope class whose source is `[pure]`. Negative-control cases check each scanner (denylist, import/adapter-name scanner, and the bytecode reader on `GtnhlibTeamResolver`/`AccessPolicy`). GS-105 adds the pure `GregScopeAssets`, `sensor/SensorKind` and `sensor/SensorCover`, and lists the sensor package's MC adapters `ItemMachineSensor`, `MachineSensorCover` and `SensorCovers` as intended adapters. GS-106 adds the pure `sensor/SensorDescription` and lists two more intended adapters, `sensor/NbtKeyValue` (the `NBTTagCompound` side of the `KeyValue` seam) and `sensor/SensorEvents` (GT types in its signatures). GS-107 adds the `registry` and `model` packages to the scanned set: the pure `registry/SensorState`, `RemovalCause`, `PosKey`, `SensorEntry`, `RegistryEvents` and `SensorRegistryCore`, the v0.1 `model/MachineSnapshot`, `MachineKind`, `SnapshotKeys` and `StatusIds` (pure in fact since v0.1, marked and now enforced because a registry entry holds a snapshot), and the intended adapter `registry/SensorRegistry`. GS-108 adds the pure `sampling/SamplerSchedule`, `SampleFolder`, `SamplerStats`, `SamplerStatsView`, `LimitsView`, `CountersView`, `MachineCountersView`, `SensorView` and `TelemetryFrame` (and pins `config/Settings` and `config/ConfigKeys`, which a `LimitsView` copies), and lists two more intended adapters, `sampling/TargetResolver` (the only telemetry class touching `World`) and `sampling/TelemetrySampler` (the one tick handler). |
| `access.AccessPolicyTest` | 43 | GS-104, design-v0.2 §5, over an in-memory `FakeTeams` resolver (team T: owner, officer, two members; team X: owner; two teamless players). A 23-row truth table with the expected values written out per row (not derived from the rule): viewer (self, team owner, officer, member, other team, teamless, op, console) x sensor owner (member, team owner, teamless, unowned) -> `canView`, `canRename` with `renameRequiresOfficer` false and true, `canOpenHub`, `canPurge`, `isOp`. A 14-row `inHubScope` table (same team either direction, other team, teamless, null Hub or sensor owner). Op no-escalation: an op may open another player's Hub but its scope stays the Hub owner's team (the op's own, strangers' and unowned sensors stay out), while commands let the op see everything. `permissions.opLevel` from `Settings` (default 2; 0 makes every player an op without asking the permission check, because vanilla's check is false for any player not on the ops list; 1 still requires the check; 4 rejects level 3; the console is op at any level; the permission check is asked for exactly the configured level). Owner and op decisions make no team lookup; `sameTeam` null handling and self without lookup; `Viewer` argument checks. |
| `access.NoErrorLoggingTeamLookupTest` | 4 | GS-104, errata E4, as a grep over every file in `src/main` (sources and resources, comments included): no `getTeamByPlayer`, no `getOrCreateTeam` (it calls `getTeamByPlayer` first, GTNHLib 0.11.46 `TeamManager.java`), no `getTeamId` (team IDs are never read, so never stored). Only `access/GtnhlibTeamResolver.java` names `gtnhlib.teams`, and it contains the `TeamManager.getTeamMap()` scan with `team.isMember(player)`. A negative-control case checks that the scanner finds each name in code, a comment and a string. |
| `AssetsExistTest` | 5 | GS-105, design-v0.2 §3.2/§12.2/§14. Finds the constants of `GregScopeAssets` by naming convention (reflection, test code only), so a new constant is checked without editing the test: every `BLOCK_ICON_*` must be a PNG at `assets/gregscope/textures/blocks/<name>.png` and every `ITEM_ICON_*` one at `textures/items/<name>.png`, read from the test classpath (the processed `main` resources that go into the jar), with the PNG signature, an `IHDR` of 16x16, bit depth 8 and colour type 6 (RGBA), decodable by `ImageIO` with an alpha channel and at least one non-transparent pixel. Every `LANG_*` key (except `*_PREFIX`), plus `gregscope.state.<id>` for every `MachineState` and `gregscope.gap.<id>` for every `GapReason`, exists with a non-empty value in `lang/en_US.lang`; the §12.2 tooltip texts are pinned. The design names are pinned: `iconsets/GREGSCOPE_SENSOR_OVERLAY` (errata E1), `machine_sensor`, `gregscope.machine_sensor`, `item.gregscope.machine_sensor.name`. The lang file is ASCII with one `key=value` per line (`#` comments, as 1.7.10's `LanguageMap` reads it) and no duplicate keys; a missing resource is detected. |
| `sensor.SensorKindTest` | 2 | GS-105 with the design-v0.3 GS-201 A2 hook. Kind codes 0/1/2 and labels `machine`, `item_flow`, `fluid_flow` pinned (`unknown` otherwise); v0.2 supports only `machine`. |
| `sensor.SensorDescriptionTest` | 8 | GS-106, design-v0.2 §3.4. The cover status line: id only, id with a label, id with a label and the registry's availability word, and the two inert forms (`GregScope sensor (inactive)` and `(unsupported data version)`), including an availability word being ignored while inert. `SensorIdentity.forAttach` (the §3.4 owner chain): the attaching player wins over the machine owner, no player falls back to the machine owner, an unowned machine gives an unowned sensor (owner and owner name both null), and the stack's display name is sanitized into the label. |
| `registry.SensorRegistryCoreTest` | 38 | GS-107, design-v0.2 §4.2/§4.3 and the design-v0.3 GS-201 A1 rules, over a fake clock, a fake team resolver, a queued UUID supplier and counting maps. One test per §4.3 row: unknown heartbeat registers LIVE and allocates the rings; the global cap refuses the 17th sensor of 16 and counts `quotaRefusedTotal`; a refused UUID is re-checked only every 1,200 ticks (and a throttled retry costs no cap scan); the per-team cap counts teammates, survives a merge (existing sensors kept, new ones refused), treats all unowned sensors as one pseudo-owner and means unlimited at 0; the LIVE fast path does exactly one id lookup and touches neither the reverse index nor the clock, the teams nor the events; a duplicate of a LIVE or an UNLOADED sensor re-keys the newcomer and leaves the original where it is; another UUID at the same (position, side) is REPLACED, another Machine Sensor on any face of the same block is REPLACED too, while a kind-1 cover on another face of that block is not (A1); a known UUID with another kind is re-keyed (A1) and an unregistered kind gets no entry at all; an unload closes the open minute with `chunk_unloaded`, keeps the minute ring, drops the second ring and still counts; MISSING, IN_ITEM and REMOVED all resume LIVE at a new position on the same entry object, and a resume is refused when the cap is full; three strikes in a row become MISSING, a successful validation resets them, and each strike records a `target_missing` second and gap; detach and destroy give REMOVED/IN_ITEM with the partial minute queued first; tombstones expire after `removedRetentionHours` and UNLOADED entries after `staleExpiryDays` (both exactly at the window are kept), LIVE never expires, and the oldest tombstone is evicted first beyond `maxSensors`; purge expires at once and the same UUID registers again with empty history; `purgeAll` also clears the refusal window; an empty registry rebuilds itself from heartbeats. Also the `PosKey` packing (lossless at the world limits, dim and side part of the key) and the pinned persisted state and cause codes. |
| `sampling.SamplerScheduleTest` | 17 | GS-108, design-v0.2 §6.1/§6.3, over a fake `nanoTime` that only moves when a sample is taken, so the budget arithmetic is exact rather than timing-dependent. Bucket balance at 0, 1, 19, 20, 21, 256 and 1024 sensors (no bucket differs by more than one); the first sensors fill the lowest buckets in order; an emptied bucket is refilled first and ties go to the lowest index; removal is an O(1) swap that leaves every surviving sensor findable at its recorded slot, is idempotent, and drops the sensor from the carry-over list; adding the same sensor twice schedules it once; an interval of 0 and a null clock are rejected. Tick loop: an empty schedule and a tick whose own bucket is empty read `nanoTime` **zero** times and report no work; every sensor is sampled exactly once per interval and the interval ends on the last bucket index; 400 us per sample against a 1,000 us budget gives exactly 3 samples and carries the rest, with `cycleNanos` 1,200 us; carry-over is served before the new bucket; the due bucket carries over too when the carry-over list alone spent the budget (regression, see the GS-108 notes); a sensor carried for a whole interval is given up on and only then; a sensor that removes itself from the schedule while it is being sampled (what a sample that finds its chunk unloaded does) costs no other sensor its turn; `sampling.enabled=false` skips everything due and carries nothing; `clear` empties everything. |
| `sampling.SampleFolderTest` | 13 | GS-108, design-v0.2 §6.2, over entries from a real `SensorRegistryCore` and hand-built snapshots (no Minecraft). One sample reaches the second ring with the right state code, flags, maintenance, progress x10000 and EU values, the counters with the §7.6 EU estimate, and the entry's `lastSnapshot`/`lastSampleEpochSec`/`lastGapReason`; the first sample sets the machine metadata (so the registry is dirty) and an unchanged one does not, while a new status id does; an absent `euPerTick`/`energyStored` is not recorded as zero and does not enter the minute's average; a generator's negative EU/t counts as generated; a multiblock records formed, maintenance and the recipes delta (the first sample only sets the baseline) and a backwards GT counter sets the reset flag and counts; a sample in the next minute closes the open one with `expectedSamples` 60 and no gap mask; `serverTicks` are the ticks that elapsed while the minute was open. Gaps: a skip records a non-valid second with the reason and the minute's gap mask while the observed sample still counts, a gap leaves `lastSnapshot` and the metadata alone, and a derived or null reason is rejected. |
| `sampling.SamplerStatsTest` | 4 | Review follow-up GS108-T5, design-v0.2 §7.6. `clockSkewRefusedTotal` accumulates the per-fold deltas and therefore survives a sensor being purged, expired or turned into a tombstone (a sum over the live accumulators would fall back); a fold with no refusal, and a negative delta, move nothing; counters saturate at `Long.MAX_VALUE` instead of overflowing; the microsecond window reports only completed windows, and nanoseconds are rounded down to whole microseconds. |
| `sampling.TelemetryFrameTest` | 11 | GS-108, design-v0.2 §7.7 plus the design-v0.3 GS-201 A6 shape. The frame's sensor list is unmodifiable and copied (mutating the source list afterwards changes nothing) and sorted by UUID so paging is stable; `sensor(id)` and `count(state)`; `TelemetryFrame.EMPTY`. A `SensorView` copies every field of the entry and a later change to the entry does not reach it; `lastSnapshot` is null for a sensor that was never sampled (A6: nullable) and `counters` is null without rings. `MachineCountersView` copies the live counters, its array getters return fresh copies a caller cannot write through, and it rejects a derived gap reason and an unknown state code. `SamplerStatsView` copies the counters and takes `duplicatesRekeyedTotal` and `quotaRefusedTotal` from the registry; `LimitsView` copies the settings. Every field of all five frame classes is final (reflection, test code only). |

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

**Textures (GS-105).** `src/main/resources/assets/gregscope/textures/items/machine_sensor.png` and
`textures/blocks/iconsets/GREGSCOPE_SENSOR_OVERLAY.png` (16x16 RGBA, original art) are generated by
`tools/gen_textures.py` (Python 3, standard library only: `struct` and `zlib`, no PIL). Regenerate from the repository
root with `python tools/gen_textures.py src/main/resources/assets/gregscope/textures`; the result must match the
committed files (`git diff --exit-code src/main/resources/assets/gregscope/textures`). Running it twice gave
byte-identical files. The script is not in the jar. `AssetsExistTest` checks the result; how the overlay looks on a
machine is a manual client check (GS-121).

**GS-103 negative controls (2026-09-17), reverted afterwards (files restored from a backup copy, `cmp` identical).**
In one run: the CRC initial value changed from `0xFFFF` to `0x0000`; `import net.minecraft.world.World;` added to the
`[pure]` `sampling/Clock.java`; the pinned codes of `waiting` and `idle` swapped in `StateCodes.code`; and
`GapRanges.missingReason` returning `unknown` instead of `server_offline`. Result: 13 of 245 tests failed, exactly the
expected ones: `MinuteSlotCodecTest.crcCheckValue`, `goldenBytesEncode`, `goldenBytesDecode`, `staleEpochIsIgnored`
(the fixtures' CRCs no longer match); `PureSourcesTest.pureClassesReferenceNoGameClasses`;
`StateCodesTest.everyStateHasItsPinnedCode`, `codesRoundTripAndAreDistinct`; and six `GapRangesTest` cases that expect
`server_offline`. Note that `MinuteSlot` round trips and the torn-slot test still passed with the wrong CRC, which is why
the golden fixtures come from an independent generator.

**GS-105 unit negative control (2026-09-17), reverted afterwards (backup copies restored, `cmp` identical).** In one
run: `gregscope.gap.probe_error` deleted from `en_US.lang` and `GregScopeAssets.BLOCK_ICON_SENSOR_OVERLAY` renamed to
`iconsets/GREGSCOPE_SENSOR_OVERLAY_X`. Result: 3 of 306 failed, exactly `AssetsExistTest.designNamesArePinned`,
`everyIconConstantIsA16x16RgbaPng` (missing resource) and `everyLangKeyConstantExists`.

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
`Viewer`, and no `gametest`, `horizonqa`, `.lua`, `fixtures`, `.hex` or test classes; GS-105 checked: the jar has `GregScopeAssets`,
`sensor/ItemMachineSensor`, `MachineSensorCover`, `SensorCovers`, `SensorCover` and `SensorKind`, and
`assets/gregscope/textures/items/machine_sensor.png` and `textures/blocks/iconsets/GREGSCOPE_SENSOR_OVERLAY.png`,
byte-identical to the committed PNGs, and no `gametest`, `horizonqa`, `.lua`, `fixtures`, `.hex`, `.py`, `tools/` or
test classes; GS-107 checked the `registry/` classes; GS-108 checked: the jar has the `sampling/` classes `SamplerSchedule`, `SampleFolder`, `SamplerStats`, `SamplerStatsView`, `LimitsView`, `CountersView`, `MachineCountersView`, `SensorView`, `TelemetryFrame`, `TargetResolver` and `TelemetrySampler`, 134 entries in all, the same two PNGs and `en_US.lang` (GS-108 adds no asset), and no `gametest`, `horizonqa`, `.lua`, `fixtures`, `.hex`, `.py`, `tools/` or test class; the only entry matching "Test" is the shipped `GregScopeTestHooks`).

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
- Every batch that places sensors ends with an `@AfterBatch` hook calling `SensorCleanup.detachAllAndPurge()`
  (review follow-up GS107-T3). Horizon-QA keeps finished cells loaded and their covers keep heartbeating, so without
  it every sensor a batch placed stays registered and counts against the shared unowned `limits.maxSensorsPerTeam`
  (64); a later batch's sensor is then refused with no entry and fails as "no registry entry", which is what
  happened in GS-108's first run. Purging alone is not enough, because a cover whose machine still ticks registers
  again on its next heartbeat, so the hook detaches the covers with GT's own `detachCover` first. Each hook logs
  `cleanup after a sensor batch -> entries=... coversDetached=... entriesPurged=...`.
- Do not run two Gradle invocations at the same time; they share `run/server`.

### Running in CI

`.github/workflows/build-and-test.yml` calls GTNH's shared `build-and-test` workflow with `horizonqa: true` and
`horizonqa-tests: gregscope`. It builds, runs the unit tests, then runs `runServer` in `ci` mode on a Linux dedicated
server under a **300 s** step timeout (server boot plus every test batch).

Batches run one after another; tests inside a batch run in parallel. Every wait is bounded. If every batch ran to its
timeout the suite would take 4220 ticks (211 s at 20 TPS: the sum of the longest `timeoutTicks` in each of the 26
batches; GS-105 added the 100-tick `gregscope.sensor` batch, GS-106 the 200-tick `gregscope.sensor.cover` and
`gregscope.sensor.cover.reload` batches, and GS-107 the 200-tick `gregscope.sensor.lifecycle`,
`gregscope.sensor.lifecycle.reload` and `gregscope.sensor.lifecycle.caps` batches, and GS-108 the `gregscope.sampler`
(60), `gregscope.sampler.real` (100), `gregscope.sampler.budget` (200) and `gregscope.sampler.disabled` (60)
batches; the earlier figures of 2100, 2800, 3200 and 3800 predated them, and the 4120 written here after GS-108 was
simply wrong - recounted from the annotations the maxima sum to 4220, GS108-T2), so a hang still ends with a
Horizon-QA report inside the CI budget. A normal run is far below that: the last full run spent about 52 s inside
test cases (longest single batch 8.3 s) within 58 s to 1 m 13 s of Gradle wall time, server boot included.

### Test classes

| class | batch | what it does |
|---|---|---|
| `BasicMachineSnapshotTests` | `gregscope.basic` | Every test runs on an LV, MV and HV Electric Furnace (`MTEBasicMachine`, suffix `[lv]`/`[mv]`/`[hv]`): idle, running, disabled, power-starved and output-blocked; asserts the probe's `state`/`statusId` and related keys. Tier values are asserted exactly: `metaName`, `energyCapacity` 2048 / 8192 / 32768 EU (`V[tier] * 64`), and for the 4 EU/t, 128-tick smelting recipe `euPerTick` 4 / 16 / 64 and `maxProgressTicks` 128 / 64 / 32 (GT's `OverclockCalculator`: the ULV recipe counts as LV, one overclock per tier above). These formula values matched what the server reported. |
| `ElectricBlastFurnaceSnapshotTests` | `gregscope.ebf` | GT's own formed EBF template: starting, idle, running, disabled while idle and while running, maintenance warning, no-maintenance shutdown, broken structure, full output bus, power loss, and `waiting` (`recipeAboveCoilHeatIsWaiting`: recipe needs 2101 K, the template gives 2001 K; input and EV power present, work allowed). |
| `IdleCostTests` | `gregscope.idle` | Structural evidence for "no idle cost", not a benchmark. GS-108 changed two of these assertions deliberately (design-v0.2 §1.4 lifts "no global tick handler" for exactly one handler). `noGregScopeListenersTileEntitiesOrGenerators`: **zero** GregScope listeners on `MinecraftForge.EVENT_BUS`, `TERRAIN_GEN_BUS` and `ORE_GEN_BUS`, and **exactly one** on the FML bus (where 1.7.10 tick events arrive), counted two independent ways (the listener object's class and the owning `ModContainer`) and required to be `GregScope.sampler()`; no GregScope class in the tile entity registry, in `GameRegistry`'s static collections or among any world's loaded tile entities. Each scan must find other mods' entries (observed: 218 listener objects, 543 tile entity classes, 106 loaded tile entities). `exactlyOneServerTickHandler`: the live listener declares exactly one `@SubscribeEvent` method, it takes a `TickEvent.ServerTickEvent`, calling it with phase START moves no counter and with phase END runs exactly one sampler tick. `handlerDoesNoWorkWithoutLiveSensors`: the registry is emptied and a whole interval is run inside one server tick (so no cover can heartbeat in between); `ticksTotal` grows by the interval while `cyclesTotal` and `samplesTotal` do not move, the schedule is empty and the frame published at the boundary has no sensors. `adapterNeverTicksGregScopeEnvironment`: a real Adapter next to an LV macerator, after 5 real ticks; the Adapter's `updatingBlocks` list is empty (asserted first) and the merged environment and GregScope's own environment report `canUpdate() == false`. Reads Forge/FML/OC internals by reflection (test code only). |
| `OpenComputersComponentTests` | `gregscope.oc` | GregScope's driver through OpenComputers' own driver registry, as an Adapter uses it: merging into `gt_energycontainer` (basic machine and controller), `getStoredEU` still present, soft error after the machine is destroyed, hatches left to OC's energy driver, LSC and BEC controllers keep their `lsc` / `bec_*` names. |
| `OpenComputersExampleScriptTests` | `gregscope.oc.openos`, `gregscope.oc.openos.ebf` | Boots a real OpenOS computer (creative case, real Adapter) and runs the unmodified example script. Output is compared exactly with the probe's snapshot: an idle LV macerator on Lua 5.3, Lua 5.2, Lua 5.4 and LuaJ, and a running EBF with maintenance and work-disabled warnings on Lua 5.3. Native architectures skip (not pass) if OC cannot load the native library on that OS/arch. |
| `AdapterBindingTests` | `gregscope.oc.adapter` | Real placed Adapter next to LV basic machines. Machine broken, then replaced by a different GT machine (twice) and by stone: component removed, energy-only component right after placement, component with `getSnapshot` and a new address a tick later reporting the new machine (the energy-only components are thrown away, not reused), an empty GT holder (the state GT's placement notifies neighbours in, reproduced without notification) gets OC's energy callbacks while GregScope's driver does not match it, removed component objects reading the position (Java calls). Adapter broken and placed again: no exception, detached component still reads the machine from Java, new Adapter's component has the same name, a new address and a working `getSnapshot`. |
| `LifecycleTests` | `gregscope.lifecycle`, `gregscope.lifecycle.hooks` | GS-101. `serverStarts`: dedicated server side; GregScope's container version is `Tags.VERSION`; lifecycle phase is `SERVER_STARTED` (every handler up to `serverStarted` ran, none after); `gregtech`, `OpenComputers`, `modularui2` and `gtnhlib` are both requirements and load-after dependencies and are loaded; the dev runtime has GTNHLib `0.11.46` and ModularUI2 `2.3.88-1.7.10`, and the `gtnhlib@[0.11.46,)` requirement accepts 0.11.46 and rejects 0.11.45; `config/gregscope.cfg` exists with every §12.3 key and its comment, no `exporter` category, and the settings loaded in preInit equal `Settings.fromRaw` of the file; the creative tab is in `CreativeTabs.creativeTabArray`. `testHooksReachGametestJvm` (own batch, it changes process-wide settings): `System.getProperty("gregscope.testHooks")` is `"true"` (set by `addon.gradle` on `runServer`/`runClient`, so CI's plain `runServer` gets it), `GregScopeTestHooks.enabled()`, and a settings override is applied and cleared. |
| `SafetyTests` | `gregscope.safety` | GS-101. `clientProxyNotLoaded`: `gregscope.clientProxyLoaded` is unset and the injected proxy is exactly `CommonProxy`. `handshakeRequiresMatchingClient`: FML's own network checker for GregScope (the `NetworkModHolder` that `FMLHandshakeServerState` consults through `checkModList(client, Side.CLIENT)`) rejects a client mod list of every other loaded mod without GregScope, rejects vanilla, rejects a different GregScope version and accepts the same version. A real client connection is still **manual** (GS-121). |
| `ChunkReloadTests` | `gregscope.reload.*` | Really unloads and reloads the chunks of an LV macerator with a real Adapter, an idle formed EBF and a running, soft-disabled EBF. Asserts new tile entity objects, soft errors while unloaded without reloading the chunk, the Adapter component's address and name, the startup-check state, persisted keys and resumed recipe progress. `adapterInOtherChunkSeesMachineAfterItsChunkReloads` puts an Adapter and an LV macerator on opposite sides of a chunk border (empty 17-wide template `gregscope:empty_17x1x5`, border computed at run time) and unloads and reloads only the machine's chunk: the Adapter's chunk stays loaded, the Adapter keeps the same component object and address, `getSnapshot` soft-errors while unloaded without loading the chunk and reads the reloaded machine in the same tick as the reload; OC's own `getStoredEU` on that component returns `0` after the reload (stale tile). |
| `ProbeBenchmarkTests` | `gregscope.bench` | GS-102, **opt-in** (`-Dgregscope.bench=true`; otherwise every test skips through `helper.assumeTrue`). Four scenarios, each built in a real state that is asserted first: LV Electric Furnace idle (`idle`/`none`) and running (`running`), GT's formed EBF template running a 400-tick EV recipe (`running`), and a TecTech **Active Transformer** built block by block in an empty 5x3x5 cell (`gregscope:empty_5x3x5`), formed by TecTech's own startup check and switched back on (`formed`, `allowedToWork`). Each then calls `GregTechMachineProbe.snapshot(te)` 1,000 times to warm up and 10,000 timed times inside one tick and logs `[GregScope bench]` p50/p99/max µs. Asserts completion (no `null` snapshot), not a time. |
| `TeamAccessTests` | `gregscope.access` | GS-104, against real GTNHLib 0.11.46 teams. Teams are made for unique fake UUIDs with `TeamManager.getOrCreateTeam(name, uuid)` plus `addOfficer`/`addMember`, and removed again in the same call by the gametest helper `TestTeams` (a `TeamManager` subclass, the only way to reach its protected tables; nothing is left for GTNHLib to save). `sameTeamResolvesGtnhlibTeams`: `GtnhlibTeamResolver.teamOf` finds each member's team and nothing for a teamless or null UUID; `sameTeam` both directions, across teams, teamless and self; `isOfficerOrOwner` for owner, officer and member; `AccessPolicy` view/open/rename (default and officer-only)/Hub scope and op no-escalation over the real resolver. `randomUuidLookupLogsNoError`: a Log4j test appender (`LogCapture`, on the root and `gtnhlib` loggers, each event counted once) sees no "Unable to find team" line for resolver and policy lookups of a random UUID; positive controls in the same capture: `getOrCreateTeam` for a new player logs it once, and `TeamManager.getTeamByPlayer(random)` logs it once (errata E4 still true). `cacheLastsOneRebuildAndTeamChangesApplyNext`: a join stays invisible to a resolver's cached "no team" until `clearCache`, while membership checks read the live team; a leave and a `TeamManager.mergeTeams` show on the next resolver, and an old resolver keeps its cached consumed team. The merge uses teams without team data (`TestTeams.createWithoutData`), see the GT5U crash note in design-v0.2 Implementation notes GS-104. All three finish in 0 ticks. |
| `SensorPlacementTests` | `gregscope.sensor` | GS-105, design-v0.2 §3.2, plus the design-v0.3 GS-201 placement hook. Placement uses GT's own code two ways: the §13.1 path (the gate `BaseMetaTileEntity.onRightclick` applies before placing: no cover on that face, `CoverRegistry.isCover`, the placer's predicate and the machine's `allowCoverOnSide`; then `CoverRegistry.getCoverPlacer(stack).placeCover(fakePlayer, stack, holder, side)`), and a sneaking FakePlayer holding the stack activating GT's machine block at the centre of a chosen face (`BlockMachines.onBlockActivated`). GT ignores activation of a tile that has never ticked (`getTimer() < 1`), so the player path first warps the cell 2 ticks and every click asserts that GT handled it; otherwise rejections would pass vacuously. `sensorItemIsRegisteredCover`: `gregscope:machine_sensor` is the item, `CoverRegistry.isCover` is true, stack size 64, creative tab icon, unlocalized name, English display name, the 3 tooltip lines and a state name resolve on the dedicated server (mod lang is loaded there); the placer is GUI-clickable and not for primitive blocks; `buildCover` gives a valid `MachineSensorCover` of kind `machine` with no identity yet (GS-106) and every read-only flag (`lets*` true, not redstone sensitive, no redstone output, no copy-paste, no tick-rate addition, no cover GUI); the registered texture is valid and not GT's error texture; GT's `ResourceUtils.getCompleteBlockTextureResourceLocation` turns the E1 key into `gregscope:textures/blocks/iconsets/GREGSCOPE_SENSOR_OVERLAY.png`, which exists in the mod's resources, as does the item icon. `placesOnLvMachine`: predicate and GT accept every face of an LV Electric Furnace, the main face included; the player path puts a `MachineSensorCover` on the clicked face only and consumes one item. `placesOnEbfControllerSide`: GT's formed EBF template controller takes a sensor on a non-front face. `rejectedOnControllerFront`: the predicate alone accepts the front, GT's `allowCoverOnSide` refuses it, neither path places and no item is consumed. `rejectedOnHatch`: GT allows covers on the LV energy hatch face but the predicate refuses it; positive control: a GT LV conveyor module is placed on the same hatch. `rejectedOnNonGtBlock`: a vanilla chest tile is not `ICoverable` and the predicate refuses a missing coverable; a GT tin cable (a GT tile, not a machine) is refused on every face. `secondSensorOnSameMachineRejected`: after one sensor, every other face is refused by both paths, and the neighbouring machine still takes its own. `machineSensorAllowedBesideOtherCovers` (GS-201 A4): a GT conveyor and a test-only kind-2 `SensorCover` stub (attached directly, never registered) on the same machine do not block a Machine Sensor; a second Machine Sensor is still refused. All finish in 0 ticks. |

| `SensorCoverTests` | `gregscope.sensor.cover`, `gregscope.sensor.cover.reload` | GS-106, design-v0.2 §3.3/§3.4. What the cover does once GT's own placement (the `SensorPlacementTests` helper) has put it on a machine. Every machine is first given a known main facing (`setMainFacing(NORTH)`) and front facing (SOUTH), because GT refuses items and fluids on those two faces whatever a cover says; the tested face is EAST, the face the neighbour block occupies. **Transparency, with real transfers and a positive control each** (an uncovered face is `CoverNone`, which is transparent, so each test first shows the same path works without the cover): `transparentToEnergy` puts a GT MV-to-LV transformer on the covered face with its input front pointing away, fills it with Horizon-QA's virtual EU supply and warps 40 ticks; the machine's stored EU rises through GT's own enet (`inputEnergyFrom` is also asserted after 25 warp ticks, the point at which GT refreshes its EU I/O faces). `transparentToItems` sets a vanilla hopper next to the covered face pointing into it and waits real ticks (a warp does not tick vanilla tiles) until cobblestone is inside the machine and the hopper's stack has shrunk. `transparentToFluids` uses an LV Chemical Reactor and fills 1,000 L of water through the covered face with GT's `IFluidHandler` entry point, then reads the fluid back from the machine's fillable stack. `transparentToRedstone` puts a redstone block against the covered face and reads 15 back through `getInternalInputRedstoneSignal`, and asserts the cover neither reacts to redstone nor drives an output. **Identity:** `attachSetsIdentityOwnerAndTime` (fresh UUID, `ct` from the test-hook clock, owner and owner name from the machine because the placer is a FakePlayer, empty label, description, minimum tick rate and tick rate 20, `isDataNeededOnClient()` false so no identity is ever synced, a second sensor gets another UUID); `anvilNameBecomesLabel` (a renamed stack with formatting codes becomes the sanitized label `Main EBF`, in the cover and in the saved tile NBT); `dropsCarryUuidAndReportInItem` (GT's `getDrops` writes `gs`/`idM`/`idL` and the Machine Sensor cover id into the drop's `gt.covers`); `uuidStableAcrossChunkReload` (own batch: a real chunk unload and reload gives a new tile and a new cover object with an equal identity, and the unload is reported); `foreignAndUnsupportedDataAreInertAndPreserved` (a `gs=2` compound with an unknown key and a bare `NBTTagInt` are both loaded inert, described as `unsupported data version` / `inactive`, written back verbatim, and still transparent); `unknownCoverIdNbtLoadsAsNoCover` (a cover id that decodes to a non-cover item loads as GT's `CoverNone`, not as a sensor, and stays transparent). **Hooks and rates:** `heartbeatReportsIdentifiedCoversOnly` (real ticks, because a warp freezes the counter the cover rate is measured against, errata E2: the identified cover heartbeats with its own side, holder and cover object, while the inert cover's machine never reports and no cover reports without an identity); `crowbarRemovalReportsDetached` (a sneaking FakePlayer with a GT crowbar through `BlockMachines.onBlockActivated`); `tickRateLocked` (a hard hammer through the same path leaves the rate at 20, errata E5); `copyPasteDisallowed` (GT's own charged Cover Copy/Paste tool in copy mode stores nothing for the sensor and does store the GT conveyor on the next face). `pastedForeignDataDoesNotEraseALiveSensor` (review follow-up GS-REV-3): GT's paste direction (`ICoverable.updateAttachedCover`, gated on the numeric cover id alone) hands a foreign compound, a bare `NBTTagInt` and a `gs=2` compound to the live cover, and the sensor keeps its identity and its saved NBT each time, while a compound that does read as a sensor is still applied. A recording listener is installed through `GregScopeTestHooks.setSensorEvents` in place of the GS-107 registry and the registry is put back afterwards (the helper asserts the registry is the installed listener when it takes over, so two overlapping recorders fail loudly instead of leaving a finished test's recorder behind, review follow-up GS106-T4); because Horizon-QA keeps finished cells loaded, sensors from earlier tests keep heartbeating into it, so its queries filter by sensor UUID or by holder. |
| `SensorLifecycleTests` | `gregscope.sensor.lifecycle`, `.reload`, `.caps` | GS-107, design-v0.2 §4. Drives the mod's own registry (`GregScope.registry()`), not a stub, with the §13.1 hooks `heartbeatNow`, `sampleNow`, `overrideSettings` and `purgeAllNow`, because a warp does not advance `MinecraftServer.getTickCounter()` (errata E2). `heartbeatRegistersLiveSensor`: no entry before the first heartbeat, then LIVE at the right (position, side), rings allocated, indexed in the reverse index, and the availability word `live` in the cover description. `crowbarDropCoverMarksRemoved`: a real GT crowbar click gives REMOVED/DETACHED, frees both rings and leaves the reverse index. `facingChangeDropRemoves`: an EBF controller's new front face drops the cover in the next tick, which is also DETACHED (a basic machine would not, because its `allowCoverOnSide` allows any GUI-clickable placer on its main facing). `survivalGetDropsMarksInItemAndReplaceResumes`: `getDrops()` gives IN_ITEM, and placing that very drop elsewhere resumes the same entry object as LIVE at the new position with the old (position, side) key gone. `destroyBlockBecomesMissingAfterThreeSamples`: `helper.destroyBlock` runs GT's `breakBlock` but not `getDrops`, so no cover hook fires and only validation notices; strikes 1 and 2 leave it LIVE, the third makes it MISSING/TARGET_MISSING and frees the rings. `duplicateUuidRekeysNewcomer`: a second machine restored from cover NBT with the same UUID is re-keyed, the fresh UUID is written back into its NBT, and the original stays LIVE where it was. `chunkUnloadMarksUnloadedAndReloadLive` (own batch): a real unload gives UNLOADED with the minute ring kept and the second ring gone, and the heartbeat after the reload makes the same entry LIVE again. `globalCapGivesOverCap` (own batch, because it overrides settings and empties the registry): with `maxSensors=16`, 17 sensors placed and heartbeated inside one server tick give 16 LIVE, one with no entry at all and the availability word `over cap`, and `quotaRefusedTotal` up by one. |
| `SamplerTests` | `gregscope.sampler`, `.real`, `.budget`, `.disabled` | GS-108, design-v0.2 §6.1/§6.2/§6.3/§7.7. Four batches, because tests in one batch run in parallel and share one sampler. **`gregscope.sampler`** (hook-driven, 0 ticks): `sampleMatchesProbe` (one `sampleNow`, then the kept snapshot map, `lastStatusId`, `metaName`, `machineName` and `metaId` all equal what `GregTechMachineProbe.snapshot` reads in the same tick); `everyIntervalPublishesAnImmutableFrame` (two `runIntervalNow` calls give two frame objects with a strictly increasing sequence, the sensor is in the frame with its kind, state, snapshot and counters, the frame reports the interval and the limits, its sensor list throws on `clear` and is sorted by UUID); `unloadedChunkNotLoadedBySampler` (a LIVE entry registered straight through the registry core at a far position whose chunk nothing has loaded - a real cover would report its own unload first - stays in an unloaded chunk over three intervals and becomes UNLOADED with `chunk_unloaded`, leaving the schedule); `unloadedDimensionNotInitialized` (an entry in an unregistered dimension id becomes UNLOADED with `dimension_unloaded` and `DimensionManager.getWorld` stays null). **`.real`**: `realTicksSampleOncePerSecond` waits 60 real ticks with no hook at all and sees 2-4 samples, a kept snapshot, second-ring entries and a sampler bucket - the `ServerTickEvent` wiring itself. **`.budget`**: `tinyBudgetSkips` overrides `sampling.tickBudgetMicros=100` (and `maxSensorsPerTeam=0`, because all 64 FakePlayer-placed sensors are unowned and share one quota), empties the registry, places 64 LV Electric Furnaces with sensors in the `gregscope:empty_5x3x5` template, runs 6 intervals of real ticks and asserts what holds whatever the JIT has done (review follow-up GS108-T1): the override reached the sampler, every sensor was either sampled or given up on at least twice (nothing is silently dropped), a `sampling_skipped` gap implies at least one budget-exceeded tick, and if nothing was skipped then every sensor was sampled in all but at most one interval. The budget-exceeded ticks and the skips themselves are logged rather than asserted, because at 100 us they only move while the sample path is still cold; `SamplerScheduleTest` pins the budget arithmetic deterministically over a fake `nanoTime`. It then measures a warmed-up sample and breaks the machines again. **`.disabled`**: `samplingDisabledRecordsSkippedGaps` turns `sampling.enabled` off and runs one interval: no sample, exactly one `sampling_skipped` second on the sensor and one on `samplingSkippedTotal`, and the sensor stays LIVE. |

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
size 1); reverted, `cmp` identical. (GS-108 rewrote that helper: `assertNoGregScopeListeners` became
`assertGregScopeListeners(.., expected)`, and the FML bus now expects exactly one GregScope listener, the sampler, so
re-running this control today fails with "FMLCommonHandler.bus() GregScope listener objects" instead.)

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

**GS-107 negative control (2026-09-17), reverted afterwards (backup copy restored, the constant is 3 again).**
With `SensorEntry.MAX_STRIKES` set to 1 (the 3-strike debounce dropped), a full `gregscope` run failed exactly one
test: `SensorLifecycleTests.destroyBlockBecomesMissingAfterThreeSamples` with "strikes after 1 validation: expected
<1> but found <0>" (the entry had already become MISSING, which frees the strike count); 83 passed, 4 skipped,
Horizon-QA status `failed`, exit code 1. No other test noticed, which is the point: the debounce is only asserted
there and in `SensorRegistryCoreTest.threeStrikesInARowBecomeMissing`.

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

**GS-105 negative controls (2026-09-17), reverted afterwards (backup copies restored, `cmp` identical).** Run with
`-Dhorizonqa.tests=gregscope:SensorPlacementTests`:

- `SensorCovers.isPlaceable` also accepting `MTEHatch`, and its one-per-machine loop checking `instanceof SensorCover`
  instead of `MachineSensorCover`: 2 of 8 failed, exactly `rejectedOnHatch` ("predicate accepted a hatch") and
  `machineSensorAllowedBesideOtherCovers` ("sensor refused beside other covers"). `secondSensorOnSameMachineRejected`
  still passed, as it should: a Machine Sensor is also a `SensorCover`.
- The warp before the player right-click disabled (so GT's `getTimer() < 1` early return applies) and the overlay key
  renamed to `iconsets/GREGSCOPE_SENSOR_OVERLAY_X`: 4 of 8 failed, `placesOnLvMachine` ("GT did not handle the sneak
  right-click"), `rejectedOnControllerFront` and `secondSensorOnSameMachineRejected` ("GT did not handle the
  right-click"), and `sensorItemIsRegisteredCover` ("icon key: expected <iconsets/GREGSCOPE_SENSOR_OVERLAY> but found
  <iconsets/GREGSCOPE_SENSOR_OVERLAY_X>"). The first development run, before the warp existed, failed
  `placesOnLvMachine` the same way, while the rejection tests' player-path checks passed vacuously.

**GS-106 negative controls (2026-09-17), reverted afterwards (backup copies restored, `cmp` identical).** Three full
`gregscope` runs, each with one deliberate defect in `MachineSensorCover`:

- `letsEnergyIn`, `letsFluidIn`, `letsItemsIn` and `letsRedstoneGoIn` returning `false` (the design-v0.2 §13.2
  transparency control): 7 of 76 failed, and each transparency test failed on its own resource -
  `transparentToEnergy` ("the covered face stopped being an EU input"), `transparentToItems` ("the covered face
  refuses items"), `transparentToFluids` ("canFill through the covered face"), `transparentToRedstone` ("expected
  <15> but found <0>"), plus `foreignAndUnsupportedDataAreInertAndPreserved` ("an inert cover blocks the face") and
  GS-105's two flag assertions (`SensorPlacementTests.placesOnLvMachine`, `sensorItemIsRegisteredCover`).
- `allowsTickRateAddition` returning `true` and the `identity == null` guard removed from `doCoverThings`: 4 of 76
  failed - `tickRateLocked` and GS-105's two flag assertions on the flag, and
  `heartbeatReportsIdentifiedCoversOnly` with "an inert cover reported: [... heartbeat EAST no-identity ...]".
- `allowsTickRateAddition` returning `true` with the flag assertion removed from `tickRateLocked`, to show the hammer
  click itself is not vacuous: `tickRateLocked` failed with "tick rate after the hammer: expected <20> but found
  <40>", i.e. GT's holder path really did add 20 ticks once the cover allowed it (errata E5).

**GS-108 negative control (2026-09-17), reverted afterwards (backup copy restored, `cmp` identical).** The
design-v0.2 §13.2 control for the sampler: the `blockExists` guard removed from `TargetResolver.resolve`, then the
`gregscope:SamplerTests` selector. `unloadedChunkNotLoadedBySampler` failed - "state: expected <UNLOADED> but found
<MISSING>" - because without the guard the three intervals resolve a position in an unloaded chunk, find no tile
there, and strike the sensor out as a missing target instead of recording `chunk_unloaded`. The other 6 sampler tests
passed. Note what this run does and does not show: the far chunk was still absent from the loaded-chunk map right
afterwards, so the observed damage is the misclassification and the lost gap reason, not an observed chunk load. The
guard is still the reason GregScope never asks `World.getTileEntity` for a position it has not been told is loaded.

**Review follow-up negative controls, GS-105 to GS-108 findings (2026-09-17), reverted afterwards (files restored
from a backup copy, `cmp` identical).** Two runs.

*Unit run, four defects injected at once:* `SensorRegistryCore.rekey` ignoring `register()`'s outcome again (GS-REV-2);
the third strike passing no gap reason to `toTombstone`, so the open minute is dropped (GS-107-02); `unloaded()` not
stamping `lastSeen` (GS-107-03); and `SamplerStats.onClockSkewRefused` keeping a running maximum instead of
accumulating (GS108-T5). Result: exactly 4 of 398 failed, one per defect and nothing else -
`SensorRegistryCoreTest.aRekeyRefusedByTheCapReportsItAndCreatesNothing`,
`.theThirdStrikeQueuesThePartialMinuteBeforeFreeingTheRings`,
`.anUnloadRefreshesLastSeenSoTheStaleWindowStartsThere` and
`SamplerStatsTest.clockSkewRefusalsAccumulateAndSurviveAPurgedSensor`.

*In-game run, two defects injected at once:* the "never downgrade an identified cover" guard removed from
`MachineSensorCover.readDataFromNbt` (GS-REV-3), and `SamplerSchedule.serveCarry` no longer calling `sink.skip` for a
sensor carried a whole interval, i.e. dropping it silently (a control for the assertion that `tinyBudgetSkips` keeps
after GS108-T1). Result: exactly 2 of 96 failed -
`SensorCoverTests.pastedForeignDataDoesNotEraseALiveSensor` and `SamplerTests.tinyBudgetSkips` with "a sensor was
neither sampled nor skipped often enough in 6 intervals: 1". The second shows that the retained, hardware-independent
half of `tinyBudgetSkips` is not vacuous. With only the cover guard removed, the paste test failed alone (1 of 15 in
the `gregscope:SensorCoverTests` selector) with "a foreign compound erased the sensor identity".

*Not covered by a negative control:* GS-REV-1 (the client wording of `getDescription`) has no automated coverage
beyond the pure `SensorDescriptionTest`, because GT's only consumer is the client WAILA path and the gametests run on
a dedicated server; GS-REV-4 and GS-107-04 are documentation-only. The GS106-T4 guard in `recordEvents` is a
loud-failure assertion, so it only fires if a future test overlaps two recorders.

### Sampler cost (GS-108)

`SamplerTests.tinyBudgetSkips` logs two numbers for the same 64 LV Electric Furnaces, on the same local Windows run
as the probe benchmark below (2026-09-17). They measure the whole design-v0.2 §6.2 procedure - resolve the target,
probe it, fold the sample into the second ring, the open minute and the counters - not just `PROBE.snapshot`.

| what | observed |
|---|---|
| warmed up: 1,280 `sampleNow` calls after 1,280 warm-up calls, in one tick | mean **6.1-7.6 us** per sample |
| cold: the first 120 real ticks of sampling, 64 sensors, `tickBudgetMicros=100` | mean **81-156 us** per sample; per-tick p50 135-175 us, p99 447-4,095 us |
| the same, across six full runs | 143-223 samples in 120 ticks |
| the same cold run | budget exceeded on **120 of 120** ticks, 111-209 `sampling_skipped` gaps, 61-69 sensors carried over |

The cold figure is real and worth knowing: HotSpot compiles on invocation counts, so a server that has just started
pays roughly twenty times the warm cost for its first few hundred samples, and the §6.3 per-tick budget is exactly
what keeps that from hurting the tick. The warm figure is what a running server pays: at the 1,000 us default budget
one tick serves about 156 samples, far above the 12.8 per tick that the default 256 sensors need, so the GS-102
decision to keep the defaults stands. The warm number is measured after 1,280 calls, which is below HotSpot's C2
threshold, so it is an upper bound rather than the steady state.

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

Review follow-ups (GS-105 to GS-108 findings), local, Windows, 2026-09-17, selector `gregscope`, on a **freshly
created world and without `config/gregscope.cfg`** (both moved away first): **94 passed, 4 skipped**
(`ProbeBenchmarkTests`, opt-in), 0 failed, 0 timed out, 0 infrastructure errors; Horizon-QA status `PASSED`, exit
code 0; Gradle wall time 1 m 12 s, 52.6 s of it inside test cases. Per class: BasicMachineSnapshotTests 15,
SensorCoverTests 15 (the new `pastedForeignDataDoesNotEraseALiveSensor`), ElectricBlastFurnaceSnapshotTests 11,
SensorLifecycleTests 8, SensorPlacementTests 8, OpenComputersComponentTests 8, SamplerTests 7,
OpenComputersExampleScriptTests 5, ChunkReloadTests 4, IdleCostTests 4, ProbeBenchmarkTests 4 (skipped),
TeamAccessTests 3, AdapterBindingTests 2, LifecycleTests 2, SafetyTests 2. Slowest: the four
`exampleScriptRunsOnOpenOs` variants at 8.15-8.20 s, `exampleScriptShowsRunningEbfWithWarnings` 7.15 s,
`tinyBudgetSkips` 6.00 s, `realTicksSampleOncePerSecond` 3.00 s, then 0.75 s and below. Logged by the run: ten
`cleanup after a sensor batch` lines (the new `@AfterBatch` hooks; the largest were `entries=16 coversDetached=16`
and `entries=7 coversDetached=4`), and for the budget test 143 samples, mean 137 us per sample cold, per-tick
p50/p99/max 159/1,658/1,658 us, budget exceeded on 120 of 120 ticks, 178 `sampling_skipped` gaps, warm sample
6,572 ns. Unit tests: **398 in 27 classes**, all green, of which 11 are new
(`registry.SensorRegistryCoreTest` 34 -> 38, `sampling.SampleFolderTest` 11 -> 13, `sensor.SensorDescriptionTest`
7 -> 8, the new `sampling.SamplerStatsTest` 4). Jar check on
`build/libs/gregscope-403919c-master+403919c13a-dirty.jar`: 135 entries, 0 matching
`gametest|horizonqa|.lua|fixtures|.hex|Test.class|tools/|.py`, 50 classes under `registry/`, `sampling/` and
`sensor/` (including the new `SensorRegistryCore$Heartbeat`), and the lang file plus both PNGs. GitHub CI has not run
this state.

GS-108, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world and without
`config/gregscope.cfg`** (both moved away first): **93 passed, 4 skipped** (`ProbeBenchmarkTests`, opt-in), 0 failed,
0 timed out, 0 issues; Horizon-QA status `passed`, exit code 0; Gradle wall time 1 m 12 s. New: `SamplerTests`,
7 passed - `sampleMatchesProbe`, `everyIntervalPublishesAnImmutableFrame`, `unloadedChunkNotLoadedBySampler` and
`unloadedDimensionNotInitialized` (batch `gregscope.sampler`, 0 ticks each), `realTicksSampleOncePerSecond` (batch
`gregscope.sampler.real`, 60 real ticks), `tinyBudgetSkips` (batch `gregscope.sampler.budget`, 120 real ticks) and
`samplingDisabledRecordsSkippedGaps` (batch `gregscope.sampler.disabled`, 0 ticks) - plus the two new `IdleCostTests`
cases `exactlyOneServerTickHandler` and `handlerDoesNoWorkWithoutLiveSensors` (0 ticks). Logged by the run: the one
`@SubscribeEvent` method is `TelemetrySampler.onServerTick(TickEvent$ServerTickEvent)`; 20 sampler ticks with an empty
registry gave 0 cycles and 0 samples; 64 sensors against a 100 us budget gave 147 samples, 183 `sampling_skipped`
gaps, the budget exceeded on all 120 ticks and a warmed-up sample of 6,116 ns (see
[Sampler cost](#sampler-cost-gs-108)). Three earlier full runs of the same suite, two of them reusing a world, gave
the same result (93 passed, 4 skipped).
Unit tests: **387 in 26 classes**, all green, of which 39 are the new
`sampling.SamplerScheduleTest` (17), `sampling.SampleFolderTest` (11) and `sampling.TelemetryFrameTest` (11), plus one
new `ShippedClassesTest` case. Other in-game classes unchanged: AdapterBindingTests 2, BasicMachineSnapshotTests 15,
ChunkReloadTests 4, ElectricBlastFurnaceSnapshotTests 11, LifecycleTests 2, OpenComputersComponentTests 8,
OpenComputersExampleScriptTests 5, SafetyTests 2, SensorPlacementTests 8, SensorCoverTests 14, SensorLifecycleTests 8,
TeamAccessTests 3; `IdleCostTests` is now 4. GitHub CI has not run this state.

GS-107, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world and without
`config/gregscope.cfg`** (both moved away first): **84 passed, 4 skipped** (`ProbeBenchmarkTests`, opt-in), 0 failed,
0 timed out, 0 issues; Horizon-QA status `passed`, exit code 0; Gradle wall time 49 s. New: `SensorLifecycleTests`,
8 passed - `heartbeatRegistersLiveSensor`, `crowbarDropCoverMarksRemoved`, `facingChangeDropRemoves`,
`survivalGetDropsMarksInItemAndReplaceResumes`, `destroyBlockBecomesMissingAfterThreeSamples`,
`duplicateUuidRekeysNewcomer` (batch `gregscope.sensor.lifecycle`), `chunkUnloadMarksUnloadedAndReloadLive` (batch
`gregscope.sensor.lifecycle.reload`, 2 ticks) and `globalCapGivesOverCap` (batch `gregscope.sensor.lifecycle.caps`).
Every other new test takes 0 real ticks: their warps and hook calls cost no server time. A run reusing the previous
world gave the same result (84 passed, 4 skipped). Unit tests: 347 in 23 classes, all green, of which 34 are the new
`registry.SensorRegistryCoreTest`. Other in-game classes unchanged: AdapterBindingTests 2, BasicMachineSnapshotTests
15, ChunkReloadTests 4, ElectricBlastFurnaceSnapshotTests 11, IdleCostTests 2, LifecycleTests 2,
OpenComputersComponentTests 8, OpenComputersExampleScriptTests 5, SafetyTests 2, SensorPlacementTests 8,
SensorCoverTests 14, TeamAccessTests 3. GitHub CI has not run this state.

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
- [x] **Adds no blocks, items, recipes, GregScope-owned saved data, mixins, or global tick handlers.**
  **v0.2 lifts parts of this on purpose (design-v0.2 §1.4):** GS-105 adds one item, and GS-108 adds exactly one
  `ServerTickEvent` END handler, `sampling/TelemetrySampler`. Mixins, access transformers, core mods, world
  generators and tile entities stay out until GS-112's Hub block. The v0.1 record below is kept as written.
  Inspection:
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
  was timed or profiled.** **v0.2 (GS-108):** there is now one `ServerTickEvent` END handler, and the claim it carries
  is narrower and still tested: a tick with no LIVE sensor does no per-sensor work at all (it returns before it reads
  `nanoTime`), which `IdleCostTests.handlerDoesNoWorkWithoutLiveSensors` and
  `SamplerScheduleTest.anIdleTickReadsNoClockAndDoesNoWork` assert; a server with sensors pays the documented
  design-v0.2 §6.3 budget, measured under [Sampler cost](#sampler-cost-gs-108). The v0.1 record below is kept as
  written. The tests show GregScope has nothing that could run periodically: no event bus listener
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
