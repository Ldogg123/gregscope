# Commands

`/gregscope` is available to everyone. Each subcommand checks its own permission, and you only ever see sensors you
are allowed to see.

```
/gregscope stats
/gregscope list [live|unloaded|missing|tombstones|stale] [page]
/gregscope info <id>
/gregscope label <id> [text]
/gregscope purge <id>
/gregscope purge --tombstones | --stale
```

## The subcommands

`stats`
: Sampling, I/O and size numbers for the whole server. The one to check if you suspect GregScope is costing you
  anything: it shows the p50, p99 and worst sampling cycle, and how many disk writes were dropped.

`list [filter] [page]`
: Every sensor you can see. The filters match the [lifecycle states](machine-sensors.md#lifecycle);
  `tombstones` covers all three gone-but-remembered states, and `stale` finds sensors whose chunks have not loaded
  in a long time.

`info <id>`
: Everything about one sensor: where it is, what state and why, its buffers, and the 5-minute and 24-hour
  summaries with the gaps listed.

`label <id> [text]`
: Rename a sensor. With no text, clears the label. Needs rename rights on that sensor.

`purge <id>` · `purge --tombstones` · `purge --stale`
: Delete a sensor's recorded data. **Operators only.**

## Sensor ids

`<id>` is a sensor's UUID, but you only need enough of it to be unambiguous — at least eight characters. Dashes and
case are ignored.

If a prefix matches more than one sensor you get the candidates listed rather than a guess.

!!! note "A sensor you cannot see"
    ...answers exactly the same as one that does not exist. The command cannot be used to probe for other players'
    machines.
