# GregScope metrics model (GS-118)

**Status:** the metric mapping of the v0.4 Prometheus exporter, fixed now so that v0.2 never publishes a frame the
exporter cannot render. **Nothing in this file is implemented in v0.2.** v0.2 ships no HTTP server, no renderer and no
`exporter` config values it reads; `config/gregscope.cfg` reserves the `exporter` category and v0.2 ignores it
(design-v0.2 section 12.3).

**Authority.** This file is what design-v0.2 section 16.2 delegates ("fixed now in `docs/metrics-model.md`, GS-118")
and what design-v0.3 section 6.4 extends. Where it decides something section 16.2 left open, or corrects an
arithmetic claim there, the decision is written down in [section 11](#11-deviations-from-design-v02-section-162) with
its evidence. Machine-readable tables in this file are parsed by
`src/test/java/io/github/ldogg123/gregscope/sampling/TelemetryFrameContractTest.java`, which checks them against the
shipped enums and against the Prometheus name rules; a table and the code cannot drift apart silently.

**Contents**

1. [What the exporter may read](#1-what-the-exporter-may-read)
2. [Identity and labels](#2-identity-and-labels)
3. [Reserved metric and label names](#3-reserved-metric-and-label-names)
4. [Closed label sets](#4-closed-label-sets)
5. [Global families](#5-global-families)
6. [Per-sensor families, common to every kind](#6-per-sensor-families-common-to-every-kind)
7. [Per-sensor families, `kind="machine"`](#7-per-sensor-families-kindmachine)
8. [Gap semantics](#8-gap-semantics)
9. [Counter and reset semantics](#9-counter-and-reset-semantics)
10. [Cardinality](#10-cardinality)
11. [Deviations from design-v0.2 section 16.2](#11-deviations-from-design-v02-section-162)
12. [Reserved for v0.3](#12-reserved-for-v03)

---

## 1. What the exporter may read

The exporter renders **one `TelemetryFrame` and nothing else**.

```java
TelemetryFrame frame = TelemetrySampler.frame();   // static volatile, never null
```

That single read is the whole data acquisition. The frame is immutable (every field of every class in the frame graph
is `final`, every collection it exposes is unmodifiable, every array getter hands out a fresh copy), so the HTTP
thread holds a consistent set of numbers for the whole render without a lock and without ever touching:

- the `SecondRing` or `MinuteRing` (history is read on the server thread only, design-v0.2 section 7.7);
- the `SensorRegistry`, a `World`, a `TileEntity` or GT (design-v0.2 section 16.1);
- the `Settings` object, the config file or the save directory.

Everything in sections 5 to 7 therefore names a getter on `TelemetryFrame`, `SensorView`, `SamplerStatsView`,
`LimitsView`, `MachineCountersView` or the `MachineSnapshot` a `SensorView` carries. A family with no such source
cannot exist, and `TelemetryFrameContractTest` fails a family table row whose source names a getter that is not there.

**Freshness.** A frame is published once per sampling interval (1 s at the default `intervalTicks=20`). Scraping
faster than the interval returns the same frame again; `gregscope_sampler_last_publish_age_seconds` says how old it
is. Design-v0.2 section 16.1 recommends `scrape_interval: 5s` to `15s`.

**A stopped server publishes `TelemetryFrame.EMPTY`.** Its `stats()` and `limits()` are real zero-valued views rather
than nulls, so the renderer needs no null checks; it has no sensors, so the per-sensor families are simply absent.

---

## 2. Identity and labels

**The only series identity is `sensor_id`.** It carries the sensor's `UUID` in the canonical dashed lower-case form
(`SensorView.id().toString()`). A sensor keeps that UUID for its whole life, across restarts, chunk unloads, machine
renames and label changes (design-v0.2 section 3.3: the UUID lives in the cover's NBT and is the source of truth).
Every per-sensor series carries `sensor_id` and no other identifying label.

**Everything descriptive lives on one info metric.**

```
gregscope_sensor_info{sensor_id="...",sensor="Main EBF",machine="Electric Blast Furnace",kind="machine",dim="0"} 1
```

Dashboards join it in the usual way:

```promql
gregscope_sensor_eu_per_tick * on(sensor_id) group_left(sensor, machine, dim) gregscope_sensor_info
```

This is why a rename does not break a query: `gregscope_sensor_eu_per_tick` keeps its identity, only the `_info`
series is replaced.

**Why these five are safe as labels.**

| Label | Value | Why its cardinality is bounded |
|---|---|---|
| `sensor_id` | `SensorView.id()` | one per sensor; the whole point of the identity rule |
| `sensor` | `SensorView.label()` | the player's label, sanitized to at most 32 code points (`Labels`, design-v0.2 section 3.5); one value per sensor, and it only ever appears on `_info` |
| `machine` | `SensorView.machineName()` | GT's `IMetaTileEntity.getLocalName()`, a lang-file string for the machine **type** (`gregtech/api/interfaces/metatileentity/IMetaTileEntity.java:620` translates `getLocalNameKey()`), capped at 64 chars by `SensorEntry`; bounded by the number of machine types in the pack, not by the number of machines |
| `kind` | `SensorKind.label(SensorView.kind())` | a closed set of 3 |
| `dim` | `SensorView.dim()` | the dimension id, bounded by the pack's dimensions; a dimension id is not a coordinate |

**Never used as a label**, on any family. These are either unbounded, player-identifying, or both:

<!-- contract:never-labels -->
`status_id`, `statusId`, `status`, `status_text`, `statusText`, `label`, `item`, `item_name`, `fluid`, `fluid_name`,
`resource`, `resource_key`, `player`, `player_name`, `owner`, `owner_name`, `ownerName`, `x`, `y`, `z`, `pos`,
`position`, `coords`, `coordinates`, `meta_name`, `metaName`, `machine_class`, `warnings`, `shutdown_reason_id`,
`recipe_check_result_id`
<!-- /contract:never-labels -->

Rationale, one line each:

- **statusId and statusText** are open vocabularies. `statusId` is documented as "kept verbatim when unknown"
  (`docs/snapshot-schema-v1.md`), so a new GT version or an addon can invent one at any time; `statusText` is
  free-form localized text. Both are visible in the Hub, in `/gregscope info` and over OpenComputers, which is where
  an open vocabulary belongs.
- **Item, fluid and resource names** are unbounded by construction. design-v0.3 section 6.4 repeats the rule for the
  flow meters ("Never labels: resource names and keys").
- **Player names and owner UUIDs** would put player identity into a metrics database that is typically world-readable
  on the LAN, and they change (a rename, a transfer). Ownership is an access-control fact, not telemetry; it is in
  the Hub, `/gregscope info` and OpenComputers.
- **Coordinates** would make every series position-dependent, so moving a machine would silently start a new series
  while the sensor's identity did not change. The position is in the Hub, `/gregscope list` and OpenComputers.

**Label values are not sanitized into identifiers.** The exporter escapes `\`, `"` and newline per the Prometheus text
format and emits the value as it is (UTF-8). A value is never lower-cased, snake-cased or truncated for the sake of
looking like an identifier; only the **names** in section 3 are identifiers.

---

## 3. Reserved metric and label names

### 3.1 The two regexes

| Kind | Regex | Where |
|---|---|---|
| Metric name | `[a-zA-Z_:][a-zA-Z0-9_:]*` | every family in sections 5 to 7 |
| Label name | `[a-zA-Z_][a-zA-Z0-9_]*` | every label in sections 5 to 7 |

`TelemetryFrameContractTest` applies both to every name this file declares. The regexes are anchored: a name must
match completely, not merely contain a match.

### 3.2 Rules on top of the regexes

1. **GregScope owns the `gregscope_` prefix and emits nothing outside it.** Every family name in this file begins
   with `gregscope_`. The exporter never emits `up`, `scrape_duration_seconds` or any other name Prometheus itself
   owns; the scrape target's own `up` series is what says whether the server is reachable
   ([section 8](#8-gap-semantics)).
2. **No colon.** The metric-name regex allows `:`, but `:` is reserved by convention for names produced by recording
   rules. No GregScope family contains one.
3. **No label name begins with `__`.** Names with that prefix are reserved for Prometheus internals (`__name__`,
   `__address__`, the `__meta_*` discovery labels) and are dropped or rejected on ingestion.
4. **`le` is never emitted.** It is reserved by the histogram convention and GregScope exports no histogram.
5. **`quantile` is emitted on exactly one family**, `gregscope_sampler_tick_micros`, and that family's TYPE is
   `gauge`, not `summary` - see [section 11](#11-deviations-from-design-v02-section-162) item 3.
6. **Suffix discipline.** `_total` appears on counters only and on every counter; `_seconds`, `_ratio`, `_eu`,
   `_ticks` and `_micros` state the unit; `_info` marks a constant-`1` info metric. A gauge never ends in `_total`.
7. **No family name is a suffix collision with another.** Prometheus derives `<name>_total`, `<name>_sum`,
   `<name>_count`, `<name>_bucket` and `<name>_created` from summaries and histograms; no two GregScope families may
   differ only by one of those suffixes, and none does.
8. **Names are frozen.** A family in this file may gain a label value from a closed set that grows, but a family name
   and a label name are part of the compatibility contract in design-v0.2 section 17: renaming one is a breaking
   change that needs a version note, exactly like renaming an NBT key.

---

## 4. Closed label sets

A **closed** label takes its values from a fixed vocabulary that ships with the mod. The exporter never invents a
value, and a dashboard may safely write `sum by (state) (...)` over a single family.

The vocabularies are **generated from the enums**, never typed out in the renderer: adding a machine state or a gap
reason to the code must change the exported set in the same commit. `TelemetryFrameContractTest` regenerates each set
from the class named in the Generator column and compares it with the Values column below, so the table is checked,
not trusted.

<!-- contract:closed-sets -->

| Label | Family | Generator | Values |
|---|---|---|---|
| `state` | `gregscope_sensor_state` | `state-codes` | `unavailable`, `starting`, `unformed`, `shutdown`, `power_starved`, `running`, `disabled`, `output_blocked`, `waiting`, `idle` |
| `state` | `gregscope_sensor_state_samples_total` | `state-codes` | `unavailable`, `starting`, `unformed`, `shutdown`, `power_starved`, `running`, `disabled`, `output_blocked`, `waiting`, `idle` |
| `state` | `gregscope_sensors` | `availability` | `live`, `unloaded`, `missing`, `in_item`, `removed` |
| `availability` | `gregscope_sensor_availability` | `availability` | `live`, `unloaded`, `missing`, `in_item`, `removed` |
| `gap_reason` | `gregscope_sensor_gap_seconds_total` | `gap-reason-stored` | `chunk_unloaded`, `dimension_unloaded`, `target_missing`, `sampling_skipped`, `probe_error`, `sensor_removed` |
| `kind` | `gregscope_sensor_info` | `sensor-kind` | `machine`, `item_flow`, `fluid_flow` |
| `kind` | `gregscope_sensors` | `sensor-kind` | `machine`, `item_flow`, `fluid_flow` |
| `quantile` | `gregscope_sampler_tick_micros` | `pinned` | `0.5`, `0.99`, `max` |

<!-- /contract:closed-sets -->

**Generators.**

| Generator | Code it is generated from |
|---|---|
| `state-codes` | `StateCodes.state(c).id()` for `c` in `0 .. StateCodes.COUNT-1` - the pinned table of design-v0.2 section 7.1, in code order, never `MachineState.ordinal()` |
| `availability` | `HubCodecs.availabilityId(HubCodecs.availabilityCode(s))` for every `SensorState` **except** `OVER_CAP` - the stable design-v0.2 section 10.1 vocabulary already used by OpenComputers, the Hub GUI and `/gregscope list` |
| `gap-reason-stored` | `GapReason.values()` filtered by `isStored()`, in bit order - the six reasons a counter can hold |
| `sensor-kind` | `SensorKind.label(k)` for `k` in `MACHINE`, `ITEM_FLOW`, `FLUID_FLOW` |
| `pinned` | not an enum; pinned by this file |

**Why `over_cap` is not in the `availability` set.** A cover that a cap refuses gets **no registry entry at all**
(design-v0.2 section 4.2: "a refused cover gets no entry, so it is never persisted and never counted"), so it can
never appear in a frame and the exporter could only ever emit a constant zero for it. The vocabulary of six ids stays
the OpenComputers and Hub contract; the exported subset is the five a frame can carry. `gregscope_sampler_quota_refused_total`
is where a refused cover shows up.

**Two value domains share the label name `state`.** On `gregscope_sensor_state` and
`gregscope_sensor_state_samples_total` it is a **machine** state (what the machine was doing); on `gregscope_sensors`
it is a **lifecycle** state (whether GregScope can see the machine at all). This is deliberate: design-v0.3 section
6.4 pins `gregscope_sensors{kind,state}` and design-v0.2 section 16.2 pins `gregscope_sensor_state{state}`, and
Prometheus scopes label vocabularies per series, so nothing breaks. **The rule that follows: a closed set is keyed by
(family, label), never by label alone, and a dashboard must never aggregate those two families on `state`.** The test
enforces the keying.

---

## 5. Global families

One set per server. `TOTAL` is the number of series the family contributes.

<!-- contract:families:global -->

| Family | Type | Labels | Series | Source |
|---|---|---|---|---|
| `gregscope_build_info` | gauge | `version`, `schema_version`, `history_version` | 1 | constant `1`; `Tags.VERSION`, `MachineSnapshot.SCHEMA_VERSION`, the design-v0.2 section 10.4 `historyVersion` |
| `gregscope_sampler_last_publish_age_seconds` | gauge | - | 1 | `System.nanoTime() - TelemetryFrame.publishedNanos()` |
| `gregscope_sampler_interval_ticks` | gauge | - | 1 | `TelemetryFrame.intervalTicks()` |
| `gregscope_sampler_tick_micros` | gauge | `quantile` | 3 | `SamplerStatsView.cycleMicrosP50()`, `cycleMicrosP99()`, `cycleMicrosMax()` |
| `gregscope_sampler_window_ticks` | gauge | - | 1 | `SamplerStatsView.windowTicks()` |
| `gregscope_sampler_last_cycle_seconds` | gauge | - | 1 | `SamplerStatsView.lastCycleNanos()` |
| `gregscope_sampler_last_frame_build_seconds` | gauge | - | 1 | `SamplerStatsView.lastFrameBuildNanos()` |
| `gregscope_sensors` | gauge | `kind`, `state` | 5 | `TelemetryFrame.count(SensorState)`, split by `SensorView.kind()` |
| `gregscope_sampler_ticks_total` | counter | - | 1 | `SamplerStatsView.ticksTotal()` |
| `gregscope_sampler_cycles_total` | counter | - | 1 | `SamplerStatsView.cyclesTotal()` |
| `gregscope_sampler_samples_total` | counter | - | 1 | `SamplerStatsView.samplesTotal()` |
| `gregscope_sampler_sample_seconds_total` | counter | - | 1 | `SamplerStatsView.sampleNanosTotal()` |
| `gregscope_sampler_budget_exceeded_ticks_total` | counter | - | 1 | `SamplerStatsView.budgetExceededTicksTotal()` |
| `gregscope_sampler_sampling_skipped_total` | counter | - | 1 | `SamplerStatsView.samplingSkippedTotal()` |
| `gregscope_sampler_probe_errors_total` | counter | - | 1 | `SamplerStatsView.probeErrorsTotal()` |
| `gregscope_sampler_duplicates_rekeyed_total` | counter | - | 1 | `SamplerStatsView.duplicatesRekeyedTotal()` |
| `gregscope_sampler_quota_refused_total` | counter | - | 1 | `SamplerStatsView.quotaRefusedTotal()` |
| `gregscope_sampler_frames_published_total` | counter | - | 1 | `SamplerStatsView.framesPublishedTotal()` |
| `gregscope_sampler_clock_skew_refused_total` | counter | - | 1 | `SamplerStatsView.clockSkewRefusedTotal()` |
| `gregscope_io_queued_total` | counter | - | 1 | `SamplerStatsView.ioQueuedTotal()` |
| `gregscope_io_dropped_total` | counter | - | 1 | `SamplerStatsView.ioDroppedTotal()` |
| `gregscope_io_errors_total` | counter | - | 1 | `SamplerStatsView.ioErrorsTotal()` |
| `gregscope_exporter_scrapes_total` | counter | - | 1 | the exporter's own counter (v0.4) |
| `gregscope_exporter_render_seconds` | gauge | - | 1 | the exporter's own timing of the last render (v0.4) |

<!-- /contract:families:global -->

**Notes.**

- `_seconds` families divide a nanosecond source by 1e9 and are rendered as a decimal, never rounded to whole
  seconds. `gregscope_sampler_tick_micros` keeps microseconds because that is the unit design-v0.2 section 6.3 states
  the tick budget in and the unit `/gregscope stats` prints; it is a diagnostic, not a duration to be summed.
- `gregscope_sensors` is emitted, for each `kind` the frame actually contains, over **all five** values of the
  `availability` vocabulary, so a state that drops to zero reads as `0` rather than vanishing. A kind with no sensors
  at all contributes no series. In v0.2 only `kind="machine"` exists, so the family is 5 series.
- `gregscope_sampler_window_ticks` is `0` until the first 60-second window completes; `gregscope_sampler_tick_micros`
  is then also all zero, which is a real answer ("no completed window yet"), not a gap.
- **Config limits are not exported.** `LimitsView` carries `maxSensors`, `maxSensorsPerTeam`, `maxOpenHubViews`,
  `tickBudgetMicros` and `samplingEnabled`; design-v0.2 section 16.2 names no family for them, they change only on a
  restart, and they are already visible in `/gregscope stats` and in the one INFO line GregScope logs at startup
  (design-v0.2 section 7.8). The exporter reads `LimitsView` for `maxSensorsExported` clamping and emits nothing from
  it.

---

## 6. Per-sensor families, common to every kind

Emitted for every sensor in the frame, whatever its kind and whatever its state. `sensor_id` is implicit on every row
below and is not repeated in the Labels column beyond the first.

<!-- contract:families:common -->

| Family | Type | Labels | Series | Source |
|---|---|---|---|---|
| `gregscope_sensor_info` | gauge | `sensor_id`, `sensor`, `machine`, `kind`, `dim` | 1 | constant `1`; `SensorView.label()`, `machineName()`, `kind()`, `dim()` |
| `gregscope_sensor_up` | gauge | `sensor_id` | 1 | `1` when `SensorView.state() == LIVE` and `lastSnapshot() != null`, else `0` |
| `gregscope_sensor_last_sample_age_seconds` | gauge | `sensor_id` | 1 | frame time minus `SensorView.lastSampleEpochSec()`; `+Inf` when it is `0` (never sampled) |
| `gregscope_sensor_availability` | gauge | `sensor_id`, `availability` | 5 | one-hot over `SensorView.state()` |
| `gregscope_sensor_samples_total` | counter | `sensor_id` | 1 | `CountersView.samplesTotal()` |
| `gregscope_sensor_gap_seconds_total` | counter | `sensor_id`, `gap_reason` | 6 | `CountersView.gapSecondsTotal()` |
| `gregscope_sensor_probe_errors_total` | counter | `sensor_id` | 1 | `MachineCountersView.probeErrorsTotal()` |

<!-- /contract:families:common -->

**16 series per sensor**, which is exactly the "16 common" of design-v0.3 section 6.4.

**Notes.**

- `gregscope_sensor_up` is emitted **always**, including when the sensor is a tombstone, because a `_up` that is only
  present while it is `1` cannot express "down". See [section 11](#11-deviations-from-design-v02-section-162) item 2.
- `gregscope_sensor_last_sample_age_seconds` is emitted always and is the primary gap signal. `+Inf` for a sensor
  that was never sampled is deliberate: `0` would mean "sampled just now".
- `CountersView` is `null` on a tombstone that has released its rings (design-v0.2 section 4.2: only LIVE and
  UNLOADED hold rings). The four counter series are then **absent**, not zero - a zero would be a lie about a counter
  that used to be large. `gregscope_sensor_info`, `_up` and `_availability` still describe it until the entry
  expires.
- `gregscope_sensor_probe_errors_total` reads `MachineCountersView`; kinds 1 and 2 will have their own counters class
  (design-v0.3 section 5.2 `FlowCounters`). It is listed as common because `probeErrorsTotal` is a per-kind counter
  with the same meaning everywhere, not because the interface declares it.

---

## 7. Per-sensor families, `kind="machine"`

Only for `SensorView.kind() == SensorKind.MACHINE`. design-v0.3 section 6.4: "`gregscope_sensor_state` for
kind=machine only".

<!-- contract:families:machine -->

| Family | Type | Labels | Series | Source |
|---|---|---|---|---|
| `gregscope_sensor_state` | gauge | `sensor_id`, `state` | 10 | one-hot over `MachineSnapshot.state()` |
| `gregscope_sensor_active` | gauge | `sensor_id` | 1 | snapshot key `active` |
| `gregscope_sensor_allowed_to_work` | gauge | `sensor_id` | 1 | snapshot key `allowedToWork` |
| `gregscope_sensor_progress_ratio` | gauge | `sensor_id` | 1 | snapshot key `progress` |
| `gregscope_sensor_eu_per_tick` | gauge | `sensor_id` | 1 | snapshot key `euPerTick` |
| `gregscope_sensor_energy_stored_eu` | gauge | `sensor_id` | 1 | snapshot key `energyStored` |
| `gregscope_sensor_energy_capacity_eu` | gauge | `sensor_id` | 1 | snapshot key `energyCapacity` |
| `gregscope_sensor_maintenance_issues` | gauge | `sensor_id` | 1 | snapshot key `maintenanceIssues` |
| `gregscope_sensor_formed` | gauge | `sensor_id` | 1 | snapshot key `formed` |
| `gregscope_sensor_input_fill_ratio` | gauge | `sensor_id` | 1 | snapshot key `inputSaturation` |
| `gregscope_sensor_output_fill_ratio` | gauge | `sensor_id` | 1 | snapshot key `outputSaturation` |
| `gregscope_sensor_input_units` | gauge | `sensor_id` | 1 | snapshot key `inputTotal` |
| `gregscope_sensor_output_units` | gauge | `sensor_id` | 1 | snapshot key `outputTotal` |
| `gregscope_sensor_input_capacity_units` | gauge | `sensor_id` | 1 | snapshot key `inputCapacity` |
| `gregscope_sensor_output_capacity_units` | gauge | `sensor_id` | 1 | snapshot key `outputCapacity` |
| `gregscope_sensor_me_inputs` | gauge | `sensor_id` | 1 | snapshot key `meInputs` |
| `gregscope_sensor_recipes_completed` | gauge | `sensor_id` | 1 | snapshot key `recipesCompleted` |
| `gregscope_sensor_state_samples_total` | counter | `sensor_id`, `state` | 10 | `MachineCountersView.stateSamplesTotal()` |
| `gregscope_sensor_eu_consumed_sampled_total` | counter | `sensor_id` | 1 | `MachineCountersView.euConsumedSampledTotal()` |
| `gregscope_sensor_eu_generated_sampled_total` | counter | `sensor_id` | 1 | `MachineCountersView.euGeneratedSampledTotal()` |
| `gregscope_sensor_recipes_counter_resets_total` | counter | `sensor_id` | 1 | `MachineCountersView.recipesCounterResetsTotal()` |

<!-- /contract:families:machine -->

### 7.1 The buffer families say level, never rate

Added in v0.3. They report what a machine is **holding**, read from its own hatches and slots, so they are the same
whoever filled them.

**`_fill_ratio` is `NaN` when nothing reports a capacity**, which is not the same as `0`. An ME-backed input has no
capacity that means anything, and a machine with an empty tank genuinely is at zero. An exporter must emit `NaN`
rather than collapse the two - Prometheus handles `NaN` natively and a dashboard that shows "no data" is telling
the truth, while one that shows 0% is not.

**`_units` is litres for fluids and item counts for items, summed together.** That is deliberately a crude number:
it exists so `inputTotal / inputCapacity` reconciles, not as a physical quantity. Per-resource breakdown is
**not exported**, because resource names as labels is exactly what section 4's never-labels rule forbids - a base
with a few hundred machines and a dozen fluids each would multiply the series count without bound. The breakdown
lives in the snapshot, the Hub and OpenComputers, where it is read one machine at a time.

**The trend is not exported either, on purpose.** GregScope computes a rising/falling/steady direction for its own
GUI, but Prometheus derives that from a gauge's own history far better than a pre-computed enum could - `deriv()`
or `delta()` over `gregscope_sensor_input_fill_ratio` gives a real rate of change over whatever window the alert
wants, instead of GregScope's fixed five minutes. Exporting the direction would add a closed label set and tell a
consumer less than it can already work out.

**And none of these is a throughput.** A fill ratio that fell cannot distinguish consumption from a slow refill;
[flow-meters.md](flow-meters.md) records why the attempt to measure throughput honestly was abandoned. An alert
should be written on the level and its derivative, never presented as litres per second.

**32 series per machine sensor**, 19 gauges and 13 counter series.

**Emission rules.**

- The ten gauges above `gregscope_sensor_state_samples_total` come from `SensorView.lastSnapshot()` and are emitted
  **only when that snapshot is non-null**, which means the sensor was LIVE and really sampled. They are never
  zero-filled ([section 8](#8-gap-semantics)).
- A key that is **absent from the snapshot** suppresses its family for that scrape. Schema v1 omits optional keys
  rather than writing a zero (`docs/snapshot-schema-v1.md`), so a steam machine has no `euPerTick`, a singleblock has
  no `formed`, `maintenanceIssues` or `recipesCompleted`, and a machine with no energy buffer has no `energyStored`.
  A machine sensor therefore usually emits fewer than 48 series; 48 is the ceiling.
- `gregscope_sensor_state` is one-hot: all ten series are emitted, the current state `1` and the other nine `0`. Zero
  is correct here because the set is a partition - the machine really is in exactly one of the ten states.
- Booleans (`_active`, `_allowed_to_work`, `_formed`) are `1` or `0`.
- `_progress_ratio` is the schema v1 `progress`, already `0.0 .. 1.0`, rendered as a decimal. It is not a percentage
  and has no `_percent` alias.
- `_eu_per_tick` follows the schema v1 sign convention: **positive is consumption, negative is generation**. The HELP
  text says so, because the opposite convention is at least as common in dashboards.
- `_recipes_completed` is a **gauge**, not a counter - see [section 9](#9-counter-and-reset-semantics).

---

## 8. Gap semantics

The one rule everything else follows: **a gap is an absence, never a zero.** This is design-v0.2 section 7.5 rule 1
("aggregates use observed samples only; there is no interpolation or zero-filling") carried into the exporter.

### 8.1 How a gap looks to Prometheus

| What happened | What the scrape shows |
|---|---|
| The chunk unloaded, the target vanished, the budget skipped the sensor, or the probe threw | `gregscope_sensor_up` is `0`, `gregscope_sensor_last_sample_age_seconds` grows, the ten snapshot gauges are **absent**, and `gregscope_sensor_gap_seconds_total{gap_reason="..."}` rises on the matching reason |
| The sensor is UNLOADED | as above, plus `gregscope_sensor_availability{availability="unloaded"}` is `1` |
| The sensor became a tombstone | `_availability` moves to `missing`/`in_item`/`removed`, `_up` is `0`, and the counter families go absent once the rings are released |
| The entry expired and left the registry | every series for that `sensor_id` disappears. Prometheus marks them stale on the first scrape that omits them, so a range query ends rather than flat-lining |
| The server stopped, or is unreachable | the scrape fails and Prometheus's own `up{job="gregscope"}` is `0`. No GregScope series exist at all for that interval |

A series that disappears is the honest rendering of "no observation". A renderer must never emit a remembered value,
and must never emit `0` for a gauge whose source key is absent.

### 8.2 The two derived reasons are not exported

`GapReason` has eight members but only bits 0 to 5 are stored (design-v0.2 section 7.2). The two derived ones do not
appear as label values on any family:

- **`server_offline`** is derived by a history reader from the runs table, for minutes outside every recorded server
  run. A running exporter cannot observe it by definition - a process that is offline does not answer a scrape - and
  Prometheus already records exactly this as `up == 0`.
- **`unknown`** is the history reader's fallback for a missing minute it cannot attribute (design-v0.2 section 7.5
  rule 3). The exporter reports gaps from the frame's counters, which are attributed at the moment the gap is
  recorded, so it has nothing to fall back to.

`gregscope_sensor_gap_seconds_total` therefore has exactly six series per sensor, and
`MachineCountersView.gapSecondsTotal(GapReason)` throws `IllegalArgumentException` for a derived reason, so a
renderer that tried to emit one would fail loudly rather than emit a wrong number.

### 8.3 Coverage is computed by the consumer, not exported

Design-v0.2 section 7.5 defines coverage as `samples / expectedSamples`, where `expectedSamples` is each stored
minute's own value. That is a **history** quantity: it depends on the interval that was in effect when each minute was
recorded, which the frame does not carry. The exporter exports the two ingredients instead:

```promql
# observed samples per second
rate(gregscope_sensor_samples_total[5m])
# expected samples per second at the interval currently in effect
20 / gregscope_sampler_interval_ticks
# coverage over the last 5 minutes
rate(gregscope_sensor_samples_total[5m]) / scalar(20 / gregscope_sampler_interval_ticks)
```

This is exact while the interval is unchanged and approximate across an interval change, which is why
`gregscope_sampler_interval_ticks` exists as its own series rather than being folded into a precomputed ratio. The
exact, per-minute coverage stays where the data is: the Hub, `/gregscope info` and the OpenComputers history table.

### 8.4 Server lag is not a gap

Design-v0.2 section 7.2: "Server lag is not a gap." A lagging server produces **fewer samples per wall-clock second**,
not gap seconds, so it shows as `rate(gregscope_sensor_samples_total[5m])` falling below `20 /
gregscope_sampler_interval_ticks` while no `gregscope_sensor_gap_seconds_total` moves. The dedicated signal is
`gregscope_sampler_budget_exceeded_ticks_total` together with `gregscope_sampler_tick_micros`; a budget skip that
really did drop a sample is counted both there and as `gap_reason="sampling_skipped"`.

---

## 9. Counter and reset semantics

### 9.1 Every `_total` is process-lifetime

Design-v0.2 section 7.6: counters are "monotonic within a process; they reset on restart, which Prometheus-style
consumers handle". Concretely:

1. **A restart resets every `_total` to 0.** `SensorCounters` and `SamplerStats` are RAM only; nothing in `.gsh` or
   `registry.dat` holds a counter. `rate()`, `increase()` and `resets()` detect the drop and do the right thing; a
   raw `gregscope_sensor_samples_total` graph will show a sawtooth across restarts and that is correct.
2. **The exporter never carries a value across a restart** and never emits a `_created` timestamp. It has no
   persisted counter state of its own.
3. **Counters saturate, they do not wrap.** `SensorCounters.add` and `SamplerStats` clamp at `Long.MAX_VALUE`
   (`SensorCounters.java:21-24`, asserted by `SamplerStatsTest`). A saturated counter reads as a flat line, which
   `rate()` renders as 0 - wrong, but monotonic. It is unreachable in practice: at one sample per second,
   `samplesTotal` needs about 2.9e11 years.
4. **A counter's series can disappear** (section 8.1). That is not a reset; it is the end of the series.
5. **A new sensor is a new series, not a reset.** Purging a sensor and placing a new cover mints a new UUID
   (design-v0.2 section 3.3), so the old `sensor_id` ends and a new one starts at 0. Re-attaching the *same* cover
   keeps the UUID and the counters keep counting, because the registry entry survives an unload.

### 9.2 `gregscope_sensor_recipes_completed` is a gauge on purpose

It is GT's own lifetime counter (`MTEMultiBlockBase.recipesDone`, design-v0.2 section 7.4), read out of the snapshot.
It is **not** GregScope's counter and GregScope cannot make it monotonic:

- GT persists it in the machine's NBT, so it survives a server restart while every GregScope `_total` resets - two
  different reset behaviours under one name would be worse than none;
- GT resets it (design-v0.2 section 7.4: "if it decreases, the step's delta is 0 and `recipesCounterReset` is set").

So it is exported as a gauge, without a `_total` suffix, and the HELP text says it is GT's lifetime count and that
dashboards should use reset-aware functions (`increase()` over a range, or `resets()`) rather than a plain
difference. The companion counter `gregscope_sensor_recipes_counter_resets_total` is GregScope's own and **is**
monotonic within the process: it counts how many decreases the sampler observed, which is the signal that tells a
dashboard its `increase()` is being applied to a counter that really did reset.

### 9.3 The EU totals are labelled estimates

`gregscope_sensor_eu_consumed_sampled_total` and `_eu_generated_sampled_total` are
`sum(|euPerTick| * intervalTicks)` over observed samples (design-v0.2 section 7.6). They are **estimates**, and the
HELP text must say so in those words:

- they assume the sampled EU/t held for the whole interval, and a machine that starts and stops between two samples
  is invisible to them;
- gap seconds contribute nothing at all, so a sensor that was unloaded half the time under-reports by about half -
  which is the no-zero-filling rule again, applied to a sum;
- at `intervalTicks=20` the sampling rate is 1 Hz against GT's 20 Hz, so the estimate is a 1-in-20 sample.

They are useful for "which machine is the big consumer" and for trends. They are not an energy audit, and GregScope
has no way to make them into one without hooking GT's energy path, which design-v0.2 section 1.4 forbids.

### 9.4 `gregscope_sampler_sample_seconds_total`

`sampleNanosTotal` divided by 1e9. It is a counter of CPU time spent inside probes, so
`rate(gregscope_sampler_sample_seconds_total[5m])` is the fraction of a core GregScope's sampling uses, directly
comparable with the tick budget of design-v0.2 section 6.3.

---

## 10. Cardinality

### 10.1 Per sensor

| Group | Series | Where |
|---|---|---|
| Common, every kind | 16 | [section 6](#6-per-sensor-families-common-to-every-kind) |
| `kind="machine"` | 39 | [section 7](#7-per-sensor-families-kindmachine) |
| **Ceiling, a machine sensor** | **55** | |

The machine group was 32 before v0.3 added the seven buffer gauges (`section 7.1`). They are all single-series:
per-resource breakdown is deliberately not exported, because resource names as labels is what
[section 4](#4-closed-label-sets)'s never-labels rule forbids, and a base with a few hundred machines holding a
dozen fluids each would multiply the series count without bound.

The v0.3 flow-meter rows this table used to reserve are **gone**: flow meters were cancelled
([flow-meters.md](flow-meters.md)), and `item_flow` / `fluid_flow` remain reserved values of `kind` without any
families behind them.

`TelemetryFrameContractTest` re-adds the Series column of each family table and fails if these numbers move.

### 10.2 Per server

| Sensors | Per-sensor series | Global series | Total |
|---|---|---|---|
| 256 (`limits.maxSensors` default) | 14,080 | 30 | **14,110** |
| 512 (`exporter.maxSensorsExported` default) | 28,160 | 30 | **28,190** |
| 1,024 (`limits.maxSensors` maximum) | 56,320 | 30 | **56,350** |

These are ceilings: a singleblock machine omits `formed`, `maintenanceIssues` and `recipesCompleted`, an unloaded
sensor omits all ten snapshot gauges, and a tombstone omits the counters too.

**`exporter.maxSensorsExported` (default 512, design-v0.2 section 16.1) is the hard bound**, applied to the frame's
id-sorted sensor list. It is a prefix of a stable order, so the same sensors are exported from scrape to scrape
rather than a changing sample. When it truncates, the exporter says so in its own metric rather than silently: v0.4
adds `gregscope_exporter_sensors_dropped` to the global table at that point.

**For context:** 14,110 series at a 15 s scrape interval is about 940 samples/s, which a single small Prometheus
handles without comment. 49,182 at 5 s is about 9,800 samples/s, which is a real load and is why the 1,024 cap and
`maxSensorsExported` both exist.

---

## 11. Deviations from design-v0.2 section 16.2

Each one is a decision this file is delegated to make, with the evidence that forced it.

1. **The per-sensor cardinality bound is 48 for a machine sensor, not "about 32".** Section 16.2 says "at most about
   32 series per sensor, so about 8k at the default of 256 and about 33k at the 1024 cap". Adding up the families
   section 16.2 itself lists gives 16 common + 32 machine = 48, so the real figures are 12,288 and 49,152. The "32"
   is right for a **v0.3 flow meter**: design-v0.3 section 6.4 states "16 common + 16 flow = **32**, which is the
   section 16.2 bound", and its 16 flow series are listed there family by family. Section 16.2's number was the flow
   figure applied to both kinds. Sections 6, 7 and 10 use 16 / 32 / 48, and the test recomputes them from the tables.
2. **`gregscope_sensor_up` is emitted always, not only when LIVE with a snapshot.** Section 16.2 lists it under
   "Per-sensor gauges (emitted only when LIVE with a snapshot)". Two things say otherwise. (a) Arithmetic: the 16
   common series design-v0.3 section 6.4 counts are reached exactly by `_info` 1 + `_up` 1 + `_last_sample_age_seconds`
   1 + `_availability` 5 + `_samples_total` 1 + `_gap_seconds_total` 6 + `_probe_errors_total` 1 = 16; without `_up`
   it is 15 and there is no other candidate. (b) Meaning: a `_up` that exists only while the sensor is up can only
   ever hold the value `1`, which makes it identical to `absent()` and useless as an alert expression -
   `gregscope_sensor_up == 0` would never match. So `_up` joins the common group and reads `0` for a sensor that is
   not LIVE or carried no snapshot in this frame. Section 16.2's grouping sentence still holds for the other ten
   gauges.
3. **`gregscope_sampler_tick_micros` is TYPE `gauge`, not `summary`.** Section 16.2 writes
   `gregscope_sampler_tick_micros{quantile="0.5|0.99|max"}`. `quantile` is the label the summary convention reserves,
   and a summary's `quantile` values must parse as floats - `"max"` does not. The family is therefore rendered as a
   gauge with a `quantile` label, which is legal text (label values are arbitrary UTF-8) and keeps all three numbers
   section 16.2 asks for. If a later version wants a real summary it must drop `"max"` into its own family,
   `gregscope_sampler_tick_micros_max`; that is a breaking rename and needs a version note.
4. **Section 16.2's "global `gregscope_sampler_*_total`, `gregscope_io_*_total`" is expanded to exact names**, one
   family per counter on `SamplerStatsView`, including `ticksTotal` and `framesPublishedTotal`, which design-v0.2
   section 7.6 does not list but the view carries. The test asserts the mapping is total: every getter on
   `SamplerStatsView` whose name ends in `Total` has exactly one family in section 5, and every section 5 counter
   names a getter that exists. This is what stops v0.4 from having to invent a name.
5. **Three additions beyond section 16.2's list**, each because something else in this file needs it:
   `gregscope_sampler_interval_ticks` (section 8.3 needs the denominator to turn `samples_total` into coverage),
   `gregscope_sampler_window_ticks` (says whether `tick_micros` describes a completed window or is still zero), and
   `gregscope_sensor_recipes_counter_resets_total` (section 9.2 needs the signal that `recipes_completed` reset).
   `gregscope_sampler_last_cycle_seconds` and `_last_frame_build_seconds` come from section 7.6's gauge list.
6. **`gregscope_sensors` carries `kind` as well as `state`**, per design-v0.3 section 5.1 A7 and section 6.4, and its
   `state` is the lifecycle vocabulary. The overload of the label name `state` is covered in
   [section 4](#4-closed-label-sets).
7. **Config limits are not exported at all** (section 5, last note).

---

## 12. Reserved for v0.3

design-v0.3 is approved but not built. This file does not define its families; it reserves what they need so that
adding them is additive:

- **Kinds.** `kind` is already a closed set of three, so `item_flow` and `fluid_flow` are values, not new labels.
  `gregscope_sensor_info.kind` and `gregscope_sensors{kind,...}` already carry it (design-v0.3 section 5.1 A7).
- **The common 16 are kind-independent.** A flow meter emits the whole of [section 6](#6-per-sensor-families-common-to-every-kind)
  and none of [section 7](#7-per-sensor-families-kindmachine). That is why `SensorView.lastSnapshot()` is nullable and
  `counters()` is the `CountersView` interface (design-v0.3 section 5.1 A6): the renderer branches on `kind()`, and
  nothing in the common group has to ask what it is looking at.
- **Label names reserved for the flow families:** `direction` and `outcome` (design-v0.3 section 6.4). They are not
  used by any v0.2 family and must not be taken for anything else.
- **The never-labels rule extends unchanged.** design-v0.3 section 6.4: "Never labels: resource names and keys."
- **Globals reserved:** `gregscope_flow_counter_resets_total`, `gregscope_flow_resource_unresolved_total`.

---

## Change log

| Version | Change |
|---|---|
| v0.2 (GS-118) | First edition. Mapping fixed for the v0.4 exporter; nothing implemented. |
