-- 조사 2: Lua에서 쓸 수 있는 기능 알아내기. 읽기만 한다(워치의 어떤 기능도 실행하지 않고 이름만 나열,
-- 워치 안의 Lua 파일을 읽어 쓰임새만 뽑음). 결과는 폰(Mi Manager 0.81+)으로 올리고, 안 되면 화면 쪽으로 본다.
-- 패처(rw6-font.lua)가 목록에서 'Test-Api'를 고르면 chunk(lvgl, root, log) 로 실행한다.
local lvgl, root, log = ...
local W, H = lvgl.HOR_RES(), lvgl.VER_RES()
local font = lvgl.Font("MiSans-Medium", 18)
local tmp = "/tmp/rw6-api-tmp.txt"
local reportPath = "/tmp/rw6-api.txt"
local phone = "http://10.1.10.1"

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
local function sh(command, limit)
    os.remove(tmp)
    safe(os.execute, command .. " > " .. tmp)
    local text = readAll(tmp, limit) or ""
    os.remove(tmp)
    return text
end
local function sortedKeys(t)
    local names = {}
    if type(t) ~= "table" then return names end
    pcall(function() for k, _ in pairs(t) do names[#names + 1] = tostring(k) end end)
    table.sort(names)
    return names
end

---------------------------------------------------------------- 결과 담기
local report = {}          -- 폰으로 올릴 전체 글 (줄 단위)
local lines = {}           -- 화면용 (34자 폭으로 자른 것)
local function wrap(text)
    if #text == 0 then lines[#lines + 1] = "" return end
    local current = ""
    for word in string.gmatch(text, "%S+") do
        while #word > 34 do
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
-- both: 화면에도 보일 것 / only: 폰으로만 (길어서 화면엔 안 넣음)
local function both(text) text = tostring(text) report[#report + 1] = text wrap(text) end
local function only(text) report[#report + 1] = tostring(text) end

---------------------------------------------------------------- 1. 기능 묶음 안의 이름들
local function describe(name, value)
    local kind = type(value)
    if kind == "function" then
        local info = safe(debug.getinfo, value, "S")
        if info and info.what == "Lua" then return name .. ":f(" .. tostring(info.short_src) .. ":" .. tostring(info.linedefined) .. ")" end
        return name .. ":f"
    elseif kind == "table" then
        return name .. ":{" .. table.concat(sortedKeys(value), ",") .. "}"
    elseif kind == "string" or kind == "number" or kind == "boolean" then
        return name .. "=" .. string.sub(tostring(value), 1, 40)
    end
    return name .. ":" .. kind
end
local function listModule(title, t)
    if t == nil then both("[" .. title .. "] 없음") return end
    local out = {}
    for _, key in ipairs(sortedKeys(t)) do
        out[#out + 1] = describe(key, safe(function() return t[key] end))
    end
    both("[" .. title .. "] " .. type(t) .. " " .. #out .. "개")
    both(table.concat(out, " "))
    local meta = safe(getmetatable, t)
    if type(meta) == "table" then
        local index = rawget(meta, "__index")
        both("  meta: " .. table.concat(sortedKeys(meta), ",")
            .. (type(index) == "table" and (" / __index: " .. table.concat(sortedKeys(index), ",")) or ""))
    end
    both("")
end

both("== RW6 조사 2 (" .. tostring(safe(os.date, "%m-%d %H:%M:%S")) .. ") ==")
both("SCRIPT_PATH=" .. tostring(rawget(_G, "SCRIPT_PATH")) .. " apps_available=" .. tostring(rawget(_G, "miwear_apps_available"))
    .. " " .. tostring(_VERSION))
both("")
local modules = { "navigator", "activity", "topic", "dataman", "vibrator", "screen", "animengine" }
for _, name in ipairs(modules) do
    local ok, mod = pcall(require, name)
    listModule(name, ok and mod or nil)
end
listModule("lvgl.KEY", safe(function() return lvgl.KEY end))
listModule("lvgl.disp", safe(function() return lvgl.disp end))
listModule("lvgl.fs", safe(function() return lvgl.fs end))
listModule("lvgl.DIR", safe(function() return lvgl.DIR end))
do  -- 화면 물체가 가진 기능 이름
    local meta = safe(getmetatable, root)
    local index = type(meta) == "table" and rawget(meta, "__index") or nil
    both("[물체 기능] " .. (type(index) == "table" and table.concat(sortedKeys(index), " ") or ("알 수 없음(" .. type(index) .. ")")))
    both("")
end

---------------------------------------------------------------- 2. 워치 안의 Lua 파일에서 쓰임새 뽑기
local luaFiles, seenFile = {}, {}
local function listTree(top)
    local raw = sh("ls -R " .. top, 300000)
    local _, headers = string.gsub(raw, "\n/[^\n]*:", "")
    if headers == 0 and string.find(raw, "/%s") then      -- -R 이 안 통하면 폴더를 하나씩 직접 따라감
        local queue, at, parts = { top }, 1, {}
        while at <= #queue and at <= 150 do
            local dir = queue[at]
            at = at + 1
            local one = sh("ls " .. dir, 60000)
            parts[#parts + 1] = dir .. ":\n" .. string.gsub(one, "^/[^\n]*:%s*\n?", "")
            for name in string.gmatch(one, "([^%s/]+)/%s") do queue[#queue + 1] = dir .. "/" .. name end
        end
        raw = table.concat(parts, "\n")
    end
    local dirs, files, exts, topNames, current = 0, 0, {}, {}, top
    for line in string.gmatch(raw, "[^\r\n]+") do
        local header = string.match(line, "^(/.*):%s*$")
        if header then
            current = string.gsub(header, "/$", "")
            dirs = dirs + 1
        else
            for name in string.gmatch(line, "%S+") do
                if current == top and #topNames < 40 then topNames[#topNames + 1] = name end
                if string.sub(name, -1) ~= "/" then
                    files = files + 1
                    local ext = string.match(name, "%.([%w_]+)$") or "-"
                    exts[ext] = (exts[ext] or 0) + 1
                    if (ext == "lua" or ext == "luac") and #luaFiles < 120 then
                        local path = current .. "/" .. name
                        if not seenFile[path] then seenFile[path] = true luaFiles[#luaFiles + 1] = path end
                    end
                end
            end
        end
    end
    local extText = {}
    for _, ext in ipairs(sortedKeys(exts)) do extText[#extText + 1] = ext .. "×" .. exts[ext] end
    both("[" .. top .. "] 폴더 " .. dirs .. " 파일 " .. files .. " (" .. table.concat(extText, " ") .. ")")
    both(table.concat(topNames, " "))
    both("")
    if #raw < 6000 then only(raw) end
end
local scriptDir = type(rawget(_G, "SCRIPT_PATH")) == "string" and string.match(SCRIPT_PATH, "^(.*)/[^/]*$") or nil
for _, top in ipairs({ "/resource/app", "/resource/watchface", "/resource/system", "/data/app", "/data/quickapp" }) do
    safe(listTree, top)
end
if scriptDir and #scriptDir > 1 then both("[내 위치] " .. scriptDir .. ": " .. string.gsub(sh("ls " .. scriptDir, 2000), "%s+", " ")) end

local found = {}           -- 종류 -> { 순서 목록, 있는지 표 }
local function add(kind, text, cap)
    local set = found[kind]
    if not set then set = { list = {}, has = {} } found[kind] = set end
    if set.has[text] or #set.list >= (cap or 40) then return end
    set.has[text] = true
    set.list[#set.list + 1] = text
end
local words = { "navigator", "activity", "topic", "vibrator", "screen", "animengine" }
local excerptCount, textCount, byteCount, readCount = 0, 0, 0, 0
local function scanText(path, src)
    textCount = textCount + 1
    local squeezed = string.gsub(src, "%s+", " ")
    for _, word in ipairs(words) do
        for call in string.gmatch(squeezed, "%f[%w_]" .. word .. "[%.:][%w_]+%s*%b()") do add(word, string.sub(call, 1, 90), 30) end
        for member in string.gmatch(squeezed, "%f[%w_]" .. word .. "[%.:]([%w_]+)") do add(word .. " 이름", member, 60) end
    end
    for fn, key in string.gmatch(squeezed, "dataman[%.:]([%w_]+)%s*%(%s*[\"']([^\"']+)") do add("dataman " .. fn, key, 200) end
    for fn, key in string.gmatch(squeezed, "topic[%.:]([%w_]+)%s*%(%s*[\"']([^\"']+)") do add("topic " .. fn, key, 80) end
    for name in string.gmatch(src, "\nfunction%s+([%w_%.:]+)%s*%(") do add("전역 함수", name, 80) end
    for name in string.gmatch(squeezed, "EVENT%.([%w_]+)") do add("EVENT", name, 60) end
    for name in string.gmatch(squeezed, "require%s*%(?%s*[\"']([^\"']+)") do add("require", name, 60) end
    local interesting = 0
    for line in string.gmatch(src, "[^\r\n]+") do
        local low = string.lower(line)
        if string.find(low, "crown", 1, true) or string.find(low, "rotary", 1, true) or string.find(low, "encoder", 1, true)
            or string.find(low, "knob", 1, true) or string.find(line, "KEY", 1, true) then
            add("용두·키 줄", string.sub((string.gsub(line, "^%s+", "")), 1, 110), 25)
        end
        if excerptCount < 6 and interesting < 40 and (string.find(line, "navigator", 1, true) or string.find(line, "activity", 1, true)
            or string.find(line, "topic", 1, true) or string.find(line, "screen%.") or string.find(line, "vibrator", 1, true)) then
            if interesting == 0 then only("--- " .. path .. " (" .. #src .. "바이트) ---") end
            interesting = interesting + 1
            only(string.sub(line, 1, 160))
        end
    end
    if interesting > 0 then excerptCount = excerptCount + 1 end
end
local function scanBytes(path, src)
    byteCount = byteCount + 1
    local tokens = {}
    for token in string.gmatch(src, "[%w_][%w_%.%/:%-]+") do
        if #token >= 3 and #tokens < 60000 then tokens[#tokens + 1] = token end
    end
    for i, token in ipairs(tokens) do
        for _, word in ipairs(words) do
            if token == word then add(word .. " 뒤(바이트코드)", (tokens[i + 1] or "") .. " " .. (tokens[i + 2] or ""), 40) end
        end
        if token == "subscribe" or token == "dataman" then add("dataman 뒤(바이트코드)", (tokens[i + 1] or "") .. " " .. (tokens[i + 2] or ""), 120) end
    end
end
local firstMain = nil
for _, path in ipairs(luaFiles) do
    if readCount >= 80 then break end
    local mine = scriptDir and string.sub(path, 1, #scriptDir) == scriptDir      -- 우리 패처 자신은 건너뜀
    local src = (not mine) and readAll(path, 400000) or nil
    if src and #src > 0 then
        readCount = readCount + 1
        if string.sub(src, 1, 4) == "\27Lua" then safe(scanBytes, path, src)
        else
            safe(scanText, path, src)
            if not firstMain and string.find(path, "main%.lua$") and not string.find(path, "rw6", 1, true)
                and not (scriptDir and string.sub(path, 1, #scriptDir) == scriptDir) then
                firstMain = path
                only("=== 처음 " .. path .. " 앞부분 ===")
                only(string.sub(src, 1, 3000))
            end
        end
    end
    src = nil
    collectgarbage()
end
both("[Lua 파일] " .. #luaFiles .. "개 (글 " .. textCount .. ", 바이트코드 " .. byteCount .. ")")
only(table.concat(luaFiles, "\n"))
both("")
local order = { "navigator", "navigator 이름", "navigator 뒤(바이트코드)", "activity", "activity 이름", "activity 뒤(바이트코드)",
    "topic", "topic 이름", "topic 뒤(바이트코드)", "용두·키 줄", "screen", "screen 이름", "vibrator", "vibrator 이름", "전역 함수", "EVENT", "require" }
local printed = {}
local function printKind(kind)
    local set = found[kind]
    printed[kind] = true
    if not set then return end
    both("[" .. kind .. "] " .. #set.list)
    both(table.concat(set.list, " | "))
    both("")
end
for _, kind in ipairs(order) do printKind(kind) end
for _, kind in ipairs(sortedKeys(found)) do if not printed[kind] then printKind(kind) end end

---------------------------------------------------------------- 화면
local panel = lvgl.Object(root, { x = 0, y = 0, w = W, h = H, border_width = 0, pad_all = 0, radius = 0, bg_color = "#000000" })
panel:clear_flag(lvgl.FLAG.SCROLLABLE)
panel:add_flag(lvgl.FLAG.CLICKABLE)
local title = lvgl.Label(panel, { x = 60, y = 34, w = W - 120, h = 26, text = "조사 2", text_color = "#ffd166", text_font = font })
local body = lvgl.Label(panel, { x = 14, y = 64, w = W - 28, h = H - 64 - 96, text = "", text_color = "#e2e5eb", text_font = font })
local function button(x, w, text, color)
    local object = lvgl.Object(panel, { x = x, y = H - 50 - 44, w = w, h = 44, radius = 10, border_width = 1, border_color = color,
        bg_color = "#252830", pad_all = 0 })
    object:clear_flag(lvgl.FLAG.SCROLLABLE)
    object:add_flag(lvgl.FLAG.CLICKABLE)
    lvgl.Label(object, { text = text, align = lvgl.ALIGN.CENTER, text_color = color, text_font = font })
    return object
end
local previousButton = button(70, 140, "이전 쪽", "#71b7ff")
local nextButton = button(222, 140, "다음 쪽", "#78d99b")

-- 첫 쪽: 폰으로 올린 결과 + 신호 145가 언제 오는지
local started = safe(os.time) or 0
local count145, last145, gaps, argInfo = 0, nil, {}, ""
local otherCounts = {}
local uploadText = "폰으로 올리는 중…"
local page, perPage = 0, 14
local pageCount = (#lines + perPage - 1) // perPage
local function show()
    if page == 0 then
        local others = {}
        for _, code in ipairs(sortedKeys(otherCounts)) do others[#others + 1] = "#" .. code .. "×" .. otherCounts[code] end
        title:set { text = "조사 2 · 첫 쪽" }
        body:set { text = table.concat({
            uploadText, "",
            "신호 145: " .. count145 .. "번",
            "켠 지 " .. ((safe(os.time) or 0) - started) .. "초 (누르면 새로 고침)",
            "간격(초): " .. table.concat(gaps, " "),
            argInfo,
            "그 밖의 모르는 신호: " .. table.concat(others, " "),
            "", "① 10초쯤 가만히 두고", "② 용두를 아래로 3칸, 위로 3칸", "③ 이 화면을 찍어 주세요",
        }, "\n") }
    else
        local out = {}
        for i = (page - 1) * perPage + 1, math.min(#lines, page * perPage) do out[#out + 1] = lines[i] end
        title:set { text = "정보 " .. page .. " / " .. pageCount }
        body:set { text = table.concat(out, "\n") }
    end
end
local known = {}
pcall(function() for _, v in pairs(lvgl.EVENT) do known[v] = true end end)
local all = safe(function() return lvgl.EVENT.ALL end)
if all ~= nil then
    pcall(function()
        root:onevent(all, function(...)
            local n = select("#", ...)
            local code = select(2, ...)
            if type(code) ~= "number" or known[code] then return end
            if code == 145 then
                local now = safe(os.time) or 0
                count145 = count145 + 1
                if last145 then
                    gaps[#gaps + 1] = tostring(now - last145)
                    if #gaps > 14 then table.remove(gaps, 1) end
                end
                last145 = now
                local kinds = {}
                for i = 1, n do
                    local value = select(i, ...)
                    kinds[#kinds + 1] = type(value) .. ((type(value) == "number" or type(value) == "string") and ("=" .. tostring(value)) or "")
                end
                argInfo = "인자 " .. n .. "개: " .. table.concat(kinds, ", ")
            else
                otherCounts[tostring(code)] = (otherCounts[tostring(code)] or 0) + 1
            end
            if page == 0 then show() end
        end)
    end)
end
panel:onevent(lvgl.EVENT.CLICKED, function() if page == 0 then show() end end)
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
show()

---------------------------------------------------------------- 폰으로 올리기 (화면을 먼저 보여 준 뒤)
local function hex(text)
    return (string.gsub(text, ".", function(c) return string.format("%02x", string.byte(c)) end))
end
local function upload()
    local text = table.concat(report, "\n") .. "\n"
    local file = io.open(reportPath, "wb")
    if file then file:write(text) file:close() end
    local size = #text
    local function answer(command)
        local resp = "/tmp/rw6-api-resp.txt"
        os.remove(resp)
        safe(os.execute, command .. " -o " .. resp)
        local got = readAll(resp, 200) or ""
        os.remove(resp)
        return tonumber(string.match(got, "^ok (%d+)"))
    end
    if answer("curl " .. phone .. "/ping") == nil then
        return "폰으로 못 올림(Mi Manager 0.81 연결 아님). 쪽을 넘겨 찍어 주세요 (" .. pageCount .. "쪽)"
    end
    if answer("curl -T " .. reportPath .. " " .. phone .. "/r/api.txt") == size then return "폰으로 올림 ✓ (" .. size .. "바이트, 방법 1)" end
    if answer("curl --data-binary @" .. reportPath .. " " .. phone .. "/r/api.txt") == size then
        return "폰으로 올림 ✓ (" .. size .. "바이트, 방법 2)"
    end
    local step, index, sent = 150, 0, 0
    local limit = math.min(size, step * 260)
    while sent < limit do
        local piece = string.sub(text, sent + 1, math.min(sent + step, limit))
        local last = sent + #piece >= limit
        local total = answer("curl " .. phone .. "/g/api.txt/" .. index .. "/" .. hex(piece) .. (last and "/end" or ""))
        if total ~= sent + #piece then return "폰으로 올리다 멈춤(" .. sent .. "/" .. size .. "). 쪽을 넘겨 찍어 주세요" end
        sent = sent + #piece
        index = index + 1
    end
    return "폰으로 올림 ✓ (" .. sent .. "/" .. size .. "바이트, 방법 3)"
end
__rw6ApiTimer = lvgl.Timer {
    period = 400, repeat_count = 1,
    cb = function()
        __rw6ApiTimer = nil
        local ok, result = pcall(upload)
        uploadText = ok and result or ("올리기 오류: " .. tostring(result))
        if page == 0 then show() end
    end,
}
log("조사 2 화면을 띄웠습니다.")
