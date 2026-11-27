-- 아래 버튼이 여는 앱 바꾸기 (워치 속성 persist.downkey.pkgname 하나만 바꾼다. 같은 화면에서 되돌릴 수 있다).
-- 쓰는 기능: 화면 물체·버튼·타이머·파일 읽기/쓰기·getprop/setprop·curl (getprop·curl 은 워치에서 이미 돌려 본 것, setprop 은 처음).
-- 패처(rw6-font.lua)가 목록에서 'Test-DownKey'를 고르면 chunk(lvgl, root, log) 로 실행한다.
local lvgl, root, log = ...
local W, H = lvgl.HOR_RES(), lvgl.VER_RES()
local font = lvgl.Font("MiSans-Medium", 18)
local KEY = "persist.downkey.pkgname"
local OURS = "com.tencent.wechatrtos"            -- RW6 알림(위챗 자리 판)
local DEFAULT = "com.xiaomi.miwear.sports"       -- 조사 1 때 읽힌 원래 값(운동)
local savedPath = "/data/rw6-downkey-original.txt"
local tmp = "/tmp/rw6-downkey.txt"
local phone = "http://10.1.10.1"

local function safe(fn, ...)
    local ok, a = pcall(fn, ...)
    if ok then return a end
    return nil
end
local function readAll(path)
    local file = io.open(path, "rb")
    if not file then return nil end
    local value = safe(function() return file:read("*a") end)
    file:close()
    return value
end
-- 지금 값 읽기: 'getprop 이름' 의 결과에서 마지막 낱말 (값만 나오든 '이름: 값' 으로 나오든)
local function current()
    os.remove(tmp)
    safe(os.execute, "getprop " .. KEY .. " > " .. tmp)
    local text = readAll(tmp) or ""
    os.remove(tmp)
    local value = nil
    for word in string.gmatch(text, "[%w_%.%-]+") do value = word end
    if value == KEY then value = nil end
    return value, (string.gsub(text, "%s+", " "))
end
local function validName(name)
    return type(name) == "string" and #name >= 3 and #name <= 80 and string.match(name, "^[%w_%.%-]+$") ~= nil
end

local notes = {}
local function note(text) notes[#notes + 1] = tostring(safe(os.date, "%H:%M:%S")) .. " " .. text end

-- 처음 열었을 때의 값을 한 번만 적어 둠 (되돌릴 때 씀)
local startValue, startRaw = current()
note("처음 읽은 값: " .. tostring(startValue) .. " (원문 '" .. tostring(startRaw) .. "')")
local original = readAll(savedPath)
if original then original = string.match(original, "[%w_%.%-]+") end
if not validName(original) then
    if validName(startValue) and startValue ~= OURS then
        local file = io.open(savedPath, "wb")
        if file then file:write(startValue) file:close() end
        original = startValue
        note("원래 값을 적어 둠: " .. original)
    else
        original = DEFAULT
        note("원래 값을 못 읽어 기본값을 씀: " .. original)
    end
else
    note("적어 둔 원래 값: " .. original)
end

---------------------------------------------------------------- 화면
local panel = lvgl.Object(root, { x = 0, y = 0, w = W, h = H, border_width = 0, pad_all = 0, radius = 0, bg_color = "#000000" })
panel:clear_flag(lvgl.FLAG.SCROLLABLE)
lvgl.Label(panel, { x = 60, y = 34, w = W - 120, h = 26, text = "아래 버튼 바꾸기", text_color = "#ffd166", text_font = font })
local body = lvgl.Label(panel, { x = 14, y = 70, w = W - 28, h = 250, text = "", text_color = "#e2e5eb", text_font = font })
local function button(y, text, color)
    local object = lvgl.Object(panel, { x = 40, y = y, w = W - 80, h = 48, radius = 10, border_width = 1, border_color = color,
        bg_color = "#252830", pad_all = 0 })
    object:clear_flag(lvgl.FLAG.SCROLLABLE)
    object:add_flag(lvgl.FLAG.CLICKABLE)
    lvgl.Label(object, { text = text, align = lvgl.ALIGN.CENTER, text_color = color, text_font = font })
    return object
end
local oursButton = button(H - 50 - 48 - 58, "RW6 알림으로 바꾸기", "#78d99b")
local backButton = button(H - 50 - 48, "원래대로 되돌리기", "#71b7ff")

local lastResult = ""
local function show()
    local value = current()
    local what = value == OURS and "RW6 알림" or (value == original and "원래 앱" or "?")
    body:set { text = table.concat({
        "지금 값 (" .. what .. "):",
        tostring(value),
        "",
        "원래 값:",
        tostring(original),
        "",
        lastResult,
    }, "\n") }
    return value
end

local busy = false
local function change(target, label)
    if busy then return end
    busy = true
    lastResult = label .. " 바꾸는 중…"
    show()
    __rw6Timer = lvgl.Timer { period = 200, repeat_count = 1, cb = function()
        __rw6Timer = nil
        local before = current()
        local ok, a, b, c = pcall(os.execute, "setprop " .. KEY .. " " .. target)
        local after = current()
        local good = after == target
        note(label .. ": " .. tostring(before) .. " → " .. tostring(after) .. " (명령 결과 " .. tostring(ok) .. "/" .. tostring(a) .. "/"
            .. tostring(b) .. "/" .. tostring(c) .. ")" .. (good and " 성공" or " 안 바뀜"))
        lastResult = good and (label .. " ✓ 시계 화면에서\n아래 버튼을 눌러 보세요") or (label .. " ✗ 값이 안 바뀜")
        show()
        -- 결과를 폰으로 (안 올라가도 상관없음)
        local file = io.open(tmp .. ".report", "wb")
        if file then
            file:write("== 아래 버튼 바꾸기 ==\n" .. table.concat(notes, "\n") .. "\n")
            file:close()
            safe(os.execute, "curl -T " .. tmp .. ".report " .. phone .. "/r/downkey.txt -o " .. tmp .. ".resp")
            os.remove(tmp .. ".report")
            os.remove(tmp .. ".resp")
        end
        busy = false
    end }
end
oursButton:onevent(lvgl.EVENT.CLICKED, function() change(OURS, "RW6 알림으로") end)
backButton:onevent(lvgl.EVENT.CLICKED, function() change(original, "원래대로") end)
show()
log("아래 버튼 바꾸기 화면을 띄웠습니다.")
