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
| Read-only, no machine control | **Kept, with one GT-wide exception** | Every `lets*` on the cover returns true. `isRedstoneSensitive` and `manipulatesSidedRedstoneOutput` return false. No synced actions. OC is read-only. The only writes are to GregScope's own data (label, purge). **Exception (GS-REV-4):** a covered side is excluded from `BaseMetaTileEntity.isRainExposed()` (`BaseMetaTileEntity.java:250-265`, which tests `hasCoverAtSide`; `Cover.isValid()` is `coverID != 0 && side != UNKNOWN`, so no cover can opt out), and that gates GT's rain fire and the rain/thunder explosions (`:506-550`, both on by default). A sensor on an exposed face therefore removes that face from GT's weather checks. This is not fixable in GregScope code and is identical for every GT cover (a conveyor or plate weatherproofs a face the same way), so it grants players no capability they do not already have; it is accepted for release and is on the GS-121 manual checklist. |
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
| `allowsCopyPasteTool()` | `false`. This covers the **copy** direction only (`BehaviourCoverTool.java:73-87`); the paste direction (`:88-99`) is gated on the numeric cover id alone and calls `ICoverable.updateAttachedCover` → `readFromNbt` on the live cover (`CoverableTileEntity.java:498-502`). `readDataFromNbt` therefore refuses to downgrade a cover that already has an identity (GS-REV-3). The Matter Manipulator is not in the pinned sources; no claim is made about it. |
| `hasCoverGUI()` | `false` in v0.2.0 |
| `isDataNeededOnClient()` | default (`false`). No GregScope packet data. |
| `getDescription()` | `"GregScope sensor <shortId>[: <label>] [<availability>]"` **on the server side only**. The availability comes from a transient field the registry sets. GT's only consumer is `CoverableTileEntity.getWailaBody` (`CoverableTileEntity.java:531-553`), which runs on the **client**, and the identity is never synced (`isDataNeededOnClient()` is false and `writeDataToByteBuf` is not overridden, so `GTPacketSendCoverData` carries only the tick rate). A client cover would therefore render every healthy sensor with the inert wording, so off the server side it returns the neutral `"GregScope sensor"` (GS-REV-1). The real status line for a player waits for the §1.2 v0.2.1 item "client-side cover label sync and a WAILA label". |

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
| LIVE | chunk loaded but the tile is missing, dead, not GT, or the cover at `side` has another UUID | `strikes++`; a `target_missing` second is recorded; at 3 the entry becomes MISSING. **The partial minute is closed with gap `target_missing` and queued first** (GS-107-02), exactly as the removal row does: it is an exit from LIVE, and a machine's last minute is the interesting one. |
| LIVE or UNLOADED | `onRemoved(DETACHED)` or `onRemoved(IN_ITEM)` | REMOVED or IN_ITEM. The partial minute gets gap `sensor_removed`, its slot is queued, RAM rings are freed. |
| tombstone | `now − stateSince > history.removedRetentionHours` (24) | expired: entry deleted, file deleted on the I/O thread |
| UNLOADED | `now − lastSeen > history.staleExpiryDays` (30) | expired. If the cover loads later, the heartbeat registers it fresh from cover NBT. **`lastSeen` is stamped on the unload itself** (GS-107-03): the heartbeat fast path deliberately reads no clock, and with `sampling.enabled=false` nothing else would ever refresh it, so the window would be measured from registration. |
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
1. Aggregates use observed samples only. There is **no interpolation or zero-filling**. Coverage = `samples / expectedSamples`, where `expectedSamples` is each stored slot's own value (the interval in effect when that minute was recorded) and, for a missing minute, the current `1200 / intervalTicks`.
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
- **Saving:** when dirty, on dim-0 `WorldEvent.Save` and on `FMLServerStoppingEvent`. **`seen` does not mark the registry dirty** (GS-107-04): a sample advances it every second, so marking dirty there would pin the flag true for ever and make it useless. GS-109's shutdown save therefore runs **regardless of `dirty`**, and a steady-state server persists `seen` at the latest on stop; a `seen` that is a few minutes stale after an unclean stop is accepted. The server thread serializes to `byte[]`. The I/O thread writes `.tmp`, copies the old file to `.bak`, then does `Files.move(ATOMIC_MOVE)`, falling back to `REPLACE_EXISTING` on `AtomicMoveNotSupportedException`.
- **Loading:** `registry.dat`, else `.bak`, else an empty registry with one WARN. The registry rebuilds itself from heartbeats, since identity lives in the covers.
  - `v > 1`: rename to `.unsupported-v<N>` and start empty.
  - LIVE is never persisted; every loaded non-tombstone entry starts as UNLOADED.
- **Runs:** on load, before appending the new run and before anything is saved, set `stop = max(start, saved)` on every run with `stop=0` (`GapRanges.Run.stopOnLoad`), using the `saved` that was loaded; then, on start, append `{start=now, stop=0}`; on stop, set `stop`. The unclean run's approximate stop time is the loaded `saved`; readers never use the registry's current `saved`, which moves past the downtime once the new process saves.

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
- `permissions.opLevel`: 1-4 means players on the server's ops list with at least that level (vanilla `canCommandSenderUseCommand` is false for every other player at any level); `0` means every player counts as op (`AccessPolicy.isOp` decides it without the game's check).
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
- **Horizon-QA:** batches `gregscope.sensor`, `.lifecycle`, `.lifecycle.hooks` (GS-101; alone because it changes process-wide settings), `.access` (GS-104), `.sampler`, `.history`, `.hub`, `.oc2`, `.commands`, `.recipes`, `.safety`, `.bench`. CI selects the whole `gregscope` namespace.
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
  - The heartbeat fast path does no allocation and only one map lookup **end to end, adapter included** (code review plus a unit test using a counting map; the counting-map test measures `SensorRegistryCore` only, so `SensorRegistry` must not look the entry up a second time - GS-107-05).
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
  - Review follow-ups: the WAILA tooltip of a healthy sensor reads "GregScope sensor", never the inert
    wording (GS-REV-1); a real player attaching a cover gets the player as owner and, over the cap, the chat
    line; and the accepted GS-REV-4 side effect is confirmed once - an outdoor machine whose exposed face
    carries a sensor no longer catches fire or explodes in a thunderstorm, exactly as it would with any other
    GT cover on that face.
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

---

## Implementation notes

### GS-101 (2026-09-17)
- **"Clamped with one WARN" (§12.3)** is implemented as one WARN per config load that names every adjusted key
  (`Settings.fromRaw`), not one WARN per key. `intervalTicks` has a set of allowed values, not a range, so "clamped"
  means snapped to the nearest allowed value, with a tie going to the larger interval (30 → 40). A value that does not
  parse falls back to its default and is part of the same WARN. Evidence: `SettingsTest`; a live run with
  `maxSensors=5`, `intervalTicks=30`, `persist=maybe` logged exactly one GregScope WARN and loaded 16 / 40 / true
  (`docs/testing.md`, GS-101 negative controls).
- **Forge rewrites unparseable values.** `Configuration.get(category, key, int|boolean, …)` replaces a value that does
  not parse as its type with the default and marks the file changed (Forge 10.13.4.1614 `net/minecraftforge/common/config/Configuration.java:180-191,298-310`),
  so such a value is rewritten on save. Out-of-range integers are left as the user wrote them and are clamped only in
  memory. `GregScopeConfig` reads the raw text before calling the typed getter, so the WARN still reports it.
- **Test hooks property.** `addon.gradle` sets `systemProperty("gregscope.testHooks", "true")` on the `runServer` and
  `runClient` tasks (RFG's `RunMinecraftTask` is a `JavaExec`, so this is independent of `--mcJvmArgs` and reaches CI's
  plain invocation). The §13.1 fallback was not needed. `LifecycleTests.testHooksReachGametestJvm` asserts it and failed
  with the line commented out. Hooks return `false` instead of silently doing nothing when disabled.
- **Client handshake** stays a manual check (GS-121), but `SafetyTests.handshakeRequiresMatchingClient` now asks FML's
  own `NetworkModHolder` for GregScope, the checker `FMLHandshakeServerState` uses via
  `FMLNetworkHandler.checkModList(client, Side.CLIENT)` (Forge 10.13.4.1614 `cpw/mods/fml/common/network/internal/FMLNetworkHandler.java:131-151`,
  `NetworkModHolder.java:54-63`): a client without GregScope, a vanilla client and a different version are rejected. It
  failed with `acceptableRemoteVersions="*"` restored.
- **Additions not named in §2:** `LifecyclePhase` (pure enum; `GregScope.phase()` lets `LifecycleTests.serverStarts`
  assert that every handler up to `serverStarted` ran) and `config/ConfigKeys` (pure key table with defaults, allowed
  values and comments; a natural source for GS-120's `DocsCoverageTest`).
- **Creative tab** `GregScopeCreativeTab` shows a vanilla comparator until GS-105 adds the sensor item.
  `getTabIconItem()` references no client class and is not annotated `@SideOnly(CLIENT)`, so
  `ShippedClassesTest.noClientClassReferences` needs no change in GS-101. `assets/gregscope/lang/en_US.lang` exists with
  only `itemGroup.gregscope`; GS-105 fills it.
- **Dependencies.** `ModularUI2:2.3.88-1.7.10:dev` and `GTNHLib:0.11.46:dev` are direct `implementation` dependencies;
  the GTNHLib entry left the constraints block (a direct dependency pins it), and MUI2 needs no constraint because GT
  5.09.54.133's pom asks for the same version. `LifecycleTests.serverStarts` asserts the loaded versions.
  `mcmod.info` lists `modularui2` and `gtnhlib` as well.

### GS-102 (2026-09-17)
- **§6.3 gate result: keep the defaults.** `ProbeBenchmarkTests` (opt-in, `-Dgregscope.bench=true`) measured
  `GregTechMachineProbe.snapshot(te)` with 1,000 warm-up + 10,000 timed calls per scenario, three runs, local
  i7-14700K, Java 8 dev server. Worst p99 was **16.3 µs** (running EBF); the others were ≤ 11.1 µs (TecTech Active
  Transformer), ≤ 4.8 µs (LV Electric Furnace idle) and ≤ 2.2 µs (running). p99 ≤ 50 µs, so `limits.maxSensors`
  stays 256 and no lean-probe subtask is opened. Full table and caveats: `docs/testing.md`, "Probe benchmark (GS-102)".
- **Worst single call.** One EBF call took 22.9 ms in one run (the other runs' maxima were 0.8-1.1 ms). It was not
  investigated; a GC or safepoint pause is the likely cause given a 16 µs p99. The §6.3 budget check happens before
  each sample, so such an outlier can overrun one tick's budget; GS-108's budget counters will measure it in practice.
- **Opt-in mechanism.** Every benchmark test calls `helper.assumeTrue(Boolean.getBoolean("gregscope.bench"), …)`
  first, so a normal run reports them as skipped (Horizon-QA `GameTestAssumptionException`), not failed or absent.
  The EBF scenario's template is still placed before the skip; that costs nothing measurable (normal full run: 51
  passed, 4 skipped, 44 s wall).
- **"One formed TecTech multiblock"** is the Active Transformer (`MTEActiveTransformer`, the smallest TecTech
  multiblock: 3x3x3). No TecTech Horizon-QA template ships in GT5U 5.09.54.133 (only `electric_blast_furnace`), so the
  test places the controller like a player, builds the cube behind its facing with `World.setBlock` and places an EV
  energy hatch. It needs an empty 5x3x5 template (`gregscope:empty_5x3x5`, gametest resources only): without a
  template a Horizon-QA cell is one block high and the isolation check failed the test for GT tiles above it.
  TecTech's `onFirstTick_EM` disables a controller that is not yet formed on its first tick (always the case here),
  so the test re-enables it after the startup check formed it. It then reads `waiting` / `no_routing` because no EU
  is supplied; a routing transformer was not measured.

### GS-103 (2026-09-17)
- **Classes.** All §14 names are implemented as `[pure]` classes: `model/StateCodes`; `history/GapReason`, `SecondRing`,
  `MinuteSlot`, `MinuteAccumulator`, `MinuteRing`, `Crc16Ccitt`, `GapRanges`, `Summaries`; `sensor/Labels`,
  `SensorIdentity`, `SensorNbtCodec`; `sampling/SensorCounters`, `LogHistogram`, `Clock`. Added and not named in §14:
  `sensor/KeyValue` (the §2 "KeyValue seam"; GS-105's cover adapts `NBTTagCompound` to it), `history/MinuteSource`
  (read access passed to `GapRanges`/`Summaries`, implemented by `MinuteRing`, so GS-113 can pass ring accessors as
  arguments), `history/SizeCeilings` (§7.8 constants and the startup INFO line text), `history/LongMath`
  (package-private saturating and big-endian helpers) and `sampling/FakeClock` (§6.2, used by later test hooks).
  `PureSourcesTest` enforces the `[pure]` rule by source grep and requires every file in `history/`, `sensor/` and
  `sampling/` to be `[pure]` unless listed as an MC adapter, so GS-105/108/109 must list theirs explicitly.
  `ShippedClassesTest` only gained five expected-class entries; none of its checks changed.
- **§7.4 merge rule, fields the design does not name.** `expectedSamples` and `maintenanceMax` take the larger value;
  `lastStateCode` comes from the newer piece only if it has samples, and `energyStoredLast` only if present (otherwise
  the older value); a `recipesCompletedDelta` of −1 on one side yields the other side's value.
- **EU values.** Averages round half away from zero and are computed exactly (`BigInteger`), so `Long.MAX_VALUE` inputs
  do not overflow. `Long.MIN_VALUE` is the "none" sentinel, so a sample of exactly `Long.MIN_VALUE` is stored as
  `MIN_VALUE+1`. With `euSamples == 0`, `euPerTickMin` and `euPerTickMax` are also written as `Long.MIN_VALUE` (§7.4
  defines the sentinel only for the average).
- **Slot validity.** Besides `epochMinute != 0`, the CRC and the window, `MinuteSlot.decode` also rejects a CRC-valid
  slot that slotLayout 1 cannot hold (state code ≥ 10, gap bits 6-7, undefined flag bits, recipes delta < −1). Reserved
  bytes are written as 0 but not checked. `MinuteRing` keeps the encoded 92,160 B and checks CRC and window on every
  read; `mergeLoaded` implements "slots already in RAM win" (§8.4) and ignores a valid slot at the wrong index.
- **Accumulator semantics.** `partialMinute` is set when a minute is closed early (`closePartial`: unload, removal,
  stop), not for a minute opened late. `serverStartMinute` is set on the minute passed in as the run's start minute.
  The §6.2 clock-skew rule is enforced in `MinuteAccumulator`: with no open minute, a sample or gap older than the
  newest closed or loaded minute is dropped and counted (`clockSkewRefused()`; GS-108 maps it to
  `clockSkewRefusedTotal` and the WARN); the same minute may reopen and `MinuteRing.put` merges the pieces. The first
  `recipesCompleted` seen in a process only sets the baseline (delta 0), because GT's counter has no earlier value.
- **§7.5 reader contract.** `GapRanges` returns ranges in epoch seconds, `from` inclusive and `to` exclusive, sorted by
  `from`, merged per reason; a stored gap minute with several reasons yields one range per reason, and one with an
  empty mask yields `unknown`. A missing minute is "inside a run" if it overlaps the run at all. "UNLOADED since a time
  ≤ that minute" compares the minute containing `since` with the missing minute. Unclean runs end at the `saved` loaded at the next
  start (never before their own start; see "Review fixes" below); the current run ends at now. The caller clips the window (not before the sensor's creation,
  not including the open minute). Rule 2 is implemented literally: a slot with samples but `lastStateCode == 0` is a
  gap. `Summaries` uses observed samples for state fractions (NaN when none) and the whole window for coverage, with each
  stored slot's own `expectedSamples` (see "Review fixes" below).
- **Labels (§3.5).** Implemented in the listed order, which has two visible effects: tab and newline are ISO controls,
  so they are removed rather than turned into spaces (`"a\tb"` → `"ab"`); and a `§` removes the following code point
  whatever it is. "Whitespace" is `Character.isWhitespace || Character.isSpaceChar` (so NBSP and U+3000 collapse). A
  trailing space exposed by the 32-code-point cut is trimmed, which keeps `sanitize` idempotent. The display name is
  the label, else `<snapshot name or metaName> #<shortId>`, else `#<shortId>`.
- **Cover NBT (§3.3).** `gs` is read as an unsigned byte (so 200 is "unsupported", not "foreign"); `gs=0` and `gs=1`
  without both id longs are inert. A compound read as unsupported is never modified by the codec; writing it back
  unchanged is the cover's job (GS-105). Half an owner UUID reads as unowned; an owned identity without `owN` has an
  empty owner name.
- **`LogHistogram`.** 16 exact buckets for 0..15, then 16 linear sub-buckets per power of two (reported quantiles are
  at most 1/16 high, capped at the exact max); `Long.MAX_VALUE` lands in bucket 959 of the 1,024. The 60 s window is
  kept by the caller (`copyFrom` + `clear`).
- **§7.8 check.** All table values follow from the layouts (asserted in `LayoutSizesTest`). One nit: the registry row's
  "<64 KB" at 256 sensors is exactly 64,000 B for the ≈250 B raw estimate; the gzipped file is what the row describes,
  so no change.
- **Fixtures.** `src/test/resources/fixtures/v1/*.hex` (minute slot valid/torn/stale, second sample, gap second) were
  generated by an independent Python script (`tools/gen_fixtures.py`, committed), not by the Java encoder. A negative control showed why: with a wrong CRC
  initial value, the Java round-trip and torn-slot tests still passed and only the golden-fixture tests failed
  (`docs/testing.md`, GS-103 negative controls).

### GS-104 (2026-09-17)
- **GTNHLib 0.11.46 team API matches §5.** Checked with `javap` on `GTNHLib-0.11.46-dev.jar` and the sources:
  `TeamManager.getTeamMap()` (unmodifiable view of a `HashMap<UUID, Team>`), `Team.isMember/isOfficer/isOwner(UUID)`,
  `addMember/addOfficer/addOwner`, `TeamManager.getOrCreateTeam(String, UUID)`, `mergeTeams(surviving, consumed)`.
  Owners are always officers and members, officers always members (`Team.java` addOwner/addOfficer).
- **E4 also covers `getOrCreateTeam`.** It calls `getTeamByPlayer` first, so it logs `Unable to find team for player`
  for every new player (`gtnhlib/.../teams/TeamManager.java` getOrCreateTeam). `NoErrorLoggingTeamLookupTest` therefore
  forbids `getTeamByPlayer`, `getOrCreateTeam` and `getTeamId` (no team ID is read, so none can be stored) anywhere in
  `src/main`. `TeamAccessTests` still creates its teams with `getOrCreateTeam` as §14 says, and uses that ERROR line as
  the positive control for its Log4j appender (observed once per new team; a direct `getTeamByPlayer(random)` also
  logs once).
- **API shape (not named in §5).** `TeamResolver<T>` is generic over the team handle (`teamOf`, `isMember`,
  `isOfficerOrOwner`, default `sameTeam`), so `AccessPolicy` stays pure. A pure `Viewer` carries the player UUID and an
  `IntPredicate` permission check (adapters pass `level -> sender.canCommandSenderUseCommand(level, "gregscope")`), or
  is `Viewer.CONSOLE` (always op). "op" is `hasPermissionLevel(permissions.opLevel)`. `AccessPolicy` methods, one per
  §5 row: `canOpenHub`, `inHubScope` (GUI and OC scope; it takes no viewer, which is how op no-escalation is
  guaranteed), `canRename`, `canView` (list/info/stats), `canPurge`. Adjacency rows stay with the OC adapters.
- **Rules where §5 leaves a detail open.** Null UUIDs are never in the same team (`sameTeam(null, x)` is false). With
  `renameRequiresOfficer=true` only the same-team case is limited; the owner and ops are not. The team checked for
  rename is the viewer's own team (`teamOf(viewer)` must contain the owner, and the viewer must be its officer or
  owner), the same team `sameTeam(viewer, owner)` looks at. An unowned Hub can be opened by ops only (the table); its
  scope is empty.
- **Cache.** `GtnhlibTeamResolver` caches `teamOf` results, including "no team", per instance until `clearCache()`;
  membership and role checks read the live `Team`. Callers make one resolver per Hub rebuild or sampler-frame
  sequence. `TeamAccessTests.cacheLastsOneRebuildAndTeamChangesApplyNext` shows a join, leave and merge applying on the
  next resolver.
- **GT5U crash found while testing merges (test-only impact).** `TeamManager.mergeTeams` calls `ITeamData.mergeData`,
  and GT's `GTPowerfailTracker.PowerfailData.mergeData` queues the surviving team's ID; at the end of the tick
  `onPostTick` looks the ID up, and if the team is gone it treats the ID as a player and throws a
  `NullPointerException` in `sendPlayerPowerfailStatus`, crashing the server (GT5U 5.09.54.133
  `gregtech/common/data/GTPowerfailTracker.java:288-293,327-338,388-398`; observed in a local run, crash report
  `run/server/crash-reports/crash-2026-09-17_02.25.12-server.txt`). It needs a surviving team to be removed in the same
  tick, which GTNHLib's own API never does; the gametest's cleanup did. The merge test now uses registered teams
  without team data (`new Team(name, id, false)` + `TeamManager.addTeamDeduplicated`), which skips `mergeData`.
  GregScope never merges or removes teams.
- **Pure-rule tests updated on purpose.** `PureSourcesTest` adds `access/` to the pure packages with
  `access/GtnhlibTeamResolver.java` as its one listed adapter; `ShippedClassesTest` only adds two expected classes.
- **Not wired yet.** No shipped code calls `AccessPolicy` or the resolver; the registry team cap (GS-107), commands (GS-111)
  and the Hub (GS-112 to GS-114) will.

### Review fixes after GS-101 to GS-104 (2026-09-17)
- **Coverage uses each slot's stored `expectedSamples` (DC-1).** `Summaries.minutes` used `minutes x` the caller's
  current `expectedSamplesPerMinute`, but `intervalTicks` is restart-only and history outlives restarts, so a window can
  mix intervals: 12 h fully observed at 20 ticks then 12 h at 100 ticks with half missing read as 100 % coverage (capped),
  and the reverse change with nothing missing read as 60 %. Now the denominator adds each stored slot's
  `expectedSamples` (§7.4, the value in effect when it was recorded; merge keeps the larger) and the current value only
  for missing minutes or a stored 0. §7.5 rule 1 now says so. `SummariesTest.coverageUsesEachSlotsStoredExpectedSamples`
  mixes 60 and 12; it failed with the old denominator (expected 156, was 60).
- **Unclean runs are closed on load (F2).** §8.3 had one registry-wide `saved` and told readers to end unclean runs at
  it. That is only right for the latest run: once the next process saves, `saved` lies after the downtime and an older
  crashed run would seem to cover the outage, turning `server_offline` minutes into `unknown`/`chunk_unloaded`. §8.3 now
  closes every `stop=0` run at the loaded `saved` before the new run is appended (`GapRanges.Run.stopOnLoad`, for GS-110
  to call), and `Run.resolve(start, stop, ongoing, now)` no longer takes `saved`; a non-ongoing `stop=0` that skipped the
  load step resolves to an empty run (claims no coverage). `GapRangesTest.uncleanRunFollowedByALaterRunKeepsTheOutageOffline`
  covers crash, downtime, restart and a later save. GS-110's `PersistenceScenarioTest` item "an unclean run uses `saved`"
  means the loaded `saved`.
- **`permissions.opLevel=0` means every player (F1).** In the pinned 1.7.10 source,
  `EntityPlayerMP.canCommandSenderUseCommand` returns false for a player not on the ops list before comparing levels
  (only `seed` on non-dedicated, `tell`, `help`, `me` are exempt), so the adapter `Viewer` recommends could never make
  non-ops count as op and 0 behaved like 1. `AccessPolicy.isOp` now returns true for `opLevel <= 0` without asking the
  check; 1-4 keep vanilla semantics. The config comment, §12.3 and the `Viewer` Javadoc say so. `AccessPolicyTest`
  pins it (a player whose check is always false is op at 0, is not asked, and is not op at 1); the earlier row "0 makes
  level-0 players ops" encoded the unreachable reading and was replaced.
- **Batch names (DC-2).** `gregscope.lifecycle.hooks` (GS-101) and `gregscope.access` (GS-104) are added to §13.2.
- **`[pure]` enforcement strengthened (PURE-1, PURE-2).** `PureSourcesTest` now also (a) walks pure packages
  recursively, (b) allows only `java.*` and `[pure]` GregScope imports, (c) rejects a `[pure]` file that names a listed
  adapter by simple name in code (a same-package use needs no import), (d) extends the denylist with GT5U's other root
  packages (`bartworks`, `bwcrossmod`, `detrav`, `galacticgreg`, `ggfab`, `goodgenerator`, `gtneioreplugin`,
  `gtnhintergalactic`, `gtnhlanth`, `kekztech`, `kubatech`, `toxiceverglades`), `org.lwjgl`, `io.netty` and
  `com.google`, and (e) reads the compiled `[pure]` classes (inner classes included) and requires every class in their
  constant pool, descriptors and signatures to be `java/*` (not `java/lang/reflect`) or a GregScope class whose source
  is `[pure]`. Check (e) immediately found that `[pure]` `model/StateCodes` depends on the v0.1 enum
  `model/MachineState`, which was not marked; it has no imports and is now marked `[pure]`. A negative control (a
  same-package `GtnhlibTeamResolver.class` reference in `AccessPolicy`) failed (b)/(c) and (e) while the old denylist
  test still passed.
- **Fixture generator committed (FIX-1).** `tools/gen_fixtures.py` (Python 3, standard library) regenerates
  `src/test/resources/fixtures/v1/`; the regenerated files differ from the previous ones only in their header comments,
  which now name the script. GS-109's `.gsh`/registry fixtures should be added to it, not produced by the Java encoder.

### GS-105 (2026-09-17)
- **Classes.** `sensor/ItemMachineSensor` (`gregscope:machine_sensor`, unlocalized `gregscope.machine_sensor`, icon
  `gregscope:machine_sensor` via `setTextureName`, stack 64, tab `gregscope`), `sensor/SensorCovers` (item registration in
  preInit, cover registration in init exactly as §3.2, the placement predicate, `sensorStack()`) and a first
  `sensor/MachineSensorCover`; all three are listed as intended adapters in `PureSourcesTest.IMPURE`. Added and not
  named in §2: the `[pure]` `GregScopeAssets` (registry, icon and lang key names; `AssetsExistTest` finds its
  `BLOCK_ICON_*`, `ITEM_ICON_*` and `LANG_*` constants by naming convention, so later tickets add a constant and get the
  check for free) and the design-v0.3 GS-201 hooks below. The creative tab icon is now the sensor item.
- **`isSupportedMte` is `public static`, not package-visible.** §2 says "package-visible static", but its caller
  `SensorCovers` lives in `sensor/`, not `probe/`. The method body is the v0.1 `isSupported` unchanged
  (`MTEBasicMachine || MTEMultiBlockBase`; `null` is false), and `supports`/`snapshot` call it.
- **The cover is read-only from the commit that makes it placeable.** GS-105 is the first ticket after which a player
  can attach the cover, and GT's `Cover` defaults (`lets*` false, copy-paste allowed, tick-rate addition allowed) would
  block energy, items, fluids and redstone on that face. So the §3.4 flag overrides land here: every `lets*` true,
  `isRedstoneSensitive`/`manipulatesSidedRedstoneOutput` false, `allowsCopyPasteTool`/`allowsTickRateAddition`/
  `hasCoverGUI` false. `SensorPlacementTests` asserts the flags; the behavioural transparency tests stay in GS-106.
  Everything else in §3.4 is GS-106: cover NBT (`identity()` is `null`, i.e. inert), `onPlayerAttach`,
  `getMinimumTickRate()=20` and the heartbeat, removal/unload hooks, `getDescription`. Until then the minimum tick rate is
  GT's default 0, so GT never calls `doCoverThings` on it (`CoverableTileEntity.java:184-193`).
- **design-v0.3 GS-201 hooks that GS-105 touches.** A2: `[pure]` `sensor/SensorCover { SensorIdentity identity(); int
  sensorKind(); }`, implemented by `MachineSensorCover` with kind 0; `[pure]` `sensor/SensorKind` pins the codes 0/1/2
  and the "kind label" (`machine`, `item_flow`, `fluid_flow`, else `unknown`) for §10.1 `kind=` and the exporter, and
  `isSupported` (v0.2: machine only), for GS-107/108/109 to use. A4: the one-per-machine check is
  `instanceof MachineSensorCover`, never `SensorCover`; `SensorPlacementTests.machineSensorAllowedBesideOtherCovers` (the
  GS-201 Horizon-QA test) proves a GT conveyor and a kind-2 `SensorCover` stub do not block a Machine Sensor, and a
  negative control with `instanceof SensorCover` failed it. A1 (reverse index keyed by position and side), A3
  (`RegistryNbtCodec` keeping unknown kinds) and A5 (`SlotLayout`) are not touched by GS-105; they belong to
  GS-107, GS-109 and the history refactor.
- **`placeCover` checks nothing (test finding).** `CoverPlacer.placeCover` only builds and attaches the cover
  (`gt5u/gregtech/api/covers/CoverPlacer.java` placeCover); the predicate and the machine's face rule are applied by the
  caller, e.g. `BaseMetaTileEntity.onRightclick` (`BaseMetaTileEntity.java:1565-1572`: no cover on the face,
  `isCover`, `isCoverPlaceable`, `allowCoverOnSide`). A test that only called `placeCover` as §13.1 literally says would
  place a sensor anywhere, so the §13.1 helper applies that same gate before `placeCover`. The Matter Manipulator is not
  in the pinned sources, so whether it applies the same gate was not verified; the design's "the Matter Manipulator
  path" is kept as the name of the helper, not as a claim about that mod.
- **GT ignores clicks on a tile that has never ticked.** `BlockMachines.onBlockActivated` returns false while
  `getTimer() < 1` (`gt5u/gregtech/common/blocks/BlockMachines.java:423-425`). A freshly placed or template machine in a
  Horizon-QA cell has not ticked, so a FakePlayer right-click is silently ignored and every "rejected" assertion would
  pass vacuously (observed: the first run failed `placesOnLvMachine` with "GT did not handle the sneak right-click").
  The player-path helper now warps the cell 2 ticks first and every click asserts that GT handled it.
- **Client-only members on the server.** `Item.getCreativeTab()` and `Item.addInformation` are `@SideOnly(CLIENT)` in
  1.7.10, so the server-side test does not call `getCreativeTab()`; `ItemMachineSensor.addInformation` overrides the
  client method without the annotation (allowed by `ShippedClassesTest`), which keeps it callable on a dedicated server,
  where the test checks the three tooltip lines. §12.2's claim that dedicated servers load mod lang holds: the English
  item name, tooltips and `gregscope.state.running` resolve in the Horizon-QA server.
- **Textures without PIL.** §12.2 names PIL; `tools/gen_textures.py` uses only the standard library (`struct`, `zlib`),
  like `tools/gen_fixtures.py`, so no extra install is needed. It writes only the GS-105 textures
  (`items/machine_sensor.png`, `blocks/iconsets/GREGSCOPE_SENSOR_OVERLAY.png`, both 16x16 RGBA); GS-112 adds the Hub
  textures to the same script. Two runs gave byte-identical files.
- **Lang.** GS-105 fills the item name, `gregscope.tooltip.sensor.1..3` (the §12.2 texts), `gregscope.state.<id>` x10
  and `gregscope.gap.<id>` x8 (derived from `MachineState`/`GapReason` ids in `AssetsExistTest`). The tile name and
  `gregscope.hub.*`, `gregscope.cmd.*` and `gregscope.chat.*` keys come with their tickets.
- **`ShippedClassesTest` lifted on purpose (§1.4 "no items").** `GameRegistry` stays a forbidden reference except in
  `sensor/SensorCovers`, which must reference it and may use only `registerItem` (plus GT's `registerCover`) among
  `register*` names; `registerWorldGenerator`, `registerTileEntity`, `registerTileEntityWithAlternatives` and
  `registerBlock` are now forbidden member names everywhere (GS-112 lifts tile entity and block registration).
  `IdleCostTests` still passes with the item registered.
- **Doc fix.** `docs/testing.md` said a suite where every batch times out takes 2100 ticks; recounted from the sources
  (longest `timeoutTicks` per batch, 17 batches with the new `gregscope.sensor`) it is 2800 ticks (140 s), still inside
  the 300 s CI step.

### GS-106 (2026-09-17)
- **Classes.** `sensor/MachineSensorCover` gains everything §3.4 lists except the registry call itself: cover NBT over
  `SensorNbtCodec`, `onPlayerAttach`, `getMinimumTickRate() = 20`, the heartbeat, the unload/removal hooks and
  `getDescription`. Added and not named in §2: the adapter `sensor/NbtKeyValue` (the `NBTTagCompound` side of the
  GS-103 `KeyValue` seam), the adapter `sensor/SensorEvents` (below) and the `[pure]` `sensor/SensorDescription` (the
  description text, so it is unit-testable). `SensorIdentity.forAttach` holds the §3.4 owner chain, also for the unit
  tests. `PureSourcesTest` lists the two new adapters on purpose; `ShippedClassesTest` only gained three
  expected-class entries and no check changed.
- **A seam instead of a direct registry call (§3.4).** §3.4 says `doCoverThings` calls
  `SensorRegistry.heartbeat(this, holder)`, and §4.3 names `onUnloaded`/`onRemoved`. `SensorRegistry` is GS-107, so
  GS-106 raises the same four events through `SensorEvents` (`heartbeat`, `unloaded`, `detached`,
  `destroyedIntoItem`), which `SensorCovers.events()` holds and GS-107's registry installs with
  `SensorCovers.setEvents`; `SensorEvents.NONE` is installed until then, and `GregScope.serverStopped` clears it, so a
  single-player world switch cannot keep a stale registry. The two removal methods are named after §4.3's causes
  (DETACHED, IN_ITEM) rather than taking a `RemovalCause`, because that enum belongs to GS-107's `registry` package.
  Every event passes the cover as the §5.1 A2 `SensorCover`, plus the holder and the cover side separately, which is
  what design-v0.3 §5.1 A1 needs to key its reverse index on `(position, side)` and what A2 needs to accept a v0.3
  meter cover raising the same events. `GregScopeTestHooks.setSensorEvents` (gated like every hook) lets
  `SensorCoverTests` watch the events before the registry exists.
- **Clock.** §6.2 wants every timestamp to come from `Clock`, so `ct` does too: `GregScope.clock()` (system clock by
  default, reset in `serverStopped`) with the §13.1 `setClock` hook, which GS-108 will reuse. `SensorCoverTests` pins
  `ct` with a `FakeClock`.
- **A picked-up sensor keeps its identity, so `onPlayerAttach` never overwrites data.** GT builds the cover, attaches
  it and *then* calls `onPlayerAttach` (`gt5u/gregtech/api/covers/CoverPlacer.java` placeCover), and a cover restored
  from a machine's item NBT is built by `CoverRegistry.buildCoverFromNbt` instead. `onPlayerAttach` therefore returns
  early when the cover already has an identity or unsupported data, so no code path can mint a second UUID over an
  existing one.
- **Unknown keys in `d` are preserved, not just an unsupported version.** §3.3 only requires writing a `gs > 1`
  compound back unchanged. The cover keeps the whole compound it read and writes the identity into a copy of it, so a
  third party's (or design-v0.3's `io`) key in `d` survives a save even for a valid v0.2 sensor. A non-compound `d`
  (for example a bare int left by another mod that reused the numeric item ID) is kept verbatim and the cover is inert.
- **Owner names are capped, GT's are not.** `IGregTechTileEntity.getOwnerName()` returns the placing player's display
  name unchanged, while §3.3 caps `owN` at 16 UTF-16 units. The dev FakePlayer name `gregscope-gametest` is 18
  characters, so the cached name is the first 16; the gametest compares against the capped value. `getOwnerName()` also
  returns the literal `"Player"` for an unset name (`BaseMetaTileEntity.java:1805-1809`), so an owned sensor can carry
  that as its cached name; the owner UUID is what identity and access use.
- **Transparency is proven by real transfers, not by the flags (§14).** GT consults a cover through
  `isEnergyInputSide`, `canInsertItem`/`getAccessibleSlotsFromSide`, `fill`/`canFill` and
  `getInternalInputRedstoneSignal`, and GT's own defaults for an *uncovered* face come from `CoverNone`, which returns
  true for every `lets*`. So each transparency test first shows the resource moves with no cover, then attaches the
  sensor and moves it again: a transformer charging the machine through the covered face, a vanilla hopper inserting
  cobblestone through it, 1,000 L of water filled through it, and a redstone block read back through it. Two GT
  details shaped the setup: a basic machine refuses items and fluids on its main facing and its front facing whatever
  the cover says (`MTEBasicMachine.allowPutStack`, `isLiquidInput`), so the tests set both facings before attaching the
  cover (a facing change drops covers, `CoverableTileEntity.checkDropCover`); and GT only refreshes its EU I/O faces
  after the holder's 20th tick (`BaseMetaTileEntity.java:425-447`), so the energy test warps 25 ticks before asserting
  `inputEnergyFrom`. The §13.2 negative control (four `lets*` set to false) failed exactly the four transparency
  tests, each on its own resource; see `docs/testing.md`.
- **A transformer, not a cable, feeds the energy test.** Horizon-QA's `supplyEU` calls
  `increaseStoredEnergyUnits` directly on the tile at that position, which bypasses cover checks, so it cannot test
  transparency by itself. It fills an MV-to-LV transformer instead, and GT's own `handleEUOutput` ->
  `IEnergyConnected.Util.emitEnergyToNetwork` -> `injectEnergyUnits` carries the EU into the machine through the
  covered face. The transformer emits at `oldOutput` (32 EU/t, the machine's input voltage) even though it debits
  itself the output loss, so the LV machine does not explode (`BaseMetaTileEntity.java:452-468`).
- **A basic machine's input fluid is not `getFluid()`.** `MTEBasicTank.getFluid()` returns the *drainable* stack; a
  fill through a face lands in the fillable stack (`getFillableStack()`). The first run of `transparentToFluids`
  failed on that: `fill` returned 1,000 and `getFluid()` was still null.
- **E5 confirmed, and the hammer path is not vacuous.** With `allowsTickRateAddition()` false, a hard-hammer
  right-click on the covered face leaves the rate at 20 and GT sends `gt.cover.info.chat.tick_rate_not_allowed`
  (`BaseMetaTileEntity.java:1589-1605`). With the flag flipped to true in a negative control, the same click raised the
  rate to 40. The test uses a hard hammer rather than a jackhammer because `GTModHandler.damageOrDechargeItem` damages
  a hammer for any player, while a jackhammer needs charge.
- **The copy tool is exercised for real.** `ItemList.Tool_Cover_Copy_Paste` is an electric `MetaBaseItem`
  (`MetaGeneratedItem01.java:5233`), so the test charges it and calls `Item.onItemUseFirst` with a sneaking FakePlayer
  (copy mode): `BehaviourCoverTool` reads `allowsCopyPasteTool()`, stores nothing for the sensor, and does store the GT
  conveyor placed on the next face in the same setup.
- **An unknown cover id loads as `CoverNone`, which is still a "valid" cover object.** `CoverRegistry.buildCoverFromNbt`
  falls back to `CoverNone` for an id it does not know, and that object reports `isValid() == true` when the id is
  non-zero, so `hasCoverAtSide` stays true for it. §8.5's "the cover is dropped on the next save" is about the item id
  no longer resolving; what the test pins is that GregScope's data is ignored, no sensor appears, and the face stays
  transparent.
- **Heartbeat cadence needs real ticks.** As errata E2 says, a warp does not advance `MinecraftServer.getTickCounter()`,
  which `CoverableTileEntity.tickCoverAtSide` takes modulo the cover's tick rate; it also gates on the holder's
  `mTickTimer > 10`. The heartbeat test therefore waits real ticks (the first heartbeat arrived after 30). It also
  found that Horizon-QA keeps every finished cell force-loaded, so covers from earlier tests keep heartbeating into a
  listener installed later: the listener's queries filter by sensor UUID and by holder, and the "inert cover never
  reports" assertion is phrased as "no event without an identity, and nothing from that machine".
- **Not wired yet.** Nothing consumes the events, and `setAvailability` has no caller, so `getDescription` shows no
  availability word until GS-107. Registration, duplicate re-keying, label writes (§3.5) and the tombstone
  transitions of §4.3 are GS-107 and GS-111.

### GS-107 (2026-09-17)
- **Classes.** New package `registry/`: the `[pure]` `SensorState` (with the §8.3 persisted codes pinned, never
  `ordinal()`), `RemovalCause`, `PosKey`, `SensorEntry`, `RegistryEvents` and `SensorRegistryCore`, plus the MC adapter
  `registry/SensorRegistry` (listed in `PureSourcesTest.IMPURE` on purpose). `ShippedClassesTest` only gained four
  expected-class entries; no check changed. Not named in §2: `SensorEntry` (§4.1 needs a value to hold, and a separate
  class is unit-testable), `PosKey` (the A1 key) and `RegistryEvents` (the seam below). `RunsTable` and
  `RegistryNbtCodec`, the other two `registry/` classes of §2, stay with GS-109.
- **A1's key does not fit in one long, so it is two fields.** design-v0.3 §5.1 A1 asks for
  `packedPos(dim,x,y,z)<<3 | side`. x and z need 26 bits each for the +-30,000,000 world limit, y needs 8 and the side
  3, which is already 63 bits, and a dimension ID is a full `int` (mod packs hand out negative and large IDs).
  `PosKey` therefore holds the dimension and one long packing `(x, y, z, side)` exactly as A1 describes for the rest;
  `packedPosition()` is `packed >>> 3`, so the numbers A1 names are all there. A unit test pins the packing at the
  world limits. Keys are built on slow paths only.
- **The fast path is one lookup and nothing else.** §14 asks for "no allocation and only one map lookup". The fast path
  is `byId.get(uuid)`, four primitive comparisons plus the kind, and a store of `lastHeartbeatTick`; it never touches
  the reverse index, the clock, the team resolver or `RegistryEvents`. The unit test injects counting maps, a counting
  clock and a counting team resolver and asserts 1 / 0 / 0 / 0, which is stronger evidence than reading the code.
  **GS-107-05 correction:** that test measures the core only, and the adapter used to add a second `core.entry(id)` on
  every heartbeat to work out the availability word, so the shipped path did two lookups. The word now comes from
  `Heartbeat.availability()`, which follows from the outcome alone (LIVE/REGISTERED/RESUMED/REKEYED → `live`,
  OVER_CAP/REKEYED_OVER_CAP → `over cap`, UNSUPPORTED_KIND → none) and is itself unit-tested, so the adapter needs no
  lookup at all.
- **`RegistryEvents`, because buckets, history loads and file deletes are other tickets.** §4.3 wants a registration to
  "assign a bucket, allocate rings, queue an async history load" and an expiry to delete the file on the I/O thread.
  The core allocates the rings itself (they are its own §4.1 state) and hands the rest out through the `[pure]`
  `RegistryEvents` (`sensorLive`, `sensorInactive`, `minuteClosed`, `sensorExpired`), which is `NONE` until GS-108 and
  GS-109 install themselves. The state machine is therefore complete and testable now.
- **A duplicate is re-keyed through the cover, not by the core.** The core mints the fresh UUID (from an injected
  supplier, so tests are deterministic), registers the newcomer under it and returns `REKEYED`; the adapter writes it
  into the cover with the new `SensorCover.rekey(SensorIdentity)` and calls `markDirty()`. `rekey` and
  `setAvailability` were added to the §5.1 A2 `SensorCover` interface rather than to `MachineSensorCover` only, so a
  v0.3 meter can be re-keyed and labelled through the same seam.
- **The caps are checked on every transition into a counted state, not only for an unknown UUID.** §4.3 only spells the
  cap out for the "unknown" row, but a tombstone that resumes is not counted while it is a tombstone, so admitting it
  unchecked would let the registry exceed `maxSensors`. UNLOADED to LIVE needs no check (it already counts).
- **Settings and the clock are read live.** The registry is built once in `serverStarting`, but it reads
  `GregScope.settings()` through a `Supplier<Settings>` and the clock through `GregScope.clock()`, so a test hook
  installed after server start (which is when Horizon-QA installs one) reaches it. A `Settings` snapshot taken at
  construction would have made `overrideSettings` useless for the cap test.
- **`TeamResolver.clearCache()`.** §5 says a resolver caches "for one Hub view rebuild or one sampler-frame sequence".
  The registry keeps one for the whole run, so a `default void clearCache() {}` was added to the interface and the core
  clears the cache before every cap question; otherwise a team merge or kick would never be seen. `GtnhlibTeamResolver`
  already had the method and now overrides it.
- **Validation lives in the adapter until GS-108.** §14 gives GS-107 the in-game test
  `destroyBlockBecomesMissingAfterThreeSamples`, which needs the §6.2 target resolution, but §6.2's `TargetResolver` is
  GS-108's. `SensorRegistry.validate(UUID)` implements exactly the part before the probe call (dimension,
  `blockExists`, the tile, then the cover's interface, kind and id, per A2) and applies the §4.3 result. The §13.1 hook
  `sampleNow(uuid)` calls it; GS-108 extends the same hook with the probe and the ring append, so no test changes.
- **Hooks added beyond §13.1's list:** `heartbeatNow(cover)` and `sampleNow(uuid)` are named there;
  `housekeepingNow()` and `purgeAllNow()` are new. Housekeeping otherwise only runs at server start (the 72,000-tick
  timer needs GS-108's single `ServerTickEvent` handler, which §1.4 reserves for that ticket), and `purgeAllNow` is
  `/gregscope purge` applied to everything, which the cap test needs because Horizon-QA keeps finished cells loaded and
  their sensors registered.
- **`model/` is now a checked pure package.** A §4.1 entry holds the last `MachineSnapshot`, so `PureSourcesTest`'s
  bytecode layer needed that class to be `[pure]`. `MachineSnapshot`, `MachineKind`, `SnapshotKeys` and `StatusIds`
  import nothing but `java.util` and have since v0.1; they are now marked and the `model` package is scanned.
- **Test findings, not design changes.**
  - `helper.destroyBlock` is `world.setBlock(..., air, 0, 3)`, which runs GT's `breakBlock` but not `getDrops`
    (`gt5u/gregtech/common/blocks/BlockMachines.java:463-498`, `BaseMetaTileEntity.java:1385-1406`), so no cover hook
    fires. That is exactly the §4.3 MISSING case: only a validation notices.
  - A facing change does not drop a Machine Sensor from a **basic** machine. `MTEBasicMachine.allowCoverOnSide` only
    refuses the main facing for a placer that is not GUI-clickable, and GregScope's is GUI-clickable
    (`MTEBasicMachine.java:981-985`), so `checkDropCover` keeps it. `facingChangeDropRemoves` therefore uses an EBF
    controller, where `allowCoverOnSide` is `side != frontFacing` (`MTEMultiBlockBase.java:296-298`), and GT drops the
    cover in the tick after `setFrontFacing` (`BaseMetaTileEntity.handleFacingChange`).
  - An OVER_CAP sensor gets **no entry at all**, so §4.2's "OVER_CAP is transient and not persisted" is literal: the
    refusal lives only in the 1,024-entry access-ordered LRU that implements the 1,200-tick retry window, and a
    purge-all clears it.
  - The OVER_CAP chat message of §4.3 needs the attaching player, which the registry only learns about one heartbeat
    later. `MachineSensorCover` keeps it in a `WeakReference` that the first heartbeat consumes, so nothing holds a
    player alive; the lang key is the new `gregscope.chat.sensor.over_cap`.
  - The A1 kind rules (a kind mismatch is a duplicate; a cover of another kind on another face of the same block is not
    a replacement) cannot happen in a v0.2 build, which registers kind 0 only. The core therefore takes the set of
    registered kinds as an `IntPredicate` (`SensorKind::isSupported` in production), and the unit tests exercise both
    rules against a kind-1-aware core. A v0.2 heartbeat of kind 1 or 2 returns `UNSUPPORTED_KIND` and creates nothing.
- **Not wired yet.** Nothing samples yet, so `lastSnapshot`, `metaId`/`metaName`/`machineName`/`lastStatusId`, the
  buckets and `historyLoaded` stay at their defaults until GS-108; nothing persists, so every server start rebuilds the
  registry from heartbeats (the §8.3 loader and `RunsTable` are GS-109), and `minuteClosed`/`sensorExpired` have no
  listener. The 72,000-tick housekeeping timer arrives with GS-108's tick handler; GS-107 runs housekeeping once at
  server start, as §4.3 says.

### GS-108 (2026-09-17)
- **Classes.** New in `sampling/`: the `[pure]` `SamplerSchedule` (buckets, carry-over and the budget loop),
  `SampleFolder` (one sample or one gap second folded into a sensor's rings, minute and counters), `SamplerStats`,
  `SamplerStatsView`, `LimitsView`, `CountersView`, `MachineCountersView`, `SensorView` and `TelemetryFrame`, plus the
  two MC adapters `TargetResolver` (the §6.2 world chain, the only telemetry class touching `World`) and
  `TelemetrySampler` (the one tick handler), both listed in `PureSourcesTest.IMPURE` on purpose. Not named in §2:
  `SampleFolder`, `SamplerStatsView`, `LimitsView`, `CountersView` and `MachineCountersView`; §7.7 does name a
  `SamplerStatsView` and a `LimitsView` in the frame, and `SampleFolder` is the pure half of §6.2 split out so the
  arithmetic of a sample is unit-testable without Minecraft.
- **Exactly one `ServerTickEvent` handler, and the guards that prove it.** `TelemetrySampler.onServerTick` is the only
  `@SubscribeEvent` method in the mod. It is registered on the FML bus (where 1.7.10 posts `ServerTickEvent`) inside
  `serverStarting`, so the listener belongs to the GregScope mod container, and unregistered in `serverStopped`.
  - `ShippedClassesTest.noPeriodicWorkHooks` lifts four references (`SubscribeEvent`, `EventBus`, `TickEvent`,
    `FMLCommonHandler`) for `sampling/TelemetrySampler` **only**; threads, timers, executors, world generators and
    tile-entity registration stay forbidden for it too. The new `theOnlyTickHandlerIsTheSampler` requires the sampler
    to really use all four (so the skip cannot pass vacuously), to name `TickEvent$ServerTickEvent` and
    `TickEvent$Phase`, and to use none of the other forbidden references.
  - `IdleCostTests` changed deliberately: `assertNoGregScopeListeners` became `assertGregScopeListeners(.., expected)`,
    which counts GregScope listeners two ways (the listener object's class and the owning `ModContainer`) and requires
    0 on all three Forge buses and exactly 1 on the FML bus, and that the one is `GregScope.sampler()`. The new
    `exactlyOneServerTickHandler` reflects over the live listener: exactly one `@SubscribeEvent` method, taking a
    `ServerTickEvent`, and phase START really does nothing while END runs one tick. The new
    `handlerDoesNoWorkWithoutLiveSensors` empties the registry and runs a whole interval inside one server tick (so no
    cover can heartbeat in between) and asserts `cyclesTotal` and `samplesTotal` do not move while `ticksTotal` does.
- **"No work" is the per-sensor work.** `SamplerSchedule.tick` returns before it reads `nanoTime` when the carry-over
  list and the due bucket are both empty, so an idle tick costs two emptiness checks and two counter increments; the
  unit test asserts the fake clock was not read at all. The once-per-interval block still runs: with no sensors that
  is one empty `TelemetryFrame` per second, which is the documented §6.3 cost of publishing.
- **Registry event ordering changed (minimal glue).** `SensorRegistryCore.unloaded` and `toTombstone` now fire
  `events.sensorInactive(entry)` **before** they clear the bucket and free the rings, so the schedule can do the §6.1
  O(1) swap-remove from `entry.bucket()`/`entry.bucketSlot()`. The entry still ends with `bucket == -1`, which is what
  `SensorRegistryCoreTest` and `SensorLifecycleTests` assert. `SensorRegistryCore.storeClosedMinute` was added so the
  sampler's closed minutes reach the ring and `RegistryEvents.minuteClosed` through the same path as the core's own.
- **`SensorEntry` gained five RAM-only fields:** `bucketSlot` (the O(1) swap-remove), `sampleDueTick` (the interval a
  carried sensor was due in), `lastGapReason` (§7.7's `SensorView.lastGapReason`), `lastSampleTick` (the `serverTicks`
  delta) and `lastProbeWarnEpochSec` (the §6.2 "one WARN per sensor per 10 minutes").
- **`serverTicks` is a delta added before the sample folds.** §7.4 wants the server ticks seen while a minute was open.
  Counting per tick per sensor would be O(N) work every tick, so the sampler adds `ownTick - entry.lastSampleTick`
  once per sample, before `MinuteAccumulator.sample` can roll the minute over. Those ticks therefore land in the
  minute that was open while they elapsed, which is what the field means; the unit test pins it.
- **`sampling.enabled=false` skips, it does not carry.** §7.2 gives gap bit 3 to both a budget skip and
  `sampling.enabled=false`. The schedule treats the disabled case as an immediate skip of everything due, so the gap is
  recorded in the interval it belongs to instead of a full interval later. It is the same `Sink.skip` path the budget
  uses, which is what makes the in-game `samplingDisabledRecordsSkippedGaps` evidence for the budget skip too.
- **Bug found by the in-game test, fixed, and pinned by a unit test.** In the first version, when the carry-over list
  alone spent the budget, the tick's own bucket was not visited at all: its sensors were neither sampled, carried nor
  skipped, so a whole interval vanished without a gap and they never aged out. `SamplerTests.tinyBudgetSkips` showed it
  as `budgetExceededTicks=120, skipped=0, carriedOver=2` where a backlog was expected.
  `SamplerScheduleTest.theDueBucketCarriesOverWhenTheCarryListSpendsTheBudget` is the regression test.
- **A second hazard of the same kind, found in review and pinned too.** Serving a sensor can take it out of the
  schedule inside the loop that is iterating its bucket: a sample whose chunk turns out to be gone makes the entry
  UNLOADED, and the registry reports that back as `sensorInactive` -> `SamplerSchedule.remove`, whose O(1) swap-remove
  moves the last element into the freed slot. An indexed loop would then skip that element for the whole interval,
  with no gap recorded. Both loops now re-read the list position after serving and only advance when the element is
  still there; only the sensor being served can be removed this way, because the registry touches exactly the id it
  was asked about. `SamplerScheduleTest.aSensorRemovedWhileItIsBeingSampledCostsNoOtherSensorItsTurn` pins it.
- **Design-v0.3 GS-201 hooks this ticket touches.**
  - **A2.** `TargetResolver` is the §6.2 chain and checks, in order, the `SensorCover` interface, `sensorKind()` and
    the id; anything else at that side is a `target_missing` strike. `SensorRegistry.validate(UUID)` now delegates to
    it and `validateAndResolve(UUID)` returns the tile entity so the sampler does not resolve twice, so GS-107's
    `SensorLifecycleTests` did not change, as its notes required.
  - **A6.** `SensorView.lastSnapshot()` is nullable and documented as kind-0 only, `SensorView.kind()` is published,
    and `CountersView` is an interface (`kind`, `samplesTotal`, `gapSecondsTotal`) with `MachineCountersView` as the
    kind-0 implementation, so a v0.3 `FlowCountersView` needs no reader change. The nullable `lastFlow` field of A6 is
    **not** added: its type `FlowReading` is GS-203's and a field of a type that does not exist would be dead weight;
    `SensorView` is a plain immutable value, so adding it later touches one constructor and one getter.
- **What the frame reports and where the numbers come from.** `duplicatesRekeyedTotal` and `quotaRefusedTotal` are
  copied from the registry core, which owns them; `clockSkewRefusedTotal` is summed over the entries' accumulators when
  a frame is built, because each accumulator counts its own refusals (§6.2); `lastFrameBuildNanos` necessarily
  describes the **previous** frame, because the stats view has to be copied before the build it would time can finish.
  The microsecond histogram is per working tick over a 60-second window (§6.3), rolled every 1,200 ticks.
- **Read live, read once.** `sampling.tickBudgetMicros` and `sampling.enabled` are read from `Settings` on every tick,
  so a test-hook override applies at once. `sampling.intervalTicks` fixes the number of buckets and is read once, at
  construction, which is what §12.3 ("a change needs a restart") says.
- **Hooks.** `sampleNow(uuid)` is now the whole §6.2 procedure (resolution, probe, fold) and still returns "did the
  target resolve", so GS-107's tests are unchanged. `runIntervalNow()` (named in §13.1) runs one interval's worth of
  ticks; `runTickNow()` runs one. The 72,000-tick housekeeping timer moved from "server start only" into the tick
  handler, as GS-107's notes said it would.
- **Measured cost, and why it differs from the GS-102 gate.** `SamplerTests.tinyBudgetSkips` logs both numbers on the
  same 64 machines (LV Electric Furnaces, local Windows run, 2026-09-17):
  - **warmed up:** 1,280 `sampleNow` calls after 1,280 warm-up calls, **mean 6.1-7.6 µs** per sample. That is the whole
    §6.2 procedure (resolve + probe + fold), against the 1.4 µs p50 GS-102 measured for `PROBE.snapshot` alone in a
    10,000-call hot loop.
  - **cold:** during the first 120 real ticks of sampling, mean 81-156 µs per sample and a per-tick p50 of 135-175 µs.
    The sample path had run only a few hundred times by then, and HotSpot compiles on invocation counts, so it was
    still interpreted. This is real: a server that has just started pays it, and the §6.3 budget is what keeps it from
    hurting the tick. At the 1,000 µs default and 6.4 µs warm, one tick serves ~156 samples, far above the 12.8 per
    tick that 256 sensors need, so the GS-102 decision to keep the defaults stands.
- **`tinyBudgetSkips` is the §14 test, with two changes forced by reality.** It needs the `gregscope:empty_5x3x5`
  template, because the default Horizon-QA cell is 5x1x5 and 64 machines do not fit (the first run failed the isolation
  check). It also breaks its 64 machines again at the end: Horizon-QA keeps finished cells loaded, every sensor placed
  by a FakePlayer is unowned, and §4.2 counts all unowned sensors against one pseudo-owner, so 64 of them fill
  `limits.maxSensorsPerTeam` (default 64) and every later batch's sensor is refused with OVER_CAP. That is exactly what
  the first run showed: five `SensorLifecycleTests` failed with "no registry entry". The observed behaviour with
  `tickBudgetMicros=100` and 64 sensors is the design's: the budget is exceeded on every tick and 111-209 intervals are
  given up on with `sampling_skipped` over six intervals (six full runs).
- **The §13.2 negative control (run once, reverted; the file was restored from a backup copy and `cmp` is identical).**
  Removing the `blockExists` guard from `TargetResolver.resolve` makes
  `SamplerTests.unloadedChunkNotLoadedBySampler` fail, as §13.2 requires: the sensor becomes MISSING after three
  strikes instead of UNLOADED with a `chunk_unloaded` gap. The far chunk was still absent from the loaded-chunk map
  right afterwards, so what this run demonstrates is the misclassification rather than a chunk load; the guard remains
  the documented reason GregScope never asks for a chunk it has not been told is there.
- **`statsCommandRuns` is deferred to GS-111.** §14 lists it under GS-108's Horizon-QA tests, but `/gregscope stats`
  is GS-111's scope and no command exists yet. Everything it would show (`SamplerStats`, the histogram, the registry
  totals, the frame) is published in the `TelemetryFrame` and asserted by `TelemetryFrameTest` and
  `SamplerTests.everyIntervalPublishesAnImmutableFrame`.
- **New Horizon-QA batches:** `gregscope.sampler` (the four hook-driven tests, 60 ticks), `gregscope.sampler.real`
  (real ticks, 100), `gregscope.sampler.budget` (overrides settings and fills the registry, 200) and
  `gregscope.sampler.disabled` (turns sampling off process-wide, 60). Four batches rather than one, because tests in a
  batch run in parallel and share one sampler: the first run had `realTicksSampleOncePerSecond` see 7 samples in 60
  ticks because its batch mates were calling `runIntervalNow`. The worst-case suite time is now 4,220 ticks (211 s)
  over 26 batches against the 300 s CI step (the figure first written here, 4,120, was wrong: recounted from the
  annotations, the longest `timeoutTicks` per batch sum to 4,220 - GS108-T2).
- **Not wired yet.** `RegistryEvents.minuteClosed` and `sensorExpired` are still no-ops in the sampler: the 64-byte
  writes and the file deletes are GS-109's, and so are `ioQueuedTotal`, `ioDroppedTotal` and `ioErrorsTotal`, which
  stay 0. Nothing reads the frame yet either; the Hub (GS-113/GS-114), OpenComputers (GS-115/GS-116) and the commands
  (GS-111) are its consumers.

### Implementation notes / review follow-ups (GS-105 to GS-108)

Findings from the adversarial review of GS-105 to GS-108, applied after GS-108. Each one is listed with what changed
and what proves it. The v0.2 behaviour rules are unchanged: still server-authoritative, still read-only towards
machines, still no mixins, ATs or reflection in `src/main`.

- **GS-REV-1: `getDescription()` never reached a player, and lied when it did.** GT's only consumer is
  `CoverableTileEntity.getWailaBody` (`CoverableTileEntity.java:531-553`), the **client** WAILA tooltip path, and the
  cover syncs no data (`isDataNeededOnClient()` false, `writeDataToByteBuf` not overridden, so
  `GTPacketSendCoverData` carries only the tick rate). A client `MachineSensorCover` therefore has `identity == null`
  and rendered every healthy sensor as "GregScope sensor (inactive)" - the wording reserved for a foreign or corrupt
  blob. The cover now returns the neutral `SensorDescription.NEUTRAL` ("GregScope sensor") whenever `getTile()` is
  null or not server side, and the full status line only on the server. `toString()` still prints the identity, so
  debugging is unaffected. The real client label is the §1.2 v0.2.1 item; §3.4's `getDescription` row now says so.
  Evidence: `SensorDescriptionTest.theNeutralWordingClaimsNoState`; the server-side gametests (`SensorCoverTests`,
  `SensorLifecycleTests`) still assert the full line and still pass. **Not covered by an automated test:** what a
  client actually renders - that stays on the GS-121 manual checklist.
- **GS-REV-2: a re-key refused by a cap reported success.** `SensorRegistryCore.rekey` dropped `register()`'s return
  and always answered `REKEYED`, so a duplicate whose fresh identity a cap refused looked registered: no entry
  existed, the availability word was cleared instead of `over cap`, and an attaching player was not told. The outcome
  is now propagated as the new `Heartbeat.REKEYED_OVER_CAP`, and the adapter runs both the re-key write-back and its
  OVER_CAP branch. The NBT rewrite deliberately still happens: keeping the colliding UUID would take the duplicate
  branch on every heartbeat, burn a fresh UUID each time and never converge. `duplicatesRekeyedTotal` still counts the
  re-key itself, as §4.3 specifies. Evidence:
  `SensorRegistryCoreTest.aRekeyRefusedByTheCapReportsItAndCreatesNothing` (negative control: make `rekey` ignore the
  outcome again -> that test alone fails).
- **GS-REV-3: GT's paste direction could erase a live sensor.** `allowsCopyPasteTool() == false` gates only
  `BehaviourCoverTool`'s copy branch (`:73-87`). The paste branch (`:88-99`) is gated on the numeric cover id alone
  and calls `ICoverable.updateAttachedCover`, which is `readFromNbt` on the **live** cover object
  (`CoverableTileEntity.java:498-502`); 1.7.10 item ids are assigned per save, so the id check is not an identity
  check across saves, and `updateAttachedCover(int, ForgeDirection, NBTTagCompound)` is public `ICoverable` API any
  mod can call. `readDataFromNbt` now refuses to downgrade: a cover that already has an identity ignores a `d` tag
  that does not read as `Status.VALID`. A freshly built cover has no identity (`CoverRegistry.buildCoverFromNbt`,
  `CoverPlacer.placeCover`), so world load, item pickup and the §3.3 rollback-preservation rule are untouched, and a
  valid sensor compound is still applied. §3.4's row no longer claims the flag covers the paste direction or the
  Matter Manipulator (which is not in the pinned sources). Evidence:
  `SensorCoverTests.pastedForeignDataDoesNotEraseALiveSensor` (negative control: remove the guard -> that test alone
  fails with "a foreign compound erased the sensor identity").
- **GS-REV-4: a sensor weatherproofs the face it sits on.** `BaseMetaTileEntity.isRainExposed()`
  (`BaseMetaTileEntity.java:250-265`) treats every side with `hasCoverAtSide` as not exposed, and `Cover.isValid()` is
  `coverID != 0 && side != UNKNOWN`, so no cover can opt out; `handleRainExposure` (`:506-550`) gates GT's rain fire
  and the rain and thunder explosions on it, and both are on by default. Not fixable in GregScope code and identical
  for every GT cover, so it is documented in §1.4 as the one exception to "read-only towards the machine", accepted
  for release, and added to the GS-121 manual checklist. No code change.
- **GS-107-02: the third strike dropped the open minute.** `strike()` was the only exit from LIVE that passed no gap
  reason to `toTombstone`, so `closeOpenMinute` was skipped and `freeRings()` threw away up to 59 s of folded minute
  data at the moment a machine was destroyed - the interesting minute. It now passes `GapReason.TARGET_MISSING`, the
  same reason the strike seconds carry. §4.3's MISSING row says so. Evidence:
  `SensorRegistryCoreTest.theThirdStrikeQueuesThePartialMinuteBeforeFreeingTheRings`.
- **GS-107-03: `lastSeen` never advanced without a sample.** Only a successful sample or a resume refreshed it, and
  UNLOADED entries expire on `now - lastSeen > staleExpiryDays`. With `sampling.enabled=false` (a supported config) a
  sensor that heartbeated for a month would be expired by its first chunk unload. `unloaded()` now stamps `lastSeen`
  before the state change; that is a slow path, so the heartbeat fast path stays clock-free. Evidence:
  `SensorRegistryCoreTest.anUnloadRefreshesLastSeenSoTheStaleWindowStartsThere`, which also asserts the fast path
  reads no clock.
- **GS-107-04: `seen` is persisted but does not mark the registry dirty.** Marking dirty whenever `lastSeen` advances
  would pin the flag true every second and make it useless, so the contract is written down in §8.3 instead: GS-109's
  shutdown save runs regardless of `dirty`. No code change; nothing reads `dirty()` yet.
- **GS-107-05: the shipped heartbeat did two map lookups.** See the GS-107 note above; the availability word now comes
  from `Heartbeat.availability()`. Evidence: `SensorRegistryCoreTest.everyHeartbeatOutcomeCarriesItsAvailabilityWord`.
- **GS-108-01 / GS108-T1: `tinyBudgetSkips` asserted JIT state.** `exceeded > 0` and `skipped > 0` hold only while the
  sample path is interpreted (80-160 us per sample); warmed up it costs 6-8 us, and 64 sensors over 20 buckets put
  about 4 samples (~30 us) in a tick against the 100 us budget, so neither counter could move. Any later batch that
  sorts before `gregscope.sampler.budget` and warms the path would have turned a correct implementation into a red CI
  run. Those two assertions are now logged as observations, and what is asserted is hardware-independent: every
  sensor is sampled or given a `sampling_skipped` gap at least twice; a skip implies at least one budget-exceeded
  tick; and if nothing was skipped, every sensor was sampled in all but at most one interval. The budget arithmetic
  itself stays pinned deterministically by `SamplerScheduleTest` over a fake `nanoTime`. Evidence that the retained
  invariant is not vacuous: with `SamplerSchedule.serveCarry` no longer calling `sink.skip`, `tinyBudgetSkips` fails
  with "a sensor was neither sampled nor skipped often enough in 6 intervals: 1".
- **GS108-T2: the documented worst-case suite time was wrong.** The sum of the longest `timeoutTicks` over the 26
  batches is 4,220 ticks (211 s), not 4,120; both copies of the figure are corrected and `docs/testing.md` now quotes
  the measured time of the last run instead of a figure from the GS-105 era.
- **GS107-T3: sensors accumulated across batches against one quota.** Horizon-QA keeps finished cells loaded and their
  covers keep heartbeating, so every sensor a batch placed stayed registered and counted against the shared unowned
  `limits.maxSensorsPerTeam`. Purging alone does not help (a cover whose machine still ticks registers again on the
  next heartbeat), so the new gametest helper `SensorCleanup` detaches the covers with GT's own `detachCover` and
  then purges, and every sensor-placing batch has an `@AfterBatch` hook calling it. Evidence: the run log now shows
  ten `cleanup after a sensor batch` lines (`entries=... coversDetached=... entriesPurged=...`) and the registry is
  empty between batches.
- **GS106-T4: `recordEvents` saved and restored a process-global listener.** Two overlapping sequence-based recorders
  would have restored each other and left a finished test's recorder installed, detaching the registry for the rest of
  the run. The helper now asserts that the registry is the installed listener when it takes over, and restores
  `GregScope.registry()` unconditionally, so an overlap fails loudly in the test that caused it.
- **GS108-T5: `clockSkewRefusedTotal` was a running maximum of a per-frame sum.** Once a sensor was purged, expired or
  became a tombstone its accumulator was freed, the sum fell back, and the counter stopped reflecting reality.
  `SampleFolder` now reports `clockSkewRefusedDelta()` for each fold and gap, `TelemetrySampler.store` adds it, and
  `SamplerStats.onClockSkewRefused` accumulates with the same saturating add as every other counter. Evidence:
  `SampleFolderTest.aSampleRefusedForClockSkewIsReportedAsADelta`, `.aGapRefusedForClockSkewIsReportedAsADelta` and
  the new `SamplerStatsTest`. **Known gap:** `SensorRegistryCore.strike()` writes its `target_missing` second straight
  to the accumulator, not through `SampleFolder`, so a clock-skew refusal on that path is still not counted; the core
  is `[pure]` and has no stats seam. It is a counter-only inaccuracy on a path that needs both a strike and a clock
  that went backwards.

### GS-109 (2026-09-17)
- **Classes.** New in `history/`: the `[pure]` `HistoryFileCodec` (the §8.2 header, its status rules and the 92,224 B
  geometry), `Crc32`, `SlotLayouts`, `FileStore` (the seam) and `IoListener`, plus the two I/O adapters `NioFileStore`
  and `HistoryIo`, which `PureSourcesTest.IMPURE` lists on purpose. New in `registry/`: the `[pure]` `RunsTable` and
  the MC adapter `RegistryNbtCodec` (also listed). §2 names `FileStore`, `NioFileStore`, `HistoryIo`, `RunsTable` and
  `RegistryNbtCodec`; added and not named there are `Crc32`, `SlotLayouts` and `IoListener`, each for a reason below.
  `ShippedClassesTest` gained nine expected-class entries and one new check (below).
- **`FileStore` is `[pure]`, its NIO implementation is not.** The interface names only `java.*` types, so GS-110's
  `PersistenceScenarioTest` can implement it in memory, which §14 asks for. `NioFileStore` and `HistoryIo` import no
  game class either (only `java.nio` and `java.util.concurrent`), but §2 lists them as the I/O adapters of the
  package, and listing them in `IMPURE` is what stops any `[pure]` class from naming them - the boundary stays
  one-way. Trap found while doing it: `PureSourcesTest` decides what is pure by looking for the literal `[pure]`
  anywhere in the file, so a class that writes "not [pure]" in its own Javadoc is treated as pure and fails the run.
  Both adapters now say it in words instead.
- **`Crc32` is written out instead of using `java.util.zip.CRC32`.** Calling that class puts the member name
  `update` into the caller's constant pool, and `ShippedClassesTest.noPeriodicWorkHooks` forbids
  `canUpdate`/`update`/`updateEntity` everywhere to catch tick hooks. A 20-line table is cheaper than weakening that
  check, and it keeps the §8.2 header checksum independent of the JDK. Its check value (`"123456789"` gives
  `0xCBF43926`) is asserted in the unit test and in `tools/gen_fixtures.py`.
- **"No threads" is lifted for exactly one class (§1.4).** `history/HistoryIo` and its inner classes are the only
  shipped classes allowed to name `java.lang.Thread`; timers, executors, event buses, world generators and tile entity
  registration stay forbidden for them too. The new `ShippedClassesTest.theOnlyThreadIsTheIoThread` checks the lift is
  not vacuous (`HistoryIo` really names `Thread`, calls `setDaemon` and carries the string `GregScope-IO`) and that
  `NioFileStore` does **not** name `Thread`. That is why the `-ea` check is a `BooleanSupplier` supplied at
  construction (`HistoryIo::onIoThread` in production) rather than a `Thread.currentThread()` call in the store: one
  lift, one class, and a unit test can drive both answers.
- **The `-ea` check, and what it does not cover.** §14 wants "the server thread does no file I/O (`NioFileStore`
  asserts the thread name under `-ea`)". Every method asserts it **except the two registry reads**: §2 loads the
  registry in `serverStarting`, before the world runs, and §8.4 lists no `ReadRegistry` task, so those two are the
  documented synchronous exception. The check is only as good as the JVM's assertion setting, and the dev
  `runServer` runs **without** `-ea` (observed: the in-game test logged "assertions are off"). `addon.gradle` now
  passes `-ea:io.github.ldogg123.gregscope...` to `runServer`/`runClient`, scoped so Forge, GT, MUI2 and MC keep
  running as they ship. `NioFileStore`'s check is the only `assert` in `src/main`.
- **Header rules, where §8.2 leaves a gap.** §8.2 names "`formatVersion > 1`" as unsupported. A version that is not 1
  at all (0, say) is no version this project ever wrote either, and the conservative action for anything unreadable is
  to rename rather than overwrite, so `inspect` treats **any** `formatVersion != 1` as `UNSUPPORTED`, and so are a
  `slotSize`/`slotCount` that are not 64/1440 and a `sensorKind`/`slotLayout` pair that do not belong together (a
  kind-1 file with layout 2 is renamed, never reinterpreted). A file whose length is not exactly 92,224 B is
  `CORRUPT`, like bad magic or a bad header CRC. Nothing is ever deleted: `.corrupt-<epochMs>`, `.unsupported-v<N>`
  and `.mismatch`, and a quarantine name that is taken gets a `-1`, `-2`, ... discriminator.
- **A slot write never creates a file.** `NioFileStore.writeSlot` checks the file is present and exactly 92,224 B
  before opening it, and throws otherwise. `RandomAccessFile("rw")` would otherwise create a headerless, sparse file
  on the first write after a `CreateFile` task was dropped by a full queue.
- **What the I/O thread reports, and to whom.** `HistoryIo` talks to the rest of the mod through the `[pure]`
  `IoListener` rather than calling `SamplerStats` and the logger directly, so it stays unit-testable in a plain JVM
  and the §7.6 counters keep one owner. `GregScope.IoStatsLog` is the shipped implementation: `onQueued`/`onDropped`
  run on the server thread and write `SamplerStats` directly, while `onError` runs on the I/O thread, so its count is
  parked in an `AtomicLong` and folded in from the next server-thread callback. The 10-minute drop-warning window
  lives in `HistoryIo` over an injectable nanosecond clock, which is what makes it testable.
- **Minimal glue that GS-109 wires, and what it deliberately does not.** §2's load-phase table puts "resolve the save
  root, load the registry, start I/O" in `serverStarting` and "join I/O" in `serverStopped`. GS-109 wires the
  *service*: `GregScope.saveRoot()` (`DimensionManager.getCurrentSaveRootDirectory()` plus `/gregscope`), the
  `NioFileStore`, the `HistoryIo` start/stop with its listener, the `flushIo` test hook, and the §7.8 startup INFO
  line, which had no caller until now. Loading `registry.dat`, appending the run, the slot writes, the async loads,
  the retention deletes and the saves stay with GS-110, which is what §14 scopes to it. With
  `history.persist=false` no store and no thread are created at all, so everything GS-110 will queue is simply not
  queued.
- **The runs table enforces its own load order.** The GS-110 follow-up "call `GapRanges.Run.stopOnLoad` for every
  stored run at registry load, before appending the new run and before any save" is structural here rather than a
  rule to remember: `RunsTable.loaded(rows, saved)` applies `stopOnLoad` while decoding, and `RegistryNbtCodec.decode`
  is the only way to build a table from a file, so appending the new run first is not expressible.
- **design-v0.3 GS-201 hooks this ticket touches.**
  - **A3 (`RegistryNbtCodec` keeps unknown kinds).** An entry whose `kind` this build does not register is never
    decoded into a `SensorEntry`: it is kept as a verbatim `NBTTagCompound` in `Loaded.preserved()`, so it is not
    counted, sampled or expired, its `.gsh` is never opened, and it is written back unchanged. The same holds for keys
    this build does not define, on the root compound and on entries it does understand, so a v0.3 to v0.2 rollback
    loses nothing. The golden fixture carries a kind-1 entry with `tier`/`lastIo`, an unknown root key and an unknown
    entry key, and the in-game test asserts all of them survive a decode/encode round.
  - **A5 (`SlotLayout` strategy).** Only the part GS-109 needs: `SlotLayouts` pins the layout codes (1 machine, 2 item
    flow, 3 fluid flow), the kind/layout pairing and which layouts this build can read, which is what the §8.2 header
    validation asks for. The strategy itself (`id()`, `validate`, `merge`, the `MinuteHeader` view,
    `MinuteRing(SlotLayout)`) belongs to the history refactor, not here.
- **Fixtures.** `tools/gen_fixtures.py` gained the four `gsh_header_*.hex` headers and `registry_v1.nbt.gz.hex`, a
  whole gzipped-NBT `registry.dat` written without any NBT library (gzip `mtime=0`, so re-runs are byte-identical).
  The GS-103 fixtures regenerate unchanged. `addon.gradle` copies `src/test/resources/fixtures/v1` into the gametest
  resources, so the in-game test reads the same file the unit tests do instead of a second copy that could drift.
- **A new Horizon-QA batch has to be named with the reload tests in mind (GS109-T1).** Horizon-QA runs batches
  alphabetically, hands out cells as tests start, and keeps finished cells loaded. The batch was first called
  `gregscope.persistence`, which sorts **before** `gregscope.reload.*`: its `v2Unsupported` cell landed at (0, 48), in
  the same chunk as `ChunkReloadTests.basicMachineSurvivesChunkReload` at (8, 48), and that test failed on two fresh
  worlds. Renamed to `gregscope.storage`, which sorts last, every older test keeps the cell it always had and the
  suite is green. The worst-case suite time is now 3,720 ticks (186 s) over 27 batches against the 300 s CI step -
  recounted mechanically from all 89 `@GameTest(` annotations, which also showed the 4,220 recorded after GS-108 was
  itself too high (the 26 pre-GS-109 batches sum to 3,520).
- **Observed and unresolved: the chunk-reload tests are wall-clock sensitive.** Besides the two failures the batch
  name explains, two runs of the deliberately broken negative-control build failed a different reload test each,
  although removing an `assert` cannot affect chunk loading. All three runs of the final state were green and every
  older log in this project shows these tests passing. Recorded in `docs/testing.md` as an observation to watch in CI,
  not as a diagnosis.

### GS-110 (2026-09-17)
- **Classes.** Three new shipped classes. `history/HistoryPersistence` owns the `.gsh` half of section 8 (which file
  exists, when it is read, what is written into it, when it is deleted) and is the registry's `RegistryEvents`
  delegate; `registry/RegistryPersistence` owns `registry.dat` (load, append this run, save); `GregScopeWorldEvents`
  carries the two Forge-bus hooks sections 8.3 and 8.4 name. Section 2 names none of the three: the section lists the
  data classes of each package, and the two services are the wiring between them, which §14 scopes to this ticket;
  the event hook could only live in the root package next to `GregScope`, because it is mod-lifecycle glue. Neither
  service imports a game class, but both are listed in `PureSourcesTest.IMPURE`, because each names an adapter
  (`HistoryIo`, `RegistryNbtCodec`) and the listing is what stops a `[pure]` class from naming *them*. The GS-109 trap
  applies again: `HistoryPersistence` says "not one of the pure ones" in words, because `PureSourcesTest` treats the
  literal marker anywhere in a file as a claim of purity.
- **A second event subscriber, and how narrow the lift is.** Section 8.3 saves "on dim-0 `WorldEvent.Save`" and
  section 8.4 makes dim-0 `WorldEvent.Unload` a shutdown trigger. Both are on the **Forge** bus, which
  `ShippedClassesTest` forbade everywhere (section 1.4 lifted "no global tick handler" for `TelemetrySampler` only,
  and that lift does not include `MinecraftForge`). `GregScopeWorldEvents` is the one deliberate second lift: it may
  name `MinecraftForge`, its bus type and `SubscribeEvent`, and nothing else on the periodic-work list.
  `theOnlyWorldEventHandlerIsThePersistenceHook` checks the lift is not vacuous (it really names all three, and both
  `WorldEvent$Save` and `WorldEvent$Unload`), that it does **not** subscribe to `WorldEvent` itself, and that no other
  shipped class reaches the Forge bus. `IdleCostTests` now expects exactly one GregScope listener on
  `MinecraftForge.EVENT_BUS` instead of zero, and `exactlyTwoWorldEventHandlers` pins the two handler signatures.
  **Two methods, not one taking `WorldEvent`:** `WorldEvent.PotentialSpawns` is a `WorldEvent` too and fires several
  times per chunk per tick, so a base-class handler would put GregScope on a hot path.
- **`history.persist=false` still saves the registry - GS-109's note is corrected here.** Section 8.4's last bullet
  reads "no `.gsh` files, RAM rings only. **The registry is still saved.**" GS-109 decided that with
  `history.persist=false` no store and no I/O thread would be created at all, which was consistent then (nothing
  queued anything) but makes that sentence impossible now. `startIo()` therefore always creates the store and the
  thread when there is a save root, and `HistoryPersistence` reads the flag live from `Settings` on every call and
  writes no `.gsh`. The `history.persist` config comment said "Write minute history **and the registry**"; it now says
  "Write minute history files under `<world>/gregscope/history/`. The registry is saved either way." Evidence:
  `PersistenceScenarioTest.persistFalseWritesNoHistoryFile` (the store stays empty, the ring still holds the history)
  and `HistoryTests.persistFalseWritesNoGsh` (no `.gsh` on the running server, `registry.dat` written).
- **One `HistoryIo` task kind that section 8.4 does not list: `QuarantineRegistry`.** Section 8.3 renames a `v > 1`
  `registry.dat` aside, and renaming is file I/O like any other: `NioFileStore.quarantineRegistry` asserts the I/O
  thread under `-ea`, so doing it on the server thread would trip GS-109's own check. It is queued instead. The
  `.bak` is deliberately left alone, which `NioFileStoreTest` already pinned; a `.bak` that is also v2 is re-read once
  after a crash, found unsupported again, and the world starts empty - no data loss, no growth.
- **The order in `serverStarting`, and why it is that order.** `startServices()` runs: the store and the I/O thread
  first (everything below queues work on them); then the registry and `registry.dat`, where `RunsTable.loaded`
  closes every unclean run at the stored `saved` *before* `startRun` appends this one (section 8.3 - and GS-109 made
  that structural, so `RegistryPersistence.load` cannot get it wrong); then the sampler with the history service
  behind it, so an expiry in the next step already deletes the file it should; then housekeeping, which drops what is
  too old before its history is ever read; then the history reads for what survived. `serverStopping` closes every
  open accumulator as a partial slot, stops the run and queues the registry write **regardless of `dirty`**
  (GS-107-04: `seen` never marks it dirty); `serverStopped` flushes and joins. Both halves are idempotent and are
  also what the overworld's `WorldEvent.Unload` calls.
- **Exactly one slot write per closed minute per sensor.** The registry core already funnels every closed minute
  through `storeSlot` -> `RegistryEvents.minuteClosed`, so the rule is structural: `HistoryPersistence` writes one
  64-byte slot per call and counts both sides (`minutesClosed()` and `slotsWritten()`), which is what makes the
  criterion checkable rather than asserted by eye. The one case where a minute closes before there is a file to write
  it into - between the `Load` and its result - is handled by remembering the ring index and writing it once the file
  is there, or, past 64 such minutes, by rewriting the whole file from the ring. "No writes while UNLOADED" needs no
  code: an UNLOADED entry has no open minute, so nothing closes.
- **What a load result does.** Absent, or quarantined by `HistoryIo` because the file was corrupt, unsupported or
  foreign: a fresh file is written from the **whole ring**, so minutes recorded before the file existed are not lost.
  A whole matching file: `MinuteRing.mergeLoaded` merges it with RAM winning (section 8.4), and the accumulator is
  told the newest merged minute so a clock that went backwards cannot reopen a minute that is already on disk. Either
  way the entry is marked `historyLoaded` (the Hub's "loading history..." gate, GS-113). A result for an entry that
  became a tombstone meanwhile is discarded and counted.
- **New registry-core API, all of it `[pure]`.** `runs()`/`setRuns()` (the runs table belongs to the state machine,
  because the §7.5 reader contract needs it), `gapContext(id, now)` (rule 3 in one place: the resolved runs plus what
  the registry knows about an UNLOADED sensor), `restore(entry)` (a decoded entry back into both indexes, no caps
  consulted, nothing marked dirty, no event fired - loading is not a transition) and `flushOpenMinutes()` (section
  8.4 at stop; no gap reason, because the minute really was observed and the seconds after it are `server_offline`
  by the runs table).
- **A restart in one JVM.** `GregScopeTestHooks.simulateRestart()` is the hook §14 asks for; it is the pair
  `stopServicesNow()` + `startServicesNow()`, which are also exposed separately so a test can move its fake clock
  between the two and produce real downtime (otherwise there is nothing for rule 3 to attribute). A third new hook,
  `applyHistoryLoadsNow()`, drains the finished reads without taking a sample - `runIntervalNow()` would take one, and
  a stray sample makes a minute 61 samples long.
- **Two in-game facts the run log forced.**
  - *The fake clock must be in the **future**.* `RunsTable.stopRun` clamps with `Math.max(start, now)`, and the run
    this process started was stamped with the **system** clock. A fake clock in the past therefore closed the run at
    its own start, not at the fake stop, and the restart test's runs-table assertion failed. The base time is now
    2033-05-18T03:34:00Z, on a minute boundary.
  - *Two chunk-unload tests must not share a batch.* Horizon-QA runs the tests of one batch together and hands out
    neighbouring cells, which can share a chunk: on a fresh world the cells were (0, 88) and (8, 88), both in chunk
    [0, 5], and the second test's setup loaded the chunk the first had just unloaded - the first then saw its sensor
    still LIVE, and the second found its own chunk already gone. Each now has its own batch, which is what the older
    reload tests already do. Both also compare the sensor's own file before and after instead of a global counter,
    because other cells keep heartbeating while a test idles a tick.
- **Horizon-QA `mode=ci` never runs the server-stop path.** The run ends with `FMLCommonHandler.exitJava`: the JVM
  shutdown hook runs `MinecraftServer.stopServer()`, but FML posts neither `FMLServerStoppingEvent` nor
  `FMLServerStoppedEvent`, and dimension 0 is never unloaded through `WorldEvent.Unload`. No automated test can
  therefore observe the real shutdown. What is covered instead: the bodies those handlers call, through
  `stopServicesNow()`/`startServicesNow()` in `simulatedRestartRestoresHistory`; and the save trigger, through
  `overworldSaveWritesTheRegistry`, which posts a real `WorldEvent.Save` on the real Forge bus. A real `/stop` and
  restart stays the §14 manual item (M) and is on the GS-121 checklist.
- **Known inaccuracy: the unloaded *reason* is RAM only.** `gapContext` picks between `chunk_unloaded` and
  `dimension_unloaded` from `SensorEntry.lastGapReason`, which section 4.1 keeps in RAM and section 8.3 does not
  persist. A sensor whose **dimension** was down when the server stopped therefore reads `chunk_unloaded` for the
  minutes after a restart, until it is sampled again. It is a label on an already-correct gap, on a path where the
  minutes in question are `server_offline` anyway (rule 3 checks the runs first), so no field was added to
  `registry.dat` for it; recorded here rather than fixed.
- **design-v0.3 GS-201 hooks this ticket touches.** A3 only: every save writes back the `Preserved` compound the load
  produced, so an entry of a kind this build does not register, and any key it does not define, survive a v0.3 to
  v0.2 round trip on a running server. `RegistryPersistence` holds that object for the life of the run and logs how
  many foreign entries it is carrying. No part of A5 was needed beyond what GS-109 already pinned.

### GS-111 (2026-09-17)
- **`/gregscope` cannot be "level 0" the way section 11 words it - this is a reality correction.** In 1.7.10
  `EntityPlayerMP.canCommandSenderUseCommand(level, name)` returns **false for every player who is not on the ops
  list, at any level, level 0 included** (`EntityPlayerMP.java:1077-1098`; only `tell`, `help`, `me` and
  single-player `seed` are special-cased), and `CommandHandler.executeCommand` gates on
  `ICommand.canCommandSenderUseCommand(sender)` (`CommandHandler.java:53`), whose `CommandBase` default is exactly
  that call. Registering the command with `getRequiredPermissionLevel() == 0` therefore hides it from ordinary
  players, which is the opposite of section 11's "any player" rows. `GregScopeCommand.canCommandSenderUseCommand`
  returns **true** and every decision is made per subcommand through `AccessPolicy`. The vanilla check is still what
  decides who is an op: the `Viewer` an `ICommandSender` becomes asks
  `player.canCommandSenderUseCommand(permissions.opLevel, "gregscope")`, which is the adapter form GS-104 already
  prescribed. This is the same errata-E4-shaped fact `Viewer`'s Javadoc records for `AccessPolicy.isOp` with
  `opLevel=0`; section 11's table is unchanged in meaning, only its "level 0" phrasing is.
- **A stranger's `label` answers "no sensor matches", not "you are not allowed".** With the default
  `permissions.renameRequiresOfficer=false`, section 5 makes `canRename` and `canView` allow exactly the same people
  (owner, same team, op), so a viewer who fails `canRename` has already failed `canView` and the prefix never
  resolved for them. The `canRename` branch is reachable only with `renameRequiresOfficer=true`, where a plain team
  member can see a sensor and not rename it. Both are asserted: `CommandTests.labelDeniedForStranger` (not found)
  and `labelDeniedForNonOfficerWhenConfigured` (denied, then allowed once the team makes them an officer).
  Deliberately not "fixed" by leaking the existence of a sensor a viewer may not see.
- **Two classes, plus one section 2 does not name.** Section 2 lists `command/ GregScopeCommand; [pure] CommandArgs`,
  and that is what shipped. `sensor/RenameCooldown` is the third: section 3.5 requires a per-player cooldown for
  **every** label write and lists three surfaces, so the cooldown belongs beside the label rules rather than inside
  one surface; `SensorRegistry` owns one instance for the server run and GS-114's Hub field will use the same
  `writeLabel` entry point. `CommandArgs` also holds the pure text helpers section 11's output needs (`age`,
  `bytes`, `percent`, the id-prefix normalizer, the paging arithmetic and the seven-day `isStale` rule), so they are
  unit-tested rather than hidden in the adapter.
- **The label write is one shared service, and it is kind-agnostic.** `SensorCover` gains `setLabel(String)` (the
  design-v0.3 section 5.1 A2 hook: a v0.3 meter is labelled the same way), `SensorRegistry.liveCover` now returns
  `SensorCover` instead of `MachineSensorCover` and checks the cover's **kind and UUID** as well (A2 again), and
  `SensorRegistry.writeLabel(id, rawText, player)` is the one place that applies section 3.5: reject over
  `Labels.MAX_INPUT_UNITS` before sanitizing, require LIVE, resolve the cover without loading a chunk, write the
  cover NBT and `markDirty()`, mirror the label into the entry, mark the registry dirty, and only then charge the
  cooldown. The caller asks `AccessPolicy.canRename` first, because only the caller knows who is asking.
- **Decisions section 11 leaves open, made here and worth knowing.**
  - **`list` is ordered by sensor UUID.** Section 11 fixes the columns and "10 per page" but not the order; ordering
    by id is the only order that keeps page 2 meaningful while sensors come and go, and it is the order
    `TelemetryFrame` already uses.
  - **`stats` sensor counts are filtered by `canView`; the machine-health numbers are not.** Section 5 puts `stats`
    in the same row as `list` and `info`, so the per-state counts are the viewer's. The histogram quantiles, the
    work and I/O counters and the RAM/disk estimates are server-wide facts about the server, not about anyone's
    sensors, and are shown to everyone. The RAM/disk estimate is derived from the whole registry for the same
    reason.
  - **`purge --stale` uses section 11's seven days, not `history.staleExpiryDays` (30).** `list stale` defines
    "stale" as UNLOADED for more than seven days; `purge --stale` removes exactly what `list stale` shows. The
    30-day setting stays what housekeeping expires by itself.
  - **The values inside a line are data, not translated text.** Section 11 says the output uses
    `ChatComponentTranslation` keys, and every line is one (`gregscope.cmd.*`, all new in `en_US.lang` and covered
    by `AssetsExistTest` through the `LANG_CMD_*` constants). Inside a line, states, gap reasons and sensor kinds
    travel as their stable ids (`live`, `server_offline`, `machine`) - the same ids OpenComputers and the section
    16.2 exporter model use - so a script that reads chat sees the same words on every client. The section 12.2
    `gregscope.state.*` and `gregscope.gap.*` keys are still there for the Hub (GS-114), which renders them.
  - **An ambiguous prefix counts every match and lists five.** The message says how many there really are; stopping
    the scan at six would have printed a number that is a listing limit rather than a count.
- **`info` is the first consumer of `historyLoaded()` and `gapContext()`.** GS-110's notes predicted GS-113 would
  be; `info` needs both (the "history is still loading" line, and rule 3 for the 24 h gap line), so that prediction
  is corrected here. `info` is also the first shipped caller of `Summaries.minutes`.
- **No `ShippedClassesTest` guard was lifted.** The command needs none: `CommandBase` is not on the periodic-work
  list, the command registers itself through `FMLServerStartingEvent.registerServerCommand` rather than
  `GameRegistry`, and it declares no member named `update`/`canUpdate`/`updateEntity`. `PureSourcesTest` gains the
  `command` package as a **pure package** with exactly one listed adapter (`command/GregScopeCommand`), and
  `CommandArgs` and `sensor/RenameCooldown` join the `[pure]` list.
- **Two in-game facts the test senders forced.** `FakePlayer` does **not** override `addChatMessage(IChatComponent)`,
  only `addChatComponentMessage`, so `EntityPlayerMP.addChatMessage` runs and dereferences a null
  `playerNetServerHandler`: a plain fake player cannot receive command output at all. And
  `FakePlayer.canCommandSenderUseCommand` is hard-coded to false, so it can never be an op. `CommandSenders.Player`
  overrides exactly those two. The console side is a stub `ICommandSender` rather than `MinecraftServer`, because
  `MinecraftServer.addChatMessage` logs every line and one of the assertions is that a call logs nothing.
- **Batches and the CI budget.** The three new batches are named `gregscope.surface.command*`, which sorts after
  `gregscope.storage.*` and therefore after every batch that unloads a chunk, so the GS-109 cell-shifting trap is
  avoided by construction; the one chunk-reload test and the one chunk-unload test have a batch each (GS-110 rule).
  All three take Horizon-QA's **default 100** `timeoutTicks` rather than 200, because every test runs to completion
  inside one server tick apart from a single `thenIdle(1)`. Worst case is now **4,620 ticks = 231 s** of the 300 s
  step, over 33 batches; measured, the three batches together take 0.2 s.
- **A negative control that was vacuous, and why.** The first attempt at "a label write never loads a chunk" removed
  only `writeLabel`'s `state() != LIVE` early return; the suite stayed green, because `liveCover` carries its own
  LIVE check and returned null before touching the world. The control only bites once every guard in front of
  `World.getTileEntity` is gone - `liveCover`'s LIVE check and its `blockExists` check as well - and then
  `labelRefusedWhenUnloadedAndChunkStaysUnloaded` fails on the write succeeding, which is also the positive evidence
  that `World.getTileEntity` really loads an unloaded chunk. Recorded because a control that passes proves nothing
  and it is easy to stop there.
- **The `statsCommandRuns` name.** Section 14 lists this test under GS-111 as `statsRuns`, while the GS-108 notes
  deferred it under the name `statsCommandRuns`. It is one test; it ships under the name the deferral recorded.

### GS-112 (2026-09-17)

**Scope.** Section 9.1 only: the `gregscope:telemetry_hub` block, its default `ItemBlock` and its non-ticking tile
entity, the owner set on place, the NBT record, and the server-side checks a right-click makes. GS-114 owns the GUI,
so a permitted right-click opens nothing yet.

- **Classes section 2 does not name.** Section 2 lists `hub/` as `BlockTelemetryHub`, `TileTelemetryHub`, `HubPanel`
  and the pure view model. GS-112 adds three more: `hub/HubNbtCodec` (the pure `gsHub` record over the existing
  `KeyValue` seam, so the tile entity's format is unit-tested the way `SensorNbtCodec` is), `hub/HubViews` (the pure
  open-view register the section 9.1 cap needs, which GS-114 will drive from the panel) and `hub/TelemetryHubs` (the
  registration class, the one that may name `GameRegistry` for a block; `SensorCovers` is its precedent). The `hub`
  package joins `PureSourcesTest.PURE_PACKAGES`, with `BlockTelemetryHub`, `TileTelemetryHub` and `TelemetryHubs`
  listed as its deliberate Minecraft adapters.
- **`sensor/NbtKeyValue` and `SensorIdentity.capOwnerName` became public.** The Hub stores an owner and an owner name
  in NBT, exactly as a sensor cover does, so it reuses the same adapter and the same 16-unit cap instead of growing a
  second copy of either. Both stay where they are; only their visibility changed.
- **The `ShippedClassesTest` lift, and its shape.** Section 1.4 lifts "no blocks, no tile entities" for v0.2, so the
  guard is lifted for **exactly one** class each way. `hub/TileTelemetryHub` is the only shipped class that may extend
  `TileEntity` or name `canUpdate`; `update` and `updateEntity` stay forbidden there too, and
  `theOnlyTileEntityIsTheHub` fails if it ever stops extending `TileEntity` or stops declaring `canUpdate` (so the
  skip cannot pass vacuously) or if any other class extends `TileEntity`. The `GameRegistry` allowlist changed from a
  set of classes with one shared set of allowed `register*` names to a **map from class to its own set**:
  `sensor/SensorCovers` keeps `registerItem`/`registerCover`, `hub/TelemetryHubs` gets
  `registerBlock`/`registerTileEntity`, and neither can make the other's call. `registerWorldGenerator` and
  `registerTileEntityWithAlternatives` stay forbidden everywhere.
- **`TelemetryHubs.install`, not `register*`.** The reader sees constant pools and cannot tell a declared method name
  from a called one, so a method here named `registerBlock` would put that name into **every caller's** constant pool
  (`GregScope` first) and widen the lift past this class. The entry point is therefore `install(CreativeTabs)`. That
  `GameRegistry` is really called is still checked: `TelemetryHubs` must name `registerBlock` and `registerTileEntity`
  and nothing else starting with `register`.
- **Deviation: one block texture, not three.** Section 9.1 wants `telemetry_hub_front/side/top`. Per-side icons need
  `Block.registerBlockIcons(IIconRegister)` and `Block.getIcon(int, int)`, and both are `@SideOnly(CLIENT)` in 1.7.10
  (`mc/net/minecraft/block/Block.java:1469-1470` and `:651-652`; `IIconRegister` is
  `net.minecraft.client.renderer.texture.IIconRegister`), so the shipped block would have to name a client class and
  `ShippedClassesTest.noClientClassReferences` would have to be lifted. GS-112 does not lift it: `tools/gen_textures.py`
  generates and commits all three PNGs, `GregScopeAssets` pins all three names (so `AssetsExistTest` covers them), and
  the block asks for `telemetry_hub_side` through `setBlockTextureName`, which is not client-only and which
  Minecraft's own `registerBlockIcons` reads. The Hub therefore renders with one texture on every face for now.
  GS-114 already needs the section 1.4 client lift for `createScreen`, and wires the front and top icons there.
- **Deviation: the view cap is checked, not held.** Section 9.1's order is access, then the open-view cap, then
  `GuiFactories.tileEntity().open`. With no `IGuiHolder` yet, calling `open` would throw inside MUI2
  (`TileEntityGuiFactory.getGuiHolder` requires one), so GS-112 stops after the two checks. It asks
  `HubViews.canOpen` and deliberately does **not** call `opened`: reserving a slot for a window that never opens would
  let a player consume the server-wide cap by right-clicking, 32 clicks to lock everyone out until a restart. The
  register is therefore always empty in production until GS-114 calls `opened`/`closed` around the real panel, and the
  in-game test drives the busy path by calling `opened` itself. `GregScope.stopServices` clears the register, so a
  single-player world switch starts at zero.
- **An unsupported Hub is operator-only.** A tile entity whose `gsHub` is newer parses no owner (that is what
  "verbatim" means), so `AccessPolicy.canOpenHub` sees a null owner and only an operator gets in. That follows
  section 5's unowned rule and is the conservative answer; GS-114's GUI still has to say "unsupported".
- **Facing.** Metadata 2..5 from the placer's yaw, with vanilla's own mapping (`BlockFurnace`: quadrant 0 -> 2 north,
  1 -> 5 east, 2 -> 3 south, 3 -> 4 west), set with `setBlockMetadataWithNotify` in `onBlockPlacedBy`, which does not
  disturb the tile entity. Nothing reads the facing yet; it is stored so GS-114 and v0.2.1 hull icons can.
- **A real player was needed in the gametests.** Every existing in-game helper places blocks as a `FakePlayer`, and
  GregScope treats a FakePlayer as "no attributable player" on purpose, so a FakePlayer can never produce the
  **owner** half of "owner set on place". `CommandSenders` gains `Real`, an `EntityPlayerMP` built exactly the way
  Forge's `FakePlayer` builds itself minus the overrides that make one fake, with the same recorded chat and a chosen
  permission level.
- **`IdleCostTests` changed deliberately.** `noGregScopeListenersTileEntitiesOrGenerators` used to assert that no
  GregScope class is in the tile entity registry; it now asserts **exactly one**, `TileTelemetryHub`, counted over
  distinct class names (`TileEntity` keeps a name->class and a class->name map, so the same class appears twice - the
  first attempt at this assertion failed with "expected 1 but found 2"). The "zero GregScope tile entities loaded in
  any world" half is unchanged and still holds, because `World.addTileEntity` and `World.setTileEntity` only add a
  tile whose `canUpdate()` is true (`mc/net/minecraft/world/World.java:4405-4412` and `:2834-2841`). `HubBlockTests`
  asserts the same thing about a Hub it has just placed, which is the assertion that fails when `canUpdate` lies.
- **GS-201 hooks.** None of design-v0.3 section 5.1's amendments touch the Hub block or tile entity: A1-A6 are about
  the registry, the sampler and the slot layouts, and the Hub's own v0.3 additions (the `kind`/flow columns and the
  `gs_filter` values 2 and 3) belong to `HubRow`/`HubViewModel`, which is GS-113. The v0.3-relevant surface built here
  is only the one section 9.1 freezes: the registry names, the `gsHub` record with its verbatim-preservation rule (a
  v0.3 Hub that stores more keys can be rolled back), and the `HubViews` seam GS-114 will drive.
- **Batches and the CI budget.** Two new batches, `gregscope.surface.hub` and `gregscope.surface.hub.reload`, both at
  Horizon-QA's default 100 `timeoutTicks`; the reload test has its own batch (GS-110 rule) and the names sort after
  `gregscope.surface.command*`, so no older test's cell moved. Worst case is now **4,820 ticks = 241 s** of the 300 s
  step over 35 batches, 59 s of headroom; measured, the two batches together take 0.10 s.

### Review follow-ups after GS-112 (2026-09-17)

Nine findings from an adversarial review of GS-109 to GS-112 were applied in one pass. No ticket scope changed; every
change is either a failure path that had no answer, a guard that was missing, or a test that could pass while the
thing it names was broken.

**1. A load that threw stranded its sensor for the rest of the run (GS-109, `HistoryIo`/`HistoryPersistence`).**
`perform()` caught `Throwable`, counted it and returned, so a `Load` that threw produced no `LoadResult` at all. On
the server side the sensor stayed `FileState.REQUESTED` with `loadQueued = true`, and `retryDroppedLoads()` only
re-queues a load the *queue* dropped, never one that was accepted and then failed. Nothing reset the flag: the sensor
kept `historyLoaded() == false` (so the Hub and OpenComputers would say "loading history" for ever), every closed
minute only went into `pending`, and not one slot was written for the rest of the run. One transient read error - on
Windows a virus scanner or backup agent holding `<uuid>.gsh` open for a moment is the everyday case - was enough.
Now `HistoryIo` answers every task that throws: a failed load becomes a `LoadResult.failed()`, which is deliberately
**not** `absent()`, because `HistoryPersistence.apply()` turns an absent file into `createFrom()` and a fresh image
written over a file that may be whole is the one irreversible mistake available here. `apply()` maps a failed result
to "stay REQUESTED, `loadQueued = false`", so the next drain asks again.

**2. A create or slot write that threw left the sensor believing in a file that was not there (GS-110).**
`createFrom()` sets `PRESENT` before queueing and only reverts it when the *queue* refuses the task, so a
`CreateFile` that failed on disk (a full disk, an unwritable `history/`) left every later minute queueing a
`WriteSlot` into nothing: one `NoSuchFileException` per sensor per minute, logged with a full stack trace and no rate
limit at all - about 15,000 stack traces an hour at `limits.maxSensors=256`, which on the disk-full variant makes the
original problem worse. `HistoryIo` now parks such failures in `drainWriteFailures()`, drained by
`HistoryPersistence.drain()`, which sends the sensor back to REQUESTED with the whole-ring sentinel: the file is read
again and then rewritten in full, so the minutes recorded meanwhile are not lost either. `GregScope.IoStatsLog.onError`
is rate-limited exactly like the drop warning (`HistoryIo.DROP_WARN_INTERVAL_NANOS`, 10 minutes), carrying the number
of failures it left out.

Both retries are bounded by `HistoryPersistence.MAX_FILE_FAILURES` (5). A file that fails every time is given up on
for the rest of the run and counted in `sensorsAbandoned()`; its ring still holds the history, and nothing is ever
written over a file that could not be read. Retrying for ever was the alternative and would have produced the same
unbounded error stream this fix removes.

**3. An abandoned I/O thread must not share its save root (GS-110, `GregScope`).** When `HistoryIo.stop()` timed out,
`stopIo()` logged a WARN and dropped the reference - but the worker was never interrupted and kept draining its queue,
and the next `startServices()` built a second `NioFileStore` and `HistoryIo` over the same folder. Both write the same
fixed `registry.dat.tmp` (and `<uuid>.gsh.tmp`), and the `-ea` check could not see it either, because `onIoThread()`
compares the thread *name* and both workers carry it. Two interleaved writes into one temporary file, moved onto
`registry.dat` and copied to `registry.dat.bak` at the next save, would take out the whole registry and the runs
table - the one mitigation section 8.3 has. `stopIo()` now keeps the abandoned worker, and `startIo()` interrupts and
joins it first (`HistoryIo.terminate`); if it still will not go, the new run logs an ERROR and keeps everything in RAM
rather than writing beside it. A task interrupted mid-write ends in `ClosedByInterruptException`, which is reported
like any other failure, so nothing half-written is moved into place.

**4. The last drain before the poison (GS-110, `GregScope.finalizeServices`/`stopIo`).** `finalizeServices()` closed
every open minute without first applying finished loads, and `drain()` is only called by the sampler on an interval
boundary. A sensor whose result was parked at that moment stayed REQUESTED, so `flushOpenMinutes()` only added ring
indexes to `pending` and the whole-file image that would have saved them was never queued. `finalizeServices()` now
drains first, and `stopIo()` drains once more after a short bounded flush (`FINAL_DRAIN_FLUSH_MILLIS`, 2 s) and
before the poison, so a load that lands during the shutdown flush still reaches the disk. The window this closes is
about one sampling interval wide, which is why it is small; it is also the window bug 1 above made unbounded.

**5. Clock jumps and the irreversible delete (GS-110, `SensorRegistryCore.housekeeping`). Applied in part, and the
reason matters.** Expiry deletes the sensor's history file, and housekeeping compared the raw wall clock against
`lastSeen`/`stateSince` with no guard at all, although section 18's risk table names a "clock-skew guard" as the
mitigation for clock jumps. The **backwards** half is now guarded: if `now` is more than
`CLOCK_SKEW_TOLERANCE_SEC` (60 s) behind the newest timestamp the registry itself holds - any entry's
`lastSeen`/`stateSince`, or the runs table's newest start or stop - the stale and tombstone sweeps are skipped and
counted (`clockSkewSkippedSweeps()`), only the cap-driven `evictOldestTombstones()` still runs, and `SensorRegistry`
writes one WARN per server run naming both timestamps. Nothing the registry holds can legitimately lie in the future,
so this direction is unambiguous.

The review's suggested **forward** guard - "skip the sweep when `now` is more than about a day ahead of the newest
`lastSeen` the registry holds" - was **not** implemented, because it would silently disable section 4.3's stale
expiry, which is the feature whose whole point is that `now - lastSeen` grows past 30 days. The unit test
`SensorRegistryCoreTest.unloadedSensorsExpireAfterTheStaleWindow` is exactly that case, and so is an ordinary world
that was not played for two months: a legitimately long shutdown and a clock that jumped forward are indistinguishable
from inside the process, because every reference the process has (`registry.dat`'s `saved`, the runs table, the world
folder's timestamps) was written by the same clock. A monotonic anchor does not help either: it is captured with the
same wrong clock at boot, and any fake-clock test would read as a jump. What remains undetected is therefore a clock
that is already wrong when the server boots; it is recorded as an open issue rather than papered over.

One consequence is worth knowing: a run whose start was stamped with a clock in the future stays in `registry.dat`'s
runs table, so expiry stays off until wall time passes it (at most until that row is evicted, `RunsTable.MAX_RUNS` =
32). The Horizon-QA run shows it: `HistoryTests` drives a fake clock at 2033, and the WARN appears once afterwards,
"the clock says 1789692582 but the registry already holds the later timestamp 2000000461". That is the conservative
answer - nothing is deleted while the recorded timeline contains stamps from the future - and it is visible in the
log rather than silent.

**6. `/gregscope stats` threw before the first frame (GS-111).** `TelemetryFrame.EMPTY` carried `null` for its
`SamplerStatsView` and `LimitsView` ("null only on EMPTY"), and `stats` dereferences `frame.stats()` eleven times with
no check, although the same method already guards `io == null` and `frame.sequence() == 0`. The command is registered
in `FMLServerStartingEvent`, before the server has ticked, so an RCON monitor or an admin asking in the first sampling
interval (1 s by default, 5 s at `intervalTicks=100`, and again after any stop) got "An unknown error occurred" and a
stack trace. `EMPTY` now carries a zero-valued `SamplerStatsView` and a `LimitsView` of the defaults, which also
protects GS-113/GS-114 and the OpenComputers readers; the "null only on EMPTY" contract is gone.

**7. A gametest was stealing the production load results (GS-109, `RegistryCodecTests`).**
`theIoThreadWritesAndReadsARealHistoryFile` called `drainLoaded()` on the live `HistoryIo` and threw away every result
that was not its own - which is bug 1's failure mode, caused on purpose by a test, for any sensor whose file happened
to be in flight. It now runs a `HistoryIo` of its own over the same real store and save root (disjoint files: one
fresh UUID's `.gsh` and its own `.tmp`, never the registry), so the evidence about the real save root is unchanged and
nothing is taken from the service. The class Javadoc, which claimed the batch touches nothing live, was corrected.

**8. The `-ea` check could switch itself off (GS-109, `RegistryCodecTests`).**
`theServerThreadCannotWriteAFile` - the only in-game enforcement of section 14's "the server thread does no file I/O"
- skipped its `assertThrows` when `desiredAssertionStatus()` was false, so losing the
`-ea:io.github.ldogg123.gregscope...` line in `addon.gradle` (a Gradle upgrade that rewrites the run tasks, a merge)
would have turned the check green rather than red. Assertions are now a precondition with a message naming the file
to fix.

**9. The CI budget was under-counted by 700 ticks (docs/testing.md).** The recount script matched `timeoutTicks` only
as a number, so the two batches that write `timeoutTicks = OPENOS_BATCH_TIMEOUT_TICKS` (450) were charged the default
100 each. The true worst case is **5,520 ticks = 276 s**, not 4,820 / 241 s, and the 300 s is a wall-clock
`timeout 300 ./gradlew runServer` that also covers Gradle configuration and Forge/GTNH boot (about 30 s locally), so
the "59 s of headroom" recorded after GS-112 does not exist. The script now resolves `static final int` constants;
`docs/testing.md` carries the corrected figure and the correction itself. No test timeout and no workflow input was
changed here, because the 300 s step is a fixed constraint for this work; the choice between raising it and lowering
`OPENOS_BATCH_TIMEOUT_TICKS` (450 against an observed 149) is left as an open issue.

**Tests.** Unit 493 -> 500: three new `HistoryIoTest` cases (a failed load answers and is not `absent`; failed
writes reach `drainWriteFailures()` while a failed delete needs no answer; `terminate()` gets rid of a parked
worker), three new `PersistenceScenarioTest` scenarios over an in-memory store that can now be told to fail a chosen
number of reads or writes, one new `SensorRegistryCoreTest` case for the backwards-clock guard, and an extended
`TelemetryFrameTest`. In game 126 -> 127: `CommandTests.statsRunsBeforeTheFirstFrameIsPublished`. Negative controls:
dropping the new `report(task)` call in `HistoryIo.perform` failed exactly the five new failure-path unit tests, and
restoring `TelemetryFrame.EMPTY`'s nulls failed exactly one in-game test with
`NullPointerException at GregScopeCommand.stats(GregScopeCommand.java:190)` - the reported bug, reproduced. Both were
reverted and verified with `cmp`.
