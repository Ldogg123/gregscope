# GregScope v0.2: final design

**Target:** GTNH 2.9.0-beta-3, pinned to:

| Component | Version |
|---|---|
| GT5U | 5.09.54.133 |
| OpenComputers | 1.12.61-GTNH |
| ModularUI2 | 2.3.88-1.7.10 |
| GTNHLib | 0.11.46 |
| Horizon-QA | 0.14.0 |
| Forge | 10.13.4.1614 |

**Status:** every decision is made and the design can be implemented as written.

**Evidence paths:**
- `gt5u/`, `mui2/`, `hqa/`, `gtnhlib/` and `oc/` mean the extracted `-sources.jar` of the pinned version from
  `https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/` (GT5-Unofficial, ModularUI2, Horizon-QA,
  GTNHLib, OpenComputers).
- `gs/` means the GregScope repo.
- `pack/` means `gregscope-server/pack`.

**Delegated decisions applied:**
- The name stays GregScope.
- The first physical Hub is EV tier.
- Ownership uses GTNHLib teams, which v0.2 can use now because GTNHLib is already a GT dependency.
- Upstream read-only getters are preferred over mixins. v0.2 needs neither.
- History is kept in separate GregScope files. This answers §15 Q7: not world NBT, not only Prometheus.

---

## 0. Errata: facts corrected from the candidate designs (re-verified)

| # | Wrong claim | Correct fact | Evidence |
|---|---|---|---|
| E1 | `Textures.BlockIcons.custom("gregscope","GREGSCOPE_SENSOR_OVERLAY")` with the PNG under `blocks/iconsets/` | The resource key is appended straight after `textures/blocks/`, so the icon name must include `iconsets/`. | `gt5u/gregtech/api/util/client/ResourceUtils.java:106-108`; the TecTech precedent `custom(domain, "iconsets/TESLA_OVERLAY")` at `tectech/loader/thing/CoverLoader.java:24-31` |
| E2 | Horizon-QA warp moves time forward, so the sampler and TickClock advance | Warp force-ticks GT tiles "without advancing global server time". `ServerTickEvent` never fires during a warp. The cover heartbeat tests the frozen `MinecraftServer.getTickCounter() % rate`. | `hqa/.../api/gt/GTNHGameTestHelper.java` class javadoc; `gt5u/.../CoverableTileEntity.java:184-193` |
| E3 | Adding callbacks changes OC Adapter addresses on upgrade | An address is kept while the set of drivers behind the merged component is unchanged. Adding callbacks to `GregTechMachineEnvironment`, or adding a driver that only matches the Hub tile, does not change the machine's driver set. | `gs/docs/opencomputers.md:68-72,158-161` |
| E4 | A failed team lookup logs once at DEBUG | `TeamManager.getTeamByPlayer` logs `GTNHLib.LOG.error("Unable to find team for player …")` on every miss. Use a `getTeamMap().values()` + `Team.isMember` scan instead. | `gtnhlib/.../teams/TeamManager.java` getTeamByPlayer, getTeamMap; `Team.java:57-65` |
| E5 | A test can call `cover.onCoverJackhammer()` and then expect `getTickRate()==20` | `Cover.onCoverJackhammer` and `adjustTickRateMultiplier` ignore `allowsTickRateAddition`. Only the holder's tool path checks it. | `gt5u/.../covers/Cover.java:449-467`; `BaseMetaTileEntity.java:1593` |
| E6 | Persist `MachineState.ordinal()+1` | Reordering the enum would silently corrupt history. Persist a pinned `StateCodes` table instead. | `gs/src/main/java/.../model/MachineState.java` |
| E7 | (v0.3) A GT pump returns how much it moved | `GTUtility.moveFluid` returns `void`. Only GTNHLib `ItemTransfer.transfer()` returns `int` (items moved). | `gt5u/gregtech/api/util/GTUtility.java:1038-1064`; `javap` of GTNHLib-0.11.46-dev `com.gtnewhorizon.gtnhlib.item.ItemTransfer` |
| E8 | Circuit ore names `circuitMV` / `circuitEV` | In this version they are `circuitGood` / `circuitData`. Always use `GTOreDictUnificator.get(OrePrefixes.circuit, Materials.MV/EV, n)`. | `MaterialsInit.java:4511-4532` |
| E9 | MUI2 GUIs can be opened with a FakePlayer in Horizon-QA | `GuiManager.open` returns immediately for a FakePlayer. Tests can only call `buildUI` directly and exercise the named handlers. | `mui2/.../factory/GuiManager.java:72` |

---

## 1. Scope

### 1.1 v0.2.0 ships
1. **Machine Sensor**, an MV GT cover. It is limited to one per `MTEBasicMachine` or `MTEMultiBlockBase`. Its UUID, label and owner are persistent.
2. **Sensor registry** with a lifecycle state machine, duplicate and move handling, and caps.
3. **Sampler**: one sample per LIVE sensor per second, within a hard per-tick budget.
4. **History**:
   - In memory: 300 one-second samples.
   - In memory and on disk: 1,440 one-minute aggregates.
   - Explicit gaps, with reasons attributed to each gap.
5. **Telemetry Hub**, an EV plain block with a read-only MUI2 GUI:
   - a list of 8 rows per page, with an All/Problems filter,
   - a detail pane with 5-minute and 24-hour text summaries and an ASCII 24-hour strip,
   - a label field.
6. **OpenComputers**: `getSensor` and `getSensorHistory` on `gt_machine`, and a new `gregscope_hub` component.
7. **Command** `/gregscope stats|list|info|label|purge`.
8. **Config** at `config/gregscope.cfg`.
9. **Assembler recipes, textures and lang.**
10. **v0.4 groundwork**: an immutable `TelemetryFrame`, process-lifetime counters, and `docs/metrics-model.md`.

### 1.2 v0.2.1 (the data format does not change)
- Chart widget (sparkline with gaps), GT theme (`GTModularScreen` / `GTGuiThemes.STANDARD`), and GT EV hull side icons on the Hub.
- A cover GUI (`CoverBaseGui`) for editing labels on the machine.
- Client-side cover label sync and a WAILA label.
- A text-free "lean" probe path, unless the GS-102 benchmark pulls it forward.
- zh_CN lang.

### 1.3 Out of scope for v0.2
- Any machine control: no synced actions, no OC writes to machines, no redstone output from sensors.
- Flow meters, including passive inventory-delta "flow" (v0.3; see §15).
- The Prometheus/HTTP exporter (v0.4; see §16). v0.2 ships only the data contract.
- Alerts, chat notifications and the TaskNH integration (v0.5).
- A GT MTE Hub, a multiblock Hub, and MTE ID reservation upstream.
- A standalone sensor block, a position-registering tool or item, and command-only registration of positions.
- Sensors on non-GT targets, hatches, casings, pipes or cables.
- ServerUtilities teams, GTNHLib `ITeamData` storage, and storing team IDs.
- Linking Hubs to sensors, range limits, or wireless mechanics. A Hub is a view of the registry.
- Persisting the one-second ring.
- Recipe identity and parallel count (§15 Q4/Q5: upstream getters, not v0.2).
- Mixins, access transformers, coremods, and reflection in shipped code.
- A GTNHLib `@Config` migration and an in-game config GUI.
- An NEI plugin (nothing needs hiding).
- Mod-removal remapping beyond FML's default missing-mapping prompt.

### 1.4 v0.1 constraints: kept or deliberately lifted
| v0.1 constraint | v0.2 | How it holds |
|---|---|---|
| Read-only, no machine control | **Kept** | Every `lets*` on the cover returns true. `isRedstoneSensitive` and `manipulatesSidedRedstoneOutput` return false. No synced actions. OC is read-only. The only writes are to GregScope's own data (label, purge). |
| No mixins, ATs or reflection in shipped code | **Kept** | Only public APIs: GT `CoverRegistry`/`CoverPlacer`/`Cover`, MUI2, GTNHLib teams, OC, Forge. Reflection appears only in test code (GS-119). |
| No world scans; never force-load chunks | **Kept** | Lookups go `DimensionManager.getWorld` → `blockExists` → `getTileEntity`, and never call `worldServerForDimension`. Housekeeping walks the registry only. |
| Bounded memory | **Kept** | Hard caps, fixed rings, capped strings, a bounded I/O queue. |
| No per-tick work beyond the documented budget | **Kept, with a new budget** | See §6.3. It replaces handoff §13 "no periodic work". |
| Server-authoritative; dedicated-server safe | **Kept** | Client code lives only in `@SideOnly(CLIENT)` methods, `createScreen` and `ClientProxy`. Gametests prove the server loads `buildUI`. |
| No items, blocks, recipes or saved data | **Lifted** | One Item, one Block + ItemBlock + TileEntity, 2 recipes, files under `<world>/gregscope/`. |
| No global tick handler | **Lifted** | Exactly one `ServerTickEvent` END handler. |
| Server-only (`acceptableRemoteVersions="*"`) | **Lifted** | The attribute is removed, so FML's handshake enforces a matching client (`gs/.../GregScope.java:14-17`). |

---

## 2. Components and packages

```
io.github.ldogg123.gregscope
├─ GregScope                 @Mod (preInit/init/postInit/serverStarting/serverStarted/serverStopping/serverStopped)
├─ CommonProxy, ClientProxy  (ClientProxy sets System property "gregscope.clientProxyLoaded" for the server-safety test)
├─ GregScopeTestHooks        clock/settings override, sampleNow, heartbeatNow, flushIo — no-ops unless -Dgregscope.testHooks=true
├─ config/      GregScopeConfig (Forge Configuration) → immutable Settings
├─ model/       v0.1 unchanged + StateCodes (pinned byte codes)
├─ probe/       v0.1 unchanged; GregTechMachineProbe.isSupportedMte(IMetaTileEntity) made package-visible static (no behaviour change)
├─ sensor/      [pure] SensorIdentity, SensorNbtCodec (over a KeyValue seam), Labels
│               ItemMachineSensor, MachineSensorCover, SensorCovers (registration)
├─ registry/    [pure] SensorState, RemovalCause, SensorRegistryCore, RunsTable
│               SensorRegistry (MC adapter), RegistryNbtCodec
├─ sampling/    [pure] SamplerSchedule, Clock, TelemetryFrame, SensorView, SamplerStats, SensorCounters, LogHistogram
│               TargetResolver (the only telemetry class touching World), TelemetrySampler (tick handler)
├─ history/     [pure] GapReason, SecondRing, MinuteSlot, MinuteAccumulator, MinuteRing, HistoryFileCodec, Crc16Ccitt, GapRanges, Summaries
│               FileStore (interface), NioFileStore, HistoryIo (single daemon thread)
├─ access/      [pure] AccessPolicy, TeamResolver (interface); GtnhlibTeamResolver (sole importer of gtnhlib.teams)
├─ hub/         BlockTelemetryHub, TileTelemetryHub (IGuiHolder<PosGuiData>), HubPanel
│               [pure] HubViewModel, HubRow/HubDetail/HubHeader DTOs + codecs over ByteSink/ByteSource
├─ command/     GregScopeCommand; [pure] CommandArgs
├─ recipe/      GregScopeRecipes
└─ integration/opencomputers/  v0.1 classes (+2 callbacks), HubDriver, HubEnvironment (final), [pure] LuaTables
```

Rule: a `[pure]` class imports nothing from Minecraft, GT, OC, GTNHLib or MUI2. Plain-JVM JUnit 5 covers it, the same way as `probe/` in v0.1.

**`@Mod` change**
```java
@Mod(modid="gregscope", version=Tags.VERSION, name="GregScope", acceptedMinecraftVersions="[1.7.10]",
     dependencies="required-after:gregtech;required-after:OpenComputers;required-after:modularui2;required-after:gtnhlib@[0.11.46,)")
```
- `@SidedProxy(clientSide=".ClientProxy", serverSide=".CommonProxy")`.
- `dependencies.gradle` gets explicit `implementation` dev deps for `ModularUI2:2.3.88-1.7.10` and `GTNHLib:0.11.46`. Both are already transitive via GT (GT5U pom lines 72-77), and the existing GTNHLib constraint becomes a real dependency.

**Load phases**
| Phase | Work |
|---|---|
| preInit | config; `GameRegistry.registerItem` / `registerBlock` / `registerTileEntity` |
| init | `CoverRegistry.registerCover` (after GT through `required-after:gregtech`, as in the TecTech precedent) |
| postInit | recipes. Never in loadComplete: GT nulls `sBefore/sAfterGTPostload` at the end of its postInit (`GTMod.java:612-618`). |
| serverStarting | resolve the save root, load the registry, start I/O, register the command |
| serverStopping | finalize, flush, close the run |
| serverStopped | join I/O and clear static state; this makes single-player world switches safe |

---

## 3. Machine Sensor cover

### 3.1 Why a cover
A cover is idiomatic GT and uses only public API. `CoverMetricsTransmitter` is a direct precedent: UUID in cover NBT, rate 20, copy-paste opt-out, placement predicate (`gt5u/.../CoverMetricsTransmitter.java:43-160`).

- **Identity follows the physical machine.** A replaced machine does not silently inherit a sensor.
- **No block space is used**, so it also works on non-front controller faces.
- **Removing the mod is safe.** Covers degrade to `CoverNone` without a crash (`CoverRegistry.java:76-85,114-127`; `CoverableTileEntity.java:103-116`).
- **Rejected alternatives:** a block binds by position and takes cable space; a tool gives no removal signal and nothing visible in the world.

### 3.2 Registration
- **preInit:** `ItemMachineSensor` is registered as `gregscope:machine_sensor`.
  - Unlocalized name `gregscope.machine_sensor`, icon `gregscope:machine_sensor`, creative tab `gregscope`, stack size 64.
- **init** (common code, both sides):
```java
IIconContainer OVERLAY = Textures.BlockIcons.custom("gregscope", "iconsets/GREGSCOPE_SENSOR_OVERLAY"); // E1; Textures.java:2699-2701
ITexture tex = TextureFactory.of(OVERLAY);
CoverRegistry.registerCover(new ItemStack(ItemMachineSensor.INSTANCE, 1, 0), tex,
    ctx -> new MachineSensorCover(ctx, tex),
    CoverPlacer.builder().onlyPlaceIf(SensorCovers::isPlaceable).build());   // CoverRegistry.java:54-68, CoverPlacer.java:267-292
```
- **PNG location:** `assets/gregscope/textures/blocks/iconsets/GREGSCOPE_SENSOR_OVERLAY.png`. The icon name is unique, which matters because `GTCustomBlockIconContainer.INSTANCES` is keyed by name only (`:43-45`).
- **`isPlaceable(side, stack, coverable)` accepts only when all of these hold:**
  - `coverable instanceof IGregTechTileEntity`;
  - `GregTechMachineProbe.isSupportedMte(mte)`;
  - no other face already holds a `MachineSensorCover` (check each of the 6 `ForgeDirection.VALID_DIRECTIONS` with `getCoverAtSide`).
- **GT's own face rules still apply:**
  - A controller refuses a cover on its front (`MTEMultiBlockBase.java:296-298`).
  - A basic machine refuses its main face for placers that are not GUI-clickable (`MTEBasicMachine.java:981-985`).
- **GUI blocking:** the placer does not call `blocksCoverableGuiOpening`, so the machine's GUI still opens.

### 3.3 Cover NBT: the source of truth for identity
GT stores the data under `d` (`Cover.java:79-105`). GregScope uses these keys inside `d`:

| Key | Type | Meaning |
|---|---|---|
| `gs` | byte `1` | GregScope marker and format version |
| `idM`, `idL` | long | sensor UUID |
| `lbl` | string | sanitized label, ≤32 code points. Absent means empty. |
| `owM`, `owL` | long | owner UUID. Absent means unowned. |
| `owN` | string ≤16 | owner name, cached for display |
| `ct` | long | creation time, epoch seconds |

**Read rules** (`SensorNbtCodec`, pure):
- **`gs` missing or `d` not a compound** (a foreign blob, e.g. the numeric item ID was reused after removal): the identity is null and the cover stays **inert**. It never registers and its description says "inactive".
- **`gs > 1`**: keep the raw compound, write it back unchanged, and make the sensor inert with the description "unsupported data version". This protects a rollback.
- **Labels** are re-sanitized on every read.

### 3.4 Cover overrides
| Member | Behaviour |
|---|---|
| constructor | `super(ctx, tex)` and `identity = null`. **No side effects.** The constructor runs during TE NBT load before `worldObj` exists (`BaseMetaTileEntity.java:161-205`) and on the client. |
| `onPlayerAttach(player, stack)` | Server side only. Creates a fresh UUID and `ct=now`. Owner is the player's UUID and name, unless the player is null or a FakePlayer; then it is the holder's `getOwnerUuid()`/`getOwnerName()` (`BaseMetaTileEntity.java:1818`), else null. Label = `Labels.sanitize(stack.hasDisplayName() ? stack.getDisplayName() : "")`, so an anvil rename sets the label. Registration happens on the next heartbeat. |
| `getMinimumTickRate()` | `20` (`Cover.java:497-504`) |
| `allowsTickRateAddition()` | `false`. The holder's jackhammer path then refuses (`BaseMetaTileEntity.java:1593`). |
| `doCoverThings(r, t)` | Server only (GT calls it only in the server branch, `BaseMetaTileEntity.java:293`). **O(1) heartbeat:** `SensorRegistry.heartbeat(this, holder)`. No probe call. |
| `onCoverUnload()` | Server side: `onUnloaded(id, pos)`. Applies only if the stored position matches (`BaseMetaTileEntity.java:923-931`). |
| `onCoverRemoval()` | Server side: `onRemoved(id, DETACHED)`. Covers crowbar, screwdriver, facing-change drops (`CoverableTileEntity.java:201-206,306-313`) and the purge case. Also called client-side, so it is guarded. |
| `onBaseTEDestroyed()` | Server side: `onRemoved(id, IN_ITEM)`. Survival breaks write covers into the drop first (`BaseMetaTileEntity.java:1393-1406`). |
| `letsEnergyIn/Out`, `letsFluidIn/Out(Fluid)`, `letsItemsIn/Out(int)`, `letsRedstoneGoIn/Out` | all `true`. The defaults are false (`Cover.java:241-250,373-414`). |
| `isRedstoneSensitive(long)` | `false` |
| `manipulatesSidedRedstoneOutput()` | `false` |
| `allowsCopyPasteTool()` | `false`. Covers GT's copy tool and Matter Manipulator (`BehaviourCoverTool.java:73-99`). |
| `hasCoverGUI()` | `false` in v0.2.0 |
| `isDataNeededOnClient()` | default (`false`). No GregScope packet data. |
| `getDescription()` | `"GregScope sensor <shortId>[: <label>] [<availability>]"`. The availability comes from a transient field the registry sets. |

### 3.5 Labels (`Labels.sanitize`, pure)
1. Strip `§x` pairs and any lone `§`.
2. Remove ISO controls and the categories Cf, Co, Cs (lone surrogates), U+2028/2029 and U+FEFF.
3. Collapse whitespace and trim.
4. Truncate to 32 code points without splitting a surrogate pair.
5. `null` becomes `""`.

Any string from C2S input or a command longer than 64 UTF-16 units is **rejected before sanitizing**, because MUI2 accepts up to 32,767 (`mui2/.../network/NetworkUtils.java:140-147`).

**Display name:** the label, else the last snapshot `name`, else `metaName`, then `" #" + shortId` (first 8 hex digits).

**Ways to set a label in v0.2.0:**
- an anvil rename before attaching;
- the Hub label field (§9.3);
- `/gregscope label`.

Every write requires:
- `AccessPolicy.canRename`;
- the sensor LIVE, since the cover is the source of truth;
- a per-player cooldown of `hub.renameCooldownSeconds` (5 s).

A write sets the cover field, calls `holder.markDirty()` and then mirrors the value into the registry. A write never loads a chunk: an unloaded sensor answers "sensor not loaded".

---

## 4. Registry and lifecycle

### 4.1 Entry (server thread only)
`SensorEntry` holds:
- **Identity and location:** `id`, `kind` (0 = machine), `dim, x, y, z, side`.
- **Owner and label:** `owner?`, `ownerName`, `label`.
- **Machine metadata:** `metaId`, `metaName≤64`, `machineName≤64`, `lastStatusId≤64`.
- **Lifecycle:** `state`, `removalCause`, `createdEpochSec`, `lastSeenEpochSec`, `stateSinceEpochSec`.
- **RAM only:** `strikes`, `bucket`, `historyLoaded`, `SecondRing`, `MinuteRing`, `MinuteAccumulator`, `SensorCounters`, `lastSnapshot` (immutable v0.1 `MachineSnapshot`), `lastSampleEpochSec`.

Indexes: `HashMap<UUID, SensorEntry>`, and a reverse index from `packedPos(dim,x,y,z)` to UUID.

### 4.2 States
| State | Meaning | Sampled | RAM rings | Counts toward caps |
|---|---|---|---|---|
| LIVE | loaded and validated | yes | yes | yes |
| UNLOADED | chunk or dimension not loaded (or the server just started) | no | minute ring kept | yes |
| OVER_CAP | transient, not persisted; the cover exists but was refused | no | none | no |
| MISSING | tombstone: chunk loaded but tile or cover gone, 3 validations in a row | no | freed | no |
| IN_ITEM | tombstone: machine picked up with the cover in its drop NBT | no | freed | no |
| REMOVED | tombstone: detached, replaced or purged | no | freed | no |

The number of tombstones is capped at `maxSensors`; beyond that the oldest tombstone expires first.

### 4.3 Transitions (`SensorRegistryCore`, pure; fake clock in tests)
| From | Event | Result |
|---|---|---|
| unknown | heartbeat, caps OK (global; and team for the owner, resolved by a `getTeamMap` scan, E4) | LIVE: assign a bucket, allocate rings, queue an async history load, mark dirty |
| unknown | heartbeat, a cap exceeded | OVER_CAP. Retry at most every 1200 ticks per UUID (an LRU of 1024 entries). `quotaRefusedTotal++`. If a player is attaching, send a chat message. |
| LIVE | heartbeat at the same position and side | fast path: store `lastHeartbeatTick`, no allocation |
| LIVE or UNLOADED | heartbeat for the same UUID at a **different** position | **duplicate**: the newcomer's cover gets a fresh UUID (written to its NBT, `markDirty`) and is handled as unknown. The original keeps its history. `duplicatesRekeyedTotal++`. |
| MISSING, IN_ITEM or REMOVED (within retention) | heartbeat at any position | **move / resume**: LIVE at the new position, history continues, the gap in between stays implicit |
| UNLOADED | heartbeat at the same position | LIVE |
| any (UUID A indexed at position P) | heartbeat of UUID B at P | A becomes REMOVED(`REPLACED`) |
| LIVE | `onCoverUnload`, `getWorld(dim)==null`, or `!blockExists` | UNLOADED. The open minute closes with gap `chunk_unloaded` or `dimension_unloaded`. |
| LIVE | chunk loaded but the tile is missing, dead, not GT, or the cover at `side` has another UUID | `strikes++`; a `target_missing` second is recorded; at 3 the entry becomes MISSING |
| LIVE or UNLOADED | `onRemoved(DETACHED)` or `onRemoved(IN_ITEM)` | REMOVED or IN_ITEM. The partial minute gets gap `sensor_removed`, its slot is queued, RAM rings are freed. |
| tombstone | `now − stateSince > history.removedRetentionHours` (24) | expired: entry deleted, file deleted on the I/O thread |
| UNLOADED | `now − lastSeen > history.staleExpiryDays` (30) | expired. If the cover loads later, the heartbeat registers it fresh from cover NBT. |
| any | `/gregscope purge` | expired immediately. A LIVE sensor that heartbeats again registers again with the same UUID and empty history. |

**Housekeeping** runs at server start and then every 72,000 ticks. It is O(entries) and never touches the world.

**Caps:**
- `limits.maxSensors` = 256 (range 16..1024) counts LIVE + UNLOADED.
- `limits.maxSensorsPerTeam` = 64 (0 = unlimited) counts LIVE + UNLOADED entries whose owner is in the same team as the new owner.
  - An owner with no team is counted on its own.
  - All unowned sensors share one pseudo-owner.
  - If a team merge pushes a team over the cap, its existing sensors are kept and new ones are refused.

---

## 5. Ownership and permissions

`TeamResolver` (interface). `GtnhlibTeamResolver` is the only class that imports `com.gtnewhorizon.gtnhlib.teams`:
- **`teamOf(uuid)`** loops over `TeamManager.getTeamMap().values()` and returns the first team where `isMember(uuid)`, else null. It **never calls `getTeamByPlayer`** (E4). A grep test enforces this.
- **`sameTeam(a,b)`** is `a.equals(b) || (t = teamOf(a)) != null && t.isMember(b)`.
- **`isOfficerOrOwner(team, p)`** uses `Team.isOfficer(p) || Team.isOwner(p)` (`Team.java:61-65`).
- **Caching:** results are cached for one Hub view rebuild or one sampler-frame sequence. No team IDs are stored anywhere, so merges, leaves and kicks apply on the next rebuild.

`AccessPolicy` rules (pure):
| Action | Allowed if |
|---|---|
| Open a Hub | viewer is the Hub owner, or `sameTeam(viewer, hubOwner)`, or op ≥ `permissions.opLevel` (2) |
| Sensors visible in a Hub (GUI and OC) | `hubOwner != null` and (`sensor.owner == hubOwner` or `sameTeam(hubOwner, sensor.owner)`). An op who opens someone else's Hub sees that Hub's scope and gets no extra visibility. |
| Rename | viewer is the owner, or op, or same team (limited to officers/owners when `permissions.renameRequiresOfficer=true`; default false) |
| `/gregscope list`, `info`, `stats` | any player, filtered by `canView(viewer, owner)` (owner, same team, or op). The console counts as op. |
| `/gregscope purge` | op only |
| OC `gt_machine` sensor callbacks | physical adjacency, as in v0.1 |
| OC `gregscope_hub` | physical adjacency to the Hub, within the Hub owner's scope. Out-of-scope IDs return "sensor not found" and do not reveal that they exist. |

- **Unowned sensors** (owner null) are visible only to ops through commands.
- **An unowned Hub** (placed by a FakePlayer) shows "Unowned hub: break and re-place".

---

## 6. Sampling

### 6.1 Schedule (`SamplerSchedule`, pure)
- **Handler:** `TickEvent.ServerTickEvent` phase END on the FML bus. Phase END means all worlds have already ticked, so readings from one tick are consistent with each other.
- **Buckets:** `sampling.intervalTicks` ∈ {20, 40, 60, 100} (default 20) gives that many buckets. A sensor that becomes LIVE goes into the least-loaded bucket (ties go to the lowest index). Removal from a bucket is an O(1) swap-remove.
- **Each tick** the handler processes the carry-over list first, then `bucket[ownTick % interval]`.
- **Once per interval** (on the last bucket index):
  - sweep open accumulators for sensors not sampled this interval and close their minutes at the boundary;
  - build and publish `TelemetryFrame`;
  - drain the history-load results queue.

### 6.2 Per-sample procedure (`TargetResolver`)
```
w = DimensionManager.getWorld(dim)     null → UNLOADED(dimension_unloaded)       // never worldServerForDimension
!w.blockExists(x,y,z)                  → UNLOADED(chunk_unloaded)                // chunkExists hash lookup, World.java:417-420
te = w.getTileEntity(x,y,z)
!(te instanceof IGregTechTileEntity h) || h.isDead()
  || !(h.getCoverAtSide(side) instanceof MachineSensorCover c) || !c.id().equals(id)  → strike (target_missing)
strikes = 0
snap = PROBE.snapshot(te)   try/catch (RuntimeException | LinkageError) → probe_error second, counter, DEBUG log, ≤1 WARN/sensor/10 min
append SecondRing, fold MinuteAccumulator (close minute if epochMinute advanced), update counters,
lastSnapshot = snap, lastSeen = now, refresh metaId/metaName/machineName/lastStatusId (dirty if changed)
```
- **Timestamps** come from `Clock.epochMillis()`: `SystemClock` in production, `FakeClock` through test hooks.
- **Wall-clock going backwards:** a sample whose minute is older than the open minute is folded into the open minute. A minute older than the newest written slot is never written: `clockSkewRefusedTotal++` and one WARN per run.

### 6.3 Documented budget (replaces handoff §13 "no periodic work")
| Work | Where | Bound |
|---|---|---|
| Cover heartbeat | host tile tick, every sensor on the same `getTickCounter() % 20` tick (`CoverableTileEntity.java:184-193`) | O(1) hash lookup each |
| Sampling | `ServerTickEvent` END | **hard 1,000 µs per tick** (`sampling.tickBudgetMicros`, 100..5000), checked with `nanoTime` before each sample. The rest of the bucket carries over. A sensor carried for ≥ `interval` ticks is skipped for that interval, gets gap `sampling_skipped`, and `samplingSkippedTotal++`. |
| Frame build and publish | once per interval | O(N) reference copies. Measured target ≤500 µs at 256 sensors (GS-108). |
| Minute close | once per minute per sensor | encode 64 B and `offer` it to the I/O queue (non-blocking) |
| Housekeeping | hourly | O(entries), no world access |
| Open Hub GUIs | MUI2 `detectAndSendChanges` per viewer per tick (`mui2/.../ModularContainer.java:114-118`) | getters return cached DTOs, so O(8 rows + detail + header) `equals` checks. View rebuilt ≤1 per interval, or on an input change throttled to 1 per 4 ticks. At most `limits.maxOpenHubViews` = 32 views. |
| OC | on demand | direct callbacks read the frame only; history callbacks are server-thread and bounded by count |
| File I/O | `GregScope-IO` daemon thread | the server thread never does file I/O except the registry byte serialization (≤150 KB, only at save) |

**GS-102 gate:** the design target is ≤50 µs per sample at p99. At 256 sensors that is 12.8 samples per tick, about 0.64 ms per tick.
- p99 ≤ 50 µs: keep the defaults.
- 50 < p99 ≤ 100 µs: default `maxSensors` becomes 128.
- p99 > 100 µs: implement the lean probe (`GregTechMachineProbe.readings` + `StateClassifier`, text fields refreshed every 60 s per sensor) inside v0.2.0 and measure again.

**`/gregscope stats`** shows:
- sensor counts by state;
- p50/p99/max µs per tick (1,024-bucket log histogram, 60 s window);
- budget-exceeded ticks, skips, probe errors, duplicates re-keyed, quota refusals;
- I/O queue depth, dropped and errored writes;
- estimated RAM and disk use, and the frame age.

---

## 7. History data model

### 7.1 `StateCodes` (pinned; a unit test fixes each mapping)
`0 unavailable, 1 starting, 2 unformed, 3 shutdown, 4 power_starved, 5 running, 6 disabled, 7 output_blocked, 8 waiting, 9 idle`.

These codes are an explicit table, not `ordinal()` (E6). A new state needs a new `slotLayout`.

### 7.2 `GapReason` (stable IDs; bit = position)
| Bit | ID | Where it is recorded |
|---|---|---|
| 0 | `chunk_unloaded` | stored |
| 1 | `dimension_unloaded` | stored |
| 2 | `target_missing` | stored |
| 3 | `sampling_skipped` | stored (budget skip, or `sampling.enabled=false`) |
| 4 | `probe_error` | stored |
| 5 | `sensor_removed` | stored (removed, over cap, or inactive during the minute) |
| 6 | `server_offline` | **derived only**, from the runs table |
| 7 | `unknown` | **derived only** |

Server lag is **not** a gap. It shows up as `samples < expectedSamples` together with a low `serverTicks`.

### 7.3 Second sample: 28 B, RAM only
`SecondRing` has 300 entries stored as columnar primitive arrays. The index comes from a **head pointer**, so two samples in the same wall-clock second are both kept.

| Field | Type | Notes |
|---|---|---|
| epochSec | i32 | |
| stateCode | u8 | 0 when gap |
| gapReason | u8 | 0 = none, else bit+1 |
| flags | u8 | b0 active, b1 allowedToWork, b2 formedKnown, b3 formed, b4 wasShutdown, b5 hasEu, b6 hasEnergy, b7 valid |
| maintenanceIssues | u8 | saturating |
| progress | u16 | ×10000 |
| reserved | u16 | 0 |
| euPerTick | i64 | valid if b5 |
| energyStored | i64 | valid if b6 |

300 × 28 = **8,400 B**. The ring is freed when the entry becomes a tombstone. It is not persisted, so a restart shows as a gap.

### 7.4 Minute slot: 64 B, big-endian, RAM and disk
| Off | Type | Field |
|---|---|---|
| 0 | i32 | epochMinute (0 = empty) |
| 4 | u8 | samples (valid samples) |
| 5 | u8 | expectedSamples (`1200 / intervalTicks`) |
| 6 | u8 | gapMask (stored bits 0-5) |
| 7 | u8 | lastStateCode |
| 8 | u8×10 | stateSamples by StateCode (saturating) |
| 18 | u8 | maintenanceMax |
| 19 | u8 | flags: b0 recipesCounterReset, b1 partialMinute, b2 serverStartMinute |
| 20 | u16 | serverTicks seen while the minute was open |
| 22 | u8 | euSamples |
| 23 | u8 | reserved 0 |
| 24 | i64 | euPerTickAvg (over euSamples; `Long.MIN_VALUE` = none) |
| 32 | i64 | euPerTickMin (generation peak, negative) |
| 40 | i64 | euPerTickMax (consumption peak) |
| 48 | i64 | energyStoredLast (`MIN_VALUE` = absent) |
| 56 | i32 | recipesCompletedDelta (−1 = not a multiblock) |
| 60 | u16 | reserved 0 |
| 62 | u16 | CRC-16/CCITT-FALSE over bytes 0..61 (test vector `"123456789"` → `0x29B1`) |

- **recipesCompleted** comes from GT's persisted `recipesDone` (`MTEMultiBlockBase.java:185,355,412`). If it decreases, the step's delta is 0 and `recipesCounterReset` is set.
- **Status IDs are not stored in slots.** `lastStatusId` lives in the registry entry.
- **`MinuteRing`** holds 1,440 slots, 92,160 B, at index `floorMod(epochMinute, 1440)`.
- **A slot is valid only if** `epochMinute != 0`, the CRC matches, and `newest−1439 ≤ epochMinute ≤ newest`. A torn or stale slot reads as a gap.
- **Merge rule (same minute reopened):**
  - samples, stateSamples, euSamples and serverTicks are added with saturation;
  - gapMask and flags are OR-ed;
  - the average is weighted by euSamples;
  - min and max are combined;
  - last and energy come from the newer slot;
  - recipesDelta is summed if both are ≥0.

### 7.5 Reader contract (same for Hub, OC, v0.4 exporter and v0.5 alerts)
1. Aggregates use observed samples only. There is **no interpolation or zero-filling**. Coverage = `samples / expectedSamples`.
2. A stored entry with state 0, or `samples == 0`, is a gap with its stored reasons.
3. For a missing slot inside the window:
   - outside every recorded server run: `server_offline`;
   - otherwise, if the registry says UNLOADED since a time ≤ that minute: `chunk_unloaded` (or `dimension_unloaded`);
   - otherwise `unknown`.
4. Gaps are returned as merged ranges `{from, to, reason}`, not as fake rows.

### 7.6 Counters (monotonic within a process; they reset on restart, which Prometheus-style consumers handle)
- **Per sensor (`SensorCounters`):**
  - `samplesTotal`
  - `stateSamplesTotal[10]`
  - `gapSecondsTotal[6 stored reasons]`
  - `probeErrorsTotal`
  - `recipesCounterResetsTotal`
  - `euConsumedSampledTotal` and `euGeneratedSampledTotal` (Σ |euPerTick| × intervalTicks; documented as **estimates**)
- **Global (`SamplerStats`):**
  - `cyclesTotal`, `samplesTotal`, `sampleNanosTotal`, `budgetExceededTicksTotal`, `samplingSkippedTotal`, `probeErrorsTotal`, `duplicatesRekeyedTotal`, `quotaRefusedTotal`
  - `ioQueuedTotal`, `ioDroppedTotal`, `ioErrorsTotal`, `clockSkewRefusedTotal`
  - gauges: `lastCycleNanos`, `lastFrameBuildNanos`, and p50/p99/max µs

### 7.7 `TelemetryFrame` (immutable; published through `static volatile`)
```
final class TelemetryFrame { long sequence; long publishedNanos; long publishedEpochMillis; int intervalTicks;
  List<SensorView> sensors /*unmodifiable, sorted by id*/; SamplerStatsView stats; LimitsView limits; }
final class SensorView { UUID id; int kind; String label; UUID owner; String ownerName; int dim,x,y,z; byte side;
  SensorState state; long stateSinceEpochSec, lastSeenEpochSec, lastSampleEpochSec; int lastGapReason;
  String metaName; int metaId; String machineName; String lastStatusId;
  MachineSnapshot lastSnapshot /*nullable, immutable v0.1*/; CountersView counters /*copied primitives*/; }
```
Readers:
- the Hub view model (server thread);
- OC direct callbacks (computer threads);
- the v0.4 HTTP thread.

Rings are **not** part of the frame. History is read only on the server thread.

### 7.8 Memory and disk ceilings (from the layouts; GS-103 asserts them)
| Item | Per sensor | × 256 (default) | × 1024 (max) |
|---|---|---|---|
| SecondRing | 8,400 B | 2.15 MB | 8.6 MB |
| MinuteRing | 92,160 B | 23.6 MB | 94.4 MB |
| Entry, accumulator, counters, last snapshot (~25 keys) | ≈3,000 B | 0.77 MB | 3.1 MB |
| **RAM** | **≈103.6 KB** | **≈26.5 MB** | **≈106 MB** |
| History file | 92,224 B | 23.6 MB (+ ≤23.6 MB tombstones within 24 h) | 94.4 MB (+ tombstones) |
| Registry (gzip NBT) | ≈250 B raw | <64 KB | <256 KB |
| Disk write rate | 64 B/min | ≈16 KB/min | ≈64 KB/min |

Tombstones use about 300 B of RAM each. At startup GregScope logs one INFO line with the RAM and disk ceilings for the configured cap.

---

## 8. Persistence

### 8.1 Layout
- **Root:** `DimensionManager.getCurrentSaveRootDirectory()` + `/gregscope/`. It is always the overworld save root, never a `DIMn` folder; entries carry `dim`.
- **Files:**
  - `registry.dat`, `registry.dat.bak`, `registry.dat.tmp`
  - `history/<uuid>.gsh`
  - renamed files that are never deleted automatically: `*.unsupported-v<N>`, `*.corrupt-<epochMs>`, `*.mismatch`
- **Why not the alternatives:**
  - WorldSavedData rewrites everything non-atomically on the main thread (`MapStorage.java:113-125`).
  - Chunk or cover NBT would bloat chunks and be copied into item drops.

### 8.2 History file `.gsh` (format 1): fixed **92,224 B**
Header, 64 B:

| Off | Type | Field |
|---|---|---|
| 0 | 4 B | magic `GSH1` |
| 4 | u16 | formatVersion = 1 |
| 6 | u16 | slotSize = 64 |
| 8 | u16 | slotCount = 1440 |
| 10 | u8 | sensorKind (0 = machine; 1 = item flow and 2 = fluid flow reserved for v0.3) |
| 11 | u8 | slotLayout (1 = machine minute v1) |
| 12 | i64 + i64 | UUID msb, lsb |
| 28 | i64 | createdEpochSec |
| 36 | 24 B | reserved 0 |
| 60 | u32 | CRC32 of bytes 0..59 |

- **Slots** start at `64 + idx*64`.
- **Header rules:**
  - Bad magic or CRC: rename to `.corrupt-*` and start a new file.
  - `formatVersion > 1`, or an unknown `slotLayout`/`sensorKind`: rename to `.unsupported-v<N>` and start a new file.
  - UUID mismatch: rename to `.mismatch`.
  - A future change to the slot format must use a new `slotLayout`, never a reinterpretation.
  - The header is written once, at creation.
- **Writes:** `RandomAccessFile("rw")`, seek, write 64 B, close. No fsync and no handle cache (Windows cannot delete open files).
- **Creating a file:** write header + zeroed body to `.tmp`, then move it into place.

### 8.3 Registry `registry.dat` (format 1): gzipped NBT
```
{ v:1, saved:long, runs:[{start:long, stop:long /*0 = unclean*/}] (≤32, oldest dropped),
  entries:[{ idM, idL, kind:b, dim, x, y, z, side:b, owM?, owL?, owN, lbl, mi:i, mn, nm, st:s(lastStatusId),
             state:b (0 UNLOADED,1 MISSING,2 IN_ITEM,3 REMOVED), cause:b, ct, seen, since:l }] }
```
- **Saving:** when dirty, on dim-0 `WorldEvent.Save` and on `FMLServerStoppingEvent`. The server thread serializes to `byte[]`. The I/O thread writes `.tmp`, copies the old file to `.bak`, then does `Files.move(ATOMIC_MOVE)`, falling back to `REPLACE_EXISTING` on `AtomicMoveNotSupportedException`.
- **Loading:** `registry.dat`, else `.bak`, else an empty registry with one WARN. The registry rebuilds itself from heartbeats, since identity lives in the covers.
  - `v > 1`: rename to `.unsupported-v<N>` and start empty.
  - LIVE is never persisted; every loaded non-tombstone entry starts as UNLOADED.
- **Runs:** on start, append `{start=now, stop=0}`; on stop, set `stop`. For an unclean run, readers use `saved` as the approximate stop time.

### 8.4 I/O thread (`HistoryIo`)
- One daemon thread named `GregScope-IO` with `ArrayBlockingQueue(history.ioQueueCapacity=4096)`.
- **Tasks:** `Load(uuid)`, `WriteSlot`, `CreateFile`, `WriteRegistry`, `Delete`, `Flush`.
- **Queue full:** drop the task, `ioDroppedTotal++`, WARN at most once per 10 minutes. The RAM ring stays correct, and the dropped slot shows as a gap after a restart.
- **Async load:** when an entry first becomes LIVE or UNLOADED in a process, its file is read. Results go to a `ConcurrentLinkedQueue`, which the sampler drains. On merge, **slots already in RAM win**. Until the merge, `historyLoaded=false`: the Hub shows "loading history…" and OC minute history returns `nil,"history loading"`. If the entry became a tombstone before the result arrived, the result is discarded.
- **Stop:** close all open accumulators as partial slots, queue the registry write, then `Flush` + poison, then `awaitTermination(10 s)`. A WARN is logged if that times out. The thread is a daemon, so the JVM cannot hang, which keeps the Horizon-QA 300 s CI step safe.
- **Shutdown triggers:** `FMLServerStoppedEvent` and dim-0 `WorldEvent.Unload` both flush and clear all static state.
- **`history.persist=false`:** no `.gsh` files, RAM rings only. The registry is still saved.

### 8.5 Crash and removal semantics (documented)
- **JVM crash:** loses the current partial minute and anything still queued. A torn slot or registry falls back to a gap or to `.bak`.
- **OS or power loss:** can lose more, because there is no fsync. This is accepted.
- **Removing GregScope:**
  - Cover NBT stores GT's packed numeric item ID (`Cover.java:68`, `GTUtility.java:1658-1677`), which resolves to `CoverNone`; the cover is dropped on the next save.
  - Machines keep working.
  - The Hub triggers FML's missing-block prompt (`-Dfml.queryResult=confirm` on dedicated servers).
  - The `gregscope/` folder stays behind and is harmless.
  - Residual risk: a later mod could reuse the numeric ID for its own cover. GT catches exceptions (`CoverRegistry.java:119-125`), and the `gs` marker makes a reinstalled GregScope ignore foreign blobs.

---

## 9. Telemetry Hub

### 9.1 Form
- **Block:** `BlockTelemetryHub` (Material.iron, hardness 5, resistance 10, pickaxe level 2, metal sound, tab `gregscope`), registered as `gregscope:telemetry_hub` with a default ItemBlock.
- **TileEntity:** `TileTelemetryHub` registered as `gregscope:telemetry_hub`, with `canUpdate()=false`.
- **Why not a GT MTE:** MTE IDs are permanent in world saves, have no allocator, and crash on collision (`CommonMetaTileEntity.java:85-101`). The enum is incomplete: HydroEnergy uses 17000+ through `pack/config/hydroenergy.cfg:33-34`.
- **Facing:** block metadata 2..5, set from player yaw.
- **Textures:** its own `telemetry_hub_front/side/top` (GT hull icons come in v0.2.1).
- **Placement:** `onBlockPlacedBy` sets the owner (null for a FakePlayer).
- **TE NBT:** `{gsHub:1, owM, owL, owN}`. An unknown `gsHub > 1` is kept verbatim and the GUI shows "unsupported".
- **Opening:** `onBlockActivated`
  - on the client, returns true;
  - on the server, checks access (chat `gregscope.hub.denied`), then the open-view cap (`gregscope.hub.busy`), then calls `GuiFactories.tileEntity().open(player,x,y,z)` (`mui2/.../test/TestBlock.java:30-32`).
- **Hub contents:** the Hub stores no telemetry. It shows every registry entry in the Hub owner's scope, across all dimensions. There is no linking and no range.

### 9.2 GUI layout (MUI2 default theme; panel ≈260×210; no player inventory)
```
Telemetry Hub · <owner>'s team · 23 sensors (20 live)          [All|Problems]
● EBF North                RUNNING      -1,920 EU/t   1s
○ Macerator line 2         UNLOADED                   12m
… 8 rows …                                          < 2 / 3 >
────────────────────────────────────────────────────────────────
Label [________________]
Electric Blast Furnace · dim 0 (120,64,-30) side east · 3fa2c1d0
running · running · "<statusText>"   progress 42%   maint 0
EU/t 1,920 (min -0 / max 2,133)   stored 1.2M / 1.6M
Last 5 min:  running 82% · idle 15% · output_blocked 3% · coverage 100%
Last 24 h:   running 61% · coverage 88% · recipes 1,204
24h  ##==--..??##==  (hourly running fraction; ? = gap)
Sampler 0.41 ms/tick p99 · skipped 0
```
- **Sort order:** severity (shutdown > power_starved > output_blocked > waiting > unformed > disabled > MISSING > UNLOADED > starting > running > idle), then display name ignoring case, then id.
- **Problems filter:** LIVE with a state in {shutdown, power_starved, output_blocked, waiting, unformed, disabled}, or MISSING, or any warning.
- **Text strip:** uses ASCII only, to avoid glyph risk in the 1.7.10 font.

### 9.3 Sync handlers (registered by name on both sides; `HubPanel` does not depend on the host)
| Name | Handler | C2S | Server-side validation |
|---|---|---|---|
| `gs_page` | IntSyncValue | yes | clamp to `[0, pages−1]` |
| `gs_filter` | IntSyncValue | yes | ∈ {0,1}, else 0 |
| `gs_select` | IntSyncValue | yes | row index 0..7 resolves to the **UUID** on the viewer's page; out of range means none; the selection survives resorting |
| `gs_label` | StringSyncValue | yes | ≤64 units before sanitizing; `Labels.sanitize`; `canRename`; selected sensor LIVE; cooldown. Writes through a live cover lookup using the §6.2 chain. On rejection the value resets. |
| `gs_rows` | GenericListSyncHandler<HubRow> | no | always 8 entries (padded) |
| `gs_header` | GenericSyncValue<HubHeader> | no | |
| `gs_detail` | GenericSyncValue<HubDetail> | no | includes `canEdit` |

- **No `registerSyncedAction`.**
- **Getters** return fields of a per-viewer `HubSession`. `HubViewModel.rebuild` runs only when `frame.sequence` or a session input changes.
- **DTOs** carry codes and numbers, and the client formats them with lang keys (`gregscope.state.<id>`). `statusText` is passed through, capped at 128.
- **Codecs** reject strings longer than the caps: displayName 40, statusId 64, statusText 128.
- **`canInteractWith`:** the MUI2 default distance check (`TileEntityGuiFactory.java:56-58`), plus an access re-check every 100 ticks. The GUI closes if access is lost.
- **`createScreen`** is `@SideOnly(CLIENT)` and returns `new ModularScreen("gregscope", panel)`.

---

## 10. OpenComputers API (backward compatible)

- **Unchanged:** schema v1, `getSnapshot()`, component names and priority.
- **Addresses:** v0.1 → v0.2 does **not** change addresses of existing machine Adapters (E3). GS-115 asserts this.
- **New table versions:** `sensorRecordVersion=1`, `historyVersion=1`.
- **Name collisions:** none. There is no `getSensor` or `getSensorHistory` in `oc/li/cil/oc/integration/gregtech/*`.

### 10.1 Sensor record
```
{ sensorRecordVersion=1, id="<uuid>", shortId="3fa2c1d0", label="", displayName="EBF North",
  owner="<uuid>"|nil, ownerName="Steve"|nil, availability="live|unloaded|over_cap|missing|in_item|removed",
  availabilitySince=<epochSec>, dimension=0, x=, y=, z=, side="east", kind="machine",
  metaName=, metaId=, machineName=, state="running"|"unavailable", statusId="running"|"machine_unavailable",
  lastSeen=<epochSec>|nil, lastSample=<epochSec>|nil, ageSeconds=<int>|nil, historyLoaded=true }
```
When there is no current snapshot, the record uses the v1-reserved `unavailable` and `machine_unavailable` (`gs/docs/snapshot-schema-v1.md`).

### 10.2 On the existing merged machine component
| Callback | direct | Returns |
|---|---|---|
| `getSensor()` | no (reads the live holder, as `getSnapshot` does) | sensor record for this machine's sensor, or `nil,"no sensor"` |
| `getSensorHistory(resolution, [count], [before])` | no | history table (§10.4) for this machine's sensor |

### 10.3 New component `gregscope_hub`
- Driver: `HubDriver extends DriverSidedTileEntity` for `TileTelemetryHub`.
- Environment: `HubEnvironment`, final, with callbacks declared directly (the same OC rule as v0.1). Priority 0.
- Scope: §5.

| Callback | direct | Returns |
|---|---|---|
| `getInfo()` | yes (limit 8) | `{apiVersion=2, schemaVersion=1, historyVersion=1, gregscopeVersion, owner, ownerName, visible, live, maxSensors, intervalTicks, secondsCapacity=300, minutesCapacity=1440, frameSequence, frameAgeSeconds}` |
| `listSensors([offset=0],[limit=32])` | yes (limit 8) | `{total, offset, sensors={record…}}`. Limit clamped to 1..64, sorted by id, read from the frame. |
| `getLatest(idOrPrefix≥8)` | yes (limit 8) | `record, snapshot` (the snapshot is the exact schema v1 map), or `record, nil` when unavailable, or `nil,"sensor not found"` / `nil,"ambiguous id"` |
| `getSensorHistory(id, resolution, [count], [before])` | **no** (rings live on the server thread) | §10.4 |

### 10.4 History table (`historyVersion=1`)
**Arguments:**
- `resolution` is `"second"` (count 1..300, default 60) or `"minute"` (count 1..240, default 60).
- `before` is an optional exclusive epochSec, defaulting to now.
- The window is `[before − count·step, before)`, oldest row first. To page back, pass the returned `from`.

**Result:**
```
{ historyVersion=1, id=, resolution="minute", step=60, from=, to=,
  rows = { {t=, samples=60, expected=60, coverage=1.0, lastState="running",
            stateSeconds={running=50, idle=10}, maintenanceMax=0, serverTicks=1200,
            euPerTickAvg=?, euPerTickMin=?, euPerTickMax=?, energyStored=?, recipesCompleted=?,
            gaps={"chunk_unloaded"} }, … },          -- only observed or partially observed minutes
  gaps = { {from=, to=, reason="server_offline"}, … } }   -- merged ranges per §7.5
```
- **Second rows:** `{t, state, active, allowedToWork, formed?, wasShutdown, progress, euPerTick?, energyStored?, maintenanceIssues}`.
- **Absent values** are missing keys, following the v1 rules; there are no nil holes in sequences.
- **Errors:** `nil,"bad resolution"`, `nil,"history loading"` (minutes only), `nil,"sensor not found"`.

**Example script:** `docs/examples/gregscope-hub.lua` lists problems and prints a 60-minute EU/t and uptime table that handles gaps. The gametest copies it byte for byte, as v0.1 does.

---

## 11. Commands (`/gregscope`, `CommandBase`, level 0; each subcommand checks its own permission)
| Subcommand | Access | Effect |
|---|---|---|
| `stats` | any | §6.3 stats |
| `list [live\|unloaded\|missing\|tombstones\|stale] [page]` | canView filter | 10 per page: shortId, availability, display name, dim/xyz, last seen. `stale` means UNLOADED for more than 7 days. |
| `info <idPrefix≥4>` | canView | the full record plus 5-minute and 24-hour summaries |
| `label <idPrefix> <text…>` | canRename, LIVE | §3.5. Empty text clears the label. |
| `purge <idPrefix>` / `purge --tombstones` / `purge --stale` | op | §4.3 |

- **An ambiguous prefix** lists up to 5 matches.
- **Output** uses `ChatComponentTranslation` keys and goes to the sender only. No INFO log line per call.

---

## 12. Recipes, textures, lang, config

### 12.1 Recipes (assembler only, in GregScope `postInit`)
**Machine Sensor (MV):**
```java
GTValues.RA.stdBuilder()
  .itemInputs(ItemList.Cover_ActivityDetector.get(1L), ItemList.Sensor_MV.get(1L),
      GTOreDictUnificator.get(OrePrefixes.circuit, Materials.MV, 1L),
      GTOreDictUnificator.get(OrePrefixes.plate, Materials.Aluminium, 2L),
      GTOreDictUnificator.get(OrePrefixes.cableGt01, Materials.Copper, 2L))
  .circuit(7).fluidInputs(SubstituteFluidStack.soldering(1 * HALF_INGOTS))
  .itemOutputs(new ItemStack(ItemMachineSensor.INSTANCE, 1))
  .duration(20 * SECONDS).eut(TierEU.RECIPE_MV).addTo(RecipeMaps.assemblerRecipes);
```
**Telemetry Hub (EV):** 8 items + circuit = 9, the assembler `maxIO` (`RecipeMaps.java:1265-1268`).
```java
GTValues.RA.stdBuilder()
  .itemInputs(ItemList.Hull_EV.get(1L), ItemList.Cover_Screen.get(1L), ItemList.Sensor_EV.get(1L),
      ItemList.Emitter_EV.get(1L), ItemList.Tool_DataStick.get(1L),
      GTOreDictUnificator.get(OrePrefixes.circuit, Materials.EV, 2L),
      GTOreDictUnificator.get(OrePrefixes.plate, Materials.Titanium, 4L),
      GTOreDictUnificator.get(OrePrefixes.cableGt01, Materials.Aluminium, 4L))
  .circuit(7).fluidInputs(Materials.SolderingAlloy.getMolten(2 * INGOTS))
  .itemOutputs(new ItemStack(BlockTelemetryHub.INSTANCE, 1))
  .duration(1 * MINUTES).eut(TierEU.RECIPE_EV).addTo(RecipeMaps.assemblerRecipes);
```
- **Rationale:** the sensor is the MV upgrade of GT's LV activity detector, so history is usable from MV through an OC Adapter. The Hub follows the §15 EV default.
- **Tier gating:** `TierEU.RECIPE_MV=120` and `RECIPE_EV=1920` (`TierEU.java:27-29`).
- **Verification:** a manual collision run on the real pack with `-Dgt.recipebuilder.debug.collision=true -Dgt.recipebuilder.debug.null=true`, plus an NEI check after NHCore's loadComplete removers.

### 12.2 Textures and lang
- **Textures:** `tools/gen_textures.py` (PIL, dev-only, not in the jar; the generated PNGs are committed). All 16×16 RGBA, original art (MIT):
  - `textures/items/machine_sensor.png`
  - `textures/blocks/iconsets/GREGSCOPE_SENSOR_OVERLAY.png` (E1)
  - `textures/blocks/telemetry_hub_front.png`, `telemetry_hub_side.png`, `telemetry_hub_top.png`
- **`assets/gregscope/lang/en_US.lang`:**
  - item, tile and itemGroup names;
  - `gregscope.tooltip.sensor.1..3` ("Attach to a GT machine or multiblock controller (sneak + right-click)", "Rename in an anvil to set a label", "Read-only: passes power, items, fluids and redstone");
  - `gregscope.state.<id>` ×10, `gregscope.gap.<id>` ×8;
  - `gregscope.hub.*`, `gregscope.cmd.*`, `gregscope.chat.*`.
- **Dedicated servers** load mod lang too (`FMLServerHandler.addModAsResource`), so chat translations work on the server.

### 12.3 Config `config/gregscope.cfg` (Forge `Configuration`; read in preInit; restart required)
```
sampling    { B:enabled=true  I:intervalTicks=20 {20,40,60,100}  I:tickBudgetMicros=1000 [100..5000] }
limits      { I:maxSensors=256 [16..1024]  I:maxSensorsPerTeam=64 [0..1024]  I:maxOpenHubViews=32 [1..256] }
history     { B:persist=true  I:removedRetentionHours=24 [1..168]  I:staleExpiryDays=30 [1..365]  I:ioQueueCapacity=4096 [256..65536] }
permissions { I:opLevel=2 [0..4]  B:renameRequiresOfficer=false }
hub         { I:renameCooldownSeconds=5 [0..300] }
# exporter { … }  reserved for v0.4; not read by v0.2
```
- Values out of range are clamped with one WARN.
- The config produces an immutable `Settings`.
- GTNHLib `@Config` is not used: it fills fields by reflection, and the config GUI is client-only.

---

## 13. Testing strategy

### 13.1 Horizon-QA facts the plan is built on
- **Warp does not advance server time** (E2). Tests that need the sampler or heartbeats either:
  - wait **real** ticks with bounded waits (a few smoke tests, ≤60 ticks each), or
  - use `GregScopeTestHooks`: `setClock(FakeClock)`, `sampleNow(uuid)`, `heartbeatNow(cover)`, `runIntervalNow()`, `flushIo()`, `overrideSettings(Settings)`.
- **Test hooks** are no-ops unless `-Dgregscope.testHooks=true`. GS-101 checks this property is set for the gametest `runServer` task. Fallback if it cannot be set: put the hooks in `src/gametest` in the same package, with package-private access into main classes.
- **GUI tests** call `buildUI` with a FakePlayer: `new PanelSyncManager(new ModularSyncManager(false), true)` and `new UISettings(RecipeViewerSettings.DUMMY)`. Tests look up the named handlers and call getters and server setters directly. They never call `detectAndSendChanges` or `sync`, because the container is null (`mui2/.../SyncHandler.java:245-250`).
- **Cover placement** goes through `CoverRegistry.getCoverPlacer(stack).placeCover(fakePlayer, stack, holder, side)`, the Matter Manipulator path.
- **Real restarts and real GUIs** are manual.
- **Suite size:** new gametests should add ≤60 s to the 300 s CI step. The benchmark is opt-in with `-Dgregscope.bench=true`.

### 13.2 Layers
- **Unit (plain JVM):** everything marked `[pure]`, with golden fixtures in `src/test/resources/fixtures/v1/`:
  - `.gsh`: valid, torn slot, bad header CRC, v2 header, wrong magic, UUID mismatch
  - `registry.dat`: valid, truncated gzip, v2
- **Horizon-QA:** batches `gregscope.sensor`, `.lifecycle`, `.sampler`, `.history`, `.hub`, `.oc2`, `.commands`, `.recipes`, `.safety`, `.bench`.
- **Manual:** GS-121 checklist.
- **Negative controls** (the v0.1 practice), each run once:
  - set `lets*` to false and confirm the transparency test fails;
  - remove the `blockExists` guard and confirm `unloadedChunkNotLoaded` fails.

---

## 14. Tickets (ordered)
Sizes: S ≤0.5 d, M 1-2 d, L ≈3 d. Every ticket ends with `./gradlew build` green and all v0.1 tests (unit + Horizon-QA) still passing.

### M1: Foundation
**GS-101 Deployment and lifecycle skeleton (S)**
- **Scope:**
  - Remove `acceptableRemoteVersions`; add `ClientProxy`; update the dependencies string and the dev deps.
  - Add all lifecycle handlers, `GregScopeConfig`/`Settings`, the `GregScopeTestHooks` gate, and the creative tab.
- **AC:**
  - The dedicated server boots in Horizon-QA.
  - The cfg is generated with the §12.3 keys, and out-of-range values are clamped with one WARN.
  - `-Dgregscope.testHooks=true` reaches the gametest JVM.
  - The jar contains no gametest classes.
- **Tests:** U `SettingsTest`. H `LifecycleTests.serverStarts`, `SafetyTests.clientProxyNotLoaded` (asserts `System.getProperty("gregscope.clientProxyLoaded")==null`). M a client without GregScope is rejected by the FML handshake.

**GS-102 Probe cost benchmark gate (S)**
- **Scope:** `ProbeBenchmarkTests` (opt-in) measures `PROBE.snapshot(te)` with 1,000 warm-up + 10,000 timed calls on:
  - LV Electric Furnace, idle and running;
  - a formed running EBF;
  - one formed TecTech multiblock.

  It logs p50/p99/max µs.
- **AC:** numbers recorded in `docs/testing.md`; the §6.3 rule applied to the defaults **before GS-108 merges**; a lean-probe subtask opened only if p99 > 100 µs.
- **Tests:** the benchmark itself; it asserts completion only.

**GS-103 Pure history and identity model (M)**
- **Scope:** `StateCodes`, `GapReason`, `Labels`, `SensorIdentity`, `SensorNbtCodec`, `SecondRing`, `MinuteSlot`, `MinuteAccumulator`, `MinuteRing`, `Crc16Ccitt`, `GapRanges`, `Summaries`, `SensorCounters`, `LogHistogram`, `Clock`.
- **AC:** byte-exact §7.3/§7.4 layouts; the §7.5 reader contract; §7.8 size constants asserted; no forbidden imports (grep test over the package sources).
- **Tests (U):**
  - `StateCodesTest`: every enum value mapped, and the codes pinned.
  - `LabelsTest`, ≥15 cases: § codes, controls, U+FEFF, surrogate pairs at the cap, >64 rejected.
  - `SensorNbtCodecTest`: round trip, `gs` missing leads to inert, `gs=2` preserved verbatim, unowned.
  - `SecondRingTest`: wrap, head order, same-second duplicates, newest-N/`before` queries.
  - `MinuteAccumulatorTest`: stateSamples saturation, min/avg/max with negative EU and absent EU, recipes reset flag, serverTicks, partial minute, merge rule.
  - `MinuteSlotCodecTest`: golden bytes, CRC vector, torn slot leads to a gap, stale epoch ignored.
  - `GapRangesTest`: runs-table attribution, merging, unknown.
  - `LayoutSizesTest`.

**GS-104 Access policy and team resolver (S)**
- **Scope:** `AccessPolicy`, `TeamResolver`, `GtnhlibTeamResolver` (map scan, per-rebuild cache).
- **AC:** §5 table exactly; no `getTeamByPlayer` anywhere in `src/main` (E4); no team IDs stored.
- **Tests:**
  - U `AccessPolicyTest` (truth table with a fake resolver, including `renameRequiresOfficer` and op-no-escalation); `NoErrorLoggingTeamLookupTest` (source grep).
  - H `TeamAccessTests`: create teams with `TeamManager.getOrCreateTeam(name, uuid)` for unique fake UUIDs, then `addMember`; assert `sameTeam`, and that no log line contains "Unable to find team" (Log4j test appender) for a random UUID.

### M2: Sensor, registry, sampler
**GS-105 Sensor item, cover registration, assets, lang (M)**
- **Scope:** `ItemMachineSensor`, `SensorCovers`, `isSupportedMte`, PNG generator + PNGs, `en_US.lang`, tooltips.
- **AC:**
  - `CoverRegistry.isCover(sensorStack)` is true.
  - The overlay name is `iconsets/GREGSCOPE_SENSOR_OVERLAY` and the PNG exists at `textures/blocks/iconsets/GREGSCOPE_SENSOR_OVERLAY.png`.
  - Placement predicate per §3.2.
- **Tests:**
  - U `AssetsExistTest`: every icon constant resolves to an existing 16×16 PNG under `textures/blocks/` + name, or `textures/items/`; every lang key constant exists.
  - H `SensorPlacementTests`: `placesOnLvMachine`, `placesOnEbfControllerSide`, `rejectedOnControllerFront`, `rejectedOnHatch`, `rejectedOnNonGtBlock`, `secondSensorOnSameMachineRejected`.
  - M overlay renders in a client.

**GS-106 `MachineSensorCover` behaviour (M)**
- **Scope:** §3.4 overrides, the `onPlayerAttach` owner and label chain, server-side gating.
- **AC:**
  - Covered faces pass EU, items, fluids and redstone.
  - `allowsTickRateAddition()==false`, and the holder's jackhammer path leaves the tick rate at 20 (E5).
  - `allowsCopyPasteTool()==false`.
  - UUID stable across chunk reload; `getDrops()` carries the UUID.
  - An unknown cover ID in NBT loads as no cover.
- **Tests (H `SensorCoverTests`):**
  - `transparentToEnergy`: a powered cable or battery buffer on the covered face raises stored EU.
  - `transparentToItems`: sided insert through the covered face.
  - `transparentToRedstone`: `letsRedstoneGoIn/Out` true.
  - `tickRateLocked`: assert `allowsTickRateAddition()==false`; if the helper supports it, also simulate a jackhammer right-click via `BaseMetaTileEntity.onRightclick` with a FakePlayer holding a jackhammer, and assert `getTickRate()==20`. Do **not** call `onCoverJackhammer` directly.
  - `copyPasteDisallowed`, `ownerIsMachineOwnerForFakePlayer`, `anvilNameBecomesLabel`.
  - `uuidStableAcrossChunkReload` (v0.1 `ChunkReload` helper).
  - `dropsCarryUuid`: `gt.covers` NBT holds `gs/idM/idL`.
  - `unknownCoverIdNbtLoadsAsNoCover`, `foreignDataBlobIsInert`.

**GS-107 Sensor registry state machine (L)**
- **Scope:** `SensorRegistryCore` (§4), the `SensorRegistry` adapter wired to the cover hooks, caps, OVER_CAP LRU, tombstones, housekeeping, reverse index.
- **AC:**
  - Every row of §4.3 is implemented.
  - The heartbeat fast path does no allocation and only one map lookup (code review plus a unit test using a counting map).
  - Caps are never exceeded; tombstones are excluded from caps and capped themselves.
- **Tests:**
  - U `SensorRegistryCoreTest`: one test per transition row; 3-strike MISSING; move from IN_ITEM/MISSING/REMOVED; duplicate with LIVE/UNLOADED re-keys the newcomer; position replaced; global and team cap with a merge scenario; OVER_CAP retry interval; expiry at 24 h / 30 d; tombstone eviction order; rebuild from an empty registry.
  - H `SensorLifecycleTests`, using `heartbeatNow`/`sampleNow` hooks:
    - `crowbarDropCoverMarksRemoved`
    - `survivalGetDropsMarksInItemAndReplaceResumes` (place a machine item with the NBT from `getDrops` elsewhere; the same UUID becomes LIVE at the new position)
    - `destroyBlockBecomesMissingAfterThreeSamples`
    - `chunkUnloadMarksUnloadedAndReloadLive`
    - `duplicateUuidRekeysNewcomer`
    - `facingChangeDropRemoves`
    - `globalCapGivesOverCap` (settings override, cap 16 with 17 sensors)

**GS-108 Sampler and TelemetryFrame (M)**
- **Scope:** `SamplerSchedule`, `TargetResolver`, `TelemetrySampler`, budget carry-over, stats, frame publish, `runIntervalNow`/`sampleNow` hooks.
- **AC:**
  - One sample per LIVE sensor per interval when there is no budget pressure.
  - Budget enforced with carry-over and `sampling_skipped`.
  - Never loads a chunk or initializes a dimension.
  - The frame sequence increases strictly, and the frame is immutable.
- **Tests:**
  - U `SamplerScheduleTest`: balance with 0/1/19/20/256/1024 sensors, least-loaded assignment after removals, fake nanoTime of 400 µs per sample with a 1 ms budget gives ≤3 per tick, skip after `interval` carries.
  - U `TelemetryFrameTest`: unmodifiable collections; source mutation does not leak.
  - H `SamplerTests`:
    - `realTicksSampleOncePerSecond`: real ticks, wait ≤60, samples 2..4.
    - `sampleMatchesProbe`: `sampleNow`, then compare `lastSnapshot` with `probe.snapshot(te)`.
    - `unloadedChunkNotLoadedBySampler`: unload, `runIntervalNow`×3, `chunkExists` still false.
    - `unloadedDimensionNotInitialized`: an entry with an unused dim ID gives UNLOADED, and `DimensionManager.getWorld(id)` stays null.
    - `tinyBudgetSkips`: `tickBudgetMicros=100` with 64 sensors and real ticks.
    - `statsCommandRuns`.

### M3: Persistence
**GS-109 File codecs, FileStore, I/O thread (M)**
- **Scope:** `HistoryFileCodec` (§8.2), `RegistryNbtCodec` + `RunsTable` (§8.3), `FileStore`/`NioFileStore` (atomic move with fallback, `.bak`), `HistoryIo` (§8.4).
- **AC:**
  - File size is exactly 92,224 B.
  - Corrupt, unsupported and mismatched files are renamed, never deleted.
  - `.bak` fallback works.
  - A full queue drops and counts.
  - Flush and join finish within 10 s; the thread is a daemon.
  - The server thread does no file I/O (`NioFileStore` asserts the thread name under `-ea`).
- **Tests:**
  - U `HistoryFileCodecTest` (fixtures), `NioFileStoreTest` (`@TempDir`, with a decorator that throws `AtomicMoveNotSupportedException`), `HistoryIoTest` (4097th task dropped, ordering, flush/join, daemon flag).
  - The registry NBT codec is tested in H: `RegistryCodecTests.roundTrip`, `v2Unsupported`, `truncatedGzipFallsBackToBak`, since the NBT classes need MC.

**GS-110 Persistence wiring, gaps, housekeeping (M)**
- **Scope:** saves on dim-0 world save and on stop; runs table; async load and merge; partial minutes at unload, removal and stop; retention and stale deletes; `history.persist=false`.
- **AC:**
  - Exactly one slot write per closed minute per sensor.
  - No writes while UNLOADED.
  - A simulated restart (service reset + reload from disk in the same JVM through a hook) restores the minute ring and registry.
  - Gap attribution follows §7.5.
- **Tests:**
  - U `PersistenceScenarioTest` over an in-memory `FileStore`: run 1 records 5 minutes, then stops; run 2 loads and history is equal; the gap between runs is `server_offline`; an unclean run uses `saved`.
  - H `HistoryTests` (FakeClock advanced 1 s per `sampleNow`, `runIntervalNow` at minute boundaries):
    - `threeMinutesWrittenToFile`: read back with the codec, samples==60, stateSamples sum.
    - `unloadClosesPartialMinuteWithChunkUnloaded`.
    - `noSlotsWhileUnloaded`.
    - `simulatedRestartRestoresHistory`.
    - `removedFileDeletedAfterRetention`: retention 1 h + clock jump.
    - `persistFalseWritesNoGsh`.
  - M a real server stop and start keeps 24 h of history, with a restart gap.

### M4: Surfaces
**GS-111 `/gregscope` command (M)**
- **Scope:** §11.
- **AC:** permission rules; prefix ambiguity; `label` writes cover NBT and refuses when unloaded without loading the chunk; `purge` op-only; no INFO log per call.
- **Tests:** U `CommandArgsTest`. H `CommandTests`: `labelPersistsAcrossChunkReload`, `labelDeniedForStranger`, `labelRefusedWhenUnloadedAndChunkStaysUnloaded`, `purgeRequiresOp`, `listFilteredByAccess`, `statsRuns`.

**GS-112 Hub block and tile (S)**
- **Scope:** §9.1.
- **AC:** registry names frozen; `canUpdate()==false`; owner set on place (null for FakePlayer); NBT round trip; unknown `gsHub` preserved; a denied activation opens nothing; the view cap is enforced.
- **Tests:** U `HubTileNbtCodecTest` (pure part). H `HubBlockTests`: `ownerSetOnPlace`, `nbtRoundTripAcrossChunkReload`, `strangerDenied` (after `onBlockActivated`, `player.openContainer == player.inventoryContainer`).

**GS-113 `HubViewModel` and DTOs (M)**
- **Scope:** scope filter, sort, Problems filter, paging, selection by UUID, 5-minute and 24-hour summaries, hourly strip, DTO codecs, rebuild throttle.
- **AC:**
  - Builds only from `TelemetryFrame` plus ring accessors passed in as arguments, with no `World`.
  - No rebuild when neither the sequence nor the inputs changed.
  - Strings capped; codecs round-trip and reject oversize input.
- **Tests (U):** `HubViewModelTest` (≥25 cases: sort order, padding to 8, page clamp after shrink, selection survives resort, coverage-weighted summaries, gap `?` in the strip, uptime with no coverage), `HubDtoCodecTest`.

**GS-114 `HubPanel` MUI2 GUI (M)**
- **Scope:** §9.2-9.3, `createScreen` client-only.
- **AC:**
  - `buildUI` succeeds on the dedicated server.
  - All named handlers are present.
  - Setters clamp and validate (page −5→0, 999→last; filter 7→0; label oversize, stranger or unloaded rejected; cooldown).
  - Getters return the same object reference between rebuilds.
  - No synced actions.
- **Tests:** H `HubGuiServerTests`: `buildUiOnDedicatedServer`, `pageSetterClamps`, `selectionResolvesUuid`, `labelSetterSanitizesAndWritesCover`, `labelSetterRejectsStranger`, `labelSetterRejectsOversize`, `rowsScopedToHubOwnerTeam`, `gettersStableWithinSequence`. M GS-121.

**GS-115 OC: machine component additions (S)**
- **Scope:** `getSensor`, `getSensorHistory` on `GregTechMachineEnvironment`; `LuaTables` builders.
- **AC:** v0.1 callbacks and output unchanged; **address unchanged** for an existing Adapter after the callbacks are added (E3); `nil,"no sensor"` without a sensor.
- **Tests:** U `LuaTablesTest` (record keys, absent-key rules, count clamps, `before` paging, gap ranges). H `OcMachineSensorTests`: `getSensorMatchesCoverUuid`, `noSensorNil`, `historyRowsOldestFirst`, `addressStableAcrossChunkReload` (reuses the v0.1 `ChunkReloadTests` pattern).

**GS-116 OC: `gregscope_hub` component and example script (M)**
- **Scope:** `HubDriver`, `HubEnvironment`, `docs/examples/gregscope-hub.lua`, a gametest copy step.
- **AC:**
  - The component name is `gregscope_hub`.
  - Direct callbacks read only the frame.
  - `listSensors` limit clamped to 1..64 with a stable order.
  - `getLatest` snapshot deep-equals `lastSnapshot.toMap()`.
  - Out-of-scope IDs return "sensor not found".
  - Bad resolution gives an error; history loading gives an error.
  - The script runs unmodified on OpenOS (Lua 5.3 and LuaJ).
- **Tests:** H `OcHubTests`: `componentPresent`, `listScopedByHubOwner`, `latestMatchesFrame`, `minuteHistoryPaging`, `unloadedSensorRecordNoSnapshot`. `HubExampleScriptTests`.

### M5: Recipes, readiness, release
**GS-117 Recipes (S)**
- **Scope:** §12.1.
- **AC:** both recipes in `RecipeMaps.assemblerRecipes` with EU/t 120/1920, durations 400/1200 ticks, ≤9 item inputs; no "OreDict entry is empty" log.
- **Tests:** H `RecipeTests.sensorRecipe`, `hubRecipeIsEv`. M pack collision run and NEI check.

**GS-118 Metrics model and frame contract (S)**
- **Scope:** `docs/metrics-model.md` (§16.2 mapping, identity and cardinality rules, gap and counter semantics); contract tests.
- **AC:** every `TelemetryFrame`/`SensorView` field is final (reflection in test code only); collections unmodifiable; reserved metric and label names match the Prometheus regexes; closed label sets are generated from the enums.
- **Tests:** U `TelemetryFrameContractTest`; U concurrency test (a reader thread checks invariants on 10k published frames).

**GS-119 Dedicated-server safety and GT API shape (S)**
- **Scope:** `GtApiShapeTests` asserts the `Cover` members GregScope overrides or calls, the `CoverPlacer.builder().onlyPlaceIf`, `Textures.BlockIcons.custom`, and the `ICoverable.getCoverAtSide` signatures (reflection in test code only); `SafetyTests` for the client-proxy marker and `buildUI` class loading.
- **AC:** passes in CI; a re-verification checklist for GT/MUI2/GTNHLib bumps is added to the handoff.
- **Tests:** these tests themselves.

**GS-120 Documentation (M)**
- **Scope:**
  - `README`: client **and** server install.
  - `handoff.md`: §12 v0.2 definition of done, §13 new budget (§6.3), §15 answers.
  - New `docs/sensors-and-hub.md`: placement, identity, lifecycle §4.3, permissions §5, caps, config, commands, mod removal §8.5.
  - New `docs/history-format-v1.md`.
  - `docs/opencomputers.md`: v0.2 section; addresses unchanged on upgrade.
  - `docs/testing.md`: new classes, GS-102 numbers, manual checklist.
  - Release notes.
- **AC:** every config key, NBT key, file field, command and OC callback in code appears in the docs (U `DocsCoverageTest` greps the docs for constants in `ConfigKeys`, `SensorNbtKeys`, `OcCallbacks`); each claim is marked tested or manual.

**GS-121 Manual client checklist and real-pack validation (M)**
- **Checks**, on `runClient` connected to `runServer`, then once on `gregscope-server/pack` with a client:
  - The overlay renders; pipes, cables and conveyors on the covered face still work.
  - Anvil label works.
  - Hub GUI: opening, paging, filter, selection, label typing and rejection for a stranger, "loading history…", gaps after a chunk unload.
  - A version-mismatched client is rejected.
  - NEI shows both recipes, and the collision run is clean.
  - After 2 h of play, stats show p99 ≤1 ms/tick and `ioDroppedTotal=0`.
  - A real restart keeps labels and 24 h of history, with a gap.
  - Mod removal on a copy of the world: it loads after the FML prompt, machines are intact, sensors are gone.
- **AC:** every item recorded with date and pack version in `docs/testing.md`.

**Suggested order:**
1. GS-101 → GS-102 (starts in parallel with GS-103/GS-104) → GS-105 → GS-106 → GS-107 → GS-108 (after the GS-102 decision) → GS-109 → GS-110.
2. GS-111, GS-112 → GS-113 → GS-114, and GS-115 → GS-116 (tracks may run in parallel).
3. GS-117 (any time after GS-105/GS-112) → GS-118 → GS-119 → GS-120 → GS-121.

**v0.2.0 definition of done:**
- All acceptance criteria met; unit and Horizon-QA suites green within the 300 s CI step.
- Transparency tests prove the sensor is read-only.
- No mixins, ATs or shipped reflection.
- Tests prove GregScope never loads a chunk or a dimension.
- Budget and memory caps enforced and visible in stats.
- The GS-121 manual checklist is signed off.

---

## 15. v0.3 flow meters: design-spike note (recommendation)

**Recommendation: approach 1, active metering covers that do the transfer themselves and count it.** Spike first (about 2 days) before committing.

**Rejected approaches:**
- **Approach 2 (hook at the GT pipe transfer boundary)** needs a mixin, which the no-mixin constraint forbids.
- **Approach 3 (instrumented hatches)** needs GT MTE IDs, the same permanent-ID problem as §9.1. Reconsider only after an upstream ID reservation.
- **Passive tank or inventory deltas** are not flow (handoff §14) and will not ship.

**Why approach 1 is feasible without mixins:**
- **Items:** GT's conveyor cover delegates to `GTItemTransfer extends com.gtnewhorizon.gtnhlib.item.ItemTransfer`, and `ItemTransfer.transfer()` returns `int` items moved (`gt5u/.../covers/CoverConveyor.java:37-51`; `javap` GTNHLib-0.11.46 `ItemTransfer`). A GregScope "Item Metering Conveyor" can count the **accepted** items exactly.
- **Fluids:** `GTUtility.moveFluid` returns `void` (E7, `GTUtility.java:1038-1064`). The metering pump must copy its three-step logic itself (drain-simulate → fill-simulate → drain+fill commit). Then **attempted** = simulated drain, **accepted** = committed fill. This answers §15 Q6: record both.

**Constraints and open decisions the spike must settle:**
1. **Read-only scope change.** A metering cover *moves* items and fluids, like GT's own pump and conveyor. That is a logistics action, not machine control, but it goes beyond v0.2's "passive" rule. **It needs explicit user sign-off before v0.3 starts.**
2. **One cover per side.** A metering cover replaces a pump or conveyor on that face, it does not stack with one. Check how it interacts with filters and regulators.
3. **Direction** comes from the IO mode (export/import), recorded as two counters.
4. **Tick cost:** the transfer runs in the cover tick, as GT's does, so the pump rate is the same as GT's pump at the same tier. Counting adds O(1). History uses the v0.2 sampler: it reads counters and does no transfer work in the sampler.
5. **Progression:** a tiered recipe per GT pump or conveyor tier, taking the GT cover as an input.

**Data-model hooks already in v0.2:**
- `sensorKind` (1 = item flow, 2 = fluid flow) in the registry and `.gsh` header.
- A new `slotLayout` = 2 or 3: 64 B minute slots with i64 `acceptedDelta`, i64 `attemptedDelta`, i64 `peakPerSecond`, the resource key index.
- Counters in `SensorCounters` with Prometheus `_total` semantics.

**Spike deliverables:**
- A prototype cover in a branch.
- Horizon-QA tests: exact item count against a chest delta with no competing I/O; fluid attempted ≥ accepted when the destination is full; counts stay correct when an adjacent pipe simultaneously inserts into the same tank (the case where deltas cancel).
- A tick-cost measurement.
- A written recommendation.

---

## 16. v0.4 Prometheus exporter: outline that v0.2 must support

### 16.1 Design (for v0.4; not in v0.2)
- **Server:** `com.sun.net.httpserver.HttpServer` from the JDK, no shading.
  - It resolves through the RFB `LaunchClassLoader`, whose parent is the platform loader (`jdk.httpserver`, exported without restriction; verified on Temurin 25). No JVM args are needed.
  - All `HttpServer` references live in one class loaded only when `exporter.enabled=true`. `LinkageError` is caught, and the exporter disables itself with one WARN.
- **Lifecycle:** start in `FMLServerStartedEvent` on dedicated servers only (by default); `stop(0)` in `FMLServerStoppingEvent` and on any failed start. The JDK dispatcher is **not** a daemon, so a missed stop hangs the JVM. Handlers run on a single daemon executor named `GregScope-Exporter`.
- **Config** (`exporter` category):
  - `enabled=false`
  - `bindAddress="127.0.0.1"`
  - `allowNonLoopbackBind=false` (two-key rule; a non-loopback bind logs a WARN)
  - `port=9941` (check against the Prometheus default-port wiki before release; 0 is allowed for tests)
  - `path="/metrics"`
  - `maxSensorsExported=512`
  - `maxResponseBytes=2 MiB`
- **Handler:**
  - GET/HEAD only (405 otherwise, 404 for other paths).
  - `Content-Type: text/plain; version=0.0.4; charset=utf-8`, exactly (Prometheus 3.x is strict).
  - The body is rendered from `TelemetryFrame` only and cached as `{sequence, byte[]}`.
  - Families are rendered one at a time (HELP/TYPE once per family, samples contiguous), with label escaping and a trailing newline.
  - Recommended `scrape_interval` is 5-15 s.
- **Tests:**
  - U: pure `PrometheusTextWriter` (escaping, family contiguity, NaN/Inf, name validation, golden file) and the HTTP wrapper on 127.0.0.1:0 (200/HEAD/405/404, Content-Type, cache, stop frees the port, non-loopback refused).
  - H: force-enable on port 0, place a sensor, `runIntervalNow` twice, scrape with 2 s timeouts, assert the series, stop.

### 16.2 Metric mapping (fixed now in `docs/metrics-model.md`, GS-118)
- **Identity:** the only series identity is `sensor_id`.
- **Descriptive info** appears only in `gregscope_sensor_info{sensor_id,sensor,machine,kind,dim} 1`.
- **Never used as labels:** statusId, statusText, item or fluid names, player names, coordinates.
- **Info and health:**
  - `gregscope_build_info{version,schema_version,history_version} 1`
  - `gregscope_sampler_last_publish_age_seconds` (from `publishedNanos`)
  - `gregscope_sampler_tick_micros{quantile="0.5|0.99|max"}`
  - `gregscope_sensors{state}`
  - `gregscope_exporter_scrapes_total`, `gregscope_exporter_render_seconds`
- **Per-sensor gauges** (emitted only when LIVE with a snapshot; `_last_sample_age_seconds` and `_availability{availability}` always emitted):
  - `gregscope_sensor_up`
  - `gregscope_sensor_state{state}` one-hot over the 10 pinned states
  - `_active`, `_allowed_to_work`, `_progress_ratio`, `_eu_per_tick`, `_energy_stored_eu`, `_energy_capacity_eu`, `_maintenance_issues`, `_formed`
  - `_recipes_completed` (GT's lifetime counter, exported as a gauge; dashboards use `resets()`-aware functions)
- **Counters:**
  - `gregscope_sensor_samples_total`
  - `_state_samples_total{state}`
  - `_gap_seconds_total{gap_reason}`
  - `_probe_errors_total`
  - `_eu_consumed_sampled_total` and `_eu_generated_sampled_total` (HELP text marks them as 1 Hz estimates)
  - global `gregscope_sampler_*_total`, `gregscope_io_*_total`
- **Cardinality bound:** at most about 32 series per sensor, so about 8k at the default of 256 and about 33k at the 1024 cap. `maxSensorsExported` caps it further.
- **What v0.2 guarantees for this:**
  - Immutable, volatile-published frames with process-lifetime counters.
  - Capped, sanitized strings.
  - Closed enums with stable IDs (`StateCodes`, `GapReason`, `SensorState`).
  - Gaps are never zero-filled.
  - The HTTP thread never needs rings, the registry, `World` or TileEntities.

---

## 17. Migration and compatibility
- **v0.1 → v0.2:** nothing to migrate; v0.1 stored nothing.
  - Schema v1 and `getSnapshot` are unchanged.
  - Machine Adapter addresses are unchanged (E3; asserted in GS-115).
  - **All clients must install v0.2.**
- **Frozen before the first public jar:** `gregscope:machine_sensor`, `gregscope:telemetry_hub` (block and TE). Any later rename goes through `FMLMissingMappingsEvent`.
- **Format versions:** cover `gs`=1, Hub `gsHub`=1, `registry.dat` `v`=1, `.gsh` formatVersion 1 / slotLayout 1, OC `sensorRecordVersion`/`historyVersion`=1, `apiVersion`=2.
  - Unknown newer versions are preserved (verbatim NBT, or renamed files) and never deleted.
  - Readers for version N−1 are kept, with fixtures.
- **Dependency bumps:** re-run GS-119 on every GT, MUI2 or GTNHLib bump. `gregtech.common.covers.Cover` is not an API package.

---

## 18. Risks and mitigations
| Risk | Mitigation |
|---|---|
| `Cover` lives in `gregtech.common.covers`, not the API package, and may change | GT pinned; all coupling in 2 classes; GS-119 shape tests; bump checklist |
| Unknown per-sample cost | GS-102 gate with pre-decided defaults and a lean-probe fallback |
| All cover heartbeats fire on the same tick | heartbeat O(1); sampling spread over buckets with a hard budget |
| Removals without callbacks (creative break, `setBlock`, explosion) | validation on every sample, 3-strike MISSING, tombstones, expiry |
| Duplicate UUIDs from NEI or creative copies | copy-paste opt-out; deterministic re-key; tested |
| Mandatory client install | FML version handshake; README and release notes |
| MUI2 `buildUI` class loading on a dedicated server | stock widgets only; client code in `createScreen`; server `buildUI` gametest |
| C2S abuse | 4 C2S values, all clamped or sanitized with length checks and access checks; no synced actions |
| GTNHLib ERROR log spam, team API churn | map-scan resolver; one adapter class; version range; grep test |
| Horizon-QA cannot warp the sampler, restart the server, or open GUIs | test hooks behind a system property; pure codec fixtures; manual checklist |
| Clock jumps, torn writes, disk stalls | clock-skew guard; per-slot CRC; atomic registry with `.bak`; bounded queue with drop counter |
| RAM at the maximum cap (≈106 MB at 1024) | default 256 (≈26.5 MB); ceiling logged at startup; config comment |
| Numeric item ID reused after mod removal | `gs` marker; GT catches exceptions; documented |
| Recipe collisions or NHCore removal | GS-117 collision run and NEI check |
| v0.3 metering covers stretch the "read-only" promise | explicit user sign-off gate in §15 |

---

## 19. Decision record (delegated questions)
| Question | Decision |
|---|---|
| Name | GregScope |
| Sensor form | GT cover, one per basic machine or multiblock controller, MV |
| Identity and label | UUID created at attach, stored in cover NBT; travels with a picked-up machine; crowbar ends the identity; label ≤32 code points set via anvil, Hub or command |
| Ownership | placer UUID (machine owner for FakePlayers); access = owner, GTNHLib team member, or op; teams resolved at check time by map scan; never stored |
| Caps | 256 global (lower if GS-102 requires), 64 per team, OVER_CAP rather than refusing placement |
| Cadence and budget | 1 Hz, 20 buckets, 1 ms/tick hard cap |
| History retention (§15 Q7) | GregScope files: RAM 5 min at 1 s; RAM and disk 24 h at 1 min; longer retention goes to Prometheus in v0.4 |
| Hub | EV plain Forge block, non-ticking TE, MUI2, scoped to the owner's team, no links |
| OC | schema v1 unchanged; `getSensor`/`getSensorHistory` on machines; `gregscope_hub` component |
| Recipes | assembler: sensor 120 EU/t, Hub 1920 EU/t |
| Config | Forge `Configuration` |
| Flow semantics (§15 Q6, v0.3) | record both attempted and accepted, via active metering covers, pending the spike and user sign-off |
| Official-pack ambition (§15 Q8) | private server addon first; the design avoids MTE IDs and mixins so it stays reviewable later |
