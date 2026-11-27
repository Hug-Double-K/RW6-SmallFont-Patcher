local SCEN = os.getenv("SCEN") or "A"
local mock = dofile("mock.lua")
package.preload["lvgl"] = function() return mock end
local repo = "/home/claude/rw6-smallfont-patcher/"
local realExec = os.execute
local realOpen = io.open
io.open = function(path, mode) if path == "/proc/mounts" then return nil end return realOpen(path, mode) end
os.execute = function(cmd)
    if string.find(cmd, "^help > ") then
        local f = io.open(string.match(cmd, "> (.+)$"), "w")
        f:write("help usage: help [-v] [<cmd>]\n  .  cd  echo  ls  ps  reboot\nBuiltin Apps:\n  am  input  curl  uorb_listener  miwear_bluetooth  lua  dumpstack\n" .. string.rep("cmd_x ", SCEN == "C" and 300 or 5))
        f:close() return 0
    end
    do
        local c, out = string.match(cmd, "^(.-) > (%S+)$")
        local fake = {
            ["mount"] = "  /data type littlefs\n  /dev/shm type tmpfs\n  /proc type procfs\n  /resource type romfs\n  /tmp type tmpfs\n  /quickapp type fatfs\n",
            ["ls /"] = "/:\n data/\n dev/\n proc/\n resource/\n tmp/\n",
            ["ls /dev"] = "/dev:\n console\n null\n resource\n uorb/\n input0\n",
            ["ls /dev/uorb"] = "/dev/uorb:\n sensor_step_counter0\n sensor_hrate0\n battery_state\n",
            ["ls /data"] = "/data:\n app/\n kvdb/\n", ["ls /resource"] = "/resource:\n font/\n i18n/\n",
            ["ls /quickapp"] = "/quickapp:\n io.github.hugdoublek.rw6noti/\n",
            ["ps"] = "  PID GROUP PRI POLICY TYPE NPX STATE EVENT SIGMASK STACK COMMAND\n 0 0 0 FIFO Kthread - Ready 0000 4096 Idle_Task\n 55 55 100 RR Task - Waiting Sem 0000 8192 miwear\n",
            ["getprop"] = "ro.build.version=3.100.043\npersist.locale=ko_KR\n",
        }
        if c and SCEN ~= "B" and (fake[c] or SCEN == "C") then
            local f = io.open(out, "w")
            f:write(SCEN == "C" and string.rep((fake[c] or "x") .. " /very/long/path/name_without_spaces_0123456789abcdefghijklmnopqrstuvwxyz ", 40) or fake[c])
            f:close() return 0
        end
        if c and SCEN == "D" then return nil, "exit", 1 end
        if c and SCEN == "E" then error("명령 실패") end
    end
    local url, out = string.match(cmd, "^curl (%S+)%s+%-o%s*(%S+)")
    if url then
        local name = string.match(url, "/lua/(.+)$")
        local src = name and io.open(repo .. "lua/" .. name, "rb")
        if not src then return 1 end
        local f = io.open(out, "wb") f:write(src:read("*a")) f:close() src:close() return 0
    end
    return 0
end
os.remove("/tmp/rw6-crown.txt")
if SCEN == "C" then local f = io.open("/tmp/rw6-crown.txt", "w") f:write("화면.KEY 화면.GESTURE | ") f:close() end

-- 1) 패처 본체에 건 고리(selectFont)를 통해 실행
local src = io.open(repo .. "lua/rw6-font.lua", "rb"):read("*a") .. "\n_G.__selectFont = selectFont\n_G.__busy = function() return busy end\n"
local chunk = assert(load(src, "rw6-font.lua"))
chunk()
local logView
for _, o in ipairs(mock.created) do if o.kind == "Textarea" then logView = o end end
local before = #mock.created
assert(__selectFont("Test-Crown") == true)
mock.pump()
assert(__busy() == false, "busy가 안 풀림")
local text = logView.props.text
assert(not string.find(text, "오류"), "오류: " .. string.sub(text, -300))
assert(string.find(text, "용두 조사 화면을 띄웠습니다", 1, true), "끝까지 못 감: " .. string.sub(text, -200))

-- 2) 조사 화면 찾기
local panel, title, body, box, prev, nxt
panel = mock.created[before + 1]
title, body, box = mock.created[before + 2], mock.created[before + 3], mock.created[before + 4]
local buttons = {}
for i = before + 1, #mock.created do local o = mock.created[i] if o.kind == "Object" and o.props.h == 44 then buttons[#buttons + 1] = o end end
prev, nxt = buttons[1], buttons[2]
assert(panel and title and body and prev and nxt)
local function screen(tag) print("---- " .. tag .. " | " .. title.props.text) print(body.props.text) end
screen("처음")
-- 3) 신호 넣기: 잡음·키·모르는 번호·코드 없이 부르는 경우·상자 스크롤
local E = mock.EVENT
for _ = 1, 50 do panel:fire(E.DRAW_MAIN) panel:fire(E.STYLE_CHANGED) end
panel:fire(E.KEY) panel:fire(E.KEY) panel:fire(E.GESTURE) panel:fire(99) panel:fire(E.PRESSED) panel:fire(E.CLICKED)
panel:fire(E.KEY, false)
box:fire(E.SCROLL) box:fire(E.SCROLL)
for i = 1, 14 do panel:fire(200 + i) end      -- 서로 다른 신호가 많이 올 때
screen("신호 뒤")
-- 4) 쪽 넘기기: 끝까지 갔다가 처음으로, 이전으로도
local pages = 0
repeat nxt:fire(E.CLICKED) pages = pages + 1 if pages <= (tonumber(os.getenv("ALL")) or 3) then screen("다음 " .. pages) end until string.find(title.props.text, "실시간", 1, true) or pages > 80
assert(pages <= 80, "쪽이 끝없이 넘어감")
print("정보 쪽 수: " .. (pages - 1))
-- 모든 쪽의 모든 줄이 폭 안에 드는지 (영문 34자 이하)
for p = 1, pages - 1 do nxt:fire(E.CLICKED) for line in string.gmatch(body.props.text, "[^\n]+") do assert(#line <= 34 or string.find(line, "[\128-\255]"), "줄이 너무 김: " .. line) end end
nxt:fire(E.CLICKED) assert(string.find(title.props.text, "실시간", 1, true))
prev:fire(E.CLICKED) screen("이전(마지막 쪽)")

local rec = io.open("/tmp/rw6-crown.txt", "rb"):read("*a")
print("기록 파일: " .. rec)
print("SCEN " .. SCEN .. " 통과")
