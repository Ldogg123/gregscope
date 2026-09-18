# GregScope

Read-only industrial telemetry for **GregTech: New Horizons**.

GregScope answers "what is this machine doing, and why?" for GT machines: running, disabled,
unformed, power-starved, output-blocked, waiting, or idle — with progress, EU/t, buffered energy,
and stable reason IDs you can automate against.

> **Status:** v0.1.0 is ready to release (see [CHANGELOG](CHANGELOG.md)). v0.2 - Machine Sensor covers, the
> Telemetry Hub and 24 h history - is feature-complete and in final validation. The mod name and ID are provisional.
>
> **v0.2 changes the install:** v0.1 was server-only, but the Telemetry Hub has a GUI, so from v0.2 every connecting
> **client needs GregScope too, at the same version**.

## Target

Built and tested against GTNH **2.9.0-beta-3** exactly:

| Component | Version |
|---|---|
| Minecraft / Forge | 1.7.10 / 10.13.4.1614 |
| GT5-Unofficial | 5.09.54.133 |
| OpenComputers | 1.12.61-GTNH |
| GTNHLib | 0.11.46 |

Other pack versions are not supported yet.

## v0.1 scope

- A normalized `MachineSnapshot` (schema v1) for GT basic machines (`MTEBasicMachine`) and
  multiblock controllers (`MTEMultiBlockBase`).
- An OpenComputers `getSnapshot()` callback on GT machines behind an Adapter. It merges into OC's existing component
  (normally `gt_energycontainer`); the component is only named `gt_machine` when OC's GregTech integration is off.

v0.1 is deliberately read-only. It adds no blocks, items, recipes, saved data, mixins, or tick
handlers, and does no work unless a computer asks for a snapshot.

## v0.2 scope

- A **Machine Sensor** GT cover (MV). Put one on a machine and GregScope records it: identity that survives the
  machine being broken and replaced, an owner, an optional label, and 24 hours of one-minute history on disk.
- A **Telemetry Hub** block (EV) with a GUI listing every sensor you can see, worst first, with a detail panel and
  5-minute and 24-hour summaries.
- `/gregscope stats | list | info | label | purge`.
- An extended OpenComputers surface: `getSensor` and `getSensorHistory` on the machine component, and a new
  `gregscope_hub` component with `getInfo`, `listSensors`, `getLatest` and `getSensorHistory`.
- Bounded by design: a hard per-tick sampling budget, caps on sensors and open GUIs, and a queue for disk writes
  that drops and counts rather than stalling the server thread.

v0.2 is still **read-only towards your factory**: it never starts, stops, configures or feeds a machine, and never
moves an item or a fluid. The only things it writes are its own data - a label, a purge, its registry and its
history files. It uses no mixins, no access transformers and no reflection in shipped code, and it never loads a
chunk.

- [Machine Sensors and the Telemetry Hub](docs/sensors-and-hub.md) — the v0.2 guide: placement, identity, access,
  limits, every config key, the `/gregscope` commands, and mod removal.
- [History file format v1](docs/history-format-v1.md) — the byte layout of the `.gsh` files, complete enough to read
  one without the mod.
- [Metrics model](docs/metrics-model.md) — the metric names, labels and cardinality rules the v0.4 Prometheus
  exporter will use.
- [Snapshot schema v1](docs/snapshot-schema-v1.md) — keys, states, classification rules, known limitations, and where
  the implementation deliberately differs from the handoff (verified against the pinned GT5U sources).
- [OpenComputers integration](docs/opencomputers.md) — Adapter setup, component naming and discovery, the
  `getSnapshot()` contract, and troubleshooting.
- [Example Lua script](docs/examples/gregscope-snapshot.lua) — lists every machine with `getSnapshot()` and prints its
  status.
- [Testing](docs/testing.md) — unit and in-game (Horizon-QA) tests, how to run them, GS-004 coverage and the v0.1
  definition-of-done checklist with evidence.
- [v0.3 design](docs/design-v0.3.md) — flow meters: metering conveyor and pump covers built on GT's own covers.
- [v0.2 design](docs/design-v0.2.md) — decided design for sensors, the Telemetry Hub, history, and the v0.4 exporter
  data model.
- [Design handoff](docs/handoff.md) — original plan and roadmap.

## Install

### v0.2 (sensors, Telemetry Hub, history)

1. Put the GregScope jar in the **server's** `mods/` folder **and in every client's**. GTNH already ships the
   dependencies (GregTech, OpenComputers, GTNHLib, ModularUI2).
2. Versions must match. A client without GregScope, or with a different version, is refused at the FML handshake
   with a message saying so - this is FML's own check, not a silent failure.
3. Craft a **Machine Sensor** (assembler, MV) and put it on a GT machine or multiblock controller. Craft a
   **Telemetry Hub** (assembler, EV) and right-click it to see every sensor you can access.
4. Optional: read the same data from OpenComputers. See [opencomputers.md](docs/opencomputers.md).

Start with [Machine Sensors and the Telemetry Hub](docs/sensors-and-hub.md), which covers placement, permissions,
limits, every config key, the commands and what happens if you remove the mod.

Single player counts as both: the jar goes in your client's `mods/` folder and the integrated server picks it up.

### v0.1 (OpenComputers only)

1. Put the jar in the **server's** `mods/` folder (or your client's, for single player).
2. Clients did **not** need GregScope for v0.1. This is the one thing v0.2 changes.
3. Place an OpenComputers Adapter against a GT machine or multiblock controller, connect a computer and call
   `getSnapshot()`. The [example script](docs/examples/gregscope-snapshot.lua) lists every machine at once.

## Building

Requires a JDK 25 (Gradle provisions the daemon JVM from `gradle/gradle-daemon-jvm.properties`; the
mod itself compiles to Java 8 bytecode via Jabel).

```bash
./gradlew build
```

Formatting is enforced in CI with Spotless:

```bash
./gradlew spotlessApply
```

## Releasing

Versions come from git tags. To release, push a tag such as `0.1.0` on a green `master` commit:

```bash
git tag -a 0.1.0 -m "GregScope 0.1.0"
```

```bash
git push origin 0.1.0
```

The GTNH release workflow (`.github/workflows/release-tags.yml`) builds the jars and creates a GitHub release. It uses
`.changelogs/<tag>.md` as the release notes when that file exists. Note that the shared workflow also uploads a
Gradle build scan (`build-scan-publish: true`), which accepts Gradle's terms of use for that run.

## License

MIT — see [LICENSE](LICENSE).
