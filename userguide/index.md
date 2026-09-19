# GregScope

**Read-only telemetry for GregTech: New Horizons.** It answers the question you actually have when a factory
stalls: *which machine stopped, and why?*

Put a Machine Sensor on a machine, and GregScope records what it is doing — the normalised state, the reason GT
itself gave for stopping, how full its buffers are, and 24 hours of history. Read it from a Telemetry Hub in-game,
from `/gregscope`, or from OpenComputers.

---

## What it tells you

| | |
|---|---|
| **Why a machine stopped** | Nine normalised states, plus the stable reason ID from GT's own shutdown reason or recipe check. Never text you have to parse. |
| **Whether it is about to stop** | Input buffer level and which way it is going. `in 12% FALLING` is a machine about to starve. |
| **What happened while you were away** | 24 hours of one-minute history per sensor, with gaps marked and explained. |
| **What it costs you** | Nothing you will notice: a hard per-tick budget, visible in `/gregscope stats`. |

## What it does not do

GregScope is **read-only towards your factory**. It never starts, stops, configures or feeds a machine, and it
never moves an item or a fluid. The only things it writes are its own data — a label, a purge, its registry and its
history files.

It also cannot measure **throughput**. It reports levels, not rates: a tank that dropped by 500 L might be 500
consumed, or 1,000 consumed while 500 arrived, and those are indistinguishable from outside.
[Why that is, and what was tried](buffers.md#what-it-cannot-tell-you).

!!! info "Version"
    Built and tested against **GTNH 2.9.0-beta-3** exactly — GT5-Unofficial 5.09.54.133, OpenComputers
    1.12.61-GTNH, GTNHLib 0.11.46. Other pack versions are not supported yet.

## Start here

- **[Getting started](getting-started.md)** — install it, craft a sensor, place it.
- **[Machine Sensors](machine-sensors.md)** — what a sensor is, where it goes, and what survives what.
- **[Telemetry Hub](telemetry-hub.md)** — the in-game screen.
- **[OpenComputers](opencomputers.md)** — read your factory from a script.
