# GregScope v0.3: flow meters, final design

> **CANCELLED 2026-09-18.** This design is not being built. It is kept because the spike's findings are worth
> having and because the reasoning for stopping should be readable next to what it stopped.
>
> The GS-202 spike's S1 gate **passed** - exact counting is achievable and is proven by a parity test against GT's
> own `moveFluid`. The reason for cancelling is a product one, from the project owner: **a cover-based meter only
> sees what flows through the cover.** A GTNH machine is fed by GT pipes, EnderIO conduits, AE2 interfaces and ME
> stocking hatches, so a meter is blind to most real setups and would report "fluid in: 0" for a machine that is
> running fine. Confident wrong numbers are worse than no numbers.
>
> Feed-agnostic alternatives were checked, not assumed: the running recipe is `protected` and unreachable without
> reflection, GT keeps no cumulative throughput counters, and tank-level deltas cannot separate consumption from
> refill. See [flow-meters.md](flow-meters.md) for the evidence and for what is proposed instead - reading the
> machine's own buffers, which is feed-agnostic but answers "is this starving?" rather than "how much flowed?".


Status: final synthesis, 2026-09-17. It builds on `docs/design-v0.2.md` (M1 in progress) and does not break any v0.2 format.

**Pins:** GT5U 5.09.54.133, GTNHLib 0.11.46, ModularUI2 2.3.88-1.7.10, Horizon-QA 0.14.0, OC 1.12.61-GTNH.

**User decisions applied:**
- Active metering covers that move items and fluids are approved. This settles design-v0.2 §15 point 1.
- No passive delta "flow" ships (handoff.md:481-491, the "v0.3 — Item and fluid flow meters" subsection of §14).
- Both attempted and accepted are recorded.

**Path prefixes:**
- `gt5u/`, `gtnhlib/` and `hqa/` mean the extracted `-sources.jar` of the pinned version (GT5-Unofficial, GTNHLib,
  Horizon-QA) from `https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/`.
- `repo/` means `gregscope/src/main/java/io/github/ldogg123/gregscope/`.
- `design` means `docs/design-v0.2.md`.

**Basis:** this is the GT-reuse design, which both judges picked as the winner, with grafts from the own-loop design. The disputed facts were re-checked in this session.

---

## 0. Verified facts and corrections

### 0.1 Facts the design depends on
| # | Fact | Evidence |
|---|---|---|
| F1 | `CoverConveyor extends CoverIOBase`.<br>• Public constructor `(ctx, tickRate, maxStacks, itemsPerStack, tex)`.<br>• `public final int tickRate`.<br>• `stacksPerTransfer` and `itemsPerStack` are **private**.<br>• `protected doTransfer` builds a `GTItemTransfer`, calls push or pull, the setters and `dropItems`, then `transfer()`, and **discards the return value**.<br>• `letsItemsIn/Out` = `coverData>=6 \|\| direction bit`.<br>• `alwaysLookConnected` is true.<br>• `getMinimumTickRate()` returns `tickRate`. | `gt5u/gregtech/common/covers/CoverConveyor.java:18-29,36-50,82-100` |
| F2 | `CoverPump`:<br>• `public final int mTransferRate`.<br>• `protected doTransfer` returns early if `getITankContainerAtSide` is null (:37-44).<br>• `protected transferFluid` → `moveFluid(src, dst, drainSide, rate, this::canTransferFluid)` (:46-52).<br>• `protected canTransferFluid` returns true.<br>• `getMinimumTickRate()` returns 1. | `CoverPump.java:22-29,37-56,116-118` |
| F3 | `moveFluid` runs these steps and returns void:<br>1. simulated drain<br>2. copy<br>3. simulated fill<br>4. filter<br>5. `dest.fill(source.drain(amount,true), true)`, with no null check on the real drain and the fill result ignored. | `gt5u/gregtech/api/util/GTUtility.java:1053-1065` |
| F4 | `CoverIOBase.doCoverThings` is public and not final. It returns before `doTransfer` when the machine processing condition fails. The mode lives in `coverData` (0..11), and the screwdriver cycles it mod 12. | `CoverIOBase.java:14-18,85-99,101-121` |
| F5 | `CoverLegacyData` writes `d` as an `NBTTagInt` and the packet as an int. Its hooks are protected and not final. `Cover.readFromNbt/writeToNBT` are final and store `tra` outside `d`. `allowsCopyPasteTool` defaults to true. | `CoverLegacyData.java:44-62`; `gt5u/gregtech/common/covers/Cover.java:55-57,79-112,540-542` |
| F6 | A cover runs when `getTickCounter() % (min + addition) == 0`. The addition is clamped to 0..1200, so it can only slow a cover down. The same frozen counter is passed in as `aTimer`. | `CoverableTileEntity.java:184-197`; `Cover.java:55,205-207,469-475` |
| F7 | `transfer()` treats `sink == null` as `itemDropping`. **`dropItems` sets `rejectedStacks` only if it is still null.** Rejected items are force-reinserted into the source; anything that still doesn't fit goes to `rejectedStacks`, or is voided if that is null. The return value is Σ(extracted − rejected). On the sink, `transfer()` calls only `resetSink`, `setAllowedSinkSlots`, `setSlotStackLimit` and `store`. | `gtnhlib/com/gtnewhorizon/gtnhlib/item/ItemTransfer.java:96-101,115-128,185-221` |
| F8 | `GTItemTransfer.dropItems(tile, side)` drops at `tile + side offset`. `DroppingItemSink` has a public `(World, IBlockPos)` constructor. | `gt5u/gregtech/api/util/GTItemTransfer.java:35-43`; `gtnhlib/.../item/DroppingItemSink.java:9-35` |
| F9 | `ItemSink` members:<br>• instance: `resetSink`, `store` (abstract), `sinkIterator`, `simulatedSinkIterator`, `setAllowedSinkSlots`, `setSlotStackLimit`, `getStoredItemsInSink`, `then`<br>• static: constant `ZERO` and a static `chain` | `gtnhlib/.../capability/item/ItemSink.java:31-138` |
| F10 | GT tier constants.<br>**Pump**, L per op at rate 1: 32, 128, 512, 2048, 8192, 32768, 131072, 524288, 8388608, 16777216, 33554432, 67108864, 134217728, 268435456.<br>**Conveyor** (tickRate, stacks, items): LV (100,1,16), MV (100,1,64), HV (20,1,64), EV (4,1,64), IV (1,1,64), LuV (1,4,64), ZPM (1,16,64), UV (1,16,256), UHV (1,16,1024), UEV (1,16,4096), UIV (1,16,16384), UMV (1,16,65536), UXV (1,16,262144), MAX (1,16,MAX_INT).<br>**Face texture:** `MACHINE_CASINGS[t][0]` + `OVERLAY_PUMP`/`OVERLAY_CONVEYOR`. | `MetaGeneratedItem01.java:4194-4250,4350-4405` |
| F11 | `CoverRegistry.buildCover(stack, side, coverable)` is public and accepts a null coverable (as `NO_COVER` shows). The 3-argument `registerCover` uses the default placer. | `CoverRegistry.java:40,54-56,87-91` |
| F12 | The `Cover` constructor sets `coverID = stackToInt(item)` (meta<<16) **before** `setTickRateAddition(getDefaultTickRateAddition())`. `getDefaultTickRate()` defaults to `getMinimumTickRate()`, so the default addition is 0 even when subclass fields are not yet set. | `Cover.java:66-77,165-168` |
| F13 | GT has no `instanceof CoverConveyor/CoverPump/CoverIOBase`, so subclasses lose no special handling. | grep of gt5u |
| F14 | **MUI2:** `Cover.buildUI` → `getCoverGui()`. `CoverIOBaseGui(CoverIOBase, guiId)` is public and registers `io_mode`, `condition_mode`, `block_mode`. `CoverBaseGui` adds the `tickRateAddition` button.<br>**MUI1:** `CoverConveyor/CoverPump.createWindow` return `ConveyorUIFactory`/`PumpUIFactory`, which adapt any `CoverLegacyData`. | `Cover.java:296-318`; `CoverIOBaseGui.java:23-110`; `CoverBaseGui.java:83-125`; `CoverLegacyDataUIFactory.java:13-19` |
| F15 | **Horizon-QA warp:** only GT tiles tick, and the global tick counter stays frozen. Rate-1 covers fire on every warped tick; rate>1 covers fire on every tick or never. GT tile fill/drain needs `mTickTimer>5`, and covers need `>10`. | design E2; `BaseMetaTileEntity.java:275,293,1891-1935`; `hqa/.../TimeWarpHandler.java:44-114` |
| F16 | The v0.2 code already written handles layout 1 only:<br>• `MinuteSlot.decode` rejects unknown states and flags.<br>• `MinuteRing.put` merges through `MinuteSlot.merge`.<br>• `SensorCounters.onSample` throws on an unknown state. | `repo/history/MinuteSlot.java:223-241,269-309`; `repo/history/MinuteRing.java:38-92`; `repo/sampling/SensorCounters.java:30-50` |
| F17 | The v0.2 §15 hook reserves:<br>• `sensorKind` 1 (item) / 2 (fluid)<br>• `slotLayout` 2/3, a 64 B slot with i64 accepted, i64 attempted, i64 `peakPerSecond` and a resource key index | `design:1021-1052` |

### 0.2 Errors in the input designs, fixed here
1. **The exclusive face is not needed for the cancellation case** (own-loop §3.1). Counting only the meter's own operations is already exact when a pipe inserts through another face. GT's BLOCK export face still lets foreign extraction out (F1 :88-90, `CoverPump.java:99-107`). **Decision:** GT face semantics are inherited unchanged. An exclusive face is deferred to a later opt-in mode that is labelled as differing from GT.
2. **Pump `letsItems` citation** (own-loop): it is `CoverPump.java:78-86`, not `:69-77` (that range is `letsEnergy`).
3. **Minimum tick rate during the super constructor** (own-loop §2): instance fields are unset at that point. If a table is needed, the meta has to come from `coverID`. In this design `getMinimumTickRate` is inherited from `CoverConveyor` (it reads `tickRate`, which is also 0 in GT's own constructor), and F12 makes this harmless.
4. **Exporter cardinality of 33–34** (own-loop): this design fits in exactly 32 (§6.4).
5. **Minute slot without a direction split** (own-loop): the direction split is restored, as §15 point 3 requires.
6. **Pump rate with no failure path** (GT-reuse): a `buildCover` result that is not a `CoverPump` would throw ClassCastException. Fixed with a fail-closed probe (§2).
7. **Dropped `peakPerSecond` hook** (GT-reuse): restored as a float32 burst peak (§4.2).
8. **"rejectedStacks drops uncounted"** (GT-reuse): these are real `EntityItem` spawns (F7). They are now counted as `spilled` (§2.3).
9. **Pump `toAccess == null` records nothing** (GT-reuse): added the `no_target` outcome. Every executed cover op now gets exactly one outcome.
10. **ItemSink method-set test** (GT-reuse): it must compare declared *instance* methods only (F9 has the static `chain` and `ZERO`).
11. **Items "attempted is exact"** (both designs): wrong. Under backpressure, `store()` is offered once per non-empty source slot (F7 :185-207). Offered, capped at tier capacity per op, is an **upper-bound proxy**. It is documented that way and exported as `items_offered_total`.
12. **"Rates are always GT's own values"** (both designs): conveyor stacks and items can only be checked at test time (the fields are private and reflection is forbidden in shipped code). The claim is narrowed accordingly (§2, §9).
13. **Heartbeat throttle under warp** (both designs): `aTimer` is frozen, so warped tests see at most one heartbeat. Tests call `heartbeatNow`.

---

## 1. Scope

**In v0.3.0**
- Item Metering Conveyor and Fluid Metering Pump, tiers LV, MV, HV, EV and IV (10 covers).
- Registry kinds 1 and 2, sampling, 24 h history, Hub, OC, commands, and the exporter model (documented; implementation stays in v0.4).
- Assembler recipes, textures, lang and config.

**Out of scope (and why)**
- Passive inventory or tank delta "flow" (user decision).
- Mixins, ATs, or reflection in shipped code.
- Filters: GT's conveyor and pump have none, and GT filter covers can't share a face.
- Exclusive face mode: differs from GT; possible later opt-in.
- Multi-fluid transfer per op: a logistics upgrade that breaks parity.
- Metering fluid regulator: candidate for v0.3.1+.
- Per-resource metric labels.
- LuV..MAX tiers: v0.3.1 adds LuV/ZPM/UV and v0.3.2 adds UHV..MAX, each only after its per-tier parity test passes.
- Counting traffic that crosses the face without the meter doing it (GT ALLOW mode or other blocks' covers).

---

## 2. Components

```
repo/flow/
  FlowTiers            pinned tier tables + init probe                    [pure table]
  FlowCounters         per-instance longs + nonce (cover side)            [pure]
  ResourceTally        K=4 {ref, meta, accepted, offered} + other, last-hit cache  [pure]
  FluidMover           moveFluid copy + accounting over IFluidHandler     [pure]
  CountingItemSink     reused ItemSink decorator                          [pure over GTNHLib interfaces]
  SpillCounter         reused Consumer<ItemStack>: counts, drops via DroppingItemSink
  MeterCover           interface extends SensorCover
  MeterSupport         identity/NBT/heartbeat/flush/description (composition)
  MeteringConveyorCover extends gregtech.common.covers.CoverConveyor
  MeteringPumpCover     extends gregtech.common.covers.CoverPump
  MeterIOGui            extends CoverIOBaseGui (MUI2)
  FlowCovers            items, registration, recipes
repo/history/  SlotLayout, MinuteHeader, MachineSlotLayout(1), FlowSlotLayout(2,3), FlowSecondRing, FlowMinuteAccumulator, FlowSummaries, ResourceDictionary
repo/sampling/ FlowSampler, FlowReading, CountersView{Machine,Flow}
```

### 2.1 Tiers and the progression guarantee (`FlowTiers`)
- **Pinned tables:** the 14 rows from F10 for each kind.
- **Init probe** (GregScope `init`, after GT; no world):
  ```java
  Cover p = CoverRegistry.buildCover(ItemList.Electric_Pump_<T>.get(1), ForgeDirection.UNKNOWN, null);
  int rate = (p instanceof CoverPump cp) ? Math.min(cp.mTransferRate, PINNED_PUMP[T]) : -1;
  Cover c = CoverRegistry.buildCover(ItemList.Conveyor_Module_<T>.get(1), ForgeDirection.UNKNOWN, null);
  boolean convOk = c instanceof CoverConveyor cc && cc.tickRate == PINNED_CONV[T].tickRate;
  ```
  Wrap everything in try/catch, because an unset `ItemList` entry throws.
- **On failure:** the tier's cover is **not registered**, its recipe is skipped, the item tooltip reads "unavailable: GT tier table changed", and one ERROR line is logged.
- **Why the rate cannot exceed GT:**
  - `getMinimumTickRate()` is inherited and equals GT's value.
  - The tick-rate addition can only slow a cover down.
  - The pump uses the smaller of pinned and probed rates.
  - The conveyor passes pinned stacks and items into GT's own constructor, and `ItemTransfer` enforces that cap.
  - Every guard in the counting code only lowers the amount.
- **Honest limit:** if a future GT bump *lowers* conveyor stacks or items, only the required parity gametests (GS-207) and the reflection-based test in GS-202 catch it. Reflection is test-only. Both are in the CI build, and the GT bump checklist lists them.

### 2.2 Shared cover behaviour (`MeterSupport`, used by both subclasses)
| Member | Behaviour |
|---|---|
| constructor | `super(ctx, <GT args>, fgTex)`; `state = new MeterState(kind, tier)`; nonce = `ThreadLocalRandom.nextLong()`. No world access and no registry calls (design §3.4 rule). |
| `onPlayerAttach` | Creates the identity (UUID, owner, anvil label) as the v0.2 sensor does. Server side: sends **one heartbeat immediately** when the holder is available. |
| `doCoverThings(r, t)` | Server only; the GregScope parts are skipped when `flow.telemetry=false`.<br>1. Heartbeat if `t − lastHb ≥ 20` or `t < lastHb` (this runs *before* the condition gate).<br>2. `ranTransfer = false; super.doCoverThings(r, t); if (!ranTransfer) counters.suppressed++`. |
| self-heal | If `d` was missing entirely, the first server `doCoverThings` mints an identity (owner from the holder if present) and calls `markDirty`. A foreign `d` (a bare int, or no `gs`) is **inert**: it transfers like GT but never registers. |
| `onCoverUnload` / `onCoverRemoval` / `onBaseTEDestroyed` | Server only. `registry.flushFinal(id, this)` runs first, then the v0.2 unloaded/removed transition. |
| `readDataFromNbt` / `saveDataToNbt` | §2.5 |
| packet | Inherited: the int `coverData` only. The UUID never reaches clients. |
| `allowsCopyPasteTool` | `false` |
| `getCoverGui` | `new MeterIOGui(this, guiId)` |
| `getDescription` | `GregScope meter <shortId>[: label] <tier> <availability>` |
| **Not overridden** | `createWindow` (GT MUI1 factory), `letsX`, `alwaysLookConnected`, `getMinimumTickRate`, `allowsTickRateAddition`, `isRedstoneSensitive`, `onCoverScrewdriverClick` |

### 2.3 `MeteringConveyorCover.doTransfer`
This mirrors `CoverConveyor.java:37-50`.
```java
state.ranTransfer = true;
GTItemTransfer t = new GTItemTransfer();                       // GT allocates one per op too
switch (getIOMode()) { case EXPORT -> t.push(c, coverSide, c.getTileEntityAtSide(coverSide));
                       case IMPORT -> t.pull(c, coverSide, c.getTileEntityAtSide(coverSide)); }
if (t.getSource() == null) { counters.noTarget++; return; }   // transfer() would return 0 anyway (F7 :118)
t.setStacksToTransfer(tier.stacks); t.setMaxItemsPerTransfer(tier.items);
spill.bind(c.getWorld(), dropPos(c, coverSide), counters);
t.setRejectedStacks(spill);                                    // BEFORE dropItems: dropItems keeps it (F7 :98)
t.dropItems(c, coverSide);
ItemSink real = t.getSink();
boolean dropping = real == null;                               // EXPORT into air / 0-slot MTE on IMPORT
if (dropping && !Settings.flow.dropWhenNoTarget) { counters.noTarget++; spill.unbind(); return; }
if (!dropping) t.sink(countingSink.bind(real, tally));
try { int ret = t.transfer();
      FlowAccounting.recordItemOp(counters, dir, dropping, ret, countingSink, tier.capacityPerOp); }
finally { countingSink.unbind(); spill.unbind(); }
```
- **`SpillCounter.accept(stack)`**: `counters.spilled += n`, then drops the stack through a `DroppingItemSink` at the same position as `GTItemTransfer.dropItems` (F8). That sink is created lazily, only on this rare path. Behaviour is identical to GT (items drop and are never voided).
- **`CountingItemSink`** forwards every *instance* method in F9 except `then` (`transfer()` never calls it). `store(s)`: `n = size; r = real.store(s); offered += n; acceptedCounted += n − r; if (r > 0) rejectedAny = true; tally.add(item, meta, n − r, n); return r`. It allocates nothing.
- **`recordItemOp`**:
  - If `dropping`: `dropped += ret`; outcome is moved if `ret > 0`, else idle. **Direction counters are not touched.**
  - Otherwise:
    - if `ret != acceptedCounted`, then `anomalies++` (a dev-build assertion also fires);
    - `accepted[dir] += ret`;
    - `offered[dir] += max(min(offered, capacityPerOp), ret)`;
    - outcome: moved if `ret > 0` (also `blocked++` when `rejectedAny`), else blocked if `offered > 0`, else idle.

### 2.4 `MeteringPumpCover`
```java
@Override protected void doTransfer(ICoverable c) {            // copy of CoverPump.java:37-44 plus outcome
  state.ranTransfer = true;
  if (!(c instanceof IFluidHandler cur)) { counters.noTarget++; return; }
  IFluidHandler acc = c.getITankContainerAtSide(coverSide);
  if (acc == null) { counters.noTarget++; return; }
  transferFluid(cur, acc, coverSide, coverData % 2 == 0);
}
@Override protected void transferFluid(IFluidHandler cur, IFluidHandler acc, ForgeDirection side, boolean export) {
  IFluidHandler src = export ? cur : acc, dst = export ? acc : cur;        // CoverPump.java:46-52
  ForgeDirection dSide = export ? side : side.getOpposite();
  FluidMover.move(src, dst, dSide, dSide.getOpposite(), rate, this::canTransferFluid, counters, tally, dir(export));
}
```
`FluidMover.move` follows F3 step by step:
```
off = src.drain(dS, max, false); if off==null||off.amount<=0||off.getFluid()==null → idle; return
p = off.copy(); a = min(p.amount, max); p.amount = a; attempted[dir] += a
p.amount = dst.fill(fS, p, false)
if (p.amount > 0 && filter.test(p)) {
   p.amount = min(p.amount, a)                           // reduce-only clamp
   d = src.drain(dS, p.amount, true)
   if d==null||d.amount<=0 → anomalies++, blocked++; return   // GT would pass null to fill
   acc = clamp(dst.fill(fS, d, true), 0, d.amount); accepted[dir] += acc; voided += d.amount-acc
   if d.amount > p.amount || d.getFluid()!=off.getFluid() → anomalies++
   tally.add(off.getFluid(), 0, acc, a); outcome = acc < a ? blocked(+moved if acc>0) : moved
} else blocked
```
- **Parity with GT:** the handler calls and amounts are the same as GT's. There is at most one fluid per op and no retries. Head-of-line blocking on multi-fluid sources is inherited from GT on purpose.
- **Allocation:** only `off.copy()`, which GT also makes.
- **Voiding** is counted and never "fixed".

### 2.5 Cover NBT (`d` is a compound)
| Key | Type | Meaning |
|---|---|---|
| `gs, idM, idL, lbl, owM, owL, owN, ct` | as design §3.3 | identity, `gs`=1 unchanged |
| `io` | int | GT `coverData` 0..11 (out of range → 0) |

**Read rules** (in pure `SensorNbtCodec`):
- `NBTTagInt` → `coverData` = that int, cover is inert.
- Compound with `gs=1` → identity + `io`.
- Compound with `gs>1` → keep the raw compound, read `io`, keep transferring with telemetry inert, and on save write the raw compound back with only `io` replaced.
- Anything else → `coverData = 0`, inert.

`tra` round-trips through GT, outside `d` (F5).

### 2.6 Registration and placement
```java
IIconContainer BADGE = Textures.BlockIcons.custom("gregscope", "iconsets/GREGSCOPE_METER_BADGE");     // E1
ITexture fg = TextureFactory.of(TextureFactory.of(OVERLAY_CONVEYOR), TextureFactory.of(BADGE));
CoverRegistry.registerCover(new ItemStack(ItemMeteringConveyor.INSTANCE, 1, t.index),
    TextureFactory.of(MACHINE_CASINGS[t.index][0], fg), ctx -> new MeteringConveyorCover(ctx, t, fg));
```
- **Placer:** GT's default placer (F11). Meters go wherever GT covers go.
- **Machine sensor rule:** v0.2 `isPlaceable` still checks **exactly** `MachineSensorCover`, so meters and machine sensors can sit on the same block without blocking each other.
- **Items:** `gregscope:metering_conveyor` and `gregscope:metering_pump`. The meta is the GT tier index (1..14, frozen now). `getSubItems` lists only the registered tiers.

---

## 3. Counting semantics (copied verbatim into `docs/flow-meters.md`)

- **accepted** is what the *adjacent block's handler* reported taken during this meter's own ops:
  - items: the `transfer()` return value, excluding world drops;
  - fluids: the real `fill` return value, clamped to [0, drained].
  - It means "handed to the adjacent block". Pipes, ME interfaces and void or creative tanks decide themselves what counts as accepted.
- **attempted**:
  - fluids: the simulated-drain amount, capped at the rate. This is exact.
  - items: **offered**, the sum of stack sizes offered to the sink, capped at `stacks × items` per op and always ≥ accepted. This is an *upper-bound proxy*, because under backpressure GT offers once per non-empty source slot.
  - `attempted − accepted` means backpressure, not loss.
- **lost**:
  - items: `dropped` (no sink, spawned into the world) + `spilled` (re-insert failed, dropped);
  - fluids: `voided` (real drain > real fill).
- **ops**: every executed cover op gets exactly one of `moved`, `blocked`, `idle`, `no_target`, `suppressed`. `blocked` is also counted for moved ops that were partly rejected. Ops are counted per cover op, not per second.
- **anomalies**: a handler contract violation, or a mismatch between the `transfer()` return and the counted total.
- **direction** is the GT IO mode at the time of the op. Counters are per direction.
- **Not counted:**
  - other blocks' transfers through the face (GT ALLOW mode or face rules);
  - anything while `flow.telemetry=false`;
  - anything on the client.
- **Invariants** (unit-tested):
  - 0 ≤ accepted[d] ≤ attempted[d];
  - totals never decrease within one instance;
  - moved + blocked_only + idle + no_target + suppressed = executed `doCoverThings` calls.
- **Resource identity:**
  - items: `i:<registryName>:<meta>` (NBT ignored);
  - fluids: `f:<Fluid.getName()>`.
  - Keys are sanitised to ≤64 characters. Numeric IDs are never used.
  - During the tick only references are compared. Strings are built only in the sampler.

---

## 4. History (no format bump)

### 4.1 v0.2 refactor (GS-201)
- Add a `SlotLayout` strategy with `id()`, `validate(bytes, off)` and `merge(older, newer, out)`.
- Add a `MinuteHeader` view over bytes 0-6, which are the same in every layout.
- `MinuteRing(SlotLayout)` and `GapRanges(MinuteHeader)`.
- `MinuteSlot` behaviour becomes `MachineSlotLayout(1)` with identical bytes.
- `SensorCounters` sits behind `CountersView` (Machine/Flow), so an unknown kind never reaches `onSample` (F16).

### 4.2 Slot layouts 2 (items) and 3 (litres): 64 B, big-endian, same schema
| Off | Type | Field |
|---|---|---|
| 0 | i32 | epochMinute |
| 4 | u8 | samples |
| 5 | u8 | expectedSamples |
| 6 | u8 | gapMask (bits 0-5 as §7.2) |
| 7 | u8 | flags: b0 counterReset, b1 partialMinute, b2 serverStartMinute, b3 ioModeChanged, b4 resourceUnresolved/overflow, b5 lastModeImport, b6 anomaly, b7 multipleResources |
| 8 | i64 | acceptedExport |
| 16 | i64 | attemptedExport |
| 24 | i64 | acceptedImport |
| 32 | i64 | attemptedImport |
| 40 | i64 | lostDelta (layout 2 dropped+spilled; layout 3 voided) |
| 48 | i32 | dominantResourceHash (FNV-1a-32 of key; 0 = none) |
| 52 | f32 | burstPeakPerSecond (max sample-interval accepted × 20 / intervalTicks; §15 `peakPerSecond` hook) |
| 56 | u48 | packed, MSB first: opsMoved 11, opsBlocked 11, opsIdle (idle+no_target) 11, opsSuppressed 11, b44 tickRateChanged, b45-47 = 0; counts saturate at 2047 |
| 62 | u16 | CRC-16/CCITT-FALSE over 0..61 |

- **Sizes:**
  - i64 is required: MAX pump ≈ 3.2e11 L/min, MAX conveyor ≈ 4.1e13 items/min.
  - 11 bits is enough: ≤1200 ops/min at 20 TPS.
  - f32 is exact up to 2^24 and approximate above that, which is fine for a burst indicator. Readers show `accepted/60` as the rate and label the peak as "burst".
- **Validation rejects:**
  - gap bits 6-7;
  - packed reserved bits;
  - any negative i64;
  - `accepted > attempted` in either direction;
  - NaN/negative peak;
  - `samples > expected`;
  - CRC mismatch.
- **Merge** (same minute reopened):
  - sums saturate, peak takes the max, flags OR;
  - hash: the side with the larger total accepted wins (tie goes to newer); if the hashes differ, set b7;
  - b5 comes from the newer side.
- **Per-minute tick rate** is not stored. The current tick rate lives in the registry and the second ring. A change sets b44.
- **`.gsh` file:** formatVersion 1, 92,224 B, `sensorKind` 1/2, `slotLayout` 2/3. A kind/layout mismatch is treated as unsupported and renamed (design §8.2).

### 4.3 `FlowSecondRing`: 300 × 32 B = 9,600 B, RAM only
`epochSec i32 | gapReason u8 | flags u8 (b0 import, b1 counterReset, b2 anomaly, b7 valid) | ops u8 (sat) | blockedOps u8 (sat) | accepted i64 | attempted i64 | lost i32 (sat) | resourceHash i32`

### 4.4 Resource dictionary (per sensor, in the registry entry)
- **Format:** `fl:[{n:string≤64, h:int, u:int lastUsedEpochMinute}]`, **at most 8 entries**. Up to 4 keys are interned per sample, in the sampler only.
- **Readers** find the name by scanning for a matching `h`. With no match they show `resource#<hash hex>` and never a wrong name.
- **Eviction** only when `u < newestMinute − 1439`. When no entry can be evicted, the slot stores the hash anyway and sets b4.

### 4.5 Ceilings
- **Per flow sensor:** 9,600 + 92,160 + ≈3,000 B ≈ **104.8 KB**.
- **Defaults:** 128 flow sensors → ≈13.4 MB RAM and 11.8 MB disk.
- **Maximum:** 1024 machine + 1024 flow → ≈211 MB. `SizeCeilings` and the startup INFO line sum both kinds.

---

## 5. Registry and sampler

### 5.1 Amendments v0.2 needs before GS-105/107/108/109/118 merge (ticket GS-201)
1. **A1 reverse index:** key it on `packedPos(dim,x,y,z)<<3 | side`.
   - REPLACED fires only for the same (pos, side).
   - For kind 0 only, the slow path also scans all 6 sides for another kind-0 UUID at that position.
   - A known UUID that heartbeats with a different kind is a duplicate and gets re-keyed.
2. **A2 `SensorCover {identity(); sensorKind();}`**, implemented by `MachineSensorCover`. `TargetResolver` checks the interface, the kind and the id; a kind mismatch counts as a `target_missing` strike.
3. **A3 `RegistryNbtCodec`** keeps entries with an unknown `kind`, and unknown keys, as verbatim NBT. Such entries are not counted, sampled or expired, and their `.gsh` is never opened. This keeps a v0.3 → v0.2 rollback safe.
4. **A4** `SensorCovers.isPlaceable` checks exactly `MachineSensorCover`.
5. **A5** the `SlotLayout`/`MinuteHeader` refactor (§4.1), covering all of F16.
6. **A6** `SensorView.lastSnapshot` is nullable for kind≠0. Add a nullable `FlowReading lastFlow`. `CountersView` becomes an interface.
7. **A7** `metrics-model.md`: `gregscope_sensors{kind,state}` and `sensor_info.kind`.
8. **A8** separate caps: `limits.maxFlowSensors=128 [0..1024]` and `limits.maxFlowSensorsPerTeam=32 [0..1024]`. `SizeCeilings` sums both kinds.

### 5.2 Entry additions for kinds 1 and 2
- **Persisted:** `tier:b`, `lastIo:b`, `fl` dictionary.
- **RAM only:**
  - `lastNonce`, `lastTotals` (primitives);
  - `FlowSecondRing`, `MinuteRing(layout 2|3)`, `FlowMinuteAccumulator`;
  - `FlowCounters` (process-lifetime and monotonic, used by OC and the exporter);
  - `lastFlow`.

### 5.3 `FlowSampler.sample` (O(1), no transfer work, shares the §6.3 budget)
1. `cover.readCounters(sink)` copies the primitives and drains the tally into a reused 5-row buffer.
2. If the nonce changed or any total dropped: baseline = 0, set `counterReset`, increment `flowCounterResetsTotal`. **Deltas are never negative.**
3. Fold into the second ring, the minute accumulator and the entry counters. Intern resource keys and update the dictionary. Close the minute on rollover.
4. `flushFinal` (unload, removal, destroy) runs steps 1-3 **without** incrementing `samples`, so nothing is lost at chunk unload.
5. Target: p99 ≤ 10 µs.

### 5.4 Latency and lifecycle
- Registration and resume happen on attach (immediate heartbeat), otherwise on the next cover op. That can take up to 1,300 ticks for an LV/MV conveyor at maximum addition.
- Between reload and the first op, the entry shows `chunk_unloaded`. This is documented.
- Counters accumulate from construction, so the first LIVE sample picks up every earlier count as a delta.
- An **over-cap meter still transfers**, because it is a working GT-equivalent cover. It simply isn't sampled.

---

## 6. Surfaces

### 6.1 Cover GUI
- **MUI2: `MeterIOGui extends CoverIOBaseGui`**
  - Calls `super.addUIWidgets` first, so GT's three mode rows and the tick-rate button are unchanged.
  - Then adds read-only `LongSyncValue`s: `gs_acc_exp`, `gs_att_exp`, `gs_acc_imp`, `gs_att_imp`, `gs_lost`, `gs_ops_moved`, `gs_ops_blocked`. Also `StringSyncValue gs_id` (≤48).
  - No C2S values are added.
  - Text: "Since load: exported 12,345 (offered ≤13,000) · imported 0 · lost 0" and "GT rate: 64 items / 20 t".
- **MUI1:** GT's factories, unchanged (modes only). This is documented. Counters appear in MUI2, the Hub, OC, and the scanner description.

### 6.2 Hub (design §9)
- **`gs_filter`** values: 0 All, 1 Problems, 2 Machines, 3 Flow. Anything else becomes 0.
- **`HubRow` gains:** `kind:b`, `unit:b`, `acceptedPerMin:long`, `attemptedPerMin:long`, `flowState:b`, `resourceKey≤64`. The client localises names from the key; the server never sends localised text.
- **`flowState`** is derived from the last 60 s and never persisted:
  - `flowing`: moved > 0 and not blocked;
  - `blocked`: blocked ≥ 50% of non-suppressed ops, counted as a Problem and sorted with `output_blocked`;
  - `starved`: all ops idle or no_target, sorted with `waiting`;
  - `halted`: only suppressed ops, or no ops for ≥ max(60 s, 3 × tickRate).
- **Detail pane:**
  - dominant resource, direction, tier / GT rate / tick rate;
  - 5 min and 24 h accepted, attempted (offered), lost, acceptance ratio, coverage;
  - an ASCII 24 h strip scaled to the busiest hour, with `?` for gaps;
  - the note "counts only its own transfers".

### 6.3 OpenComputers (Adapter addresses unchanged, E3)
- **`getInfo`:** `apiVersion=3`, plus `flowSensors` and `maxFlowSensors`.
- **`listSensors(offset, limit, [kind="machine"|"flow"|"all"])`:** the default is `"machine"`, so v0.2 scripts see exactly the same output.
- **Flow record:** `sensorRecordVersion=1`, `kind="item_flow"|"fluid_flow"`, with no machine keys. Its `flow` table:
  ```
  flow={unit="item"|"L", direction, condition, tier="HV", tickRate, capacityPerOp,
        acceptedTotal={export,import}, attemptedTotal={export,import}, droppedTotal?, spilledTotal?, voidedTotal?,
        ops={moved,blocked,idle,noTarget,suppressed}, anomalies, lastMinute={accepted,attempted,burstPeakPerSecond},
        resources={{key,accepted,attempted}…≤4}, state}
  ```
- **`getLatest(flowId)`** returns `record, nil`.
- **`getSensorHistory`:** `historyVersion=1`, with `kind` and `unit` added.
  - Minute rows: `{t, samples, expected, coverage, accepted={export,import}, attempted={…}, lost, burstPeakPerSecond, ops={moved,blocked,idle,suppressed}, resource?, direction, flags…, gaps}`.
  - Second rows: `{t, accepted, attempted, lost, direction, resource?}`.
- **`gt_machine.getFlowMeters()`:** up to 6 records, by side. Meters on pipes or tanks are reachable only through `gregscope_hub`.

### 6.4 Exporter model (fixed in `metrics-model.md`, implemented in v0.4)
- **Common:**
  - `gregscope_sensors{kind,state}`;
  - `gregscope_sensor_info{…,kind=machine|item_flow|fluid_flow}`;
  - `gregscope_sensor_state` for kind=machine only.
- **Flow families** (a direction is emitted only once it has been seen; a sensor has one unit family):

| Family | Series |
|---|---|
| `gregscope_sensor_items_accepted_total{sensor_id,direction}` / `…_fluid_accepted_liters_total` | ≤2 |
| `gregscope_sensor_items_offered_total{…}` / `…_fluid_attempted_liters_total` | ≤2 |
| `gregscope_sensor_items_lost_total` (dropped+spilled, per HELP) / `…_fluid_voided_liters_total` | 1 |
| `gregscope_sensor_flow_operations_total{outcome=moved\|blocked\|idle\|suppressed}` (`no_target` folded into idle, per HELP) | 4 |
| `gregscope_sensor_flow_anomalies_total` | 1 |
| gauge `gregscope_sensor_flow_capacity_per_second` | 1 |
| gauge `gregscope_sensor_flow_tick_interval_ticks` | 1 |
| gauge `gregscope_sensor_flow_state{state}` (one-hot) | 4 |

- **Cardinality:** 16 common + 16 flow = **32**, which is the §16.2 bound.
- **Globals:** `gregscope_flow_counter_resets_total`, `gregscope_flow_resource_unresolved_total`.
- **Never labels:** resource names and keys.

### 6.5 Commands
- `list [flow]`
- `info` (flow record plus 5 m / 24 h summaries)
- `stats` (flow counts, resets, anomalies)
- `meters dump` lists meter positions (useful before removing the mod)
- `purge` is unchanged

---

## 7. Recipes, textures, lang, config

**Recipes** (assembler, `postInit`, registered tiers only):
```java
GTValues.RA.stdBuilder()
  .itemInputs(ItemList.Conveyor_Module_<T>.get(1L) /* or Electric_Pump_<T> */,
              ItemList.Cover_ItemDetector.get(1L)   /* or Cover_FluidDetector */,
              GTOreDictUnificator.get(OrePrefixes.circuit, Materials.<T>, 1L))           // E8
  .circuit(8).fluidInputs(SubstituteFluidStack.soldering(1 * HALF_INGOTS))
  .itemOutputs(new ItemStack(item, 1, t.index)).duration(10 * SECONDS).eut(TierEU.RECIPE_<T>)
  .addTo(RecipeMaps.assemblerRecipes);
```
- The recipe consumes the same-tier GT cover, so a meter never costs less than the GT cover.
- Circuit 8 isn't used by any GT5U assembler recipe that consumes a pump or conveyor (`AssemblerRecipes.java:426-445,739-787,919-967`). The NHCore collision run and the NEI check from design §12.1 are still required.
- Meters are available from LV, so flow telemetry comes before the MV sensor. That is accepted.

**Textures:**
- One badge icon: `textures/blocks/iconsets/GREGSCOPE_METER_BADGE.png`.
- Ten generated item icons from `tools/gen_textures.py`.
- GT's animated overlays are referenced, not copied.
- No client multi-pass rendering.

**Lang:**
- `item.gregscope.metering_{conveyor,pump}.<tier>.name`
- `gregscope.tooltip.meter.1` "Moves exactly like the GT %s of the same tier"
- `.2` "Counts accepted and offered/attempted transfer"
- `.rate` "%s per %s ticks"
- `.3` "Removing GregScope turns this cover into nothing"
- `.unavailable`
- `gregscope.flow.state.*` ×4, `gregscope.flow.dir.*` ×2, `gregscope.meter.gui.*`
- Screwdriver chat reuses GT's `gt.interact.desc.*`.

**Config:**
```
limits { I:maxFlowSensors=128 [0..1024]  I:maxFlowSensorsPerTeam=32 [0..1024] }
flow   { B:telemetry=true          # false: meters still move exactly like GT, never register/count
         B:dropWhenNoTarget=true } # false: skip op instead of dropping into world (reduce-only)
```
There are no rate, filter or tier options.

---

## 8. Migration and compatibility
- **Format versions:**
  - cover `gs` stays 1 (the `io` key is added);
  - `registry.dat` stays v=1 (adds kinds 1/2, `tier`, `lastIo`, `fl`);
  - `.gsh` stays formatVersion 1, with new layouts 2/3;
  - OC `apiVersion` becomes 3; record and history versions stay 1;
  - Hub DTO changes are fine because the FML handshake requires matching versions.
- **v0.2 → v0.3:** nothing to migrate.
- **v0.3 → v0.2 rollback:**
  - meters become `CoverNone`, the GT cover inside is lost, and logistics stop;
  - flow registry entries and `.gsh` files are kept inert (A3);
  - a later re-upgrade resumes the entries whose covers still exist; the others are tombstoned by normal validation.
- **Removing GregScope:** same as the rollback case.
  - The warning goes in the release notes, the README and design §8.5, together with `/gregscope meters dump`.
  - The world stays loadable because an unknown cover item resolves to `CoverNone` (GS-106 pattern).
- **GT bump checklist** (enforced by GS-217 shape tests):
  - `CoverConveyor`: constructor, `tickRate`, `doTransfer` bytecode hash, private `stacksPerTransfer/itemsPerStack` values (test reflection only);
  - `CoverPump`: constructor, `mTransferRate`, `doTransfer`/`transferFluid`/`canTransferFluid`;
  - `GTUtility.moveFluid` bytecode hash;
  - `CoverIOBase.doCoverThings`;
  - `CoverLegacyData` NBT and packet;
  - `CoverIOBaseGui` constructor and sync names;
  - `ItemTransfer.transfer` return and `dropItems` rejectedStacks-if-null behaviour;
  - `ItemSink` instance method set;
  - `DroppingItemSink` constructor;
  - `FlowTiers` tables.

---

## 9. Risks
| Risk | Mitigation |
|---|---|
| Coupling to non-API `gregtech.common.covers` and GTNHLib `ItemTransfer` | Pinned GT version; shape and bytecode-hash tests; required parity suite; fail-closed probe |
| Rate bypass if GT lowers conveyor stacks or items | Required parity gametests plus a test-only reflection comparison in CI; tick rate and pump rate checked at runtime |
| Drops read as flow | `dropping` branch counts only toward lost; test `exportIntoAirCountedAsDropped` |
| Item `offered` is an overestimate under backpressure | Named "offered", capped, documented as an upper bound; exported as `items_offered_total` |
| Fluid voiding by foreign handlers | `voided` + `anomalies`; GT behaviour is never "fixed" |
| Decorator misses a future `ItemSink` instance method | Reflection method-set unit test (instance methods only) |
| ALLOW-mode or foreign transfers through the face are not counted | Documented; Hub note; exclusive mode deferred as opt-in |
| Counter reset on chunk reload | Nonce, `counterReset` flag, `flushFinal`, monotonic registry counters |
| Registration latency on slow covers (≤1300 t) | Immediate heartbeat on attach; cumulative counters; documented |
| Warp artifacts (frozen counter, heartbeat at most once) | Direct `doCoverThings` driver; warp only at rate 1; `heartbeatNow` in tests; 11-tick priming; every test asserts accepted > 0 |
| Void-mode Super Tank inflates accepted | Documented as adjacent-block semantics; fixtures assert void is off |
| No counters in MUI1 | Documented; available in MUI2, Hub, OC and scanner |
| GT cover lost when the mod is removed | Tooltip line 3, release notes, `meters dump` |
| RAM doubles at maximum caps | Flow default is 128; ceilings summed and logged |
| Hash collision in the ≤8-entry dictionary | About 8/2^32; worst case a wrong name within a single sensor's dictionary; accepted |
| NHCore recipe collision on circuit 8 | §12.1 collision flags and an NEI check in GS-215/GS-218 |

---

## 10. Spike plan (GS-202, time-box 3 d, on a branch)

Prototype in this order. Each step de-risks the next:
1. **S1 Pump parity and exact counts (0.5 d).** `MeteringPumpCover` at LV and IV, counters only, plus `FluidMover` fake-handler unit tests.
   - **Go:** tank deltas identical to GT `CoverPump` on twin Super Tank fixtures; destination at cap−50 gives attempted 160 / accepted 50 after 5 LV ops.
2. **S2 Conveyor decorator and drop detection (0.75 d).** `MeteringConveyorCover` at LV and IV with `CountingItemSink` and `SpillCounter`.
   - **Go:** IV chest→chest with 1000 cobble gives accepted == destination count == 1000 and the 17th op adds 0; export into air gives dropped == EntityItem count with direction counters at 0; parity with GT `CoverConveyor` at LV (16) and IV (64).
3. **S3 Competing pipe (0.5 d).** Feeder Super Tank → steel pipe → metered tank T → meter → D, warp 400.
   - **Go:** accepted == D exactly; conservation holds including the pipe `FluidTankInfo`; |ΔT| < accepted/4. That proves a passive delta would be wrong while the meter is exact.
4. **S4 NBT, packet, removal (0.5 d).** A compound `d` round-trips through GT's final `readFromNbt/writeToNBT`, `tra` is preserved, the client gets only the int, GT's MUI1 window and `MeterIOGui.buildUI` work on the dedicated server, and an unknown item ID NBT loads as `CoverNone`.
   - **Go:** all pass without reflection.
5. **S5 Tick cost (0.25 d).** Opt-in benchmark comparing GT cover vs meter (pump LV/IV, conveyor IV): steady state, 1,000 warm-up + 10,000 timed × 3.
   - **Go:** p99 overhead ≤ max(2 µs, 10%).
6. **S6 Negative control (0.25 d).** Temporarily make the meter call `store` twice or loop the pump twice.
   - **Go:** the parity tests fail.

**No-go handling:**
- S1/S2 parity cannot be reached through subclassing → fall back to the own-loop base (`extends CoverIOBase`, as in the other design), with GT face semantics kept.
- S3 inexact → stop v0.3 and reopen design §15.
- S4 fails → re-scope the NBT (sidecar identity in the registry keyed by pos+side).
- S5 over budget → profile; fix the allocations; if still over, drop the resource tally from the tick.

The written go/no-go note goes into `docs/flow-meters.md`.

---

## 11. Tickets (ordered)
**Sizes:** S ≤0.5 d, M 1-2 d, L ≈3 d.
**Test marks:** U = plain JVM, H = Horizon-QA, M = manual, B = opt-in bench.
**Every ticket:** `./gradlew build` green, with all v0.1/v0.2 suites passing.

**Order:** GS-201 (now, with the v0.2 tickets) → GS-202 (gate) → 203 → 204 → 205 → 206 → 207 → 208 → 209 → 210 → {211, 212, 213} → 214 → 215 → 216 → 217 → 218.
**Estimate:** ≈13–15 d including the spike.

### GS-201 v0.2 amendments for flow kinds (M), landed in v0.2 before GS-105/107/108/109/118 merge
- **Acceptance criteria:**
  - §5.1 A1–A8 implemented and documented in design-v0.2;
  - v0.2 byte formats unchanged (GS-103 fixtures pass as-is);
  - F16 code paths are behind `SlotLayout`/`CountersView`.
- **Unit tests:**
  - `RegistryCodecTest.unknownKindPreservedVerbatim` (fixture `registry-v1-kind2.dat`)
  - `.unknownKindNotCountedSampledOrExpired`
  - `RegistryCoreTest.sameBlockDifferentSideNotReplaced`
  - `.kindMismatchRekeys`
  - `.kindMismatchIsStrike`
  - `MinuteRingTest.layout1Identical`
  - `.foreignLayoutRejected`
  - `SizeCeilingsTest.sumsKinds`
- **Horizon-QA:** `SensorPlacementTests.machineSensorAllowedBesideOtherCovers` (GT conveyor on another face, plus a test-only kind-2 `SensorCover` stub).

### GS-202 Spike: prototype meters, parity gate (M-L, time-box 3 d)
- **Acceptance criteria:** §10 S1–S6 go criteria met; note written.
- **Unit tests:**
  - `FluidMoverTest.callSequenceEqualsMoveFluid` (a recording fake compares against `GTUtility.moveFluid` on the same fakes)
  - `GtTierTablesTest.matchesSourceRows`
- **Horizon-QA:**
  - `FlowSpikeTests.itemsChestToChestExact`
  - `.fluidDestFullAttemptedGeAccepted`
  - `.competingPipeDeltaCancel`
  - `.parityLvIv`
  - `.nbtCompoundRoundTrip`
  - `GtCoverShapeTests` (constructors, public fields, protected hooks, private conveyor values via test reflection, bytecode hashes)
- **Bench:** `MeterCostBenchmarkTests`.

### GS-203 Pure flow model (M)
- **Scope:** `FlowCounters`, `ResourceTally`, `FlowAccounting`, `FluidMover`, `CountingItemSink`, `SpillCounter` logic, `SensorNbtCodec` `io`. All marked `[pure]` and registered in `PureSourcesTest`.
- **Acceptance criteria:**
  - §3 invariants hold on randomized op sequences;
  - no allocation on the counting path;
  - read rules as in §2.5.
- **Unit tests:**
  - `FlowAccountingTest`: dropping goes to lost only; offered cap; outcome partition sums to ops; mismatch → anomaly; long at MAX × 1200.
  - `FluidMoverTest`: over-drain, null real drain, fluid mismatch, real fill < simulated → voided, filter false → blocked, never more than the rate.
  - `CountingItemSinkTest`: forwards every declared instance method except `then`; accepted == Σ(n−r).
  - `ResourceTallyTest`: K=4 plus other, last-hit.
  - `SensorNbtCodecTest`: int blob inert, gs>1 raw kept with `io` replaced, `io` out of range → 0.
  - `AllocationTest.countingPathZeroAlloc`.

### GS-204 Layouts 2/3, second ring, accumulator, dictionary (M)
- **Acceptance criteria:**
  - §4.2 offsets exact, with Python golden fixtures;
  - merge rules;
  - dictionary never evicts inside 1440 minutes;
  - hash lookup never returns a wrong name.
- **Unit tests:**
  - `FlowSlotCodecTest` with `fixtures/v1/flow_l2_valid.hex`, `flow_l3_valid.hex`, `torn`, `acceptedGtAttempted`, `packedReservedBit`, `nanPeak`
  - `.mergeRules`
  - `.opsSaturate2047`
  - `FlowAccumulatorTest.counterResetNoNegative`
  - `.flushWithoutSample`
  - `.burstPeakScaling`
  - `ResourceDictionaryTest.noReuseInsideWindow`
  - `.fullSetsUnresolvedFlag`
  - `.unknownHashShowsHex`
  - `LayoutSizesTest.flowSecondRing9600`
  - `GapRangesTest.flowHeader`

### GS-205 Items, tier probe, registration, assets, lang (M)
- **Acceptance criteria:**
  - metas 1..5 are covers and 6..14 place nothing;
  - probe fails closed on a non-instance or mismatch (injected fake probe);
  - pump rate = min(pinned, probed);
  - assets and lang complete;
  - no client references on the dedicated server.
- **Unit tests:**
  - `FlowTiersTest.probeMismatchUnregisters`
  - `.pumpRateNeverAbovePinned`
  - `AssetsExistTest` (+10 items, badge)
  - `LangKeysTest`
- **Horizon-QA:**
  - `MeterRegistrationTests.probeMatchesGtAllTiers`
  - `.coverPlacesOnMachinePipeAndTank`
  - `.unregisteredMetaNotCover`
  - `SafetyTests.noClientRefsInMeters`

### GS-206 `MeterSupport`: identity, NBT, heartbeat, flush (M)
- **Acceptance criteria:**
  - constructor has no side effects;
  - immediate heartbeat on attach;
  - throttle ≥ 20, running before the condition gate;
  - suppressed ops counted;
  - `flushFinal` before transitions;
  - copy tool refused;
  - `telemetry=false` still transfers;
  - foreign blob inert but transferring.
- **Unit tests:** `MeterNbtTest`, `HeartbeatThrottleTest` (counter wrap and frozen timer).
- **Horizon-QA:**
  - `MeterLifecycleTests.attachRegistersImmediately`
  - `.nbtRoundTripKeepsModesAndTra`
  - `.packetIsSingleInt`
  - `.copyToolRefused`
  - `.suppressedStillHeartbeats` (uses `heartbeatNow`)
  - `.telemetryOffStillMoves`
  - `.foreignBlobInertTransfers`
  - `.removalDegradesToCoverNone`

### GS-207 `MeteringConveyorCover` (M)
- **Acceptance criteria:** §2.3; per-op parity with GT `CoverConveyor` for LV..IV; drops and spills separated; `dropWhenNoTarget=false` skips the op.
- **Horizon-QA** (direct `doCoverThings`; IV also under warp):
  - `ConveyorMeterTests.exactChestToChest`
  - `.destPrefilled1700Accepted28Conservation` (no EntityItem)
  - `.exportIntoAirCountedAsDropped`
  - `.dropDisabledCountsNoTarget`
  - `.importModeCountsImport`
  - `.conditionalSuppressedCounted`
  - `.multipleItemTypesAttributed`
  - `.parityPerTierLvToIv` (same moved count; `getMinimumTickRate` equal; `getTickRate() ≥ GT` after `setTickRateAddition`)
  - `.realTickSmokeLv` (≤110 real ticks → 1 op, 16 items)
  - `.negativeControlDetectsDoubleStore`

### GS-208 `MeteringPumpCover` (M)
- **Acceptance criteria:** §2.4; identical tank deltas to GT `CoverPump` for LV..IV; exact attempted, accepted and voided; `no_target` when there's no neighbour handler.
- **Horizon-QA** (Super_Tank_LV front DOWN, void off asserted, primed 11 ticks):
  - `PumpMeterTests.parityPerTierLvToIv`
  - `.destFullAttempted160Accepted50`
  - `.competingPipeExact`
  - `.multiInputHatchOnlyFirstFluid`
  - `.importDirection`
  - `.noNeighbourCountsNoTarget`
  - `.neighbourAllowModeTransferNotCounted`

### GS-209 Registry and sampler for kinds 1/2 (L)
- **Acceptance criteria:**
  - §5.2–5.4;
  - no transfer work in the sampler;
  - no negative deltas;
  - unload between samples loses nothing;
  - independent caps;
  - over-cap meter still moves;
  - flow sample p99 ≤ 10 µs;
  - frame immutable (GS-118).
- **Unit tests:** `SensorRegistryCoreTest.flowCapsSeparate`, `.flowReplacedOnlySameSide`, `FlowStateTest` (4 derivations).
- **Horizon-QA:**
  - `FlowSamplerTests.minuteDeltasEqualCounterDeltas` (heartbeatNow, sampleNow ×60 with FakeClock, runIntervalNow)
  - `.chunkUnloadFlushesFinal`
  - `.reloadSetsCounterReset`
  - `.dominantResourceHash`
  - `.overCapMeterStillMoves`
  - `.sampleCostWithinBudget` (256 mixed sensors, `samplingSkippedTotal==0`)

### GS-210 Flow persistence (M)
- **Acceptance criteria:** `.gsh` kinds 1/2 with layouts 2/3; `fl`, `tier` and `lastIo` persisted; restart keeps 24 h history and names; kind/layout mismatch renamed; housekeeping kind-agnostic.
- **Unit tests:** `FlowFileCodecTest` (valid, mismatch, torn), `RegistryCodecTest.flowEntryRoundTrip`.
- **Horizon-QA:** `FlowPersistenceTests.historySurvivesReload`, `.dictionaryResolvesAfterReload`.

### GS-211 Meter cover GUI (S)
- **Acceptance criteria:** §6.1; GT handlers present and working; `gs_*` read-only; MUI1 `createWindow` is GT's factory; works on the dedicated server.
- **Horizon-QA:**
  - `MeterGuiServerTests.gtModeHandlersPresent`
  - `.ioModeSetterChangesCoverData`
  - `.countersMatchCover`
  - `.noGregScopeC2S`
  - `.mui1WindowIsGtFactory`

### GS-212 Hub flow rows (M)
- **Acceptance criteria:** §6.2; filter clamp; codec caps; blocked counts as a Problem.
- **Unit tests:** `HubRowCodecTest.flowFieldCaps`, `HubSortTest.flowSeverity`, `DetailFormatTest.flowStripGaps`.
- **Horizon-QA:** `HubGuiServerTests.flowFilterClamps`, `.blockedMeterIsProblem`, `.flowRowRateAndResource`.

### GS-213 OpenComputers flow API (M)
- **Acceptance criteria:** §6.3; v0.2 scripts unchanged (golden output); `docs/examples/gregscope-flow.lua`.
- **Unit tests:** `FlowRecordKeysTest`, `HistoryRowsNoNilHolesTest`.
- **Horizon-QA:**
  - `OcFlowTests.listSensorsDefaultMachineOnly`
  - `.flowRecordShape`
  - `.flowHistoryRows`
  - `.getFlowMetersOnMachine`
  - `.adapterAddressUnchanged`
  - `.flowExampleScriptByteForByte`

### GS-214 Metrics model and commands (S)
- **Acceptance criteria:** §6.4 table in pure `FlowMetricNames`; exactly ≤32 series per flow sensor; no resource labels; §6.5 commands.
- **Unit tests:** `MetricsModelTest.flowCardinality32`, `.noResourceLabels`, `.helpTextStatesOfferedUpperBound`.
- **Horizon-QA:** `CommandTests.listFlow`, `.infoFlowRecord`, `.metersDump`, `.statsFlowSection`.

### GS-215 Recipes (S)
- **Acceptance criteria:** 10 recipes (inputs, circuit 8, `RECIPE_<T>`, output meta), only for registered tiers; no empty-OreDict log line.
- **Horizon-QA:** `RecipeTests.meterRecipesPerTier`, `.noEmptyOreDictLog`, `.probeFailedTierHasNoRecipe`.
- **Manual:** NHCore collision flags and NEI check.

### GS-216 Tick-cost benchmark (S)
- **Acceptance criteria:** §10 S5 method on the final code; results in `docs/testing.md`; p99 overhead ≤ max(2 µs, 10%); flow sample p99 ≤ 10 µs. If missed, a follow-up is opened before release.
- **Bench:** `MeterCostBenchmarkTests` (`-Dgregscope.bench=true`).

### GS-217 Docs, shape and safety tests (M)
- **Acceptance criteria:**
  - `docs/flow-meters.md` (§3 verbatim, face semantics, head-of-line blocking, offered upper bound, removal warning);
  - design §8.5/§17 updates;
  - config and OC docs;
  - §8 GT bump checklist enforced.
- **Unit tests:** `DocsCoverageTest` (new keys, callbacks, metric families), `ShippedClassesTest.noReflectionMixinAtInFlow`.
- **Horizon-QA:** `GtApiShapeTests` extended with §8 members, `.meterClassesLoadOnDedicatedServer`, `.neverLoadsChunk`.

### GS-218 Manual validation and release (M)
- **Acceptance criteria:** GS-121-style checklist on the real pack's dedicated server:
  - MUI1 and MUI2 cover GUIs (modes, jackhammer, counters);
  - Hub flow view;
  - restart persistence;
  - meters on GT pipes, a Super Tank, and an ME interface (accepted semantics);
  - 2 h with ≥20 meters: sampler p99 ≤ 1 ms/tick, anomalies 0;
  - rollback v0.3 → v0.2 → v0.3 keeps entries;
  - mod removal on a world copy loads;
  - release notes cover GT cover loss, "counts only its own transfers", offered upper bound, LV–IV only.
- **Tests:** manual; full CI suite ≤ 300 s.

### Later
- v0.3.1: LuV/ZPM/UV (per-tier parity required first).
- v0.3.2: UHV..MAX.
- Metering fluid regulator (subclass `CoverFluidRegulator`, per-op cap `mTransferRate × tickRate`).
- Opt-in exclusive face and single-resource filter, only if users ask for them. Both are labelled as differing from GT.
