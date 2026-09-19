# OpenComputers

Everything the Hub shows is readable from a script. There are two components.

## `gt_machine` — one machine

Put an **OpenComputers Adapter** against a GT machine or multiblock controller and connect it to a computer.
GregScope adds its callbacks to the component OpenComputers already exposes there.

```lua
local component = require("component")
local m = component.gt_machine

local snap = m.getSnapshot()
print(snap.state, snap.statusId)        --> "power_starved"  "insufficient_power"
print(snap.euPerTick, snap.progress)
print(snap.inputSaturation)             --> 0.12, or nil when unmeasurable
```

`getSnapshot()`
: The full machine snapshot — state, the reason it stopped, power, progress, buffers. No sensor required; this
  works on any supported machine behind an Adapter.

    This call is also the **only** place ME stocking-bus amounts appear: asking the network costs a data export
    and a lookup per configured slot, too much for the per-tick sampler, so the Hub and `/gregscope` omit it and a
    script that asks directly gets it.

`getSensor()`
: The sensor record, if the machine has one. `nil, "no sensor"` if it does not.

`getSensorHistory(resolution, count, before)`
: Minute or second history for the machine's sensor.

## `gregscope_hub` — every machine you can see

Put an Adapter against a **Telemetry Hub** instead, and you get the whole scope in one component — no Adapter per
machine.

```lua
local gs = component.gregscope_hub

for _, s in ipairs(gs.listSensors().entries) do
  if s.state ~= "running" then
    print(s.displayName, s.state, s.statusId)
  end
end
```

`getInfo()`
: The Hub itself: how many sensors are in scope, how many are live, the sampling interval.

`listSensors(limit, offset)`
: A page of sensor records. `limit` is clamped to 1–64; `total` counts the whole scope, not the page.

`getLatest(id)`
: One sensor's current record.

`getSensorHistory(id, resolution, count, before)`
: That sensor's history, with gaps as explicit ranges.

A worked example that prints a live table is in the repo at
[`docs/examples/gregscope-hub.lua`](https://github.com/Ldogg123/gregscope/blob/master/docs/examples/gregscope-hub.lua) —
it is the exact file the in-game tests run, so it cannot drift from what the mod actually does.

## Things worth knowing

**Scope.** The Hub component shows the **Hub owner's** sensors. Same rule as the screen.

**Errors are soft.** A callback that cannot answer returns `nil, "reason"` rather than raising, so a polling loop
does not need `pcall` around every call:

```lua
local rec, err = gs.getLatest(id)
if not rec then print("no reading: " .. err) end
```

The exception is a genuinely wrong argument type — passing a number where a string belongs raises OpenComputers'
own `bad argument #1 (string expected, got number)`, as it does for every component.

**Ids can be short.** Eight characters is enough if unambiguous; dashes and case are ignored. An ambiguous prefix
returns `nil, "ambiguous id"`.

**It never loads a chunk.** Asking about a sensor whose chunk is unloaded returns its last known reading, not a
freshly loaded one.

**Addresses.** Adapter component addresses do not change when you upgrade GregScope. They *do* change if you add
or remove the mod entirely — that is OpenComputers' behaviour, not GregScope's. Look them up again after either.
