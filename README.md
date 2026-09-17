# GregScope

Read-only industrial telemetry for **GregTech: New Horizons**.

GregScope answers "what is this machine doing, and why?" for GT machines: running, disabled,
unformed, power-starved, output-blocked, waiting, or idle — with progress, EU/t, buffered energy,
and stable reason IDs you can automate against.

> **Status:** pre-release (v0.1 in development). The mod name and ID are provisional.

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

- [Snapshot schema v1](docs/snapshot-schema-v1.md) — keys, states, classification rules, known limitations, and where
  the implementation deliberately differs from the handoff (verified against the pinned GT5U sources).
- [OpenComputers integration](docs/opencomputers.md) — Adapter setup, component naming and discovery, the
  `getSnapshot()` contract, and troubleshooting.
- [Example Lua script](docs/examples/gregscope-snapshot.lua) — lists every machine with `getSnapshot()` and prints its
  status.
- [Design handoff](docs/handoff.md) — original plan and roadmap.

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

## License

MIT — see [LICENSE](LICENSE).
