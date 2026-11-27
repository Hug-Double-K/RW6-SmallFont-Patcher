-- Test-Crown2.lua 를 가짜 워치로. SCEN: A 아래=#62 위=#63 +145 둘 다, topic(이름,함수) / B 둘 다 #145뿐, topic(이름,물체,함수), 폰 없음
-- C topic·activity·dataman 없음, EVENT.ALL 없음 / D 부르면 다 오류 / E 화면이 가려짐(isShown false), 자동 열기 두 번 연속
local SCEN = os.getenv("SCEN") or "A"
local PORT = os.getenv("PORT")
local mock = dofile("mock.lua")
mock.EVENT.ALL = (SCEN ~= "C") and 0 or nil
package.preload["lvgl"] = function() return mock end
local subs, datasubs = {}, {}
if SCEN ~= "C" then
    package.preload["topic"] = function() return { subscribe = function(name, a, b)
        if SCEN == "D" then error("bad argument #1 to 'subscribe'") end
        if SCEN == "B" then
            if type(a) == "function" then error("bad argument #2 to 'subscribe' (lv_obj expected, got function)") end
            subs[name] = function(...) b(a, ...) end return true
        end
        if name == "sensor_ots0" then error("no such topic") end
        subs[name] = a return { handle = name }
    end } end
    package.preload["activity"] = function() return { APPID = { LUA = 1, WATCHFACE = 2 }, isShown = function(id)
        if SCEN == "D" then error("boom") end
        return SCEN ~= "E" end } end
    package.preload["dataman"] = function() return { subscribe = function(key, obj, cb)
        if SCEN == "D" or key == "healthCalories" then error("unknown key " .. key) end
        datasubs[key] = function(v) cb(obj, v) end end } end
end
local realExec = os.execute
local curls = {}
os.execute = function(cmd)
    if string.find(cmd, "^curl ") then
        curls[#curls + 1] = cmd
        if SCEN == "B" or SCEN == "D" or not PORT then return 1 end
        return realExec((string.gsub(cmd, "http://10%.1%.10%.1", "http://127.0.0.1:" .. PORT)):gsub("^curl ", "curl -sS --noproxy 127.0.0.1 "))
    end
    return 0
end
local root = mock.Object(nil, {})
local before = #mock.created
local logs = {}
assert(loadfile("/home/claude/rw6-smallfont-patcher/lua/Test-Crown3.lua"))(mock, root, function(t) logs[#logs + 1] = t end)
assert(logs[#logs] == "조사 3 화면을 띄웠습니다.")
local title, big, body = mock.created[before + 2], mock.created[before + 3], mock.created[before + 4]
local buttons = {}
for i = before + 1, #mock.created do local o = mock.created[i] if o.kind == "Object" and o.props.h == 44 then buttons[#buttons + 1] = o end end
assert(#buttons == 2)
local E = mock.EVENT
local guard = 0
while not string.find(title.props.text, "끝", 1, true) do
    guard = guard + 1 assert(guard < 200, "단계가 안 끝남")
    local t = big.props.text
    root:fire(E.DRAW_MAIN) root:fire(E.STYLE_CHANGED)
    if string.find(t, "아래로", 1, true) then
        root:fire(145) if SCEN ~= "B" then root:fire(62) end
        if subs.sensor_ots then subs.sensor_ots({ delta = -3, timestamp = 12345 }) end
    elseif string.find(t, "위로", 1, true) then
        root:fire(145) if SCEN ~= "B" then root:fire(63) end
        if subs.sensor_ots then subs.sensor_ots("\1\2\3\255raw") end
    else
        if subs.battery_state and guard % 5 == 0 then subs.battery_state({ level = 80 }, 7) end
    end
    root:fire(E.PRESSED, false)
    mock.pump()
end
print("---- 단계 끝 | " .. title.props.text .. " | " .. big.props.text)
mock.pump()        -- 올리기
print("---- 올린 뒤 | " .. big.props.text) print(body.props.text)
local function hidden(o) return o.flags[mock.FLAG.HIDDEN] == true end
print("버튼 보임: 열기 " .. tostring(not hidden(buttons[1])) .. " 자동 " .. tostring(not hidden(buttons[2])) .. "")
local n0 = #curls
buttons[1]:fire(E.CLICKED)
print("열기 누름 → curl " .. (#curls - n0) .. "번: " .. tostring(curls[#curls])) print(body.props.text)
if not hidden(buttons[2]) then
    buttons[2]:fire(E.CLICKED)
    local n1 = #curls
    root:fire(62) root:fire(62) root:fire(62)      -- 한 번 돌릴 때 여러 번 와도 한 번만 열어야 함 (방금 버튼으로 열었으니 4초 안엔 0번)
    print("자동 켠 뒤 같은 신호 3번 → curl " .. (#curls - n1) .. "번 (4초 안이라 0이어야 함)")
    assert(#curls - n1 == 0)
    root:fire(63) assert(#curls - n1 == 0, "위 신호에 열림")
end

for k, f in pairs(datasubs) do if k == "systemStatusBattery" then f(80 * 256) elseif k == "healthStepCount" then f(2147483647) elseif k == "timeHour" then f(19 * 256) f(20 * 256) end end
mock.pump()
print(body.props.text)
print("==== 보고 글 ====") print(io.open("/tmp/rw6-crown3.txt", "rb"):read("*a"))
print("SCEN " .. SCEN .. " 통과")
