# GregScope

Read-only industrial telemetry for **GregTech: New Horizons**.

GregScope answers "what is this machine doing, and why?" for GT machines: running, disabled,
unformed, power-starved, output-blocked, waiting, or idle — with progress, EU/t, buffered energy,
and stable reason IDs you can automate against.

> **Status:** v0.1.0 is ready to release (see [CHANGELOG](CHANGELOG.md)); v0.2 (sensors, Telemetry Hub, history) is in
> development. The mod name and ID are provisional.

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
- [Testing](docs/testing.md) — unit and in-game (Horizon-QA) tests, how to run them, GS-004 coverage and the v0.1
  definition-of-done checklist with evidence.
- [v0.3 design](docs/design-v0.3.md) — flow meters: metering conveyor and pump covers built on GT's own covers.
- [v0.2 design](docs/design-v0.2.md) — decided design for sensors, the Telemetry Hub, history, and the v0.4 exporter
  data model.
- [Design handoff](docs/handoff.md) — original plan and roadmap.

## Install (v0.1)

1. Put the GregScope jar into the **server's** `mods/` folder (or your client's, for single player). It requires
   GregTech and OpenComputers, which GTNH already includes.
2. Clients do not need GregScope to join the server.
3. Place an OpenComputers Adapter against a GT machine or multiblock controller, connect it to a computer, and call
   `getSnapshot()` on the machine's component. Run the [example script](docs/examples/gregscope-snapshot.lua) to see
   every machine at once.

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
