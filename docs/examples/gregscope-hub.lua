-- gregscope-hub.lua: read a GregScope Telemetry Hub from an OpenComputers computer.
--
-- Usage:
--   gregscope-hub                  hub summary, the problem list, and the 60-minute table of the first problem
--   gregscope-hub <idPrefix>       the same, but the table is the sensor whose id starts with <idPrefix>
--
-- Put an OpenComputers Adapter against a Telemetry Hub; the Adapter then carries a gregscope_hub component. The Hub
-- decides what a computer may see: its owner's sensors and its owner's team's, across every dimension, and nothing
-- else. See docs/opencomputers.md.

local component = require("component")
local shell = require("shell")

local args = shell.parse(...)
local wanted = args[1]
if type(wanted) ~= "string" then
  wanted = nil -- run as a disk's autorun.lua, OpenOS passes the filesystem proxy instead of shell arguments
end

local MINUTES = 60
-- design-v0.2 section 9.2: a LIVE sensor in one of these states is a problem, and so is any sensor that is not LIVE.
local PROBLEM_STATES = {
  shutdown = true, power_starved = true, output_blocked = true,
  waiting = true, unformed = true, disabled = true,
}

local function fmt(value)
  if value == nil then
    return "-"
  end
  if type(value) == "number" then
    if math.type and math.type(value) == "integer" then
      return tostring(value) -- Lua 5.3+: exact 64-bit integer, never format through a float
    end
    if value == math.floor(value) and math.abs(value) < 2 ^ 53 then
      return string.format("%.0f", value)
    end
    return string.format("%.3f", value)
  end
  return tostring(value)
end

-- Percent as a whole number, rounded half up, so Lua 5.3 and LuaJ print the same digits.
local function percent(fraction)
  return fmt(math.floor(fraction * 100 + 0.5)) .. "%"
end

if not component.isAvailable("gregscope_hub") then
  print("No gregscope_hub component. Put an OpenComputers Adapter against a GregScope Telemetry Hub.")
  return
end
local hub = component.gregscope_hub

local info = hub.getInfo()
print("GregScope Telemetry Hub")
print(string.format("  gregscope %s  api %s  schema %s  history %s",
  fmt(info.gregscopeVersion), fmt(info.apiVersion), fmt(info.schemaVersion), fmt(info.historyVersion)))
print(string.format("  owner %s  visible %s  live %s  max %s",
  info.ownerName or "(unowned)", fmt(info.visible), fmt(info.live), fmt(info.maxSensors)))
print(string.format("  frame #%s  age %ss  interval %s ticks  capacity %ss/%smin",
  fmt(info.frameSequence), fmt(info.frameAgeSeconds), fmt(info.intervalTicks),
  fmt(info.secondsCapacity), fmt(info.minutesCapacity)))

-- Every sensor in scope, one page at a time: listSensors clamps its limit to 64 and reports the true total.
local sensors = {}
local offset = 0
repeat
  local page = hub.listSensors(offset, 64)
  for _, record in ipairs(page.sensors) do
    sensors[#sensors + 1] = record
  end
  offset = offset + #page.sensors
until #page.sensors == 0 or offset >= page.total

local problems = {}
for _, record in ipairs(sensors) do
  if record.availability ~= "live" or PROBLEM_STATES[record.state] then
    problems[#problems + 1] = record
  end
end

print(string.format("Problems (%s):", fmt(#problems)))
if #problems == 0 then
  print("  none")
end
for _, record in ipairs(problems) do
  print(string.format("  %s  %-9s %-14s %s",
    fmt(record.shortId), fmt(record.availability), fmt(record.state), fmt(record.displayName)))
end

-- The sensor the table is about: the one asked for, else the first problem, else the first sensor.
local chosen
if wanted then
  for _, record in ipairs(sensors) do
    if record.id:sub(1, #wanted) == wanted or record.shortId == wanted then
      chosen = record
      break
    end
  end
  if not chosen then
    print("No sensor in this Hub's scope starts with " .. wanted)
    return
  end
else
  chosen = problems[1] or sensors[1]
end
if not chosen then
  return
end

local history, err = hub.getSensorHistory(chosen.id, "minute", MINUTES)
if not history then
  -- Soft errors: "history loading" right after a restart, "sensor not found" once it leaves the Hub's scope.
  print(string.format("Last %s min of %s: %s", fmt(MINUTES), fmt(chosen.shortId), tostring(err)))
  return
end

print(string.format("Last %s min of %s (%s)", fmt(MINUTES), fmt(chosen.shortId), fmt(chosen.displayName)))
print("  minute       coverage  running  EU/t avg")
local observed = 0
for _, row in ipairs(history.rows) do
  observed = observed + 1
  local running = row.stateSeconds.running or 0
  local uptime = row.samples > 0 and running / row.samples or 0
  print(string.format("  %-12s %-9s %-8s %s",
    fmt(row.t), percent(row.coverage), percent(uptime), fmt(row.euPerTickAvg)))
end
-- Gaps are ranges, never zero-filled rows: a minute nobody observed is missing, with the reason it is missing for.
local missing = 0
for _, gap in ipairs(history.gaps) do
  local minutes = (gap.to - gap.from) / 60
  missing = missing + minutes
  print(string.format("  gap %s..%s  %s (%s min)", fmt(gap.from), fmt(gap.to), fmt(gap.reason), fmt(minutes)))
end
print(string.format("  %s min: %s observed, %s missing", fmt(MINUTES), fmt(observed), fmt(missing)))
