# Troubleshooting

## "No component has `getSnapshot`"

- The Adapter must touch the **basic machine** or the **multiblock controller** — not a hatch, bus or casing.
- Check the Adapter is actually connected to the computer (`components` in the OpenOS shell).
- Right after placing a machine next to an Adapter, the component has only OpenComputers' own callbacks for about
  a tick, because GregScope's driver matches once GT has created the machine inside the tile entity. Look again a
  moment later — and expect a new address.

## A client cannot connect

GregScope must be on the client **at the same version** as the server. The rejection message says which. See
[Getting started](getting-started.md#install).

## A machine shows `unloaded` and never updates

That is correct: GregScope does not load chunks. The sensor keeps its identity and history, and the history
records a gap for the time nobody was there. It resumes when something else loads the chunk.

## A sensor says `over cap`

You are past [`limits.maxSensors`](configuration.md#limits) or `maxSensorsPerTeam`. The sensor keeps its identity
and its history; it just is not being sampled. Raise the cap or remove a sensor elsewhere.

## The Hub is empty but I have sensors

- The Hub shows **its owner's** sensors. If someone else placed it, it shows their scope.
- A sensor placed by a robot or other non-player is **unowned** and appears in nobody's Hub — only operators see
  it with `/gregscope list`.

## A machine's input shows `n/a` instead of a percentage

It is fed by an **ME network**. There is no capacity for the level to be a fraction of, so GregScope says `n/a`
rather than pretending it is 0%. The amount is still real — it is what the network holds.

For ME **item** busses the amount is not available yet either; you will see the input counted but not measured.
[More →](buffers.md#me-hatches)

## `/gregscope stats` shows dropped writes

The disk is not keeping up with history writes. Raise
[`history.ioQueueCapacity`](configuration.md#history), or set `history.persist = false` if you do not need the
24-hour history. Writes are dropped rather than blocking the server thread, so this costs you history, never TPS.

## Removing the mod

Safe, and tested. Load the world once without GregScope and accept Minecraft's prompt about the missing block and
item entries.

- Your **machines are untouched** — GregScope never changed them.
- Every Machine Sensor cover disappears with the mod, as any cover from a removed mod does.
- `<world>/gregscope/` is left on disk. Delete it, or keep it: reinstalling picks the registry and history back up.
