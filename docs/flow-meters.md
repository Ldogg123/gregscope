# Flow meters (v0.3) — spike notes

Design: [design-v0.3.md](design-v0.3.md). This file carries the go/no-go findings the spike (GS-202) is required to
produce, written as they are measured rather than at the end.

Branch: `spike/gs-202-flow-meters`. Nothing here is shipped yet.

---

## S1 — Pump parity and exact counts: **GO**

*2026-09-18, GT5U 5.09.54.133*

### The finding that shapes the whole design

**`GTUtility.moveFluid` returns `void`.** (`GTUtility.java:1054-1064`.) It reports nothing about how much fluid it
moved, so the obvious implementation — let GT do the transfer and read back the amount — is not available. That is
not a detail; it is the reason the v0.3 design has a `FluidMover` component at all, and it was worth confirming
first because the fallback differs depending on the answer.

Reading tank levels before and after is not a substitute, and the design already says why: section 10 S3's competing
pipe moves fluid through the same tank in the same tick, so a level delta credits another block's transfer to the
meter. Measuring the *transfer* is the only exact option.

So GregScope performs the moves itself and counts as it goes. That is only safe if it does **exactly** what GT does,
which is what the parity gate below establishes.

### GT's sequence, and the three details that matter

```java
FluidStack liquid = source.drain(drainSide, maxAmount, false);   // simulate
if (liquid == null) return;
liquid = liquid.copy();
liquid.amount = dest.fill(fillSide, liquid, false);              // simulate
if (liquid.amount > 0 && (allowMove == null || allowMove.test(liquid))) {
    dest.fill(fillSide, source.drain(drainSide, liquid.amount, true), true);
}
```

1. **The filter sees the fillable amount, not the offered one.** `liquid.amount` is overwritten by the simulated
   fill before `allowMove.test` runs.
2. **The real drain is an argument to the real fill.** The drain runs first, and whatever it returns — including
   `null`, or less than was asked for — goes straight into `fill`. GT never checks it, so a handler that returns
   `null` there still receives a `fill(side, null, true)` call, and is entitled to count it.
3. **Nothing puts a shortfall back.** If the real fill accepts less than the real drain removed, the difference is
   simply gone. Section 3 calls that `voided` and counts it as loss; a meter that quietly under-reported instead
   would be hiding real fluid loss from the player.

### Evidence

`FlowSpikeTests.moverMatchesGtCallSequence` runs GT's own `moveFluid` and GregScope's `FluidMover` against two
handlers built identically, and compares the recorded call sequence on **both** handlers, argument for argument,
plus the resulting tank contents. Ten scenarios, all matching:

| Scenario | Result |
|---|---|
| plain move | `MOVED(attempted=1000, accepted=1000, voided=0)` |
| LV rate | `MOVED(attempted=32, accepted=32, voided=0)` |
| IV rate | `MOVED(attempted=8192, accepted=8192, voided=0)` |
| empty source | `IDLE(attempted=0, accepted=0, voided=0)` |
| full destination | `BLOCKED(attempted=1000, accepted=0, voided=0)` |
| **destination at cap−50** | **`MOVED(attempted=160, accepted=50, voided=0)`** |
| fluid mismatch | `BLOCKED(attempted=1000, accepted=0, voided=0)` |
| short real drain | `MOVED(attempted=1000, accepted=30, voided=0)` |
| real fill below simulation | `MOVED(attempted=1000, accepted=20, voided=980)` |
| real drain returns null | matches, including GT's `fill(side, null, true)` |

The bolded row is the design's stated S1 go criterion — "destination at cap−50 gives attempted 160 / accepted 50" —
met exactly.

`FluidMoverTest` covers the same rules on a plain JVM (15 cases), where GT cannot be loaded.

### S6 negative control, and the hole it found

The control was the plausible defensive bug: skip the real `fill` when the real drain returned `null`.

**It passed the first time.** Not because the mover was right, but because none of the nine scenarios then in the
table produced a null real drain — the "short real drain" case returns a smaller parcel, not nothing. The parity
test could not see the difference at all.

Adding a `real drain returns null` scenario closed it, and the control then failed exactly as it should:

```
real drain returns null: the mover called the DESTINATION differently from GTUtility.moveFluid:
expected <[fill(SOUTH,waterx1000,false), fill(SOUTH,null,true)]> but found <[fill(SOUTH,waterx1000,false)]>
```

Recorded because it is the more useful half of the result: a parity test is only worth the scenarios in its table,
and the control is what proves the table covers the behaviour being claimed.

### Verdict

**GO.** Exact counting is achievable without changing GT's behaviour, and the fidelity is enforced by a test that
demonstrably fails when fidelity is broken. The section 10 no-go fallback (an own-loop base extending
`CoverIOBase`) is not needed for the fluid path.

**Not yet established** — these are the remaining gates, and none of them is implied by S1:

- **S2** items: the conveyor decorator, `CountingItemSink`, and drop detection.
- **S3** the competing pipe, which is the case that justifies measuring transfers rather than tank deltas.
- **S4** cover NBT round-trip through GT's final `readFromNbt`/`writeToNBT`, and the GUI on a dedicated server.
- **S5** tick cost: p99 overhead ≤ max(2 µs, 10%).

`MeteringPumpCover` itself is not written yet. S1 proves the counting core; wiring it into a `CoverPump` subclass is
the next step, and `CoverPump.transferFluid` being `protected` (`CoverPump.java:46`) is what makes that look
feasible.
