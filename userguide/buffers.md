# What a machine is holding

A sensor also reports the machine's own **buffers** — what is in its hatches, tanks and slots right now.

This is read from the machine itself, so it is the same whoever filled it: a GT pipe, an EnderIO conduit, an AE2
stocking hatch, or you by hand. There is nothing to configure and nothing to route through GregScope.

## The line that matters

The Hub shows it as:

```
in 12% FALLING
```

A machine at 12% is unremarkable. A machine at 12% **and falling** is about to stop. That difference is the whole
point of the feature — otherwise you would have to sit watching the screen to notice.

The direction comes from the last five minutes. It reads `RISING`, `FALLING`, `steady`, or nothing at all when
there is not enough to go on.

## What it can tell you

| | |
|---|---|
| "Is this EBF about to run out of oxygen?" | **Yes.** That is what the line is for. |
| "Is the output backing up?" | **Yes** — output saturation climbing toward 100%. |
| "What exactly is in there?" | **Yes** — the largest four by amount, then a rollup of the rest. |

## What it cannot tell you

| | |
|---|---|
| "How much oxygen per second does it use?" | **No.** |
| "Who supplied it?" | **No.** |

GregScope measures **levels, not rates**. A level that dropped by 500 L might be 500 consumed, or 1,000 consumed
while 500 arrived — indistinguishable from outside the machine.

??? question "Why not just meter the flow?"
    It was designed, prototyped, and then deliberately abandoned.

    A metering cover can only count what passes **through the cover**. In a GTNH base fed by GT pipes, EnderIO
    conduits and AE2 interfaces, a meter would see one route and be blind to the rest — so the Hub would cheerfully
    report "fluid in: 0" for a machine running perfectly well on an AE2 hatch.

    That does not read as *unmeasured*, it reads as *broken*. A telemetry mod that reports confident wrong numbers
    is worse than one that reports nothing, and the numbers would only ever be complete for players who rebuilt
    their factory around GregScope's covers — which inverts the point of a read-only observability mod.

    Every feed-agnostic alternative was checked first: the running recipe is not reachable through GT's public API,
    GT keeps no cumulative throughput counters, and tank deltas cannot separate consumption from refill.

## ME hatches

A **fluid** hatch backed by an ME network reports what the **network** holds, not the trickle buffered in the
hatch. That is the number you want, and GT computes it for us.

Two consequences:

- Such an input shows **`n/a`** rather than a percentage, because "the network" has no capacity to be a fraction
  of. `n/a` and `0%` mean different things and GregScope keeps them apart everywhere.
- **ME item busses do not report amounts yet.** They are counted, so you can see the input is ME-backed, but the
  quantity needs a direct query to AE2. Known gap, not a bug.

## Reading it elsewhere

=== "Command"

    ```
    /gregscope info <id>
    ```
    ```
    Input: 45%  f:oxygen=4000/128000, i:minecraft:iron_ingot:0=17
    Output: 3%  f:steam=1000/32000
    ```

=== "OpenComputers"

    ```lua
    local snap = component.gt_machine.getSnapshot()
    print(snap.inputSaturation)   -- 0.45, or nil when unmeasurable
    for _, line in ipairs(snap.inputs) do print(line) end
    ```

The `key=amount/capacity` encoding is stable: `f:` for a fluid, `i:` for an item, the capacity omitted when the
holder does not report one, then `other=<amount>x<count>` and `me=<count>`.

## What it costs

Measured, not assumed: **p50 0.3 µs, p99 0.7 µs** per machine, against the 1 ms/tick budget the whole sampler
shares. Reading the machine's state — which GregScope already did — costs about 3.4 µs, so this is under a tenth of
what was already there.

It also never writes to your machines. The obvious GT API for reading a multiblock's fluids quietly does write, so
GregScope does not use it.
