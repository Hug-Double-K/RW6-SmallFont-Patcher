-- 조사 3(안전판): 용두를 돌릴 때 화면에 오는 신호가 무엇인지(아래/위 구분), 폰을 거쳐 RW6 알림을 여는 길이 되는지.
-- Test-Crown2 가 워치를 재시작시켜서(2026-11-27), 워치에서 이미 문제없이 돌았던 기능만 쓴다:
-- 화면 물체·신호 듣기(onevent)·타이머·파일 읽기·curl. topic / activity / dataman 은 부르지 않는다.
local lvgl, root, log = ...
local W, H = lvgl.HOR_RES(), lvgl.VER_RES()
local font = lvgl.Font("MiSans-Medium", 18)
local phone = "http://10.1.10.1"
local reportPath = "/tmp/rw6-crown3.txt"

local function safe(fn, ...)
    local ok, a, b = pcall(fn, ...)
    if ok then return a, b end
    return nil
end
local function readAll(path, limit)
    local file = io.open(path, "rb")
    if not file then return nil end
    local value = safe(function() return file:read(limit or "*a") end)
    file:close()
    return value
end
local function sortedKeys(t)
    local names = {}
    pcall(function() for k, _ in pairs(t) do names[#names + 1] = tostring(k) end end)
    table.sort(names)
    return names
end
-- 값을 한 줄 글로 (표는 한 단계만, 이상한 글자는 16진수로)
local function plain(value)
    local kind = type(value)
    if kind == "string" then
        if #value <= 48 and not string.find(value, "[^\32-\126]") then return '"' .. value .. '"' end
        local hex = string.gsub(string.sub(value, 1, 48), ".", function(c) return string.format("%02x", string.byte(c)) end)
        return "bin" .. #value .. ":" .. hex
    elseif kind == "number" or kind == "boolean" or kind == "nil" then
        return tostring(value)
    end
    return kind
end
local function describe(value)
    if type(value) ~= "table" then return plain(value) end
    local parts = {}
    for _, key in ipairs(sortedKeys(value)) do
        if #parts >= 24 then parts[#parts + 1] = "…" break end
        local item = safe(function() return value[key] end)
        if item == nil then item = safe(function() return value[tonumber(key)] end) end
        parts[#parts + 1] = key .. "=" .. plain(item)
    end
    return "{" .. table.concat(parts, ",") .. "}"
end
local function describeArgs(...)
    local out = {}
    for i = 1, select("#", ...) do out[#out + 1] = describe((select(i, ...))) end
    return table.concat(out, " ; ")
end

---------------------------------------------------------------- 단계
local phases = {
    { key = "가만1", text = "손을 떼고\n가만히 두세요", secs = 8 },
    { key = "아래", text = "용두를 아래로만\n천천히 계속 돌리세요", secs = 8 },
    { key = "가만2", text = "멈추세요", secs = 4 },
    { key = "위", text = "용두를 위로만\n천천히 계속 돌리세요", secs = 8 },
    { key = "가만3", text = "멈추세요", secs = 4 },
}
local phaseIndex, left = 0, 3           -- 0 = 시작 전 3초
local current = "준비"
local eventCount, eventOrder = {}, {}
local function bump(store, phase, name)
    store[phase] = store[phase] or {}
    store[phase][name] = (store[phase][name] or 0) + 1
end

---------------------------------------------------------------- 신호 듣기 (화면 물체에 오는 것)
local eventNames = {}
pcall(function() for k, v in pairs(lvgl.EVENT) do eventNames[v] = tostring(k) end end)
local function noisy(name)
    for _, word in ipairs({ "DRAW", "COVER", "REFR", "GET_SELF", "STYLE", "LAYOUT", "SIZE", "CHILD", "HIT_TEST", "#26", "#27" }) do
        if string.find(name, word, 1, true) then return true end
    end
    return false
end
local autoName, autoOn, lastLaunch = nil, false, 0
local launch                              -- 아래에서 정의
local function onEvent(code)
    if type(code) ~= "number" then return end
    local name = eventNames[code] or ("#" .. code)
    if noisy(name) then return end
    bump(eventCount, current, name)
    local order = eventOrder[current] or {}
    eventOrder[current] = order
    if #order < 60 then order[#order + 1] = name end
    if autoOn and autoName == name and launch then launch("자동") end
end
local all = safe(function() return lvgl.EVENT.ALL end)
if all ~= nil then pcall(function() root:onevent(all, function(_, code) onEvent(code) end) end) end

---------------------------------------------------------------- 화면
local panel = lvgl.Object(root, { x = 0, y = 0, w = W, h = H, border_width = 0, pad_all = 0, radius = 0, bg_color = "#000000" })
panel:clear_flag(lvgl.FLAG.SCROLLABLE)
local title = lvgl.Label(panel, { x = 60, y = 34, w = W - 120, h = 26, text = "조사 3", text_color = "#ffd166", text_font = font })
local big = lvgl.Label(panel, { x = 24, y = 90, w = W - 48, h = 120, text = "3초 뒤 시작합니다", text_color = "#78d99b",
    text_font = font })       -- 글자 크기는 워치에서 확인된 18만 씀
local body = lvgl.Label(panel, { x = 14, y = 220, w = W - 28, h = H - 220 - 100, text = "", text_color = "#e2e5eb", text_font = font })
local function button(x, w, text, color)
    local object = lvgl.Object(panel, { x = x, y = H - 50 - 44, w = w, h = 44, radius = 10, border_width = 1, border_color = color,
        bg_color = "#252830", pad_all = 0 })
    object:clear_flag(lvgl.FLAG.SCROLLABLE)
    object:add_flag(lvgl.FLAG.CLICKABLE)
    object:add_flag(lvgl.FLAG.HIDDEN)
    local label = lvgl.Label(object, { text = text, align = lvgl.ALIGN.CENTER, text_color = color, text_font = font })
    return object, label
end
local openButton = button(70, 140, "알림 열기", "#78d99b")
local autoButton, autoLabel = button(222, 140, "자동 끔", "#71b7ff")
local status = {}
local function setStatus(index, text)
    status[index] = text
    local out = {}
    for i = 1, 6 do if status[i] then out[#out + 1] = status[i] end end
    body:set { text = table.concat(out, "\n") }
end

---------------------------------------------------------------- 폰으로 올리기 / 폰에 시키기
local function curl(arguments)
    local resp = "/tmp/rw6-crown3-resp.txt"
    os.remove(resp)
    safe(os.execute, "curl " .. arguments .. " -o " .. resp)
    local got = readAll(resp, 200) or ""
    os.remove(resp)
    return got
end
local function uploadText(name, text)
    local file = io.open(reportPath, "wb")
    if not file then return false end
    file:write(text)
    file:close()
    return tonumber(string.match(curl("-T " .. reportPath .. " " .. phone .. "/r/" .. name), "^ok (%d+)")) == #text
end
launch = function(why)
    local now = safe(os.time) or 0
    if now - lastLaunch < 4 then return end        -- 같은 돌림에 여러 번 가지 않게
    lastLaunch = now
    local before = safe(os.clock) or 0
    local got = curl(phone .. "/do/launch-noti")
    local took = (safe(os.time) or 0) - now
    setStatus(5, "[" .. why .. "] 폰 답: " .. (#got > 0 and string.gsub(got, "%s+$", "") or "없음") .. " (" .. took .. "초, cpu "
        .. string.format("%.2f", (safe(os.clock) or 0) - before) .. ")")
end

---------------------------------------------------------------- 결과 글 만들기
local function countsLine(store, phase)
    local out = {}
    for _, name in ipairs(sortedKeys(store[phase] or {})) do out[#out + 1] = name .. "×" .. store[phase][name] end
    return #out > 0 and table.concat(out, " ") or "없음"
end
local function buildReport()
    local lines = { "== RW6 조사 3 안전판 (" .. tostring(safe(os.date, "%m-%d %H:%M:%S")) .. ") ==", "" }
    lines[#lines + 1] = "[화면 신호: 단계별 횟수]"
    for _, phase in ipairs({ "준비", "가만1", "아래", "가만2", "위", "가만3" }) do
        lines[#lines + 1] = phase .. ": " .. countsLine(eventCount, phase)
    end
    lines[#lines + 1] = ""
    lines[#lines + 1] = "[화면 신호: 온 순서]"
    for _, phase in ipairs({ "가만1", "아래", "가만2", "위", "가만3" }) do
        lines[#lines + 1] = phase .. ": " .. table.concat(eventOrder[phase] or {}, " ")
    end
    return table.concat(lines, "\n") .. "\n"
end
-- '아래' 단계에만 온 신호 찾기 (가만히·위에서는 안 온 것)
local function onlyIn(phase, others)
    local found = {}
    for _, name in ipairs(sortedKeys(eventCount[phase] or {})) do
        local elsewhere = false
        for _, other in ipairs(others) do
            if eventCount[other] and eventCount[other][name] then elsewhere = true end
        end
        if not elsewhere then found[#found + 1] = name end
    end
    return found
end

---------------------------------------------------------------- 끝난 뒤
local function finish()
    current = "끝"
    title:set { text = "조사 3 · 끝" }
    big:set { text = "결과를 폰으로\n올리는 중…" }
    __rw6Timer = lvgl.Timer { period = 300, repeat_count = 1, cb = function()
        __rw6Timer = nil
        local okUpload = safe(uploadText, "crown3.txt", buildReport())
        local sent = 0
        for _, id in ipairs({ "610140741", "610140737", "610241925" }) do      -- 깔려 있는 다른 Lua 워치페이스(쓰임새 참고용)
            local path = "/data/app/watchface/market/" .. id .. "/lua/main.lua"
            local size = readAll(path, 200000)
            if size and #size > 0 and string.match(curl("-T " .. path .. " " .. phone .. "/r/face-" .. id .. ".lua"), "^ok") then sent = sent + 1 end
        end
        local down = onlyIn("아래", { "가만1", "가만2", "가만3", "위", "준비" })
        local up = onlyIn("위", { "가만1", "가만2", "가만3", "아래", "준비" })
        autoName = down[1]
        big:set { text = okUpload and "폰으로 올렸습니다 ✓" or "폰으로 못 올렸습니다" }
        setStatus(1, "Mi Manager 기록의 '복사'를 눌러 보내 주세요" .. (sent > 0 and (" (참고 파일 " .. sent .. "개 포함)") or ""))
        setStatus(2, "아래 전용 신호: " .. (#down > 0 and table.concat(down, " ") or "없음") .. " / 위 전용: "
            .. (#up > 0 and table.concat(up, " ") or "없음"))
        setStatus(3, "① '알림 열기'를 눌러 RW6 알림이 뜨는지" .. (autoName and "\n② '자동'을 켜고 용두를 아래로" or ""))
        openButton:clear_flag(lvgl.FLAG.HIDDEN)
        if autoName then autoButton:clear_flag(lvgl.FLAG.HIDDEN) end
    end }
end
openButton:onevent(lvgl.EVENT.CLICKED, function() lastLaunch = 0 launch("버튼") end)
autoButton:onevent(lvgl.EVENT.CLICKED, function()
    autoOn = not autoOn
    autoLabel:set { text = autoOn and "자동 켬" or "자동 끔" }
    setStatus(4, autoOn and ("자동: " .. tostring(autoName) .. " 이 오면 엽니다") or "자동 끔")
end)

---------------------------------------------------------------- 1초마다
local function tick()
    left = left - 1
    if left <= 0 then
        phaseIndex = phaseIndex + 1
        local phase = phases[phaseIndex]
        if not phase then finish() return end
        current = phase.key
        left = phase.secs
        big:set { text = phase.text }
        title:set { text = "조사 3 · " .. phaseIndex .. "/" .. #phases }
    end
    setStatus(1, "남은 시간 " .. left .. "초")
    __rw6Timer = lvgl.Timer { period = 1000, repeat_count = 1, cb = function() __rw6Timer = nil tick() end }
end
setStatus(1, "남은 시간 3초")
__rw6Timer = lvgl.Timer { period = 1000, repeat_count = 1, cb = function() __rw6Timer = nil tick() end }
log("조사 3 화면을 띄웠습니다.")
