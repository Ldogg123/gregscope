# GregScope v0.3: machine buffer telemetry — design

**Replaces the cancelled flow meters** ([flow-meters.md](flow-meters.md) has the reasoning). Where a meter could
only see what flowed through its own cover, this reads what the **machine itself is holding** — whoever put it
there. No new blocks, no new covers, no recipes, no new plumbing for the player.

## 1. What this answers, and what it does not

| Question | Answered |
|---|---|
| "Is this EBF about to run out of oxygen?" | **Yes.** Input buffer level, and whether it is falling. |
| "Is this machine's output backing up?" | **Yes.** Output buffer level against capacity. |
| "What is in this machine right now?" | **Yes**, feed-agnostic. |
| "How much oxygen per second does it consume?" | **No.** A level delta cannot separate consumption from refill. |
| "Who supplied it?" | **No.** |

The second half is written into the player docs and the metric help text, not left for a chart to imply. This is the
same rule that cancelled flow meters: a confident wrong number is worse than no number.

## 2. Two findings that shape the implementation

Both were verified against GT5U 5.09.54.133 before any code was written.

### 2.1 `getStoredFluids()` is not usable — it writes to the machine

The obvious API is `MTEMultiBlockBase.getStoredFluids()`. GregScope **cannot call it**:

```java
protected void setHatchRecipeMap(MTEHatchInput hatch) {
    if (filtersFluid()) {
        hatch.mRecipeMap = getRecipeMap();   // a write, on every call
    }
}
```

`getStoredFluidsForColor` calls that for every input hatch (`MTEMultiBlockBase.java:1910-1916`). GregScope's central
promise is that it never writes to a machine, and that promise is enforced by tests. It also allocates an
`ArrayList` and a `HashMap` per call, which a sampler on a 1 ms/tick budget should not do.

**Instead:** iterate the public hatch lists directly — `mInputHatches`, `mOutputHatches`, `mInputBusses`,
`mOutputBusses` (`:229-232`) — and read each one through `MTEBasicTank`'s getters, `getFluid()`,
`getFluidAmount()`, `getCapacity()`. All read-only, no GT allocation, and GregScope controls the cost. Basic
machines use `getFillableStack()` / `getDrainableStack()` and their own `mInputSlotCount` slots.

### 2.2 ME hatches: read the network, not the local buffer

First attempt was to flag ME inputs and report no amount, on the grounds that a stocking hatch's local buffer is not
the machine's supply. That was too pessimistic, and GT already solves it.

`MTEHatchInputME.getTankInfo()` resolves **each configured slot against the ME network**:

```java
IAEFluidStack request = AEFluidStack.create(slot.config);
request.setStackSize(Integer.MAX_VALUE);
IAEFluidStack result = sg.extractItems(request, Actionable.SIMULATE, getRequestSource());
```

`SIMULATE` takes nothing, so it stays read-only, and the number that comes back is **what the network holds** —
which is what a player actually wants to know. So the walk reads every hatch through `getTankInfo` rather than
through the tank getters: a plain hatch answers with its own tank, an ME hatch answers with the network, and
GregScope needs no AE2 dependency at all because it never names an AE2 type.

One adjustment falls out of it. An ME hatch reports `Integer.MAX_VALUE` as capacity, meaning "the network, however
big that is". Treating that as a real capacity would make saturation read ~0% for a machine that is in fact
perfectly supplied, so it is recorded as **unmeasurable** instead. An ME-backed input is not a buffer that can be
full, and saying it is 0% full would be the confident wrong number this design keeps trying to avoid.

**Items are not solved this way, and are deferred.** `MTEHatchInputBusME.getStackInSlot` returns `null` for stocked
slots outside recipe processing, and the method that does hold the network amount, `updateInformationSlot`,
**writes** to the hatch (`slot.extracted = ...`), so GregScope cannot call it. Reading item stock would mean going
to the ME network directly — `getProxy().getStorage().getItemInventory()` and a `SIMULATE` extract of our own, the
way OpenComputers' AE2 integration does. That works, but it costs a compile dependency on AE2 and a class-guard so
GregScope still loads without it (the pattern v0.1 already uses for Forestry's apiary). Deferred to its own ticket
(GS-306) so the dependency is a decision in its own right rather than a side effect of this one. Until then, an ME
item bus contributes a flag and no amount, and the UI says so.

## 3. What is captured

Per machine, per sample, into the snapshot:

- **fluidsIn / fluidsOut**: up to `K` entries of `{fluid, amount, capacity}`, biggest first, plus an `other` rollup
  and a count. `K = 4`, matching the resource-tally width the cancelled design had settled on.
- **itemsIn / itemsOut**: the same shape, `{item, count}`.
- **inputSaturation / outputSaturation**: `amount / capacity` across the buffers of each direction, `NaN` when
  there is no capacity to speak of. This is the single number worth alerting on and the one the Hub shows.
- **meInputs**: a count of ME-backed inputs. Fluids from those hatches carry real network amounts (section 2.2);
  item busses contribute the flag only, until GS-306.

Resource identity follows the rules the cancelled design already fixed: `f:<Fluid.getName()>` and
`i:<registryName>:<meta>`, sanitised to 64 characters, never numeric IDs. Strings are built in the sampler, never in
the tick.

## 4. Trend, without a new file format

"Falling" is the whole point, and it does not need a format bump. The existing `SecondRing` carries 300 samples at
28 B (`SecondRing.java:33`), and the minute slot has two reserved `u16` fields
([history-format-v1.md](history-format-v1.md) §4). The 5-minute trend is computed from the second ring at read
time, so nothing new is persisted and `.gsh` stays format 1 at 92,224 B.

A 24-hour buffer history would need a slot layout change. **Out of scope**, deliberately: the useful signal is "is
it falling right now", and the 24-hour view already has the state history to explain what happened.

## 5. Cost

This is the risk. A multiblock can have dozens of hatches, and the sampler must stay inside its 1 ms/tick budget
with up to `limits.maxSensors` machines.

- The walk is O(hatches) with a reused buffer and no allocation on the sampling path.
- Only the top `K` are kept, by a bounded insertion into a fixed array — no sort, no collection.
- `ProbeBenchmarkTests` gains a case with a large multiblock, and the budget stays the design's p99 ≤ 1 ms/tick.
- If a real EBF with many hatches does not fit, the fallback is to sample buffers every *n*-th cycle rather than
  every cycle, and to say so in the docs.

**Measured (2026-09-18, GS-302):** `ProbeBenchmarkTests.bufferWalkOnAFormedEbf`, 10,000 timed calls on a formed
EBF holding water and cobblestone — **p50 0.3 µs, p99 0.7 µs**. For scale, the existing snapshot of the same
machine is p50 3.4 µs / p99 16.9 µs, so the walk adds well under a tenth of what the probe already costs, against a
1 ms/tick budget. The gate is met and section 5's every-*n*-th-cycle fallback is not needed.

One honest caveat: GT's own EBF template has few hatches. The walk is O(hatches), so a 30-hatch multiblock would
cost proportionally more — roughly 2 µs on this machine, still negligible. If a pathological build ever shows
otherwise, the fallback stands.

## 6. Surfaces

- **Snapshot**: new keys, additive. Schema stays v1 — v0.1 consumers keep working, and the OC contract's promise is
  that existing keys do not change, not that none are added. `SnapshotKeys.ORDER` grows; `DocsCoverageTest` makes
  the new names documented.
- **Hub**: the detail panel gains a saturation line. Width-budgeted, per the GS-121 lesson.
- **OpenComputers**: `getSnapshot` carries the new keys; no new callback.
- **`/gregscope info`**: the buffer lines.
- **metrics-model.md**: `gregscope_machine_buffer_fill_ratio{sensor_id,direction}` and a per-resource gauge, with
  the "level not throughput" caveat in the help text.

## 7. Tickets

| # | Scope | Size |
|---|---|---|
| GS-301 | `BufferReading` + `Buffers` pure model: top-K selection, saturation, ME marking, identity keys | M |
| GS-302 | Probe: read multiblock hatches and basic machine tanks/slots, read-only, no allocation | M |
| GS-303 | Snapshot keys, OC, `/gregscope info` | S |
| GS-304 | Hub detail line and the 5-minute trend from the second ring | M |
| GS-305 | Benchmark, docs (`sensors-and-hub.md`, `metrics-model.md`, `testing.md`) | S |
| GS-306 | ME **item** stock via a direct network query, with an AE2 soft dependency and a class guard | M |

**Order:** GS-301 → GS-302 → GS-303 → GS-304 → GS-305.

**Gate before GS-303:** GS-302's benchmark must show the walk fits the budget on a large multiblock. If it does not,
section 5's fallback applies and the design is amended before any surface is built on it.


### v0.3 GS-301 to GS-305 (2026-09-19): machine buffer telemetry

Replaces the cancelled flow meters. Where a meter could only see what passed through its own cover, this reads what
the machine itself is holding, so an AE2 hatch, an EnderIO conduit and a GT pipe all look the same to it.

**Three findings shaped it, each verified before code was written.**

1. **`getStoredFluids()` is unusable.** It calls `setHatchRecipeMap` for every input hatch, which assigns
   `hatch.mRecipeMap` - a write to the machine, and never writing to a machine is the product's whole premise. The
   walk goes through the public hatch lists instead. `BufferProbeTests.theWalkDoesNotWriteToTheMachine` proves it
   both ways: it clears the field, runs the walk, asserts nothing moved, then calls GT's own method and asserts it
   **does** set it - "walk wrote nothing; getStoredFluids set mRecipeMap on 1 hatches".
2. **ME fluid hatches can be read honestly after all.** The first design flagged them and reported no amount. The
   user pushed back, correctly: the interesting number is what the network holds, and `getTankInfo` already
   resolves each configured slot against the network with `extractItems(..., SIMULATE, ...)`. Reading every hatch
   through `getTankInfo` therefore gets network amounts with **no AE2 dependency at all**. ME item busses are the
   exception - `getStackInSlot` returns null outside recipe processing and the method that holds the amount writes
   to the hatch - so they stay flagged-only until GS-306.
3. **The reserved byte was enough for the trend.** The second ring's `u16` at offset 10 now carries input
   saturation, so the five-minute direction comes from 300 real samples with no format bump and no extra memory.

**Cost, measured rather than asserted:** p50 0.3 us, p99 0.7 us per machine, against the 1 ms/tick budget the
sampler shares. The existing snapshot of the same machine is p50 3.4 us, so the walk adds under a tenth of what the
probe already cost.

**The honesty rules, and where they are enforced.** Saturation is `NaN` when nothing reports a capacity, and that
is kept distinct from `0.0` at every layer: the model, the codec, `/gregscope info` ("n/a"), the Hub ("n/a") and the
exporter contract. An ME-backed input is not a buffer that can be full; an empty tank with room genuinely is 0%.
And nothing anywhere is a rate - `flow-meters.md` records why.

**Two things the tests caught in my own work.** The deadband was compared on raw doubles, so a 0.50-to-0.52 ramp
decoded from permyriad as 0.020000000000000018 and exactly-the-deadband read as a rise; it now compares at the
ring's resolution. And `TelemetryFrameContractTest.theDocumentedCardinalityIsTheSumOfTheFamilyTables` refused the
new metric families until the documented per-sensor ceiling moved from 48 to 55 and the totals table was
recomputed - which is exactly the job that test was written to do.

**One design correction.** The design's section 6 proposed "a per-resource gauge" for the exporter. That would have
violated the metrics model's own never-labels rule: resource names as labels, on a base with hundreds of machines,
multiplies series without bound. The exported families are seven single-series gauges instead, and the per-resource
breakdown stays in the snapshot, the Hub and OpenComputers, where it is read one machine at a time. The **trend is
not exported either**, on purpose: Prometheus derives direction from a gauge's own history with `deriv()` far
better than a fixed five-minute enum could.

**Results.** 677 unit tests in 46 classes and 175 in-game tests green on a fresh world. Worst-case batch budget
6,370 ticks = 318.5 s over 43 batches, against the 600 s CI step.

**Open:** GS-306, ME item stock through a direct network query, which needs an AE2 compile dependency and a class
guard. Deliberately not smuggled in as a side effect of this milestone.


### GS-306 (2026-09-19): ME stocking-bus item stock

The ME **fluid** side was solved for free in GS-302 - `getTankInfo` already asks the network. Items needed a real
query, and the routes GT offers are all closed to a mod that will not write to a machine or use reflection:

| Route | Verdict |
|---|---|
| `MTEHatchInputBusME.slots` | `protected`. Reflection only. |
| `updateInformationSlot(int)` | Public, and **writes** `slot.extracted` / `slot.extractedAmount`. |
| `getStackInSlot` on a stocked slot | Returns `null` outside recipe processing. |
| `getCopiedData(player)` | **Public, read-only**, and lists the configured stacks under `itemsToStock`. |

So `MeItemProbe` reads the configuration out of the data-stick export and prices each entry against
`getProxy().getStorage().getItemInventory()` with `extractItems(..., Actionable.SIMULATE, ...)`. SIMULATE takes
nothing, so the read stays read-only end to end.

**No AE2 dependency was added.** AE2 is already on the compile classpath transitively through GT, and
`MeItemProbe` is the only class that names an AE2 type. It is referenced from exactly one place - inside the branch
where `BufferProbe` has already identified an ME bus by class name - and an ME bus cannot exist without AE2, so on
a pack without it the class is never loaded. Same guard shape as v0.1's Forestry apiary handling.

**It is deliberately off the sampling path.** `getCopiedData` allocates a fresh `NBTTagCompound` and serialises
every configured stack into it, then each stack costs a network lookup. That is far heavier than the 0.3 us buffer
walk, and it would run for every machine every sample. `MachineProbe` therefore gained `detailedSnapshotAt`, which
the OpenComputers machine component calls and the sampler does not: a script that asks can pay, a per-tick loop
cannot. The asymmetry is documented in the user guide rather than left to be discovered.

**Honest limitation, recorded rather than glossed.** GregScope's Horizon-QA suite runs without AE2, so **this path
has never executed against a real ME network.** It is read-only by construction and every call is wrapped against a
downed or unformed network, but "compiles and is structurally safe" is not "verified". Building an ME controller,
drive, cells and power inside a game test to cover it is possible and was not done. Manual checklist row **M16**
exists for exactly this, and the user guide carries a warning admonition saying the same thing to players.

**Also not handled:** a bus in auto-pull mode has no fixed configuration - it takes whatever the recipe asks for -
so there is no list to price and it stays flagged-only. Reporting the whole network instead would be a different
and much larger claim.
