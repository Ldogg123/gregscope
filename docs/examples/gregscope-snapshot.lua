-- gregscope-snapshot.lua: list GT machines that expose GregScope's getSnapshot() and print their status.
--
-- Usage:
--   gregscope-snapshot             summary of every machine
--   gregscope-snapshot <address>   summary, then a full dump of one machine (an address prefix is enough)
--
-- Machines keep OpenComputers' component name (usually gt_energycontainer), so they are discovered by callback,
-- not through component.gt_machine. See docs/opencomputers.md.

local component = require("component")
local shell = require("shell")

local args = shell.parse(...)
local wanted = args[1]
if type(wanted) ~= "string" then
  wanted = nil -- run as a disk's autorun.lua, OpenOS passes the filesystem proxy instead of shell arguments
end

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

local function dump(snapshot)
  local keys = {}
  for key in pairs(snapshot) do
    keys[#keys + 1] = key
  end
  table.sort(keys) -- pairs() order is unspecified
  for _, key in ipairs(keys) do
    local value = snapshot[key]
    if type(value) == "table" then
      value = "[" .. table.concat(value, ", ") .. "]"
    end
    print(string.format("  %-26s %s", key, fmt(value)))
  end
end

-- Collect every component whose proxy has getSnapshot, sorted by address for stable output.
local machines = {}
for address, componentType in component.list() do
  local proxy = component.proxy(address)
  if proxy and proxy.getSnapshot then
    machines[#machines + 1] = { address = address, type = componentType, proxy = proxy }
  end
end
table.sort(machines, function(a, b) return a.address < b.address end)

if #machines == 0 then
  print("No GregScope machines found. Place an Adapter next to a GT machine or multiblock controller.")
  return
end

local dumped = false
for _, machine in ipairs(machines) do
  local short = machine.address:sub(1, 8)
  local ok, snapshot, err = pcall(machine.proxy.getSnapshot)
  if not ok then
    print(string.format("%s %s: error: %s", short, machine.type, tostring(snapshot)))
  elseif not snapshot then
    -- Soft error: nil, "machine unavailable"
    print(string.format("%s %s: %s", short, machine.type, tostring(err)))
  else
    print(string.format("%s %-11s %s", short, fmt(snapshot.kind), fmt(snapshot.name)))
    print(string.format("         state=%s statusId=%s progress=%s euPerTick=%s",
      fmt(snapshot.state), fmt(snapshot.statusId), fmt(snapshot.progress), fmt(snapshot.euPerTick)))
    print("         " .. fmt(snapshot.statusText))
    if snapshot.warnings and #snapshot.warnings > 0 then
      print("         warnings: " .. table.concat(snapshot.warnings, ", "))
    end
    if wanted and not dumped and machine.address:sub(1, #wanted) == wanted then
      print("Full snapshot of " .. machine.address .. ":")
      dump(snapshot)
      dumped = true
    end
  end
end

if wanted and not dumped then
  print("No readable machine matches address " .. wanted)
end
