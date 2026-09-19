# Getting started

## Install

GregScope needs to be on **both the server and every client**. Earlier versions were server-only; the Telemetry
Hub has a GUI, so from v0.2 the client needs it too, at the same version.

1. Drop the jar into `mods/` on the server **and** in each client's instance.
2. That is all — GTNH already ships everything it depends on (GregTech, OpenComputers, GTNHLib, ModularUI2).

Single player counts as both: the jar goes in your instance's `mods/` folder and the integrated server picks it up.

!!! warning "Versions must match"
    A client without GregScope, or running a different version, is refused when it connects, with a message saying
    so. That is FML's own check — it fails clearly rather than desyncing quietly.

## Craft a Machine Sensor

An **assembler** recipe at **MV**: 120 EU/t for 20 seconds.

| | |
|---|---|
| Activity Detector Cover | 1 |
| MV Sensor | 1 |
| Good circuit | 1 |
| Aluminium plate | 2 |
| Copper cable | 2 |
| Molten soldering alloy | 72 L |

## Put it on a machine

**Sneak + right-click** a Machine Sensor onto any GT basic machine or multiblock controller.

That is the whole setup. The machine is now being recorded — no configuration, no wiring, nothing to point at
anything.

!!! tip "Name it first"
    Rename the sensor **item** in an anvil before you place it, and the sensor carries that name. Much easier than
    finding it by UUID later. You can also rename it any time from the Hub or with
    [`/gregscope label`](commands.md).

## See what it found

Three ways, same data:

=== "In-game screen"

    Craft a **Telemetry Hub** (assembler, EV) and right-click it. Every sensor you can see, worst first.
    [More →](telemetry-hub.md)

=== "Chat"

    ```
    /gregscope list
    /gregscope info <id>
    ```
    [More →](commands.md)

=== "OpenComputers"

    ```lua
    local gs = component.gregscope_hub
    for _, s in ipairs(gs.listSensors().entries) do
      print(s.displayName, s.state)
    end
    ```
    [More →](opencomputers.md)

## Next

- A sensor's identity survives more than you would expect, and less than you might assume —
  [Machine Sensors](machine-sensors.md) has the rules.
- Running a server for other people? [Configuration](configuration.md) covers the caps and permissions.
