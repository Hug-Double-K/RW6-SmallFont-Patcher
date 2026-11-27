-- lua/face7/main.lua (정식 워치페이스 시작 파일) + face.lua 를 가짜 워치로.
-- SCEN: M1 처음(받아서 실행, 새 판 2 가 저장소에 있음) / M2 두 번 멈춘 뒤(안전 화면 → 다시 시도) / M3 파일이 깨짐(→ 새로 받기) / M4 못 받음
local SCEN = os.getenv("SCEN") or "M1"
local DIR = os.getenv("RW6FACE_DIR")
local REPO = "/home/claude/rw6-smallfont-patcher/lua/face7/"
local mock = dofile("mock.lua")
mock.EVENT.ALL = 0
package.preload["lvgl"] = function() return mock end
local clock = 5000
os.time = function() return clock end
local subs = {}
package.preload["dataman"] = function() return { subscribe = function(key, obj, cb) subs[key] = function(v) cb(obj, v) end end } end
local function slurp(p) local f = io.open(p, "rb") if not f then return nil end local t = f:read("*a") f:close() return t end
local function put(path, text) local f = assert(io.open(path, "wb")) f:write(text) f:close() end
local curls, launches, uploads = {}, 0, {}
os.execute = function(cmd)
    if string.find(cmd, "^ls ") then return 1 end
    if not string.find(cmd, "^curl ") then return 0 end
    curls[#curls + 1] = cmd
    local out = assert(string.match(cmd, " %-o (%S+)$"), cmd)
    if string.find(cmd, "face7/face.lua", 1, true) then
        if SCEN == "M4" then return 1 end
        local text = slurp(REPO .. "face.lua")
        if string.find(cmd, "-m 40", 1, true) then text = string.gsub(text, "local FACE_VER = 5\n", "local FACE_VER = 6\n") end   -- 새 판 받기
        put(out, text)
    elseif string.find(cmd, "face7.pak", 1, true) then put(out, slurp(REPO .. "face7.pak"))
    elseif string.find(cmd, "version.txt", 1, true) then put(out, "6\n")
    elseif string.find(cmd, "/do/weather", 1, true) then put(out, "temp 9\n")
    elseif string.find(cmd, "/do/launch-noti", 1, true) then launches = launches + 1 put(out, "ok x\n")
    elseif string.find(cmd, "-T ", 1, true) then uploads[#uploads + 1] = slurp(string.match(cmd, "%-T (%S+)")) put(out, "ok 1\n")
    else error("모르는 curl: " .. cmd) end
    return 0
end
if SCEN == "M2" then put(DIR .. "face.lua", slurp(REPO .. "face.lua")) put(DIR .. "boot.txt", "2") end
if SCEN == "M3" then put(DIR .. "face.lua", "local x = = 1") end
local src = string.gsub(slurp(REPO .. "main.lua"), "/data/rw6face/", DIR)
assert(load(src, "main.lua"))()
local function labels() local t = {} for _, o in ipairs(mock.created) do if o.kind == "Label" then t[#t + 1] = tostring(o.props.text) end end return table.concat(t, " | ") end
local function step(n) for _ = 1, (n or 1) do clock = clock + 1 mock.pump() end end
local function has(name) for _, o in ipairs(mock.created) do if o.kind == "Image" and o.props.src and string.find(o.props.src, name, 1, true) then return true end end return false end
local function button(text) for i, o in ipairs(mock.created) do if o.kind == "Label" and o.props.text == text then return mock.created[i - 1] end end end
local root = mock.created[1]
assert(root.flags[mock.FLAG.EVENT_BUBBLE])
if SCEN == "M4" then
    step(2) assert(string.find(labels(), "받지 못했습니다 X", 1, true) and not button("다시 시도").flags[mock.FLAG.HIDDEN]) assert(not slurp(DIR .. "face.lua"))
    print("M4 통과") return
end
if SCEN == "M2" then
    assert(string.find(labels(), "안전 화면", 1, true) and not has("bg.png") and #curls == 0)
    button("다시 시도"):fire(mock.EVENT.CLICKED) button("다시 시도"):fire(mock.EVENT.CLICKED)
elseif SCEN == "M3" then
    assert(string.find(labels(), "파일 오류 X", 1, true))
    button("새로 받기"):fire(mock.EVENT.CLICKED)
end
for _ = 1, 30 do mock.pump() end         -- 받기·그림 풀기
assert(has("bg.png"), labels())
assert(slurp(DIR .. "boot.txt") == "0", "무사히 떴으면 0: " .. tostring(slurp(DIR .. "boot.txt")))
assert(not button("값 시험") and not button("값 상태"), "정식판에 시험 버튼이 있음")
assert(subs.systemStatusBattery and subs.healthStepCount and subs.healthHeartRate, "값 세 가지를 스스로 들어야 함")
subs.systemStatusBattery(24064) subs.healthStepCount(1493760) subs.healthHeartRate(19712)
assert(has("bat_9.png") and has("bat_4.png") and has("st_5.png") and has("st_8.png") and has("val_7.png"))
step(4) assert(has("val_9.png"), "기온 9")
-- 용두 / 말풍선 터치
root:fire(62) root:fire(63) root:fire(145) mock.pump() assert(launches == 0, "용두로는 안 엶")

step(12)
local tap
for _, o in ipairs(mock.created) do if o.kind == "Object" and o.props.w == 96 then tap = o end end
tap:fire(mock.EVENT.CLICKED) mock.pump()
assert(launches == 1, "말풍선 터치로 열기")
-- 새 판 확인(40초 뒤): 저장소 2 → 받아서 저장, 지금 화면은 그대로
step(45)
assert(string.find(slurp(DIR .. "face.lua"), "local FACE_VER = 6\n", 1, true), "새 판 저장")
step(5) assert(string.find(uploads[#uploads], "새 판 6 받음", 1, true), uploads[#uploads])
for _, c in ipairs(curls) do assert(string.find(c, "raw.githubusercontent", 1, true) or string.find(c, "connect-timeout", 1, true), "시간 제한 없는 폰 요청: " .. c) end
print(SCEN .. " 통과 / curl " .. #curls .. "번")
