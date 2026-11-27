-- Test-Api.lua 를 가짜 워치로 돌려 본다. SCEN: A 정상(방법1) B 폰 없음 C 조각만 됨 D 바이트코드·기능 없음 E 명령이 다 오류
local SCEN = os.getenv("SCEN") or "A"
local PORT = os.getenv("PORT")
local FAKE = "/tmp/fakewatch"
local mock = dofile("mock.lua")
mock.EVENT.ALL = (SCEN ~= "D") and 0 or nil
package.preload["lvgl"] = function() return mock end
if SCEN ~= "D" and SCEN ~= "E" then
    package.preload["navigator"] = function() return { navigateTo = function() end, finish = print, PAGE = { HOME = 1 } } end
    package.preload["topic"] = function() return setmetatable({}, { __index = { subscribe = function() end } }) end
    package.preload["dataman"] = function() return { subscribe = function() end, get = function() end } end
    SCRIPT_PATH = "/data/app/watchface/687261404/lua/main.lua"
    miwear_apps_available = true
end
local realExec, realOpen = os.execute, io.open
local function remap(path) if string.find(path, "^/resource") or string.find(path, "^/data") then return FAKE .. path end return path end
io.open = function(path, mode) return realOpen(remap(path), mode) end
local curls = 0
os.execute = function(cmd)
    if SCEN == "E" then error("명령 실패") end
    local dir, out = string.match(cmd, "^ls %-R (%S+) > (%S+)$")
    if dir and os.getenv("NOREC") then return realExec("(echo '" .. dir .. ":'; ls -p " .. remap(dir) .. " | sed 's/^/ /') > " .. out .. " 2>/dev/null") end
    if dir then return realExec("ls -Rp " .. remap(dir) .. " 2>/dev/null | sed 's#" .. FAKE .. "##' > " .. out) end
    dir, out = string.match(cmd, "^ls (%S+) > (%S+)$")
    if dir then return realExec("(echo '" .. dir .. ":'; ls -p " .. remap(dir) .. " | sed 's/^/ /') > " .. out .. " 2>/dev/null") end
    if string.find(cmd, "^curl ") then
        curls = curls + 1
        if SCEN == "B" or not PORT then return 1 end
        if SCEN == "C" and (string.find(cmd, " -T ", 1, true) or string.find(cmd, "--data-binary", 1, true)) then return 1 end
        if SCEN == "D" and string.find(cmd, " -T ", 1, true) then return 1 end
        return realExec((string.gsub(cmd, "http://10%.1%.10%.1", "http://127.0.0.1:" .. PORT)):gsub("^curl ", "curl -sS --noproxy 127.0.0.1 "))
    end
    return 0
end
local logs = {}
local root = mock.Object(nil, {})
local chunk = assert(loadfile("/home/claude/rw6-smallfont-patcher/lua/Test-Api.lua"))
local before = #mock.created
chunk(mock, root, function(t) logs[#logs + 1] = t end)
assert(logs[#logs] == "조사 2 화면을 띄웠습니다.", "끝까지 못 감")
local title, body = mock.created[before + 2], mock.created[before + 3]
local buttons = {}
for i = before + 1, #mock.created do local o = mock.created[i] if o.kind == "Object" and o.props.h == 44 then buttons[#buttons + 1] = o end end
print("---- 올리기 전 | " .. title.props.text) print(body.props.text)
mock.pump()
for _ = 1, 3 do root:fire(145) end root:fire(200) root:fire(mock.EVENT.DRAW_MAIN) root:fire(145, false)
print("---- 올린 뒤 | " .. title.props.text) print(body.props.text)
local pages, all = 0, {}
repeat buttons[2]:fire(mock.EVENT.CLICKED) pages = pages + 1 all[#all + 1] = body.props.text until string.find(title.props.text, "첫 쪽", 1, true) or pages > 200
assert(pages <= 200, "쪽이 끝없이 넘어감")
buttons[1]:fire(mock.EVENT.CLICKED) buttons[1]:fire(mock.EVENT.CLICKED)
print("정보 쪽 수: " .. (pages - 1) .. " · curl " .. curls .. "번")
if os.getenv("ALL") then print(table.concat(all, "\n")) end
local first = string.match(body.props.text, "") and nil
io.open = realOpen
local rep = realOpen("/tmp/rw6-api.txt", "rb"):read("*a")
print("보고 글 " .. #rep .. "바이트")
if os.getenv("REP") then print(rep) end
print("SCEN " .. SCEN .. " 통과")
