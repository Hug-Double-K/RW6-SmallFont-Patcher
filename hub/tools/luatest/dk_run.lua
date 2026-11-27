-- Test-DownKey.lua 시험. SCEN: A 값만 나옴·잘 바뀜 / B '이름: 값' 꼴 / C setprop 이 무시됨 / D getprop 결과 없음 / E 명령이 오류 / F 이미 우리 값인데 적어 둔 것 없음
local SCEN = os.getenv("SCEN") or "A"
local mock = dofile("mock.lua")
package.preload["lvgl"] = function() return mock end
local prop = (SCEN == "F") and "com.tencent.wechatrtos" or "com.xiaomi.miwear.sports"
local realOpen, realExec, realRemove = io.open, os.execute, os.remove
local function remap(p) if string.find(p, "^/data/") then return "/tmp/fakedata" .. string.sub(p, 6) end return p end
realExec("mkdir -p /tmp/fakedata; rm -f /tmp/fakedata/rw6-downkey-original.txt")
io.open = function(p, m) return realOpen(remap(p), m) end
os.remove = function(p) return realRemove(remap(p)) end
local sets, curls = {}, 0
os.execute = function(cmd)
    if SCEN == "E" then error("명령 실패") end
    local out = string.match(cmd, "^getprop persist%.downkey%.pkgname > (%S+)$")
    if out then
        if SCEN == "D" then return 1 end
        local f = realOpen(out, "w") f:write(SCEN == "B" and ("persist.downkey.pkgname: " .. prop .. "\n") or (prop .. "\n")) f:close() return 0
    end
    local v = string.match(cmd, "^setprop persist%.downkey%.pkgname (%S+)$")
    if v then sets[#sets + 1] = v if SCEN ~= "C" then prop = v end return 0 end
    if string.find(cmd, "^curl ") then curls = curls + 1 assert(string.find(cmd, "/r/downkey.txt", 1, true)) return 0 end
    error("모르는 명령: " .. cmd)
end
local root = mock.Object(nil, {})
local before = #mock.created
local logs = {}
assert(loadfile("/home/claude/rw6-smallfont-patcher/lua/Test-DownKey.lua"))(mock, root, function(t) logs[#logs + 1] = t end)
assert(logs[#logs] == "아래 버튼 바꾸기 화면을 띄웠습니다.")
local body
local buttons = {}
for i = before + 1, #mock.created do local o = mock.created[i]
    if o.kind == "Object" and o.props.h == 48 then buttons[#buttons + 1] = o end
    if o.kind == "Label" and o.props.h == 250 then body = o end end
assert(#buttons == 2 and body)
local E = mock.EVENT
print("-- 처음\n" .. body.props.text)
buttons[1]:fire(E.CLICKED) buttons[1]:fire(E.CLICKED)        -- 두 번 눌려도 한 번만
mock.pump()
print("-- 바꾼 뒤\n" .. body.props.text)
assert(#sets <= 1, "두 번 바뀜")
if SCEN == "A" or SCEN == "B" then assert(prop == "com.tencent.wechatrtos") assert(string.find(body.props.text, "O 성공", 1, true)) end
if SCEN == "C" then assert(string.find(body.props.text, "X 실패", 1, true)) end
buttons[2]:fire(E.CLICKED) mock.pump()
print("-- 되돌린 뒤\n" .. body.props.text)
if SCEN ~= "E" and SCEN ~= "C" then assert(prop == "com.xiaomi.miwear.sports", "원래대로 안 돌아감: " .. prop) end
for _, v in ipairs(sets) do assert(v == "com.tencent.wechatrtos" or v == "com.xiaomi.miwear.sports", "이상한 값: " .. v) end
print("setprop " .. #sets .. "번: " .. table.concat(sets, ", ") .. " · curl " .. curls .. "번")
print("SCEN " .. SCEN .. " 통과")
