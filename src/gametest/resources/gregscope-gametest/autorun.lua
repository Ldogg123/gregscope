-- GregScope game test runner (OpenComputersExampleScriptTests, HubExampleScriptTests). OpenOS runs this file once at
-- boot as the autorun of the writable disk. It runs one unmodified docs/examples/*.lua script with stdout redirected
-- to a file on the same disk, catches every Lua error, and publishes a status marker last so Java never reads partial
-- output. Never add an init.lua next to this file: the BIOS would boot it instead of the OpenOS floppy.
--
-- Java chooses what to run by writing two optional files next to this one:
--   gregscope-script.txt   the script file name (default gregscope-snapshot.lua)
--   gregscope-await.txt    a callback name to wait for on some component (default getSnapshot)
--   gregscope-args.txt     one argument for a second run, whose output goes to gregscope-dump.txt

local component = require("component")
local computer = require("computer")
local fs = require("filesystem")

local proxy = ...
local root
if type(proxy) == "table" then
  for mounted, path in fs.mounts() do
    if mounted.address == proxy.address then
      root = path
    end
  end
end
if not root then
  root = fs.path(os.getenv("_") or "/") or "/"
end

local outPath = fs.concat(root, "gregscope-out.txt")
local statusTmp = fs.concat(root, "gregscope-status.tmp")
local statusPath = fs.concat(root, "gregscope-status.txt")

local function traceback(message)
  return debug.traceback(tostring(message), 2)
end

local function readLine(name)
  local file = io.open(fs.concat(root, name), "r")
  if not file then
    return nil
  end
  local line = file:read("*l")
  file:close()
  return line
end

local scriptName = readLine("gregscope-script.txt") or "gregscope-snapshot.lua"
local awaitMethod = readLine("gregscope-await.txt") or "getSnapshot"

local out = assert(io.open(outPath, "w"))
local status, detail = "ok", ""

local runnerOk, runnerErr = xpcall(function()
  -- Java only starts the computer once the component is on the network; this bounded wait is a safety net.
  local deadline = computer.uptime() + 5
  repeat
    local found = false
    for address in component.list() do
      local candidate = component.proxy(address)
      if candidate and candidate[awaitMethod] then
        found = true
        break
      end
    end
    if found then
      break
    end
    os.sleep(0.25)
  until computer.uptime() > deadline

  local chunk, loadErr = loadfile(fs.concat(root, scriptName))
  if not chunk then
    status, detail = "error", "load error: " .. tostring(loadErr)
    return
  end
  local function capture(file, ...)
    local previous = io.output()
    io.output(file) -- process-local stdout: print() in the example now writes to the file
    local ok, err = xpcall(chunk, traceback, ...) -- string arguments only, exactly like the shell passes them
    io.output(previous)
    if not ok then
      status, detail = "error", "lua error: " .. tostring(err)
    end
    return ok
  end

  -- 1. `gregscope-snapshot` with no arguments: summary of every machine.
  if not capture(out) then
    return
  end

  -- 2. `gregscope-snapshot <address prefix>`: summary plus the full dump, when Java supplied a prefix.
  local argsFile = io.open(fs.concat(root, "gregscope-args.txt"), "r")
  if argsFile then
    local prefix = argsFile:read("*l")
    argsFile:close()
    local dump = assert(io.open(fs.concat(root, "gregscope-dump.txt"), "w"))
    capture(dump, prefix)
    dump:close()
  end
end, traceback)
if not runnerOk then
  status, detail = "error", "runner error: " .. tostring(runnerErr)
end
out:close()

local marker = assert(io.open(statusTmp, "w"))
marker:write(status, "\n", detail, "\n", tostring(_VERSION), "\n")
marker:close()
assert(fs.rename(statusTmp, statusPath))
