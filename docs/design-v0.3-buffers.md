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
