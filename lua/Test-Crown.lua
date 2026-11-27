-- 용두(크라운) 조사용 화면. 읽기만 한다: 워치의 어떤 파일도 고치지 않고 /tmp 에 기록 파일만 남긴다.
-- 패처(rw6-font.lua)가 목록에서 'Test-Crown'을 고르면 이 파일을 받아 chunk(lvgl, root, log) 로 실행한다.
-- 알아내려는 것: (1) 워치페이스가 용두 돌림·누름 신호를 받는지 (2) 입력 장치에 용두가 잡혀 있는지
--               (3) Lua에서 쓸 수 있는 기능 이름들 (4) 워치 명령 목록(앱을 여는 명령이 있는지)
local lvgl, root, log = ...
local W, H = lvgl.HOR_RES(), lvgl.VER_RES()
local font = lvgl.Font("MiSans-Medium", 18)
local recordPath = "/tmp/rw6-crown.txt"
local helpPath = "/tmp/rw6-help.txt"

local function safe(fn, ...)
    local ok, a, b = pcall(fn, ...)
    if ok then return a, b end
    return nil
end

local function readAll(path)
    local file = io.open(path, "rb")
    if not file then return nil end
    local value = file:read("*a")
    file:close()
    return value
end

local function append(path, text)
    local file = io.open(path, "a")
    if not file then return end
    file:write(text)
    file:close()
end

-- 표(table)의 이름들을 가나다순 한 줄 글로
local function keysOf(t)
    local names = {}
    if type(t) ~= "table" then return names end
    pcall(function()                              -- 나열이 막혀 있으면 빈 목록
        for k, _ in pairs(t) do names[#names + 1] = tostring(k) end
    end)
    table.sort(names)
    return names
end

-- 긴 글을 화면 폭에 맞게 줄 단위로 자름
local lines = {}
local function addLine(text)
    text = tostring(text)
    if #text == 0 then lines[#lines + 1] = "" return end
    local current = ""
    for word in string.gmatch(text, "%S+") do
        while #word > 34 do                       -- 한 낱말이 너무 길면 잘라서
            if #current > 0 then lines[#lines + 1] = current current = "" end
            lines[#lines + 1] = string.sub(word, 1, 34)
            word = string.sub(word, 35)
        end
        if #current == 0 then current = word
        elseif #current + 1 + #word <= 34 then current = current .. " " .. word
        else lines[#lines + 1] = current current = word end
    end
    if #current > 0 then lines[#lines + 1] = current end
end
local function addList(title, names, source)
    addLine("[" .. title .. "] " .. #names .. "개" .. (source ~= nil and (" (" .. type(source) .. ")") or ""))
    addLine(table.concat(names, " "))
    addLine("")
end

---------------------------------------------------------------- 지난 기록 (화면이 다시 떴을 때)
local previous = readAll(recordPath)

---------------------------------------------------------------- 정보 모으기 (모두 읽기만)
local eventNames = {}      -- 숫자 -> 이름
local eventTable = lvgl.EVENT
if type(eventTable) == "table" then
    pcall(function()
        for k, v in pairs(eventTable) do eventNames[v] = tostring(k) end
    end)
end
-- 표가 아니라 다른 형태(userdata)여도 이름으로 꺼내 본다. 없거나 오류면 nil
local function field(t, k)
    if t == nil then return nil end
    return safe(function() return t[k] end)
end
local function has(t, k) return field(t, k) ~= nil end

addLine("== 요약 ==")
addLine("lvgl.indev: " .. type(field(lvgl, "indev")) .. " / lvgl.group: " .. type(field(lvgl, "group")))
addLine("EVENT.KEY " .. tostring(has(eventTable, "KEY")) .. " GESTURE " .. tostring(has(eventTable, "GESTURE"))
    .. " ALL " .. tostring(has(eventTable, "ALL")) .. " ROTARY " .. tostring(has(eventTable, "ROTARY")))

-- 입력 장치 목록 (종류: 1 터치, 2 키패드, 3 버튼, 4 인코더=용두일 가능성)
local indev = field(lvgl, "indev")
local getNext, getAct = field(indev, "get_next"), field(indev, "get_act")
local indevText = "indev types:"
if indev ~= nil then
    local current = nil
    for _ = 1, 8 do
        local nextOne = getNext and safe(getNext, current) or nil
        if not nextOne then break end
        indevText = indevText .. " " .. tostring(safe(function() return nextOne:get_type() end))
        current = nextOne
    end
    local act = getAct and safe(getAct) or nil
    indevText = indevText .. " / act: " .. tostring(act and safe(function() return act:get_type() end))
else
    indevText = indevText .. " none"
end
addLine(indevText)
addLine("(1 터치, 2 키패드, 3 버튼, 4 인코더)")

-- 불러올 수 있는 기능 묶음
local candidates = { "topic", "activity", "dataman", "vibrator", "animengine", "screen", "navigator",
    "app", "system", "uorb", "router", "launcher", "watchface", "key", "input", "miwear", "vela",
    "json", "cjson", "socket", "lfs", "ffi", "bit", "uv" }
local loaded = {}
for _, name in ipairs(candidates) do
    local ok, mod = pcall(require, name)
    if ok and mod ~= nil then loaded[#loaded + 1] = name .. ":" .. type(mod) end
end
addList("require 되는 것", loaded)

-- 표준이 아닌 전역 이름
local standard = { _G = 1, _VERSION = 1, assert = 1, collectgarbage = 1, dofile = 1, error = 1, getmetatable = 1,
    ipairs = 1, load = 1, loadfile = 1, next = 1, pairs = 1, pcall = 1, print = 1, rawequal = 1, rawget = 1,
    rawlen = 1, rawset = 1, require = 1, select = 1, setmetatable = 1, tonumber = 1, tostring = 1, type = 1,
    xpcall = 1, coroutine = 1, debug = 1, io = 1, math = 1, os = 1, package = 1, string = 1, table = 1, utf8 = 1 }
local globals = {}
for _, name in ipairs(keysOf(_G)) do
    if not standard[name] then globals[#globals + 1] = name .. ":" .. type(_G[name]) end
end
addList("전역(표준 제외)", globals)
addList("package.loaded", keysOf(package.loaded))
addList("lvgl.indev", keysOf(indev), indev)
addList("lvgl.group", keysOf(field(lvgl, "group")), field(lvgl, "group"))
addList("lvgl", keysOf(lvgl), lvgl)
addList("lvgl.EVENT", keysOf(eventTable), eventTable)
addList("os", keysOf(os), os)

-- 워치 명령 목록 (앱을 여는 명령이 있는지 보려고)
os.remove(helpPath)
os.execute("help > " .. helpPath)
local help = readAll(helpPath) or ""
os.remove(helpPath)
help = string.gsub(help, "%s+", " ")
addLine("[명령 목록] " .. #help .. "자")
addLine(help)

---------------------------------------------------------------- 화면
local panel = lvgl.Object(root, {
    x = 0, y = 0, w = W, h = H,
    border_width = 0, pad_all = 0, radius = 0, bg_color = "#000000",
})
panel:clear_flag(lvgl.FLAG.SCROLLABLE)
panel:add_flag(lvgl.FLAG.CLICKABLE)

local title = lvgl.Label(panel, {
    x = 60, y = 34, w = W - 120, h = 26,
    text = "용두 조사", text_color = "#ffd166", text_font = font,
})
local body = lvgl.Label(panel, {
    x = 14, y = 64, w = W - 28, h = H - 64 - 96,
    text = "", text_color = "#e2e5eb", text_font = font,
})

-- 용두를 돌렸을 때 스크롤로 오는지 보려고 둔 긴 상자 (실시간 쪽에서만 보임)
local box = lvgl.Object(panel, {
    x = W - 74, y = 64, w = 60, h = 200,
    border_width = 1, border_color = "#454a55", radius = 8, pad_all = 4, bg_color = "#16181d",
})
box:add_flag(lvgl.FLAG.SCROLLABLE)
lvgl.Label(box, {
    x = 0, y = 0, w = 44, h = 900,
    text = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16\n17\n18\n19\n20\n21\n22\n23\n24\n25\n26\n27\n28\n29\n30",
    text_color = "#71b7ff", text_font = font,
})

local function button(x, w, text, color)
    local object = lvgl.Object(panel, {
        x = x, y = H - 50 - 44, w = w, h = 44, radius = 10,
        border_width = 1, border_color = color, bg_color = "#252830", pad_all = 0,
    })
    object:clear_flag(lvgl.FLAG.SCROLLABLE)
    object:add_flag(lvgl.FLAG.CLICKABLE)
    lvgl.Label(object, { text = text, align = lvgl.ALIGN.CENTER, text_color = color, text_font = font })
    return object
end
local previousButton = button(70, 140, "이전 쪽", "#71b7ff")
local nextButton = button(222, 140, "다음 쪽", "#78d99b")

---------------------------------------------------------------- 실시간: 어떤 신호가 몇 번 왔는지
local order, counts, extra = {}, {}, {}
local total = 0
local page = 0            -- 0 = 실시간, 1.. = 정보 쪽
local perPage = 14
local pageCount = (#lines + perPage - 1) // perPage

-- 화면 그리기 때문에 계속 오는 신호는 세지 않음
local function noisy(name)
    return string.find(name, "DRAW", 1, true) or string.find(name, "COVER", 1, true)
        or string.find(name, "REFR", 1, true) or string.find(name, "GET_SELF", 1, true)
        or string.find(name, "STYLE", 1, true) or string.find(name, "LAYOUT", 1, true)
        or string.find(name, "SIZE", 1, true) or string.find(name, "CHILD", 1, true)
        or string.find(name, "RENDER", 1, true) or string.find(name, "INVALIDATE", 1, true)
        or string.find(name, "HIT_TEST", 1, true)
end

local function show()
    if page == 0 then
        local out = { "용두를 위·아래로 돌리고", "눌러 보세요. 화면도 눌러요.", "신호 " .. total .. "번 [장치/키/방향]" }
        local shown, unknown = 0, 0
        for i = #order, 1, -1 do                   -- 이름을 아는 신호 먼저 (최근 것부터)
            local name = order[i]
            if not string.find(name, "#", 1, true) and shown < 8 then
                out[#out + 1] = name .. " x" .. counts[name] .. (extra[name] and (" " .. extra[name]) or "")
                shown = shown + 1
            end
        end
        for i = #order, 1, -1 do                   -- 이름 모르는 번호는 뒤에 3개까지만
            local name = order[i]
            if string.find(name, "#", 1, true) and unknown < 3 then
                out[#out + 1] = name .. " x" .. counts[name] .. (extra[name] and (" " .. extra[name]) or "")
                unknown = unknown + 1
            end
        end
        if previous and #previous > 0 then
            out[#out + 1] = "지난번:"
            local tail = string.sub(previous, -150)
            for i = 1, #tail, 30 do out[#out + 1] = string.sub(tail, i, i + 29) end
        end
        title:set { text = "용두 조사 · 실시간" }
        body:set { text = table.concat(out, "\n") }
        box:clear_flag(lvgl.FLAG.HIDDEN)
    else
        local out = {}
        for i = (page - 1) * perPage + 1, math.min(#lines, page * perPage) do out[#out + 1] = lines[i] end
        title:set { text = "정보 " .. page .. " / " .. pageCount }
        body:set { text = table.concat(out, "\n") }
        box:add_flag(lvgl.FLAG.HIDDEN)
    end
end

local function note(where, code)
    local name = eventNames[code] or ("#" .. tostring(code))
    if noisy(name) then return end
    name = where .. "." .. name
    total = total + 1
    if not counts[name] then
        counts[name] = 0
        order[#order + 1] = name
        append(recordPath, name .. " ")          -- 처음 본 신호 이름만 파일에 남김 (화면이 넘어가도 남도록)
    else
        for i = 1, #order do
            if order[i] == name then table.remove(order, i) break end
        end
        order[#order + 1] = name
    end
    counts[name] = counts[name] + 1
    -- 키·방향 값이 있으면 같이 적음
    local detail = nil
    if getAct then
        local act = safe(getAct)
        if act then
            local key = safe(function() return act:get_key() end)
            local dir = safe(function() return act:get_gesture_dir() end)
            local kind = safe(function() return act:get_type() end)
            detail = "[" .. tostring(kind) .. "/" .. tostring(key) .. "/" .. tostring(dir) .. "]"
        end
    end
    extra[name] = detail
    if page == 0 then show() end
end

local function watch(object, where)
    local all = field(eventTable, "ALL")
    if all ~= nil then
        local ok = pcall(function()
            object:onevent(all, function(_, code) note(where, code) end)
        end)
        if ok then return end
    end
    for _, name in ipairs({ "KEY", "GESTURE", "ROTARY", "SCROLL", "SCROLL_BEGIN", "SCROLL_END", "PRESSED",
        "RELEASED", "CLICKED", "LONG_PRESSED", "FOCUSED", "DEFOCUSED", "LEAVE", "VALUE_CHANGED" }) do
        local code = field(eventTable, name)
        if code ~= nil then
            eventNames[code] = eventNames[code] or name
            pcall(function() object:onevent(code, function() note(where, code) end) end)
        end
    end
end
watch(panel, "화면")
watch(box, "상자")
watch(root, "바탕")

previousButton:onevent(lvgl.EVENT.CLICKED, function()
    page = page - 1
    if page < 0 then page = pageCount end
    show()
end)
nextButton:onevent(lvgl.EVENT.CLICKED, function()
    page = page + 1
    if page > pageCount then page = 0 end
    show()
end)

append(recordPath, "| ")
show()
log("용두 조사 화면을 띄웠습니다.")
