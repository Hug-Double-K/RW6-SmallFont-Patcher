-- DoubleK's 7 (Lua) 워치페이스의 시작 파일. 일은 /data/rw6face/face.lua 가 하고, 이 파일은 그것을 받아 두고 안전하게 부르기만 한다.
-- 안전장치: face.lua 가 시작하다 워치가 두 번 연속 멈추면(재시작되면) 더는 부르지 않고 안전 화면을 띄운다 → 켤 때마다 재시작되는 일을 막음.
local lvgl = require("lvgl")
local W, H = lvgl.HOR_RES(), lvgl.VER_RES()
local dir = "/data/rw6face/"
local facePath, guardPath = dir .. "face.lua", dir .. "boot.txt"
local sourceUrl = "https://raw.githubusercontent.com/Hug-Double-K/RW6-SmallFont-Patcher/main/lua/face7/face.lua"
local font = lvgl.Font("MiSans-Medium", 18)

local function readAll(path)
    local file = io.open(path, "rb")
    if not file then return nil end
    local ok, value = pcall(function() return file:read("*a") end)
    file:close()
    return ok and value or nil
end
local function writeAll(path, text)
    local file = io.open(path, "wb")
    if not file then return false end
    local ok = pcall(function() file:write(text) end)
    file:close()
    return ok
end

local root = lvgl.Object(nil, { w = W, h = H, align = lvgl.ALIGN.CENTER, border_width = 0, pad_all = 0, bg_color = "#071B33" })
root:clear_flag(lvgl.FLAG.SCROLLABLE)
root:add_flag(lvgl.FLAG.EVENT_BUBBLE)          -- 길게 누르기(워치페이스 바꾸기)가 워치로 넘어가게
local status = lvgl.Label(root, { x = 40, y = 170, w = W - 80, h = 150, text = "", text_color = "#e2e5eb", text_font = font })
local function button(y, text, color)
    local object = lvgl.Object(root, { x = 76, y = y, w = W - 152, h = 52, radius = 10, border_width = 1, border_color = color,
        bg_color = "#252830", pad_all = 0 })
    object:clear_flag(lvgl.FLAG.SCROLLABLE)
    object:add_flag(lvgl.FLAG.CLICKABLE)
    object:add_flag(lvgl.FLAG.HIDDEN)
    lvgl.Label(object, { text = text, align = lvgl.ALIGN.CENTER, text_color = color, text_font = font })
    return object
end
local retryButton = button(330, "다시 시도", "#78d99b")
local fetchButton = button(392, "새로 받기", "#71b7ff")
local busy, started = false, false      -- started: 워치페이스가 이미 떴음(같은 누름이 두 번 와도 다시 띄우지 않음)
local function problem(text)
    busy = false
    status:set { text = text }
    retryButton:clear_flag(lvgl.FLAG.HIDDEN)
    fetchButton:clear_flag(lvgl.FLAG.HIDDEN)
end

local function run()
    local chunk, loadError = loadfile(facePath)
    if not chunk then problem("워치페이스 파일 오류 X\n" .. tostring(loadError)) return end
    local count = tonumber(readAll(guardPath) or "") or 0
    writeAll(guardPath, tostring(count + 1))            -- 시작한다고 적어 둠 (15초 뒤 무사하면 0 으로)
    status:set { text = "" }
    retryButton:add_flag(lvgl.FLAG.HIDDEN)
    fetchButton:add_flag(lvgl.FLAG.HIDDEN)
    local ok, runError = pcall(chunk, lvgl, root, function() end, "face")
    if not ok then problem("워치페이스 실행 오류 X\n" .. tostring(runError)) return end
    busy, started = false, true
    __rw6FaceGuard = lvgl.Timer { period = 15000, repeat_count = 1, cb = function() __rw6FaceGuard = nil writeAll(guardPath, "0") end }
end
local function fetch()
    status:set { text = "워치페이스를 받는 중입니다\n(처음 한 번)" }
    __rw6FaceBoot = lvgl.Timer { period = 200, repeat_count = 1, cb = function()
        __rw6FaceBoot = nil
        local path = "/tmp/rw6-face-get.lua"
        os.remove(path)
        os.execute("mkdir -p /data/rw6face")
        os.execute("curl " .. sourceUrl .. " -o " .. path)
        local text = readAll(path)
        local good = text and #text > 1000 and string.find(text, "-- RW6FACE END", 1, true) and loadfile(path)
        os.remove(path)
        if not good then problem("받지 못했습니다 X\n폰의 Mi Manager 연결을 확인하고\n다시 눌러 주세요") return end
        if not writeAll(facePath, text) then problem("저장하지 못했습니다 X") return end
        writeAll(guardPath, "0")
        run()
    end }
end
retryButton:onevent(lvgl.EVENT.CLICKED, function()
    if busy or started then return end
    busy = true
    writeAll(guardPath, "0")
    if readAll(facePath) then run() else fetch() end
end)
fetchButton:onevent(lvgl.EVENT.CLICKED, function()
    if busy or started then return end
    busy = true
    os.remove(facePath)
    fetch()
end)

local crashes = tonumber(readAll(guardPath) or "") or 0
if crashes >= 2 then
    problem("안전 화면입니다\n워치페이스가 시작하다 두 번 멈췄습니다\n나가려면 화면을 길게 누르세요")
elseif readAll(facePath) then
    busy = true
    run()
else
    busy = true
    fetch()
end
