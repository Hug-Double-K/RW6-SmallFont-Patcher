-- Test-Face.lua 를 가짜 워치로. SCEN: A 처음(그림 받기)+전부 정상 / B 그림 있음, 지난 키 상태, 폰 없음, dataman 없음
-- C 묶음이 잘려서 옴 / D curl 시간 제한 옵션을 모름, dataman 이 오류, navigator 없음 / E 그림 기능(Image)이 없음
local SCEN = os.getenv("SCEN") or "A"
local DIR = os.getenv("RW6FACE_DIR")
assert(DIR and os.getenv("RW6FACE_TMP"))
local mock = dofile("mock.lua")
mock.EVENT.ALL = 0
if SCEN == "E" then mock.imageFails = true end
local clock, dateText = 1000, "2026 11 27 21 01"
os.time = function() return clock end
local realDate = os.date
os.date = function(f) if f == "%Y %m %d %H %M" then return dateText end return realDate(f) end
local subs, finished = {}, 0
if SCEN ~= "B" then
    package.preload["dataman"] = function() return { subscribe = function(key, obj, cb)
        assert(type(key) == "string" and type(obj) == "table" and type(cb) == "function")
        if SCEN == "D" then error("unknown key " .. key) end
        subs[key] = function(v) cb(obj, v) end end } end
end
if SCEN ~= "D" then package.preload["navigator"] = function() return { finish = function() finished = finished + 1 end } end end
local curls, uploads, launches = {}, {}, 0
local function put(path, text) local f = assert(io.open(path, "wb")) f:write(text) f:close() end
os.execute = function(cmd)
    if not string.find(cmd, "^curl ") then return 0 end
    curls[#curls + 1] = cmd
    local out = string.match(cmd, " %-o (%S+)$")
    assert(out, "curl 에 -o 가 없음: " .. cmd)
    if string.find(cmd, "face7.pak", 1, true) then
        local data = io.open("/home/claude/rw6-smallfont-patcher/lua/face7/face7.pak", "rb"):read("*a")
        if SCEN == "C" then data = string.sub(data, 1, 50000) end
        put(out, data) return 0
    end
    if SCEN == "B" then return 1 end
    if SCEN == "D" and string.find(cmd, "connect-timeout", 1, true) then return 1 end
    if string.find(cmd, "/do/weather", 1, true) then put(out, "temp -3\n")
    elseif string.find(cmd, "/do/launch-noti", 1, true) then launches = launches + 1 put(out, "ok com.tencent.wechatrtos\n")
    elseif string.find(cmd, "-T ", 1, true) then
        local src = string.match(cmd, "%-T (%S+)")
        local text = io.open(src, "rb"):read("*a")
        uploads[#uploads + 1] = text put(out, "ok " .. #text .. "\n")
    else error("모르는 curl: " .. cmd) end
    return 0
end
if SCEN == "B" then put(DIR .. "keys.txt", "systemStatusBattery=ok\nhealthStepCount=trying\n") end
local root = mock.Object(nil, {})
local before = #mock.created
local logs = {}
assert(loadfile("/home/claude/rw6-smallfont-patcher/lua/Test-Face.lua"))(mock, root, function(t) logs[#logs + 1] = t end)
assert(#logs == 1)
local panel = mock.created[before + 1]
assert(panel.flags[mock.FLAG.EVENT_BUBBLE] and not panel.flags[mock.FLAG.SCROLLABLE])
local function step(n) for _ = 1, (n or 1) do clock = clock + 1 mock.pump() end end
local function labels() local t = {} for i = before + 1, #mock.created do local o = mock.created[i] if o.kind == "Label" then t[#t + 1] = tostring(o.props.text) end end return table.concat(t, " | ") end
local function shownAt(x, y)      -- 그 자리에 보이는 그림 이름
    for i = before + 1, #mock.created do local o = mock.created[i]
        if o.kind == "Image" and o.props.x == x and o.props.y == y and o.props.src and not o.flags[mock.FLAG.HIDDEN] then
            assert(io.open(o.props.src, "rb"), "없는 그림: " .. o.props.src):close()
            return (string.match(o.props.src, "([^/]+)%.png$"))
        end
    end
end
if SCEN ~= "B" then
    assert(string.find(labels(), "받는 중", 1, true))
    for _ = 1, 30 do mock.pump() end           -- 받기 + 풀기(12장씩)
end
if SCEN == "C" then assert(string.find(labels(), "잘림 X", 1, true), labels()) assert(not io.open(DIR .. "ver.txt")) print("C 통과: " .. labels()) return end
local buttons = {}
for i = before + 1, #mock.created do local o = mock.created[i] if o.kind == "Object" and o.props.h == 40 then buttons[#buttons + 1] = o end end
assert(#buttons == 2, "버튼 수 " .. #buttons .. " / " .. labels())
if SCEN == "E" then step(6) assert(string.find(uploads[#uploads], "그림 오류 1%d", 1) or string.find(uploads[#uploads], "그림 오류 %d+"), uploads[#uploads]) print("E 통과(그림 기능이 없어도 안 죽음)") return end
local E = mock.EVENT
assert(io.open(DIR .. "ver.txt")):close()
-- 시계·날짜
assert(shownAt(0, 0) == "bg")
assert(shownAt(80, 122) == "time_2" and shownAt(142, 122) == "time_1" and shownAt(228, 122) == "time_0" and shownAt(290, 122) == "time_1")
assert(shownAt(143, 90) == "date_m11" and shownAt(204, 90) == "date_d27" and shownAt(265, 90) == "date_w5", "2026-11-27 은 금요일")
step(3)
if SCEN == "B" then
    assert(shownAt(124, 272) == nil and launches == 0)
    assert(string.find(io.open(DIR .. "keys.txt"):read("*a"), "healthStepCount=bad", 1, true))
else
    assert(shownAt(104, 272) == "val_minus" and shownAt(124, 272) == "val_3", "기온 -3")
end
dateText = "2026 11 27 21 02" step(1)
assert(shownAt(290, 122) == "time_2")
-- 터치만: 안 엶 / 용두(62 63 뒤 145 두 번): 한 번만 엶 / 혼자 온 145: 안 엶 / 8초 안 다시: 안 엶
root:fire(62) root:fire(63) step(1)
local base = #curls
root:fire(62) root:fire(63) root:fire(145) root:fire(145) root:fire(21)
mock.pump() clock = clock + 1
local function launchCurls() local n = 0 for i = base + 1, #curls do if string.find(curls[i], "launch-noti", 1, true) then n = n + 1 end end return n end
local want = (SCEN == "B") and 2 or 1          -- 폰이 없으면 두 가지 꼴을 다 해 봄(둘 다 답 없음)
assert(launchCurls() == want, "열기 요청 " .. launchCurls())
assert(string.find(curls[#curls], "launch-noti", 1, true), "열기 요청 뒤에 다른 일을 함: " .. curls[#curls])
step(4) root:fire(145) step(1) root:fire(62) root:fire(63) root:fire(145) step(1)
assert(launchCurls() == want, "되풀이해서 엶")
step(20)
-- 다시 열 준비가 된 뒤에는 또 열림 / 앱에서 돌아온 직후(쉬었다 옴)의 신호로는 안 엶
root:fire(62) root:fire(63) root:fire(145) mock.pump() clock = clock + 1
assert(launchCurls() == 2 * want, "조용해진 뒤 다시 돌렸는데 안 엶 " .. launchCurls())
clock = clock + 40 mock.pump()                         -- 앱이 40초 떠 있다가 돌아옴
root:fire(62) root:fire(63) root:fire(145) root:fire(145) step(2)
assert(launchCurls() == 2 * want, "돌아온 직후 신호로 다시 엶")
step(8) want = 2 * want
if SCEN ~= "B" then assert(#uploads >= 1 and string.find(uploads[#uploads], "열기 요청 (용두)", 1, true) and string.find(uploads[#uploads], "쉬었다 옴 4", 1, true) and string.find(uploads[#uploads], "145@%d+x2"), uploads[#uploads]) end
-- 용두 끔
buttons[2]:fire(E.CLICKED) buttons[2]:fire(E.CLICKED)        -- 같은 누름이 두 번 와도 한 번만
assert(string.find(labels(), "용두 끔", 1, true))
root:fire(62) root:fire(63) root:fire(145) step(2)
assert(launchCurls() == want)
-- 값 시험: 누를 때마다 한 가지 키
step(3)
for i = 1, 4 do buttons[1]:fire(E.CLICKED) step(3) end
local keys = io.open(DIR .. "keys.txt"):read("*a")
if SCEN == "A" then
    assert(subs.systemStatusBattery and subs.healthStepCount and subs.healthHeartRate)
    assert(keys == "systemStatusBattery=ok\nhealthStepCount=ok\nhealthHeartRate=ok\n", keys)
    subs.systemStatusBattery(25600) subs.healthStepCount(6240 * 256) subs.healthHeartRate(2147483647)
    assert(shownAt(40, 30) == "bi_100" and shownAt(94, 27) == "bat_1" and shownAt(126, 27) == "bat_0")
    local pct = false
    for i = before + 1, #mock.created do local o = mock.created[i] if o.props.src and string.find(o.props.src, "bpct_3", 1, true) then pct = true end end
    assert(pct, "% 자리")
    assert(shownAt(262, 370) == "st_6" and shownAt(349, 370) == "st_0", "걸음 4자리 가운데")
    assert(shownAt(288, 272) == nil)
    subs.systemStatusBattery(7 * 256) subs.healthHeartRate(19712 - 512) subs.healthStepCount(12345 * 256)
    assert(shownAt(40, 30) == "bi_10" and shownAt(94, 27) == "bat_7" and shownAt(110, 27) == nil)
    assert(shownAt(288, 272) == "val_7" and shownAt(308, 272) == "val_5" and shownAt(248, 370) == "st_1")
    step(6)
    assert(string.find(uploads[#uploads], "healthStepCount = ok / 받은 횟수 2 / 받은 값 1597440 3160320", 1, true), uploads[#uploads])
elseif SCEN == "D" then
    assert(string.find(keys, "systemStatusBattery=error", 1, true) and string.find(labels(), "X", 1, true))
elseif SCEN == "B" then
    assert(string.find(labels(), "심박 X", 1, true), labels())
end
if SCEN == "D" then
    local seen = false
    for _, c in ipairs(curls) do if string.find(c, "^curl http://10.1.10.1/do/") then seen = true end end
    assert(seen and string.find(uploads[#uploads], "curl 꼴 \n", 1, true) == nil)
end
print(SCEN .. " 통과 / curl " .. #curls .. "번, 올림 " .. #uploads .. "번 / " .. labels())
