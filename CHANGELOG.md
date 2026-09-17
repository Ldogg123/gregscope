# Changelog

All notable changes to GregScope. Versions come from git tags (see [Releasing](README.md#releasing)).

## 0.1.0 (unreleased)

First release: read-only machine telemetry for GTNH 2.9.0-beta-3 through OpenComputers.

### Added

- `MachineSnapshot` schema v1 for GT basic machines (`MTEBasicMachine`) and multiblock controllers
  (`MTEMultiBlockBase`): normalized `state` (`starting`, `unformed`, `shutdown`, `power_starved`, `running`,
  `disabled`, `output_blocked`, `waiting`, `idle`), stable `statusId` reason IDs taken from GT's own shutdown reasons
  and recipe-check results, progress, EU/t, buffered energy or steam, maintenance, efficiency and more. See
  [docs/snapshot-schema-v1.md](docs/snapshot-schema-v1.md).
- OpenComputers `getSnapshot()` on GT machines behind an Adapter. It merges into OpenComputers' existing component
  (`gt_energycontainer`, `lsc`, `bec_*`), so existing scripts keep working. See
  [docs/opencomputers.md](docs/opencomputers.md).
- Example Lua script [docs/examples/gregscope-snapshot.lua](docs/examples/gregscope-snapshot.lua), tested on a real
  OpenOS computer on Lua 5.2, 5.3, 5.4 and LuaJ.

### Notes

- Server-side only: clients do not need GregScope to join a server that runs it.
- Adds no blocks, items, recipes, saved data, mixins, access transformers or tick handlers, and does no work unless a
  computer calls `getSnapshot()`.
- Installing or removing GregScope gives Adapter-attached GT components a new address once (OpenComputers behaviour).
- Verification: 82 unit tests and 47 Horizon-QA in-game tests on GT5U 5.09.54.133 and OpenComputers 1.12.61-GTNH, plus
  a boot of the release jar in the official 2.9.0-beta-3 server pack. See [docs/testing.md](docs/testing.md).
