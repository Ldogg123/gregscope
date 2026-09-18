# Testing

How GregScope v0.1 is verified, what each layer covers, and what is **not** covered yet.

GregScope is checked in two layers:

1. **Unit tests** (plain JVM, JUnit 5) for the pure snapshot core: classification, snapshot building and parsing.
2. **Horizon-QA in-game tests** on a real dedicated server with GT5-Unofficial 5.09.54.133 and OpenComputers
   1.12.61-GTNH: real machines, real OpenComputers drivers and Adapters, real chunk unloads, and the documented Lua
   example running on a real OpenOS computer.

Both run on every push and pull request in CI (`.github/workflows/build-and-test.yml`).

## Unit tests

Location: `src/test/java`. Run with `./gradlew test` (also part of `./gradlew build`). 500 tests in 36 classes:

| class | tests | covers |
|---|---|---|
| `ShippedClassesTest` | 10 | Reads every compiled class of the shipped mod (the `main` output on the test classpath) as bytes, without loading it, and checks its constant pool (every referenced class, member, descriptor and string literal) and super class. Fails on any reference to `net/minecraft/client/` or `cpw/mods/fml/client/` (slash or dot form), on `@SideOnly(CLIENT)`, and on periodic-work hooks: `SubscribeEvent`, `EventBus`, `TickEvent`, `FMLCommonHandler`, `MinecraftForge`, `GameRegistry`, `IWorldGenerator`, threads, timers, executors, members named `canUpdate`/`update`/`updateEntity`, or a `TileEntity` subclass. Also checks that the expected shipped classes were found and no test class was scanned, and that the reader sees an annotation descriptor and a string literal in a real class file. GS-101 (v0.2): `clientProxyIsOnlyNamedByTheSidedProxy` fails if any shipped class other than `ClientProxy` references `io/github/ldogg123/gregscope/ClientProxy` as a class (only the `@SidedProxy` strings in `GregScope` may name it), if the `@SidedProxy` strings do not name `ClientProxy`/`CommonProxy`, or if `ClientProxy` does not set `gregscope.clientProxyLoaded`; the expected-class list now also holds `ClientProxy`, `GregScopeTestHooks`, `config/Settings` and `config/GregScopeConfig`. The client-reference and periodic-work checks are unchanged in GS-101; GS-105/GS-108/GS-112 lift parts of them deliberately (design-v0.2 §1.4). GS-103 only added `model/StateCodes`, `history/MinuteSlot`, `history/SecondRing`, `sensor/SensorNbtCodec` and `sampling/LogHistogram` to the expected-class list; no check changed. GS-104 only added `access/AccessPolicy` and `access/GtnhlibTeamResolver` to that list; no check changed. GS-107 likewise only added `registry/SensorRegistryCore`, `registry/SensorEntry`, `registry/SensorState` and `registry/SensorRegistry` to that list; no check changed. GS-105 lifts the `GameRegistry` part of the periodic-work check deliberately and narrowly (design-v0.2 §1.4, "no items" lifted): only `sensor/SensorCovers` may reference `cpw/mods/fml/common/registry/GameRegistry`, and the new `gameRegistryIsUsedOnlyToRegisterTheSensorItem` requires that it does, that it calls `registerItem`, and that it references no `register*` name other than `registerItem` and GT's `registerCover`; the member names `registerWorldGenerator`, `registerTileEntity`, `registerTileEntityWithAlternatives` and `registerBlock` are now forbidden in every shipped class (GS-112 lifts tile entity and block registration for the Hub). The client-reference check is unchanged: the item sets its icon with `setTextureName`, and neither the item nor the cover overrides a client-only method with `@SideOnly(CLIENT)` or references a client class. The expected-class list adds `GregScopeAssets` and the `sensor/` classes `ItemMachineSensor`, `MachineSensorCover`, `SensorCovers`, `SensorCover` and `SensorKind`. GS-106 changed no check either: it only adds `sensor/NbtKeyValue`, `sensor/SensorEvents` and `sensor/SensorDescription` to the expected-class list. The cover reads and writes NBT, reports to a listener and builds its description without touching a client class, a tick hook or `GameRegistry`. GS-108 lifts the tick part of the periodic-work check, deliberately and narrowly (design-v0.2 §1.4, "no global tick handler" lifted for exactly one handler): only `sampling/TelemetrySampler` may reference `SubscribeEvent`, `EventBus`, `TickEvent` and `FMLCommonHandler`, and the new `theOnlyTickHandlerIsTheSampler` requires that it really references all four (so the skip cannot pass vacuously), that it names `TickEvent$ServerTickEvent` and `TickEvent$Phase`, and that it uses none of the other forbidden references (threads, timers, executors, `MinecraftForge`, `GameRegistry`, world generators). Every other shipped class is still forbidden all of them. That there is exactly **one** `@SubscribeEvent` method is checked on the running server by `IdleCostTests.exactlyOneServerTickHandler`, because this reader sees the constant pool, not the method table. The expected-class list adds `sampling/SamplerSchedule`, `SampleFolder`, `TelemetryFrame`, `SensorView`, `TargetResolver` and `TelemetrySampler`. GS-110 lifts the Forge-bus part of the same check for exactly one more class (design-v0.2 sections 8.3 and 8.4 hang the registry save on the overworld's `WorldEvent.Save` and the shutdown flush on its `WorldEvent.Unload`, which needs `@SubscribeEvent` on `MinecraftForge.EVENT_BUS`): only `GregScopeWorldEvents` may reference `MinecraftForge`, its bus type and `SubscribeEvent`, and the new `theOnlyWorldEventHandlerIsThePersistenceHook` requires that it really references all three (so the skip cannot pass vacuously), that it names `WorldEvent$Save` and `WorldEvent$Unload`, that it does **not** subscribe to `WorldEvent` itself (its `PotentialSpawns` subclass fires several times per chunk per tick), that it uses none of the other forbidden references, and that no other shipped class names `MinecraftForge` at all - the sampler's own lift does not include it. That there are exactly **two** `@SubscribeEvent` methods is checked on the running server by `IdleCostTests.exactlyTwoWorldEventHandlers`. The expected-class list adds `history/HistoryPersistence`, `registry/RegistryPersistence` and `GregScopeWorldEvents`. GS-112 lifts the tile entity part for exactly one more class (design-v0.2 §1.4 lifts "no blocks, no tile entities"): only `hub/TileTelemetryHub` may extend `TileEntity` and may name `canUpdate`, and the new `theOnlyTileEntityIsTheHub` requires that it really does both (so neither skip can pass vacuously), that it names neither `update` nor `updateEntity`, that it uses none of the periodic-work references, and that no other shipped class extends `TileEntity`. That `canUpdate()` really answers **false** is checked on the running server by `HubBlockTests` (this reader sees the constant pool, not method bodies) - the GS-112 negative control below is exactly that case. `GAME_REGISTRY_USERS` became a map from class to the exact `register*` names that class may name, so `sensor/SensorCovers` keeps `registerItem`/`registerCover` while `hub/TelemetryHubs` gets `registerBlock`/`registerTileEntity` and neither can make the other's call; `registerWorldGenerator` and `registerTileEntityWithAlternatives` stay forbidden everywhere. Because the reader cannot tell a declared method name from a called one, `TelemetryHubs`' own entry point is called `install` rather than `register*`. The expected-class list adds `hub/HubNbtCodec`, `hub/HubViews`, `hub/BlockTelemetryHub`, `hub/TileTelemetryHub` and `hub/TelemetryHubs`. |
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
| `PureSourcesTest` | 8 | GS-103, the §2 `[pure]` rule, in three layers. Every GS-103 and GS-104 pure class (and the v0.1 enum `model/MachineState`, which `StateCodes` uses) exists and is marked `[pure]`; every source file in `history/`, `sensor/`, `sampling/` and `access/`, subpackages included, is `[pure]` unless listed as an intended MC adapter (GS-104 lists `access/GtnhlibTeamResolver.java`; later tickets must add theirs on purpose; GS-109 lists `history/NioFileStore.java`, `history/HistoryIo.java` and `registry/RegistryNbtCodec.java`, and adds the `[pure]` `history/Crc32`, `HistoryFileCodec`, `SlotLayouts`, `FileStore`, `IoListener` and `registry/RunsTable`). A class that is not `[pure]` must not write the literal `[pure]` even in its own Javadoc, because the scan looks for that token in the file: saying "not [pure]" in `HistoryIo` and `NioFileStore` made the test treat both as pure and fail. Source denylist: no `[pure]` file has an import or qualified name (outside comments) from `net.minecraft`, `net.minecraftforge`, `cpw.mods`, `gregtech`, `gtPlusPlus`, `tectech`, GT5U's other root packages (`bartworks`, `bwcrossmod`, `detrav`, `galacticgreg`, `ggfab`, `goodgenerator`, `gtneioreplugin`, `gtnhintergalactic`, `gtnhlanth`, `kekztech`, `kubatech`, `toxiceverglades`), `li.cil`, `com.gtnewhorizon(s)`, `com.cleanroommc`, `java.lang.reflect`, `org.spongepowered`, `org.lwjgl`, `io.netty` or `com.google`. Source allowlist: `[pure]` imports are `java.*` or `[pure]` GregScope classes only, and no `[pure]` file names a listed adapter by simple name in code. Bytecode allowlist: every class referenced by a compiled `[pure]` class (inner classes included; constant pool, descriptors, signatures) is `java/*` (not `java/lang/reflect`) or a GregScope class whose source is `[pure]`. Negative-control cases check each scanner (denylist, import/adapter-name scanner, and the bytecode reader on `GtnhlibTeamResolver`/`AccessPolicy`). GS-105 adds the pure `GregScopeAssets`, `sensor/SensorKind` and `sensor/SensorCover`, and lists the sensor package's MC adapters `ItemMachineSensor`, `MachineSensorCover` and `SensorCovers` as intended adapters. GS-106 adds the pure `sensor/SensorDescription` and lists two more intended adapters, `sensor/NbtKeyValue` (the `NBTTagCompound` side of the `KeyValue` seam) and `sensor/SensorEvents` (GT types in its signatures). GS-107 adds the `registry` and `model` packages to the scanned set: the pure `registry/SensorState`, `RemovalCause`, `PosKey`, `SensorEntry`, `RegistryEvents` and `SensorRegistryCore`, the v0.1 `model/MachineSnapshot`, `MachineKind`, `SnapshotKeys` and `StatusIds` (pure in fact since v0.1, marked and now enforced because a registry entry holds a snapshot), and the intended adapter `registry/SensorRegistry`. GS-108 adds the pure `sampling/SamplerSchedule`, `SampleFolder`, `SamplerStats`, `SamplerStatsView`, `LimitsView`, `CountersView`, `MachineCountersView`, `SensorView` and `TelemetryFrame` (and pins `config/Settings` and `config/ConfigKeys`, which a `LimitsView` copies), and lists two more intended adapters, `sampling/TargetResolver` (the only telemetry class touching `World`) and `sampling/TelemetrySampler` (the one tick handler). GS-110 lists the two persistence services, `history/HistoryPersistence` and `registry/RegistryPersistence`: neither imports a game class, but each names an adapter above (`HistoryIo`, `RegistryNbtCodec`), and listing them is what keeps the boundary one-way - no `[pure]` class may name them either. GS-112 adds the `hub` package to the scanned set with the pure `hub/HubNbtCodec` and `hub/HubViews`, and lists its three Minecraft adapters `hub/BlockTelemetryHub`, `hub/TileTelemetryHub` and `hub/TelemetryHubs`. |
| `access.AccessPolicyTest` | 43 | GS-104, design-v0.2 §5, over an in-memory `FakeTeams` resolver (team T: owner, officer, two members; team X: owner; two teamless players). A 23-row truth table with the expected values written out per row (not derived from the rule): viewer (self, team owner, officer, member, other team, teamless, op, console) x sensor owner (member, team owner, teamless, unowned) -> `canView`, `canRename` with `renameRequiresOfficer` false and true, `canOpenHub`, `canPurge`, `isOp`. A 14-row `inHubScope` table (same team either direction, other team, teamless, null Hub or sensor owner). Op no-escalation: an op may open another player's Hub but its scope stays the Hub owner's team (the op's own, strangers' and unowned sensors stay out), while commands let the op see everything. `permissions.opLevel` from `Settings` (default 2; 0 makes every player an op without asking the permission check, because vanilla's check is false for any player not on the ops list; 1 still requires the check; 4 rejects level 3; the console is op at any level; the permission check is asked for exactly the configured level). Owner and op decisions make no team lookup; `sameTeam` null handling and self without lookup; `Viewer` argument checks. |
| `access.NoErrorLoggingTeamLookupTest` | 4 | GS-104, errata E4, as a grep over every file in `src/main` (sources and resources, comments included): no `getTeamByPlayer`, no `getOrCreateTeam` (it calls `getTeamByPlayer` first, GTNHLib 0.11.46 `TeamManager.java`), no `getTeamId` (team IDs are never read, so never stored). Only `access/GtnhlibTeamResolver.java` names `gtnhlib.teams`, and it contains the `TeamManager.getTeamMap()` scan with `team.isMember(player)`. A negative-control case checks that the scanner finds each name in code, a comment and a string. |
| `AssetsExistTest` | 5 | GS-105, design-v0.2 §3.2/§12.2/§14. Finds the constants of `GregScopeAssets` by naming convention (reflection, test code only), so a new constant is checked without editing the test: every `BLOCK_ICON_*` must be a PNG at `assets/gregscope/textures/blocks/<name>.png` and every `ITEM_ICON_*` one at `textures/items/<name>.png`, read from the test classpath (the processed `main` resources that go into the jar), with the PNG signature, an `IHDR` of 16x16, bit depth 8 and colour type 6 (RGBA), decodable by `ImageIO` with an alpha channel and at least one non-transparent pixel. Every `LANG_*` key (except `*_PREFIX`), plus `gregscope.state.<id>` for every `MachineState` and `gregscope.gap.<id>` for every `GapReason`, exists with a non-empty value in `lang/en_US.lang`; the §12.2 tooltip texts are pinned. The design names are pinned: `iconsets/GREGSCOPE_SENSOR_OVERLAY` (errata E1), `machine_sensor`, `gregscope.machine_sensor`, `item.gregscope.machine_sensor.name`, and, with GS-112, the §17 frozen Telemetry Hub names `telemetry_hub` (block and ItemBlock), `gregscope:telemetry_hub` (tile entity), `gregscope.telemetry_hub`, `tile.gregscope.telemetry_hub.name` and the three block icons `telemetry_hub_front/side/top`. The lang file is ASCII with one `key=value` per line (`#` comments, as 1.7.10's `LanguageMap` reads it) and no duplicate keys; a missing resource is detected. |
| `sensor.SensorKindTest` | 2 | GS-105 with the design-v0.3 GS-201 A2 hook. Kind codes 0/1/2 and labels `machine`, `item_flow`, `fluid_flow` pinned (`unknown` otherwise); v0.2 supports only `machine`. |
| `sensor.SensorDescriptionTest` | 8 | GS-106, design-v0.2 §3.4. The cover status line: id only, id with a label, id with a label and the registry's availability word, and the two inert forms (`GregScope sensor (inactive)` and `(unsupported data version)`), including an availability word being ignored while inert. `SensorIdentity.forAttach` (the §3.4 owner chain): the attaching player wins over the machine owner, no player falls back to the machine owner, an unowned machine gives an unowned sensor (owner and owner name both null), and the stack's display name is sanitized into the label. |
| `registry.SensorRegistryCoreTest` | 38 | GS-107, design-v0.2 §4.2/§4.3 and the design-v0.3 GS-201 A1 rules, over a fake clock, a fake team resolver, a queued UUID supplier and counting maps. One test per §4.3 row: unknown heartbeat registers LIVE and allocates the rings; the global cap refuses the 17th sensor of 16 and counts `quotaRefusedTotal`; a refused UUID is re-checked only every 1,200 ticks (and a throttled retry costs no cap scan); the per-team cap counts teammates, survives a merge (existing sensors kept, new ones refused), treats all unowned sensors as one pseudo-owner and means unlimited at 0; the LIVE fast path does exactly one id lookup and touches neither the reverse index nor the clock, the teams nor the events; a duplicate of a LIVE or an UNLOADED sensor re-keys the newcomer and leaves the original where it is; another UUID at the same (position, side) is REPLACED, another Machine Sensor on any face of the same block is REPLACED too, while a kind-1 cover on another face of that block is not (A1); a known UUID with another kind is re-keyed (A1) and an unregistered kind gets no entry at all; an unload closes the open minute with `chunk_unloaded`, keeps the minute ring, drops the second ring and still counts; MISSING, IN_ITEM and REMOVED all resume LIVE at a new position on the same entry object, and a resume is refused when the cap is full; three strikes in a row become MISSING, a successful validation resets them, and each strike records a `target_missing` second and gap; detach and destroy give REMOVED/IN_ITEM with the partial minute queued first; tombstones expire after `removedRetentionHours` and UNLOADED entries after `staleExpiryDays` (both exactly at the window are kept), LIVE never expires, and the oldest tombstone is evicted first beyond `maxSensors`; purge expires at once and the same UUID registers again with empty history; `purgeAll` also clears the refusal window; an empty registry rebuilds itself from heartbeats. Also the `PosKey` packing (lossless at the world limits, dim and side part of the key) and the pinned persisted state and cause codes. The GS-110 review follow-up adds `aClockThatWentBackwardsExpiresNothing`: with a clock set a year before the newest timestamp the registry itself holds, `housekeeping()` expires neither the tombstone nor the stale entry, counts the refused sweep in `clockSkewSkippedSweeps()` and reports both timestamps; a minute of skew is inside the tolerance; and with the clock right again both windows apply exactly as before. |
| `sampling.SamplerScheduleTest` | 17 | GS-108, design-v0.2 §6.1/§6.3, over a fake `nanoTime` that only moves when a sample is taken, so the budget arithmetic is exact rather than timing-dependent. Bucket balance at 0, 1, 19, 20, 21, 256 and 1024 sensors (no bucket differs by more than one); the first sensors fill the lowest buckets in order; an emptied bucket is refilled first and ties go to the lowest index; removal is an O(1) swap that leaves every surviving sensor findable at its recorded slot, is idempotent, and drops the sensor from the carry-over list; adding the same sensor twice schedules it once; an interval of 0 and a null clock are rejected. Tick loop: an empty schedule and a tick whose own bucket is empty read `nanoTime` **zero** times and report no work; every sensor is sampled exactly once per interval and the interval ends on the last bucket index; 400 us per sample against a 1,000 us budget gives exactly 3 samples and carries the rest, with `cycleNanos` 1,200 us; carry-over is served before the new bucket; the due bucket carries over too when the carry-over list alone spent the budget (regression, see the GS-108 notes); a sensor carried for a whole interval is given up on and only then; a sensor that removes itself from the schedule while it is being sampled (what a sample that finds its chunk unloaded does) costs no other sensor its turn; `sampling.enabled=false` skips everything due and carries nothing; `clear` empties everything. |
| `sampling.SampleFolderTest` | 13 | GS-108, design-v0.2 §6.2, over entries from a real `SensorRegistryCore` and hand-built snapshots (no Minecraft). One sample reaches the second ring with the right state code, flags, maintenance, progress x10000 and EU values, the counters with the §7.6 EU estimate, and the entry's `lastSnapshot`/`lastSampleEpochSec`/`lastGapReason`; the first sample sets the machine metadata (so the registry is dirty) and an unchanged one does not, while a new status id does; an absent `euPerTick`/`energyStored` is not recorded as zero and does not enter the minute's average; a generator's negative EU/t counts as generated; a multiblock records formed, maintenance and the recipes delta (the first sample only sets the baseline) and a backwards GT counter sets the reset flag and counts; a sample in the next minute closes the open one with `expectedSamples` 60 and no gap mask; `serverTicks` are the ticks that elapsed while the minute was open. Gaps: a skip records a non-valid second with the reason and the minute's gap mask while the observed sample still counts, a gap leaves `lastSnapshot` and the metadata alone, and a derived or null reason is rejected. |
| `sampling.SamplerStatsTest` | 4 | Review follow-up GS108-T5, design-v0.2 §7.6. `clockSkewRefusedTotal` accumulates the per-fold deltas and therefore survives a sensor being purged, expired or turned into a tombstone (a sum over the live accumulators would fall back); a fold with no refusal, and a negative delta, move nothing; counters saturate at `Long.MAX_VALUE` instead of overflowing; the microsecond window reports only completed windows, and nanoseconds are rounded down to whole microseconds. |
| `sampling.TelemetryFrameTest` | 11 | GS-108, design-v0.2 §7.7 plus the design-v0.3 GS-201 A6 shape. The frame's sensor list is unmodifiable and copied (mutating the source list afterwards changes nothing) and sorted by UUID so paging is stable; `sensor(id)` and `count(state)`; `TelemetryFrame.EMPTY`. A `SensorView` copies every field of the entry and a later change to the entry does not reach it; `lastSnapshot` is null for a sensor that was never sampled (A6: nullable) and `counters` is null without rings. `MachineCountersView` copies the live counters, its array getters return fresh copies a caller cannot write through, and it rejects a derived gap reason and an unknown state code. `SamplerStatsView` copies the counters and takes `duplicatesRekeyedTotal` and `quotaRefusedTotal` from the registry; `LimitsView` copies the settings. Every field of all five frame classes is final (reflection, test code only). The review follow-up extends `theEmptyFrameIsUsableBeforeTheFirstPublish`: `TelemetryFrame.EMPTY` now carries a zero-valued `SamplerStatsView` and a `LimitsView` of the defaults instead of nulls, because `/gregscope stats` reads eleven of those numbers without a null check and would otherwise throw during the first sampling interval of a run. |
| `history.HistoryFileCodecTest` | 12 | GS-109, design-v0.2 §8.2, against the golden `gsh_header_*` fixtures. CRC-32 check value `"123456789"` -> `0xCBF43926`, empty input 0, an offset window, an out-of-range window. The file is exactly 92,224 B (= `SizeCeilings.HISTORY_FILE_BYTES`), its body is zeroed (`epochMinute` 0 = empty) and slot offsets run 64..92,160 with both ends refused. The golden 64-byte header encodes and decodes field for field (format 1, 64 x 1440, kind 0, layout 1, a UUID with a negative low half, `createdEpochSec`) and `Header.create` equals it. Status rules: a flipped header CRC, bad magic, a zero-length or truncated file and `null` are `CORRUPT` (suffix `.corrupt-<epochMs>`); format 2, slot layout 2 with kind 1, a kind/layout mismatch and slot count 1441 are `UNSUPPORTED` (suffix `.unsupported-v<N>`); another sensor's file is `MISMATCH` (suffix `.mismatch`), while no expected id means nothing to mismatch and `OK` has no suffix. `SlotLayouts` pins the kind/layout pairing 0->1, 1->2, 2->3, anything else `NONE`, with only layout 1 supported. |
| `history.NioFileStoreTest` | 9 | GS-109, design-v0.2 §8.1 to §8.3, over a JUnit `@TempDir`. A created history file is one 92,224 B image and leaves no `.tmp` behind; a slot write lands at its own offset and touches neither the header nor the neighbouring slot; a slot write with no file, with a short file or with a 63-byte slot throws and creates nothing. Delete is idempotent; quarantine renames and never overwrites (a second `.corrupt-123` becomes `.corrupt-123-1`) and quarantining a file that is not there is not an error. `registry.dat` is replaced through `.tmp`, the old file becomes `.bak` (nothing to back up on the first write), and a quarantined `registry.dat` leaves the `.bak` in place. `anAtomicMoveThatIsNotSupportedFallsBackToAPlainReplacement` injects a mover that throws `AtomicMoveNotSupportedException` for every `ATOMIC_MOVE`: all three replacements still arrive, the atomic move is attempted first every time, and the production mover really asks for it. `everyWriteAssertsItRunsOnTheIoThread` asserts `-ea` is on in this JVM and that all six writes plus the two history reads throw `AssertionError` from a store whose thread check answers "no", while the two registry reads (the documented `serverStarting` exception) still work; `theProductionStoreAsksTheIoThreadItself` shows the shipped constructor refusing the JUnit thread. |
| `history.HistoryIoTest` | 9 | GS-109, design-v0.2 §8.4, over an in-memory `FileStore` that records its calls. With the default capacity 4,096 and the thread deliberately not started, tasks 1-4,096 are accepted and the **4,097th is dropped** and counted (queue size and the queued count do not move). Drops are reported at most once per 10 minutes over a fake nanosecond clock: the first drop warns at once, 100 more do not, one at `interval - 1` does not, one at `interval` does. With the thread running: tasks run in the order they were queued (create, slot, registry, delete), `flush` waits for them, the thread is a daemon, `stop()` finishes and refuses later tasks. A load parks its result for the server thread (`OK` with the whole file, `absent` for a sensor with no file); a file with bad magic is quarantined as `.corrupt-*` and reported `CORRUPT`, another sensor's file as `.mismatch`. A task that throws is counted in `ioErrorsTotal` and reported, and the next task still runs. A task parked on a gate (a stalled disk) makes `flush(500 ms)` time out well inside the 10 s stop budget, and the stop still finishes once it is released. The review follow-up adds three cases for what happens when a task throws: a failed load still produces a result that is `failed()` and explicitly **not** `absent()` (nothing was renamed, and the next attempt reads the file normally); a failed create and a failed slot write turn up in `drainWriteFailures()` while a failed delete and a failed registry write need no answer; and `terminate()` interrupts and joins a worker parked on a stuck task, which is what lets the next server run own the save root. |
| `registry.RunsTableTest` | 7 | GS-109, design-v0.2 §8.3 and review fix F2. An empty table claims nothing and a stop without a start is not an error. `loaded` closes every unclean run (`stop == 0`) at the loaded `saved`, never before its own start, and makes nothing current. `startRun` appends, refuses a second open run and stores `stop == 0` (what makes a crash recognisable); `stopRun` clamps to the start and keeps the first stop. Only the newest 32 rows survive, oldest dropped, and the current row survives the trim. `resolve` ends the current run at `now`, keeps stored stops, and gives a row that never got its load step an empty run. `downtimeBetweenRunsIsServerOffline` feeds the resolved runs to `GapRanges`: minutes inside a run read `unknown`, the ten minutes between the crashed run's `saved` and the next start read `server_offline`. |
| `history.PersistenceScenarioTest` | 8 | GS-110, design-v0.2 section 14, over an in-memory `FileStore` with the real `SensorRegistryCore`, `HistoryPersistence`, `HistoryIo` daemon thread and `HistoryFileCodec`; only the disk is faked. `aSecondRunLoadsBackExactlyWhatTheFirstOneWrote`: run 1 creates the file before the first minute closes, records five minutes (four closed by a rollover, the fifth as a partial slot at stop), writes **exactly one slot per closed minute**, drops nothing, and the file passes its own header rules; run 2 then loads it back, marks the entry `historyLoaded`, takes all five slots and does not recreate the file, and every slot is equal field for field, the first with all 60 samples. `theGapBetweenTwoRunsIsServerOffline`: ten minutes of downtime between the two runs read as exactly one `server_offline` range (section 7.5 rule 3). `aMissingMinuteInsideARunWithAnUnloadedSensorIsChunkUnloaded`: the other branch of rule 3. `anUncleanRunIsClosedAtTheSavedItWasWrittenWith`: `stop == 0` becomes the loaded `saved`, appending this process's run does not move it, a clean run keeps its own stop, and a run that starts after `saved` never stops before it started. `noSlotIsWrittenWhileTheSensorIsUnloaded`: the partial minute of the unload itself is written and nothing after it. `persistFalseWritesNoHistoryFile`: nothing is read, created or written, the store stays empty and the minute ring still holds the history. `anExpiredSensorLosesItsHistoryFile`: the file survives the removal, and the `removedRetentionHours` sweep deletes it. `aLoadResultForAGoneSensorIsDiscarded` and `ramWinsOverDiskOnMerge` (the RAM slot wins over the one on disk, and the minute that closed while the file was still being read reaches it afterwards). The review follow-up adds three failure scenarios over the same in-memory store, which can now be told to fail a chosen number of reads or writes: a read that throws leaves the file untouched and is retried until it works (`aReadThatThrowsIsRetriedAndLeavesTheFileAlone`); a create that throws sends the sensor back to REQUESTED so no slot is ever written into a file that is not there, and the retry writes the whole ring, minutes recorded meanwhile included (`aCreateThatThrowsDoesNotLeaveTheSensorThinkingItHasAFile`); and a file that fails every time is given up on after `MAX_FILE_FAILURES` tries rather than retried once per interval for ever (`aFileThatKeepsFailingIsGivenUpOnRatherThanRetriedForEver`). |
| `command.CommandArgsTest` | 21 | GS-111, design-v0.2 §11. Every parsing rule of the subcommand table: no subcommand, an unknown one (the word is kept for the message), case insensitivity; `list` with a filter, a page, both in either order and neither, and the refusals (`0`, a number too large to be a page, two pages, two filters, `-1`, an unknown filter name); `info`/`label`/`purge` needing at least four characters of id, counted with the dashes stripped; `label` joining the rest of the line, an empty text meaning "clear", and a text over `Labels.MAX_INPUT_UNITS` refused before sanitizing (exactly at the limit is accepted); `purge --tombstones`, `purge --stale` and `purge <id>`. Prefix matching ignores dashes and case and accepts a full UUID in either form; an ambiguous prefix returns at most the limit. Paging is 10 per page and clamps below 1 and above the last page. `isStale` is UNLOADED for **more than** seven days (exactly seven is not stale). The text helpers: `age` (`never`, `now`, `30s`, `5m`, `3h`, `2d`), `bytes` (1024-based, one decimal, `?` for a negative) and `percent` (clamped, `-` for `NaN`). |
| `sensor.RenameCooldownTest` | 5 | GS-111, design-v0.2 §3.5. An unknown player may write at once; a write starts the cooldown for that player only and expires exactly at `renameCooldownSeconds`; the console (null UUID) and `renameCooldownSeconds <= 0` are never limited and the console is never tracked; a clock that went backwards costs one cooldown at most instead of locking the player out until wall-clock time catches up (§6.2); `clear` forgets everyone. |
| `hub.HubTileNbtCodecTest` | 12 | GS-112, design-v0.2 §9.1. The Telemetry Hub's `gsHub` record over an in-memory `KeyValue`: the four key names and the format are pinned; an owned Hub round-trips; an unowned one writes no owner keys and clears any it had; an empty compound and `null` are ABSENT, and so is a foreign type under the marker or a marker of 0 (no record this project ever wrote); `gsHub` above the format is UNSUPPORTED, parses nothing and leaves the compound untouched, and the marker is read unsigned (200, not -56); half an owner (one of the two longs) is no owner and a valid record all the same; an owner with no cached name is still an owner; the owner name is capped at 16 UTF-16 units on write and on read, the same rule a sensor's owner name follows; writing never touches a foreign key. |
| `hub.HubViewsTest` | 8 | GS-112, design-v0.2 §9.1 and the `limits.maxOpenHubViews` cap of §12.3. Asking never records anything; the cap counts open views and a closed view frees the slot; a viewer who already holds a view is never refused and never counts twice; closing what is not open changes nothing; a viewer without a UUID can never open; a cap of 0 or less allows nothing new but does not close what is open; `clear` forgets everything; `opened(null)` is rejected. |

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
encoder.

**Golden fixtures (GS-109).** The same script now also writes the design-v0.2 §8.2/§8.3 fixtures: `gsh_header_valid.hex`
(a format-1 history header, kind 0, layout 1), `gsh_header_bad_crc.hex` (its CRC-32 flipped in the lowest bit),
`gsh_header_v2.hex` (format 2, CRC valid), `gsh_header_flow_layout.hex` (kind 1 with the reserved layout 2, CRC valid)
and `registry_v1.nbt.gz.hex`, a whole gzipped-NBT `registry.dat` written **without any NBT library** (16 bytes per
line; `gzip` `mtime=0` keeps re-runs byte-identical). It holds two runs (the second unclean, `stop=0`), two machine
entries - one with an unknown key - and one design-v0.3 kind-1 entry with `tier`/`lastIo`, plus an unknown root key,
so decoding it checks the key names and types of §8.3 independently of the Java codec and exercises the design-v0.3
§5.1 A3 rule. CRC-32 is computed bitwise in the script and its `0xCBF43926` check value is asserted. Two consecutive
runs produced byte-identical files, and the GS-103 fixtures were regenerated unchanged.

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
- `addon.gradle` gives both run tasks `-ea:io.github.ldogg123.gregscope...` (GS-109). The dev JVM otherwise runs
  without assertions, and `NioFileStore`'s "no file I/O on the server thread" check is an `assert`; scoping `-ea` to
  GregScope's packages leaves Forge, GT, MUI2 and MC running as they ship.
- `GregScopeTestHooks.flushIo()` (GS-109) waits until the I/O thread has written everything queued so far, so a test
  can read a `.gsh` or `registry.dat` back without sleeping. It returns true when there is no I/O thread at all
  (`history.persist=false`).
- Do not run two Gradle invocations at the same time; they share `run/server`.

### Running in CI

`.github/workflows/build-and-test.yml` calls GTNH's shared `build-and-test` workflow with `horizonqa: true` and
`horizonqa-tests: gregscope`. It builds, runs the unit tests, then runs `runServer` in `ci` mode on a Linux dedicated
server under a **300 s** step timeout (server boot plus every test batch).

Batches run one after another; tests inside a batch run in parallel. Every wait is bounded. If every batch ran to its
timeout the suite would take **5,520 ticks (276 s at 20 TPS)**: the sum of the longest `timeoutTicks` in each of the
**35** batches (GS-110 left it at 4,320 over 30; GS-111 added `gregscope.surface.command`,
`.surface.command.reload` and `.surface.command.unload` at Horizon-QA's default 100 ticks each, because every test in
them is synchronous apart from one `thenIdle(1)`; GS-112 adds `gregscope.surface.hub` and `.surface.hub.reload`, also
at the default 100, for the same reason).

> **Corrected after GS-112 (review follow-up).** The figures written here up to and including GS-112 - 4,820 ticks,
> 241 s, "59 s of headroom" - were **700 ticks short**. The recount script matched `timeoutTicks` only as a number,
> so the two batches that write `timeoutTicks = OPENOS_BATCH_TIMEOUT_TICKS` (`OpenComputersExampleScriptTests`, 450)
> were charged the default 100 each. The script now resolves `static final int` constants the same way it already
> resolved the batch-name strings, and the true worst case is **5,520 ticks = 276 s**. The 300 s is a wall-clock
> `timeout 300 ./gradlew runServer` around the whole invocation, not a budget for the batches alone: the last full
> run took 59 s of Gradle wall time, of which only 28 s lay between `Done (0.658s)` and the `HorizonQA RESULT` line,
> so roughly **30 s of Gradle configuration plus Forge/GTNH boot sits inside the same 300 s**. There is therefore
> **no headroom at all** in the worst case (276 + ~30 > 300): a run in which several long batches really hit their
> timeouts would be killed before Horizon-QA writes `build/horizonqa/horizonqa-result.json`. A normal run is nowhere
> near it (51 s in test cases), so this is about what happens when something hangs, not about everyday cost. The two
> ways out are to raise the workflow's `timeout` input above 300 or to bring `OPENOS_BATCH_TIMEOUT_TICKS` (450, three
> times the 149 ticks those tests really take) down; neither is done here, because the 300 s step is a fixed
> constraint for this work - it is recorded as an open issue instead. The paragraph below is the GS-109 recount it grew out of. GS-109 added `gregscope.storage` (200), and recounted the rest mechanically - a scan of all 89
`@GameTest(` annotations with balanced parentheses, resolving batch constants - because the figure written here after
GS-108 was still wrong. The 26 pre-GS-109 batches sum to 3,520 ticks, not 4,220 (GS108-T2 fixed 4,120 to 4,220 and
overshot; the earlier 2,100, 2,800, 3,200 and 3,800 predated later batches). Per batch, the maxima are 200 for
`gregscope.bench`, `.ebf`, `.reload.ebf`, `.reload.ebf.running`, `.sampler.budget`, `.sensor.cover`,
`.sensor.cover.reload`, `.sensor.lifecycle`, `.sensor.lifecycle.caps`, `.sensor.lifecycle.reload` and `.storage`;
60 for `.sampler` and `.sampler.disabled`; **450 for `.oc.openos` and `.oc.openos.ebf`**; 100 for the other twelve.
A hang in a single batch still ends with a Horizon-QA report, but see the corrected budget above: several batches
hitting their timeouts in one run no longer fit inside the 300 s CI step. A normal run is far below that: the last full run spent 51.1 s inside test cases
(longest single test 7.45 s) within about 1 m of Gradle wall time, server boot included.

**A new batch's name matters (GS-109).** Horizon-QA runs batches in alphabetical order, hands out test cells as tests
start, and keeps a finished cell loaded. A batch that sorts **before** `gregscope.reload.*` therefore takes cells in
chunks those tests unload, and the unload silently does nothing. GS-109's batch was first called
`gregscope.persistence`; its `v2Unsupported` cell landed at (0, 48), in the same chunk as
`ChunkReloadTests.basicMachineSurvivesChunkReload` at (8, 48), and that test failed on two fresh worlds (once
"expected different instances", once a stale OC Adapter address). Renaming it `gregscope.storage`, which sorts after
every existing batch, left every older test with the cell it always had and the suite went green. A new batch that
must run early has to be checked against the `reload` cells. GS-111 followed the same rule: its three batches are
called `gregscope.surface.command*`, which sorts after `gregscope.storage.*` and therefore after every batch that
unloads a chunk, so no older test's cell moved. GS-112's `gregscope.surface.hub*` sorts after those again, for the
same reason, and its one chunk-reload test has a batch to itself.

### Test classes

| class | batch | what it does |
|---|---|---|
| `BasicMachineSnapshotTests` | `gregscope.basic` | Every test runs on an LV, MV and HV Electric Furnace (`MTEBasicMachine`, suffix `[lv]`/`[mv]`/`[hv]`): idle, running, disabled, power-starved and output-blocked; asserts the probe's `state`/`statusId` and related keys. Tier values are asserted exactly: `metaName`, `energyCapacity` 2048 / 8192 / 32768 EU (`V[tier] * 64`), and for the 4 EU/t, 128-tick smelting recipe `euPerTick` 4 / 16 / 64 and `maxProgressTicks` 128 / 64 / 32 (GT's `OverclockCalculator`: the ULV recipe counts as LV, one overclock per tier above). These formula values matched what the server reported. |
| `ElectricBlastFurnaceSnapshotTests` | `gregscope.ebf` | GT's own formed EBF template: starting, idle, running, disabled while idle and while running, maintenance warning, no-maintenance shutdown, broken structure, full output bus, power loss, and `waiting` (`recipeAboveCoilHeatIsWaiting`: recipe needs 2101 K, the template gives 2001 K; input and EV power present, work allowed). |
| `IdleCostTests` | `gregscope.idle` | Structural evidence for "no idle cost", not a benchmark. GS-108 and GS-110 changed these assertions deliberately (design-v0.2 §1.4 lifts "no global tick handler" for exactly one handler; §8.3/§8.4 add one world-save hook). `noGregScopeListenersTileEntitiesOrGenerators`: **exactly one** GregScope listener on `MinecraftForge.EVENT_BUS` (`GregScopeWorldEvents.instance()`), **zero** on `TERRAIN_GEN_BUS` and `ORE_GEN_BUS`, and **exactly one** on the FML bus (where 1.7.10 tick events arrive, `GregScope.sampler()`), each counted two independent ways (the listener object's class and the owning `ModContainer`) and checked to be the expected object; `exactlyTwoWorldEventHandlers`: the hook declares exactly two `@SubscribeEvent` methods, taking `WorldEvent.Save` and `WorldEvent.Unload`, and neither takes `WorldEvent` itself (its `PotentialSpawns` subclass fires several times per chunk per tick); no GregScope class in the tile entity registry, in `GameRegistry`'s static collections or among any world's loaded tile entities. Each scan must find other mods' entries (observed: 218 listener objects, 543 tile entity classes, 106 loaded tile entities). `exactlyOneServerTickHandler`: the live listener declares exactly one `@SubscribeEvent` method, it takes a `TickEvent.ServerTickEvent`, calling it with phase START moves no counter and with phase END runs exactly one sampler tick. `handlerDoesNoWorkWithoutLiveSensors`: the registry is emptied and a whole interval is run inside one server tick (so no cover can heartbeat in between); `ticksTotal` grows by the interval while `cyclesTotal` and `samplesTotal` do not move, the schedule is empty and the frame published at the boundary has no sensors. `adapterNeverTicksGregScopeEnvironment`: a real Adapter next to an LV macerator, after 5 real ticks; the Adapter's `updatingBlocks` list is empty (asserted first) and the merged environment and GregScope's own environment report `canUpdate() == false`. Reads Forge/FML/OC internals by reflection (test code only). |
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
| `RegistryCodecTests` | `gregscope.storage` | GS-109, design-v0.2 §8.1 to §8.4. `registry.dat` is NBT, so its codec is tested here rather than in a unit test. `roundTrip`: a LIVE and a REMOVED entry plus a runs table survive encode/decode field for field, a LIVE entry comes back UNLOADED (§8.3 "LIVE is never persisted"), the tombstone keeps its state and cause, an unowned sensor stays unowned, the open run is closed at `saved` on load, and a second round through the codec changes nothing. `v2Unsupported`: `v=2` decodes as `UNSUPPORTED` with the version reported so the rename can name it, and loads nothing. `truncatedGzipFallsBackToBak`: a `registry.dat` cut in half throws while it is read and `registry.dat.bak` takes over with its own entries; a whole `registry.dat` wins again; two unreadable files give `UNREADABLE` with **both files still on disk**; a world with neither gives `NONE`. `goldenRegistryDecodesAndKeepsWhatItCannotUnderstand`: the Python-generated `registry_v1.nbt.gz.hex` decodes to the expected runs, entries, negative dimension id, side and owner name, its unclean run is closed at `saved`, and the design-v0.3 kind-1 entry is **not** decoded but kept verbatim - written back with `tier` and `lastIo` intact, next to the unknown root key `future` and the unknown entry key `xtra` (design-v0.3 §5.1 A3). `anEntryWithoutAnIdIsDroppedAndCounted`: a row with no `idM`/`idL` is skipped and counted. `theIoThreadWritesAndReadsARealHistoryFile`: the save root is `<world>/gregscope/`, the I/O thread is running and is a daemon, a created file is 92,224 B and passes its own header rules, a slot lands at index 11, an async load returns the same bytes, and a delete removes it - all driven through the `flushIo` hook. `theServerThreadCannotWriteAFile`: with assertions on for GregScope's packages, `NioFileStore` throws `AssertionError` when the server thread tries to delete a history file. Two review follow-ups. `theIoThreadWritesAndReadsARealHistoryFile` no longer drains the **production** load queue: it runs a `HistoryIo` of its own over the same real store and save root, because `drainLoaded()` takes every result, and swallowing one would leave that sensor REQUESTED - "loading history" and not one minute written - for the rest of the server run, with nothing in the suite noticing. And `theServerThreadCannotWriteAFile` now treats `-ea` as a **precondition**: it used to skip its only assertion when assertions were off, so losing the `-ea:io.github.ldogg123.gregscope...` line in `addon.gradle` would have turned the design §14 check green instead of red. |
| `HistoryTests` | `gregscope.storage.history`, `.unload`, `.unloadwrites` | GS-110, design-v0.2 §7.5/§8.1/§8.3/§8.4. Persistence on the running server. Time is a `FakeClock` at a fixed minute boundary **in the future** (a run started under the system clock must not be asked to stop before it started) and history is driven with `sampleNow`; every test empties the registry first, because Horizon-QA keeps finished cells loaded and their covers keep heartbeating. `threeMinutesWrittenToFile`: 181 one-second samples close three whole minutes; each closed minute costs **exactly one** 64-byte write and none is dropped; the real file is 92,224 B, passes its own header rules, and each of its three slots has 60 samples whose per-state counts add up, no gap mask, and the same bytes as the RAM ring. `simulatedRestartRestoresHistory`: the `stopServicesNow`/`startServicesNow` hooks run exactly the `serverStopping` + `serverStopped` and `serverStarting` bodies with five minutes of fake downtime in between; the entry is rebuilt from `registry.dat` as UNLOADED (§8.3 never persists LIVE), its minutes come back equal from the `.gsh`, the previous run is closed at the moment the services stopped and this one starts when they started, the downtime reads as one `server_offline` range, and the cover's next heartbeat makes the same UUID LIVE again. `overworldSaveWritesTheRegistry`: a real `WorldEvent.Save` posted on the Forge bus for the overworld writes `registry.dat`, which decodes back to this registry with the sensor stored UNLOADED; a second save with nothing dirty writes nothing; another dimension's save is ignored. `removedFileDeletedAfterRetention`: a detached cover gives a tombstone whose file survives `history.removedRetentionHours` and is deleted by the housekeeping sweep after a two-hour clock jump. `persistFalseWritesNoGsh`: with `history.persist=false` no file is read, created or written and the ring still holds the minute, while `registry.dat` is still saved (the other half of the §8.4 sentence). `unloadClosesPartialMinuteWithChunkUnloaded` and `noSlotsWhileUnloaded` each get **their own batch**, because two tests of one batch get neighbouring cells that can share a chunk: with both in one batch the second test's setup loaded the chunk the first had just unloaded and the first saw its sensor still LIVE. The first asserts that a real chunk unload changes exactly one slot of that sensor's file, flagged partial and carrying `chunk_unloaded` with the ten samples taken before it; the second that five further intervals with the clock moved a minute each change **no** slot of it. Both compare the sensor's own file before and after rather than a global counter, because other cells keep heartbeating while a test idles a tick. |
| `CommandTests` | `gregscope.surface.command`, `.reload`, `.unload` | GS-111, design-v0.2 §11/§5/§3.5. Drives the shipped `GregScopeCommand` through `processCommand` with recording senders (`CommandSenders`: a `FakePlayer` subclass that records chat instead of sending a packet and answers `canCommandSenderUseCommand` at a chosen level, and a non-player stub that the command turns into `Viewer.CONSOLE`). Sensors come from `SensorFixtures`, which places a GT machine whose item NBT already carries a chosen identity, because an in-game attach by a `FakePlayer` leaves the sensor **unowned** (§3.4) and every §5 row is about owners. `statsCommandRuns` (the test GS-108 deferred): the seven §6.3 lines, p50 <= p99 <= max from the 1,024-bucket histogram, a published frame, and the sensor counts filtered by `canView` - 1 for the console and the owner, 0 for a stranger. `listFilteredByAccess`: a real GTNHLib team; the owner sees their own and their team mate's sensor and not a stranger's, the stranger sees only their own, the console sees all of them, an unowned sensor is op-only, `list tombstones` says so when there are none and page 2 of one page clamps to 1. `ambiguousPrefixListsMatches`: two sensors sharing a four-character prefix give the ambiguity message plus two rows and no `info` answer; the full UUID resolves and prints the kind label `machine`, both window lines and the location; an unmatched prefix, a three-character prefix and an unknown subcommand each get their own message. `labelDeniedForStranger`: a stranger cannot even see the sensor, the owner renames the cover and the registry, a second rename in the same tick hits the 5 s cooldown, and the console (no player UUID, so no cooldown) clears the label. `labelDeniedForNonOfficerWhenConfigured`: with `permissions.renameRequiresOfficer=true` a plain team member sees the sensor in `list` and is refused by `label`, and may rename once the team makes them an officer. `purgeRequiresOp`: the owner sees their sensor and is still refused, a player holding level 2 purges it, a bulk purge is refused for a non-op and says "nothing to purge" for the console. `noLogLinePerCall`: a positive control INFO line is captured, then six calls (`stats`, `list`, `info`, `label`, an unknown subcommand and `purge`) write no INFO line from the `gregscope` logger. `labelPersistsAcrossChunkReload` (own batch): a label written by the command is in the cover NBT after a real chunk unload and reload, the registry picks it up again on the next heartbeat and `info` shows it. `labelRefusedWhenUnloadedAndChunkStaysUnloaded` (own batch): with the chunk unloaded the write answers "sensor not loaded", the label is unchanged and the chunk is still unloaded; `info` and `list unloaded` answer from RAM without loading it either. The review follow-up adds `statsRunsBeforeTheFirstFrameIsPublished` (batch `gregscope.surface.command`): the command is registered in `FMLServerStartingEvent`, before the server has ticked, so `stats` can be asked while the published frame is still `TelemetryFrame.EMPTY`. The test reaches that state with `simulateRestart()` and asserts the seven lines still come out, with frame sequence 0 and age `never`, and that the next interval publishes a frame as usual. Before the fix this threw a `NullPointerException` on `frame.stats()`. |
| `HubBlockTests` | `gregscope.surface.hub`, `.reload` | GS-112, design-v0.2 §9.1/§5. The Telemetry Hub block and tile entity on the dedicated server, through the shipped objects only: the block is placed with its own `ItemBlock.placeBlockAt` (what a player's right-click calls, and what runs `onBlockPlacedBy`) and a right-click is `BlockTelemetryHub.onBlockActivated` itself. `hubBlockAndTileAreRegistered`: the §17 frozen names - `GameRegistry.findBlock`/`findItem` return the shipped block and its default `ItemBlock`, the unlocalized name and the server-side English name, and `TileEntity.createAndLoadEntity` of a compound whose `id` is `gregscope:telemetry_hub` builds a `TileTelemetryHub` whose `canUpdate()` is false. `ownerSetOnPlace`: a **real** player (`CommandSenders.Real`, an `EntityPlayerMP` that is not a `FakePlayer`) becomes the owner with their name, the facing lands in metadata 2..5 and equals `facingFromYaw`, and the placed Hub is in **no** world's tile entity tick list - which is what `canUpdate()==false` buys (`World.addTileEntity`/`setTileEntity` only add a tile that answers true, `World.java:4405-4412` and `:2834-2841`). `fakePlayerLeavesTheHubUnowned`: a `FakePlayer` is no attributable player (§3.4), so the Hub stays unowned. `nbtRoundTripAndUnknownVersionPreserved`: the `gsHub`/`owM`/`owL`/`owN` record survives a `writeToNBT`/`readFromNBT` round trip through the real tile entity, and a compound with `gsHub=2` plus an unknown key parses nothing, refuses `setOwner` and is written back verbatim (unknown key and the preserved owner keys included) under the registered `id`. `nbtRoundTripAcrossChunkReload` (own batch): an owned Hub and an unsupported one go through a real chunk unload and reload; the new tile entities are different objects and carry the same owner and the same unknown key. `strangerDenied`: a stranger gets `gregscope.hub.denied` and one chat line only, their `openContainer` is still their own inventory and no view is reserved, while the owner is not refused. `unownedHubIsOperatorsOnly`: a Hub placed by a FakePlayer refuses a stranger and accepts a player at `permissions.opLevel`. `viewCapEnforced`: with `limits.maxOpenHubViews=1` and one view already open, the owner's click answers `gregscope.hub.busy` (not `denied`) and does not change the view register; with the view closed the same click is allowed. GS-114 owns the GUI, so a permitted click opens nothing yet and nothing is reserved for a window that does not exist. |

Helpers (`GtPlacement`, `OcComponents`, `OcFileSystem`, `Snapshots`, `StubContext`, `ChunkReload`, `SensorFixtures`,
`CommandSenders`) are test-only.
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

**GS-109 negative controls (2026-09-17), reverted afterwards (backup copies restored, `cmp` identical).** Two, one per
layer.

1. *The golden fixtures are an independent check of the byte layout.* `Crc32`'s reflected polynomial changed from
   `0xEDB88320` to `0xEDB88321`. The unit suite went to **7 failures out of 436**, and every one of them was a
   fixture test or the check value: `crc32CheckValue`, `goldenHeaderBytesEncode`, `goldenHeaderBytesDecode`,
   `aWholeFileWithTheGoldenHeaderIsOk`, `aNewerFormatVersionIsUnsupportedAndNamesItsVersion`,
   `aReservedFlowLayoutIsUnsupportedNotReinterpreted` and `anotherSensorsFileIsAMismatch`. The self-consistent tests
   (encode then decode, and `aKindLayoutMismatchIsUnsupported`, which re-checksums with the same broken CRC) still
   passed, which is exactly the GS-103 result repeated for `.gsh`: without the independently generated fixtures a
   wrong checksum would be invisible.
2. *"The server thread does no file I/O" is really enforced.* The `assert` in `NioFileStore.checkThread()` was replaced
   by a plain call to the supplier. `NioFileStoreTest.everyWriteAssertsItRunsOnTheIoThread` and
   `theProductionStoreAsksTheIoThreadItself` failed (2 of 436), and in-game
   `RegistryCodecTests.theServerThreadCannotWriteAFile` failed with "the server thread was allowed to touch the disk:
   expected AssertionError to be thrown but nothing was thrown".

**Assertions are off in the dev JVM, and GS-109 turns them on for GregScope only.** The first in-game negative-control
run logged `assertions are off in this JVM, so the server-thread file I/O check is NOT enforced here`: RFG's
`runServer`/`runClient` run without `-ea`, so the design-v0.2 §14 acceptance criterion could not fire in-game at all.
`addon.gradle` now passes `-ea:io.github.ldogg123.gregscope...` to both run tasks, which scopes assertions to
GregScope's own packages - Forge, GT, MUI2 and MC keep running with assertions off, as they ship. `NioFileStore`'s
thread check is the only `assert` in `src/main`. With it on, the in-game test enforces the criterion and the negative
control above fails as it should; the test also logs which of the two states it is in, so it can never pass quietly
because assertions were disabled.

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

**GS-110 negative controls (2026-09-17), reverted afterwards (backup copies restored, `cmp` identical).** Two runs,
one defect each.

*Unit run:* `HistoryPersistence.writePending` no longer reading `state.pending`, i.e. a minute that closed while the
sensor's file was still being read is silently dropped instead of being written once the file is there. Result:
exactly 1 of 446 failed, `PersistenceScenarioTest.ramWinsOverDiskOnMerge` with "the pending minute must reach the
file: expected <1> but was <0>". Nothing else noticed, which is the point: that window (a minute closing between the
`Load` and its result) is only asserted there.

*In-game run:* `GregScope.startServices()` no longer calling `HistoryPersistence.requestAll(...)`, i.e. entries
restored from `registry.dat` never get their history file read. Result: exactly 1 of 109 failed,
`HistoryTests.simulatedRestartRestoresHistory` with "the history file was not merged after the restart"; 108 passed,
4 skipped, Horizon-QA status `FAILED`, exit code 1. That is the design-v0.2 §8.4 rule "when an entry first becomes
LIVE **or UNLOADED** in a process, its file is read": without it a restart would show an empty 24 h until the sensor
happened to become LIVE again, and only this test notices.

*Not covered by a negative control:* the real `FMLServerStoppingEvent`/`FMLServerStoppedEvent` wiring, because
Horizon-QA's `mode=ci` never posts them (see "Last full run"); the bodies they call are covered by the simulated
restart.

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

Review follow-ups after GS-112, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world and
without `config/gregscope.cfg`** (both moved away first): **127 passed, 4 skipped** (`ProbeBenchmarkTests`, opt-in),
0 failed, 0 timed out, 0 infrastructure errors; Horizon-QA status `PASSED`, exit code 0; Gradle wall time 56 s for
`runServer`, 51.0 s of it inside test cases, 28 s of it between `Done (0.610s)` and the `HorizonQA RESULT` line. One
new test, `CommandTests.statsRunsBeforeTheFirstFrameIsPublished` (batch `gregscope.surface.command`, 0 ticks), and
two changed ones in `RegistryCodecTests` (its own `HistoryIo` instead of the production load queue; `-ea` as a
precondition rather than a branch). Per class: BasicMachineSnapshotTests 15, SensorCoverTests 15,
ElectricBlastFurnaceSnapshotTests 11, CommandTests 10, HubBlockTests 8, OpenComputersComponentTests 8,
SensorLifecycleTests 8, SensorPlacementTests 8, HistoryTests 7, RegistryCodecTests 7, SamplerTests 7, IdleCostTests 5,
OpenComputersExampleScriptTests 5, ChunkReloadTests 4, ProbeBenchmarkTests 4 (skipped), TeamAccessTests 3,
AdapterBindingTests 2, LifecycleTests 2, SafetyTests 2. Slowest unchanged: the four `exampleScriptRunsOnOpenOs`
variants at 7.45 s, then `exampleScriptShowsRunningEbfWithWarnings` 7.35 s and `SamplerTests.tinyBudgetSkips` 6.0 s.
Unit tests: **500 in 36 classes**, all green (493 before), of which 7 are new: `HistoryIoTest` +3,
`PersistenceScenarioTest` +3, `SensorRegistryCoreTest` +1 (`TelemetryFrameTest` gained assertions rather than a
case). `./gradlew --no-daemon spotlessApply` then `clean build`: BUILD SUCCESSFUL, checkstyle and spotlessCheck
clean. Jar check on `build/libs/gregscope-dd35f05-master+dd35f05d1f-dirty.jar`: **194 entries** (was 193; the one new
entry is `history/HistoryIo$WriteFailure`), 0 matching `gametest|horizonqa|.lua|fixtures|.hex|Test.class|tools/|.py`.
One WARN is expected in the log after `HistoryTests`: "GregScope is not expiring anything: the clock says 1789692582
but the registry already holds the later timestamp 2000000461" - the new backwards-clock guard seeing the fake clock
`HistoryTests` stamped into the runs table (see the design implementation notes). GitHub CI has not run this state.

**Worst-case batch budget: 5,520 ticks = 276 s** against the 300 s CI step, over the same **35** batches (115
`@GameTest(` annotations; the new test joined an existing batch). This is a **correction of 700 ticks**, not growth:
see the boxed note under "Running in CI". With about 30 s of Gradle plus Forge boot inside the same wall-clock
timeout, the worst case does not fit; a normal run takes 51 s in test cases and is nowhere near it.

**Review-follow-up negative controls (2026-09-17), reverted afterwards (backup copies restored, `cmp` identical).**

- Unit: the new `report(task)` call removed from `HistoryIo.perform`'s catch block, so a task that throws goes back to
  answering nobody. Result: exactly 5 of 500 failed, all of them the new failure-path tests -
  `HistoryIoTest.aFailedLoadStillProducesAResultThatIsNotAbsent`, `.aFailedFileWriteIsReportedToTheServerThread`,
  `PersistenceScenarioTest.aReadThatThrowsIsRetriedAndLeavesTheFileAlone`,
  `.aCreateThatThrowsDoesNotLeaveTheSensorThinkingItHasAFile` and
  `.aFileThatKeepsFailingIsGivenUpOnRatherThanRetriedForEver`. Nothing else noticed, which is the point: no older test
  looks at what happens after an I/O error.
- In game: `TelemetryFrame.EMPTY`'s `stats` and `limits` set back to `null`, on its own fresh world. Result: exactly 1
  of 127 failed, `CommandTests.statsRunsBeforeTheFirstFrameIsPublished`, with
  `java.lang.NullPointerException at io.github.ldogg123.gregscope.command.GregScopeCommand.stats(GregScopeCommand.java:190)`
  - the reported bug reproduced through the shipped command; 126 passed, status `FAILED`, exit code 1. The first
  attempt at this control failed on the test's own `assertNotNull` precondition instead, so the test was reordered to
  run the command first and the control repeated: a frame that cannot answer now fails the way it would fail an
  operator.

#### Previous runs

GS-112, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world and without
`config/gregscope.cfg`** (both moved away first): **126 passed, 4 skipped** (`ProbeBenchmarkTests`, opt-in), 0 failed,
0 timed out, 0 infrastructure errors; Horizon-QA status `PASSED`, exit code 0; Gradle wall time 1 m 3 s for
`runServer`, 51.1 s of it inside test cases. New: `HubBlockTests`, 8 passed across two new batches -
`gregscope.surface.hub` (`hubBlockAndTileAreRegistered`, `ownerSetOnPlace`, `fakePlayerLeavesTheHubUnowned`,
`nbtRoundTripAndUnknownVersionPreserved`, `strangerDenied`, `unownedHubIsOperatorsOnly`, `viewCapEnforced`, 0 ticks
each) and `gregscope.surface.hub.reload` (`nbtRoundTripAcrossChunkReload`, 2 ticks, 0.10 s); the two batches together
took 0.10 s. `IdleCostTests.noGregScopeListenersTileEntitiesOrGenerators` changed deliberately: it now expects
**exactly one** GregScope class in the tile entity registry, `hub/TileTelemetryHub`, counted over distinct names
(`TileEntity` keeps a name->class and a class->name map, so the same class turns up twice), and still **zero**
GregScope tile entities in any world's `loadedTileEntityList`, which holds because `World.addTileEntity` and
`World.setTileEntity` only add a tile whose `canUpdate()` is true. Per class: BasicMachineSnapshotTests 15,
SensorCoverTests 15, ElectricBlastFurnaceSnapshotTests 11, CommandTests 9, HubBlockTests 8,
OpenComputersComponentTests 8, SensorPlacementTests 8, SensorLifecycleTests 8, SamplerTests 7, RegistryCodecTests 7,
HistoryTests 7, IdleCostTests 5, OpenComputersExampleScriptTests 5, ProbeBenchmarkTests 4 (skipped),
ChunkReloadTests 4, TeamAccessTests 3, LifecycleTests 2, AdapterBindingTests 2, SafetyTests 2. Slowest unchanged: the
four `exampleScriptRunsOnOpenOs` variants at 7.45 s. Unit tests: **493 in 36 classes**, all green, of which 21 are new
(`hub.HubTileNbtCodecTest` 12, `hub.HubViewsTest` 8, `ShippedClassesTest.theOnlyTileEntityIsTheHub`). Jar check on
`build/libs/gregscope-dd35f05-master+dd35f05d1f-dirty.jar`: 193 entries (was 181), 0 matching
`gametest|fixtures|.hex|Test.class`, the new shipped classes `hub/BlockTelemetryHub`, `hub/TileTelemetryHub`,
`hub/TelemetryHubs`, `hub/HubViews` and `hub/HubNbtCodec` (+ its `Result`, `Status` and one synthetic type), the three
new textures `assets/gregscope/textures/blocks/telemetry_hub_front|side|top.png`, and `tile.gregscope.telemetry_hub`,
`gregscope.hub.denied` and `gregscope.hub.busy` in the shipped lang file. GitHub CI has not run this state.

**Worst-case batch budget after GS-112: 4,820 ticks = 241 s** (superseded - it was 700 ticks short, see the entry above) against the 300 s CI step, recounted mechanically over
all 114 `@GameTest(` annotations in 35 batches (the sum of the longest `timeoutTicks` per batch; a test with no
`timeoutTicks` counts as Horizon-QA's default 100). GS-111 left it at 4,620 ticks over 33 batches. The two GS-112
batches take the **default 100**, like the GS-111 ones: every test in them runs to completion inside one server tick
except for a single `thenIdle(1)` between the unload and the assertions. **59 s of headroom is left**, so the next
chunk-unload batch (100 at least, 200 if it follows the older reload batches) is close to the limit; a further test
that can share an existing cell should.

**GS-112 in-game negative controls (2026-09-17), reverted afterwards (backup copies restored, `cmp` identical).** Two
separate builds, each run on its own fresh world:

- `TileTelemetryHub.canUpdate()` changed to return **true**. The unit suite stayed green, which is the point of the
  in-game check: `ShippedClassesTest` reads the constant pool and can see that the method is declared, never what it
  returns. Result: exactly 2 of 126 failed, `HubBlockTests.hubBlockAndTileAreRegistered` ("the Hub tile entity says it
  ticks") and `HubBlockTests.ownerSetOnPlace` ("the Hub ticks"); 124 passed, status `FAILED`, exit code 1.
  `IdleCostTests` stayed green, correctly: it sorts before `gregscope.surface.hub`, so no Hub had been placed when it
  scanned the loaded tile entities.
- The `hub.canOpen(player)` guard removed from `BlockTelemetryHub.onBlockActivated`, so every viewer went straight to
  the view-cap check. Result: exactly 2 of 126 failed, `HubBlockTests.strangerDenied` and
  `HubBlockTests.unownedHubIsOperatorsOnly` (the stranger was never told `gregscope.hub.denied`); 124 passed, status
  `FAILED`, exit code 1.

GS-111, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world and without
`config/gregscope.cfg`** (both moved away first): **118 passed, 4 skipped** (`ProbeBenchmarkTests`, opt-in), 0 failed,
0 timed out, 0 infrastructure errors; Horizon-QA status `PASSED`, exit code 0; Gradle wall time 58 s for `runServer`,
51.05 s of it inside test cases. New: `CommandTests`, 9 passed across three new batches -
`gregscope.surface.command` (`statsCommandRuns`, `listFilteredByAccess`, `ambiguousPrefixListsMatches`,
`labelDeniedForStranger`, `labelDeniedForNonOfficerWhenConfigured`, `purgeRequiresOp`, `noLogLinePerCall`, 0 ticks
each), `gregscope.surface.command.reload` (`labelPersistsAcrossChunkReload`, 0.10 s) and
`gregscope.surface.command.unload` (`labelRefusedWhenUnloadedAndChunkStaysUnloaded`, 0.10 s). Per class:
BasicMachineSnapshotTests 15, SensorCoverTests 15, ElectricBlastFurnaceSnapshotTests 11, CommandTests 9,
OpenComputersComponentTests 8, SensorPlacementTests 8, SensorLifecycleTests 8, SamplerTests 7, RegistryCodecTests 7,
HistoryTests 7, IdleCostTests 5, OpenComputersExampleScriptTests 5, ProbeBenchmarkTests 4 (skipped),
ChunkReloadTests 4, TeamAccessTests 3, LifecycleTests 2, AdapterBindingTests 2, SafetyTests 2. Slowest unchanged: the
four `exampleScriptRunsOnOpenOs` variants at 7.50 s, `exampleScriptShowsRunningEbfWithWarnings` 7.30 s. Unit tests:
**472 in 34 classes**, all green, of which 26 are new (`command.CommandArgsTest` 21, `sensor.RenameCooldownTest` 5).
Jar check on `build/libs/gregscope-dd35f05-master+dd35f05d1f-dirty.jar`: 181 entries (was 167), 0 matching
`gametest|fixtures|.hex|Test.class`, and the new shipped classes `command/CommandArgs` (+ its five nested types),
`command/GregScopeCommand` and `sensor/RenameCooldown`. GitHub CI has not run this state.

**Worst-case batch budget after GS-111: 4,620 ticks = 231 s** against the 300 s CI step, recounted mechanically over
all `@GameTest(` annotations in 33 batches (the sum of the longest `timeoutTicks` per batch; a test with no
`timeoutTicks` counts as Horizon-QA's default 100). GS-110 left it at 4,320 ticks over 30 batches. The three GS-111
batches take the **default 100** rather than the 200 the other chunk-unload batches use, because every test in them
runs to completion inside one server tick except for a single `thenIdle(1)` between the unload and the assertions;
200 would have put the figure at 4,920 = 246 s, which the GS-110 note already called close. The measured time is far
below the bound either way: the three batches together took 0.2 s.

**GS-111 in-game negative controls (2026-09-17), reverted afterwards (backup copies restored, `cmp` identical).** Two
separate builds, each run on its own fresh world:

- The `policy.canPurge(viewer)` guard removed from `GregScopeCommand.purge`. Result: exactly 1 of 118 failed,
  `CommandTests.purgeRequiresOp` with `gregscope.cmd.purge.one 0d011011` - the non-op purge succeeded; 117 passed,
  Horizon-QA status `FAILED`, exit code 1.
- The chunk guard removed from the label write: `SensorRegistry.liveCover` lost both its `SensorState.LIVE` check and
  its `world.blockExists` check, and `writeLabel` lost its `LIVE` early return, so the cover lookup went straight to
  `World.getTileEntity`. Result: exactly 1 of 118 failed,
  `CommandTests.labelRefusedWhenUnloadedAndChunkStaysUnloaded` with `gregscope.cmd.label.set 0f021011 Should not
  stick` - the write reached the cover, which means `World.getTileEntity` really did load the chunk; 117 passed,
  status `FAILED`, exit code 1. **A first attempt at this control was vacuous and is worth recording:** removing only
  `writeLabel`'s `LIVE` early return changed nothing, because `liveCover` carries its own `LIVE` check and returned
  null before touching the world, so the suite stayed green (118 passed). The control only bites once *every* guard
  in front of the world lookup is gone.

GS-110, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world and without
`config/gregscope.cfg`** (both moved away first): **109 passed, 4 skipped** (`ProbeBenchmarkTests`, opt-in), 0 failed,
0 timed out, 0 infrastructure errors; Horizon-QA status `PASSED`, exit code 0; Gradle wall time 1 m 0 s for
`spotlessApply build runServer` together. New: `HistoryTests`, 7 passed across three new batches -
`gregscope.storage.history` (`threeMinutesWrittenToFile`, `simulatedRestartRestoresHistory`,
`overworldSaveWritesTheRegistry`, `removedFileDeletedAfterRetention`, `persistFalseWritesNoGsh`, 0 ticks each),
`gregscope.storage.history.unload` (`unloadClosesPartialMinuteWithChunkUnloaded`, 0.10 s) and
`gregscope.storage.history.unloadwrites` (`noSlotsWhileUnloaded`, 0.10 s) - plus `IdleCostTests` 4 -> 5
(`exactlyTwoWorldEventHandlers`). Per class: BasicMachineSnapshotTests 15, SensorCoverTests 15,
ElectricBlastFurnaceSnapshotTests 11, OpenComputersComponentTests 8, SensorPlacementTests 8, SensorLifecycleTests 8,
SamplerTests 7, RegistryCodecTests 7, HistoryTests 7, IdleCostTests 5, OpenComputersExampleScriptTests 5,
ProbeBenchmarkTests 4 (skipped), ChunkReloadTests 4, TeamAccessTests 3, LifecycleTests 2, AdapterBindingTests 2,
SafetyTests 2. Slowest unchanged: the four `exampleScriptRunsOnOpenOs` variants at 7.45 s,
`exampleScriptShowsRunningEbfWithWarnings` 7.35 s, `tinyBudgetSkips` 6.00 s, `realTicksSampleOncePerSecond` 3.00 s,
then 1.30 s and below. Logged by the run: `GregScope registry: NONE, 0 entries restored, 0 kept for another version,
1 recorded runs` at server start and, inside `simulatedRestartRestoresHistory`, `GregScope is shutting down: 1 partial
minutes closed, 6 slot writes queued, registry queued` followed by `GregScope registry: PRIMARY, 1 entries restored,
0 kept for another version, 2 recorded runs` and `GregScope housekeeping expired 0 entries at start; 1 history files
are being read`. Unit tests: **446 in 32 classes**, all green, of which 10 are new
(`history.PersistenceScenarioTest` 8, plus one new case each in `ShippedClassesTest` and, in-game, `IdleCostTests`).
Jar check on `build/libs/gregscope-dd35f05-master+dd35f05d1f-dirty.jar`: 167 entries, 0 matching
`gametest|fixtures|.hex|Test.class`, and the new shipped classes `GregScopeWorldEvents`,
`history/HistoryPersistence` (+ `$FileState`, `$Tracked`) and `registry/RegistryPersistence`. GitHub CI has not run
this state.

**Worst-case batch budget after GS-110: 4,320 ticks = 216 s** against the 300 s CI step, recounted mechanically over
all 97 `@GameTest(` annotations in 30 batches (the sum of the longest `timeoutTicks` per batch; a test with no
`timeoutTicks` counts as Horizon-QA's default 100). GS-109 left it at 3,720 ticks over 27 batches; the three new
batches add 200 ticks each. The measured time is far below that: the whole suite ran in about 56 s of server time,
and the three new batches together took 0.2 s. The new batches are the reason the figure moved at all - each of the
two chunk-unload tests needs its own batch (see `HistoryTests` above), and a batch costs its longest timeout in the
worst case even when it holds one fast test.

**Horizon-QA `mode=ci` never runs the server-stop path.** The run finishes with
`FMLCommonHandler.exitJava`, so the JVM shutdown hook runs `MinecraftServer.stopServer()` but FML posts neither
`FMLServerStoppingEvent` nor `FMLServerStoppedEvent`, and dimension 0 is never unloaded through
`WorldEvent.Unload`; the run log has no GregScope shutdown line at the end and `<world>/gregscope/registry.dat` is
whatever the last in-test save wrote. GregScope's stop path is therefore covered by
`HistoryTests.simulatedRestartRestoresHistory`, which calls exactly those two bodies through the test hooks, and the
save trigger by `overworldSaveWritesTheRegistry`, which posts the real `WorldEvent.Save`. A **real** `/stop` and
restart stays on the GS-121 manual checklist, as design-v0.2 §14 already scopes it (M).

GS-109, local, Windows, 2026-09-17, selector `gregscope`, on a **freshly created world and without
`config/gregscope.cfg`** (both moved away first): **101 passed, 4 skipped** (`ProbeBenchmarkTests`, opt-in), 0 failed,
0 timed out, 0 infrastructure errors; Horizon-QA status `PASSED`, exit code 0; Gradle wall time about 1 m 10 s, 52.4 s
of it inside test cases. New: `RegistryCodecTests`, 7 passed in the new batch `gregscope.storage` (0 ticks each) -
`roundTrip`, `v2Unsupported`, `truncatedGzipFallsBackToBak`, `goldenRegistryDecodesAndKeepsWhatItCannotUnderstand`,
`anEntryWithoutAnIdIsDroppedAndCounted`, `theIoThreadWritesAndReadsARealHistoryFile` and
`theServerThreadCannotWriteAFile`. Per class: BasicMachineSnapshotTests 15, SensorCoverTests 15,
ElectricBlastFurnaceSnapshotTests 11, OpenComputersComponentTests 8, SensorPlacementTests 8, SensorLifecycleTests 8,
SamplerTests 7, RegistryCodecTests 7, OpenComputersExampleScriptTests 5, ProbeBenchmarkTests 4 (skipped),
IdleCostTests 4, ChunkReloadTests 4, TeamAccessTests 3, LifecycleTests 2, AdapterBindingTests 2, SafetyTests 2.
Slowest: the four `exampleScriptRunsOnOpenOs` variants at 8.05-8.30 s, `exampleScriptShowsRunningEbfWithWarnings`
7.10 s, `tinyBudgetSkips` 6.00 s, `realTicksSampleOncePerSecond` 3.00 s, then 0.70 s and below; the whole
`gregscope.storage` batch finished inside one second. Logged by the run at server start:
`GregScope history root .\world\gregscope , I/O queue 4096` and the design-v0.2 section 7.8 ceiling line
(`RAM 26.5 MB ... history files 23.6 MB ... registry 64 KB raw, writes 16.4 KB/min`), and inside the batch
`assertions are on in this JVM, so the server-thread file I/O check is enforced`. Two more fresh-world runs of the
same state gave the same result (101 passed, 4 skipped). Unit tests: **436 in 31 classes**, all green, of which 38 are
new (`history.HistoryFileCodecTest` 12, `history.HistoryIoTest` 9, `history.NioFileStoreTest` 9,
`registry.RunsTableTest` 7, plus one new `ShippedClassesTest` case). Jar check on
`build/libs/gregscope-dd35f05-master+dd35f05d1f-dirty.jar`: 160 entries, 0 matching
`gametest|fixtures|.hex`, and the nine new classes under `history/` and `registry/`
(`Crc32`, `HistoryFileCodec` + `$Header`/`$Status`, `SlotLayouts`, `FileStore`, `IoListener`, `NioFileStore` +
`$Mover`, `HistoryIo` + `$Runner`/`$Task`/`$Kind`/`$LoadResult`, `RunsTable` + `$Row`, `RegistryNbtCodec` +
`$Loaded`/`$Preserved`/`$Source`). GitHub CI has not run this state.

**Chunk-reload flakiness seen while developing GS-109 (recorded, unresolved).** Six local runs were made for this
ticket. Two failed a chunk-reload test because the batch was named `gregscope.persistence` (see "A new batch's name
matters" above). Two more, both with the deliberately broken negative-control build, failed a different reload test
each - `ChunkReloadTests.adapterInOtherChunkSeesMachineAfterItsChunkReloads` ("Expected a TileEntity at (7,0,2) but
found none") and `SensorCoverTests.uuidStableAcrossChunkReload` ("expected different instances but both were
`BaseMetaTileEntity@...`") - although removing an `assert` cannot affect chunk loading. The three runs of the final
state were all green, and every older log in this project shows these tests passing, so this is recorded as an
observation rather than a diagnosis: the `ChunkReload` helper depends on the chunk really being evicted and reloaded
within a couple of ticks, and that is wall-clock sensitive on a loaded machine. Worth watching in CI.

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
  **v0.2 lifts parts of this on purpose (design-v0.2 §1.4):** GS-105 adds one item, GS-108 adds exactly one
  `ServerTickEvent` END handler (`sampling/TelemetrySampler`), GS-110 adds one Forge-bus world-save hook
  (`GregScopeWorldEvents`), and GS-112 adds one block with one **non-ticking** tile entity
  (`hub/BlockTelemetryHub`, `hub/TileTelemetryHub`, `canUpdate() == false`, so it joins no world's tick list).
  Mixins, access transformers, core mods and world generators stay out. The v0.1 record below is kept as written.
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
