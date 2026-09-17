-- GregScope game test runner (OpenComputersExampleScriptTests). OpenOS runs this file once at boot as the autorun of
-- the writable disk. It runs the unmodified docs/examples/gregscope-snapshot.lua with stdout redirected to a file on
-- the same disk, catches every Lua error, and publishes a status marker last so Java never reads partial output.
-- Never add an init.lua next to this file: the BIOS would boot it instead of the OpenOS floppy.

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

local out = assert(io.open(outPath, "w"))
local status, detail = "ok", ""

local runnerOk, runnerErr = xpcall(function()
  -- Java only starts the computer once the Adapter component is on the network; this bounded wait is a safety net.
  local deadline = computer.uptime() + 5
  repeat
    local found = false
    for address in component.list() do
      local candidate = component.proxy(address)
      if candidate and candidate.getSnapshot then
        found = true
        break
      end
    end
    if found then
      break
    end
    os.sleep(0.25)
  until computer.uptime() > deadline

  local chunk, loadErr = loadfile(fs.concat(root, "gregscope-snapshot.lua"))
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
