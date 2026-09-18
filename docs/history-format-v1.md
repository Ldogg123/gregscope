# GregScope history file format, version 1 (GS-120)

This document describes the `.gsh` files GregScope writes, completely enough that a third party could read one
without the mod. It is the reference for design-v0.2 sections 8.2 and 7.4, and it is checked against the code:
`HistoryFileCodecTest`, `MinuteSlotCodecTest` and `LayoutSizesTest` assert the offsets, widths and sizes below, and `src/test/resources/fixtures/v1/`
holds golden byte images that fail if any of them moves.

**Everything is big-endian.** Nothing in these files is compressed or encrypted.

---

## 1. Where the files are

```
<world>/gregscope/
  registry.dat                 the sensor registry (gzipped NBT, not described here)
  history/<sensor-uuid>.gsh    one fixed-size file per sensor
```

`<sensor-uuid>` is the canonical dashed form of the sensor's UUID, the same id `/gregscope list` prints and the
OpenComputers `getSensor` callback returns.

Writing history at all is controlled by `history.persist`. When it is false the registry is still saved, so sensors
and their labels survive a restart; only the minute history is dropped.

## 2. File size

A `.gsh` file is **exactly 92,224 bytes**, always, from the moment it is created:

```
64                       header
+ 1440 minutes * 64 B    minute ring
= 92224
```

The size never changes, so a slot can be rewritten in place at a known offset and the file needs no index. A file of
any other length is not format 1 and is rejected (see section 5).

## 3. Header, 64 bytes

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | 4 B | magic | ASCII `GSH1` |
| 4 | u16 | formatVersion | `1` |
| 6 | u16 | slotSize | `64` |
| 8 | u16 | slotCount | `1440` |
| 10 | u8 | sensorKind | `0` machine. `1` item flow and `2` fluid flow are reserved for v0.3 |
| 11 | u8 | slotLayout | `1` = machine minute v1, the layout in section 4 |
| 12 | i64 | sensor UUID, most significant bits | |
| 20 | i64 | sensor UUID, least significant bits | |
| 28 | i64 | createdEpochSec | when the sensor was first registered |
| 36 | 24 B | reserved | all zero |
| 60 | u32 | CRC-32 | over bytes 0..59 |

The header is written once, when the file is created, and never rewritten. `slotSize` and `slotCount` are stored
rather than assumed so a reader can reject a file from a future format without guessing.

## 4. Minute slot, 64 bytes

The ring holds 1,440 slots: 24 hours at one slot per minute. Slot *i* starts at byte `64 + i * 64`.

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | i32 | epochMinute | Unix time in minutes. **`0` means the slot is empty** |
| 4 | u8 | samples | samples actually observed in this minute |
| 5 | u8 | expectedSamples | `1200 / sampling.intervalTicks` |
| 6 | u8 | gapMask | bits 0..5, one per stored gap reason (section 4.2) |
| 7 | u8 | lastStateCode | the state at the end of the minute (section 4.1) |
| 8 | 10 x u8 | stateSamples | samples seen in each state, indexed by state code |
| 18 | u8 | maintenanceMax | highest maintenance-problem count seen |
| 19 | u8 | flags | b0 `recipesCounterReset`, b1 `partialMinute`, b2 `serverStartMinute` |
| 20 | u16 | serverTicks | server ticks observed in the minute |
| 22 | u8 | euSamples | samples that carried an EU reading |
| 23 | u8 | reserved | zero |
| 24 | i64 | euPerTickAvg | `Long.MIN_VALUE` = no reading |
| 32 | i64 | euPerTickMin | `Long.MIN_VALUE` = no reading |
| 40 | i64 | euPerTickMax | `Long.MIN_VALUE` = no reading |
| 48 | i64 | energyStoredLast | `Long.MIN_VALUE` = absent |
| 56 | i32 | recipesCompletedDelta | `-1` = the machine is not a multiblock |
| 60 | u16 | reserved | zero |
| 62 | u16 | CRC-16/CCITT-FALSE | over bytes 0..61 |

Note the two different sentinels. `Long.MIN_VALUE` means *this machine had no such reading*; it is not zero, because
zero EU/t is a real and meaningful value. `recipesCompletedDelta = -1` means *this machine cannot have the reading at
all*, which is different again from a multiblock that completed no recipes (`0`).

### 4.1 State codes

Pinned. A code never changes meaning; a new state takes the next free number.

| Code | State |
|---|---|
| 0 | `unavailable` |
| 1 | `starting` |
| 2 | `unformed` |
| 3 | `shutdown` |
| 4 | `power_starved` |
| 5 | `running` |
| 6 | `disabled` |
| 7 | `output_blocked` |
| 8 | `waiting` |
| 9 | `idle` |

Code `0` doubles as "no state" in a gap entry. `stateSamples` at offset 8 is indexed by exactly these codes, which is
why it is ten bytes long.

### 4.2 Gap reasons

`gapMask` is a bit set: bit *n* is set when reason *n* applies to that minute. Only reasons 0..5 are ever stored;
6 and 7 are derived at read time and have no bit.

| Bit | Id | Stored |
|---|---|---|
| 0 | `chunk_unloaded` | yes |
| 1 | `dimension_unloaded` | yes |
| 2 | `target_missing` | yes |
| 3 | `sampling_skipped` | yes |
| 4 | `probe_error` | yes |
| 5 | `sensor_removed` | yes |
| - | `server_offline` | no, derived: the minute falls outside any recorded server run |
| - | `unknown` | no, derived: the fallback when nothing else explains the gap |

### 4.3 The sensor's own NBT, in the cover

The history file holds the *data*; the sensor's **identity** lives in the GT cover's NBT, inside the machine's tile
entity in the world save. That is why breaking a machine and putting it back keeps the sensor: the id travels with
the cover, not with the file.

All of GregScope's keys sit under one compound named `gs`, so nothing else in the cover is touched.

| Key | Type | Field |
|---|---|---|
| `gs` | u8 | format marker, `1`. Read unsigned. A value this build does not know means the record is left strictly alone |
| `idM` | i64 | sensor UUID, most significant bits |
| `idL` | i64 | sensor UUID, least significant bits |
| `lbl` | string | the player's label. Sanitized on every read, so a hand-edited value cannot inject formatting |
| `owM` | i64 | owner UUID, most significant bits. Absent when the sensor is unowned |
| `owL` | i64 | owner UUID, least significant bits. Absent when the sensor is unowned |
| `owN` | string | the owner's last known name, cached for display, capped at 16 characters |
| `ct` | i64 | `createdEpochSec`, matching the header field of the same name |

Half an owner - one of the two longs without the other - is treated as no owner, and the record is still valid.

A record in a format this build does not understand is preserved byte for byte and written back unchanged rather
than being dropped or rewritten, so downgrading after an upgrade does not destroy data.

## 5. Reading a file, and what "valid" means

A reader should, in order:

1. Check the length is exactly 92,224.
2. Check the magic is `GSH1` and `formatVersion` is 1.
3. Check the header CRC-32 over bytes 0..59.
4. Check `slotSize` is 64 and `slotCount` is 1440.
5. Check the UUID matches the file name.

GregScope classifies the result rather than throwing: a file that fails any of these is **renamed aside** with a
suffix describing why, and a fresh file is started. Nothing is deleted, so a corrupt file is always recoverable by
hand. A slot whose CRC-16 fails is treated as empty; one bad minute never invalidates the other 1,439.

**The window rule.** The ring is not sorted, and slot index is `epochMinute mod 1440`. A slot only counts as part of
the current window when `newest - 1439 <= epochMinute <= newest`, where `newest` is the largest `epochMinute` in the
file. Slots outside that range are stale leftovers from an earlier day sitting in a position the ring has not reached
again yet, and a reader must ignore them rather than treat them as data. This is the single most important rule for
anyone writing their own reader.

## 6. Compatibility

`formatVersion` and `slotLayout` are separate on purpose. A new *slot* layout (v0.3's flow meters will add one) can
be introduced by bumping `slotLayout` and the `sensorKind` byte while the file structure, size and header stay at
version 1, so an existing reader can still see how many minutes a file holds and which sensor it belongs to even when
it cannot decode the minutes themselves.

GregScope never rewrites an older file into a newer format in place. If `formatVersion` is one it does not
understand, it renames the file aside and starts a new one.

## 7. Removal and retention

- Removing the **mod** leaves `<world>/gregscope/` untouched. Minecraft will offer to remove GregScope's block and
  item entries from the save; the history files are ordinary files in the world folder and are simply ignored.
- Removing a **sensor** keeps its history for `history.removedRetentionHours` hours, then deletes the file.
- A sensor whose chunk has not loaded for `history.staleExpiryDays` days expires, and its file is deleted with it.
