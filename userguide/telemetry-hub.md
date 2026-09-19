# Telemetry Hub

A block that shows you every sensor you can see, worst first. **Assembler recipe at EV**: 1920 EU/t for one minute.

Right-click it to open.

## What the screen shows

- **A row per sensor**, sorted worst-first — shutdowns and power-starved machines at the top, healthy ones at the
  bottom. You should not have to hunt for the problem.
- **A Problems filter** that hides everything that is working.
- **A detail panel** for the selected sensor: where it is, what state it is in and why, its power, how full its
  buffers are and which way they are going, and 5-minute and 24-hour summaries with an hourly uptime strip.
- **A label field**, if you are allowed to rename that sensor.

## Whose sensors it shows

The Hub shows **its owner's** sensors, not the viewer's.

That matters on a shared server: if an operator opens someone else's Hub, they see what that Hub sees and nothing
more. Opening a Hub is never a way to widen your own access.

## Who can open it

| Action | Who |
|---|---|
| See a sensor | Its owner, anyone on the owner's team, and operators |
| Open a Hub | Its owner, the owner's team, and operators |
| Rename a sensor | Its owner and the owner's team — optionally officers only, see [configuration](configuration.md#permissions) |
| Purge a sensor's data | Operators |

"Team" means a GTNHLib team. A sensor placed by something that is not a player is **unowned** and appears in
nobody's Hub — only operators see it.

!!! note "Open screens are capped"
    A server-wide limit on simultaneously open Hub screens keeps a crowd of players from making the server build
    the same data dozens of times over. See [`limits.maxOpenHubViews`](configuration.md#limits).
