-- DK_07(물결)을 Lua 워치페이스로 옮기는 첫 시험판. 패처 목록의 'Test-Face'로 실행(설치 없음).
-- 하는 일: 그림 묶음을 한 번 받아 풀고, 시간·날짜를 그리고, 용두를 돌리면(어느 쪽이든) 폰을 거쳐 RW6 알림을 연다.
-- 워치에서 이미 돈 기능만 씀: 화면 물체·그림(Image, 깔려 있는 Lua 워치페이스와 같은 꼴)·신호 듣기·타이머·파일·curl.
-- 아직 워치에서 안 돌려 본 것은 사용자가 버튼을 누를 때만, 한 번에 하나씩, 부르기 직전에 기록을 남기고 부른다:
--   '값 시험' = dataman.subscribe(키, 물체, 함수) 한 가지 키 / '나가기' = navigator.finish()
local lvgl, root, log = ...
local W, H = 432, 514
local font = lvgl.Font("MiSans-Medium", 18)
local phone = "http://10.1.10.1"
local PAK_VER = "1"
local PAK_URL = "https://raw.githubusercontent.com/Hug-Double-K/RW6-SmallFont-Patcher/main/lua/face7/face7.pak"

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
local function writeAll(path, text)
    local file = io.open(path, "wb")
    if not file then return false end
    local ok = pcall(function() file:write(text) end)
    file:close()
    return ok
end
local function now() return safe(os.time) or 0 end
local startTime = now()

---------------------------------------------------------------- 그림을 둘 곳
local dir = safe(os.getenv, "RW6FACE_DIR")
local function canWrite(prefix)
    return writeAll(prefix .. "probe", "1") and readAll(prefix .. "probe", 8) == "1"
end
if not dir then
    dir = "/data/rw6face/"
    if not canWrite(dir) then
        safe(os.execute, "mkdir -p /data/rw6face")
        if not canWrite(dir) then
            dir = "/data/rw6face_"
            if not canWrite(dir) then dir = "/tmp/rw6face_" end
        end
    end
end
local tmp = safe(os.getenv, "RW6FACE_TMP") or "/tmp/"

---------------------------------------------------------------- 기록 (폰으로 올릴 것)
local notes, events = {}, {}
local uploadAt = nil            -- 이 시각이 되면 기록을 폰으로 올림
local function note(text)
    if #notes >= 60 then table.remove(notes, 1) end
    notes[#notes + 1] = "+" .. (now() - startTime) .. "s " .. text
end
local keyOrder = { "systemStatusBattery", "healthStepCount", "healthHeartRate" }
local keyTitle = { systemStatusBattery = "배터리", healthStepCount = "걸음", healthHeartRate = "심박" }
local keyState, keyRaw, keyCount = {}, {}, {}
local function loadKeys()
    local text = readAll(dir .. "keys.txt", 2000) or ""
    for key, state in string.gmatch(text, "([%w_]+)=(%a+)") do keyState[key] = state end
end
local function saveKeys()
    local out = {}
    for _, key in ipairs(keyOrder) do if keyState[key] then out[#out + 1] = key .. "=" .. keyState[key] end end
    writeAll(dir .. "keys.txt", table.concat(out, "\n") .. "\n")
end
local curlOpts, phoneSeen = nil, "아직 안 물어봄"
local imageErrors = 0
local lastAnswer = ""
local function buildReport()
    local lines = { "== RW6 Lua 워치페이스 시험 (" .. tostring(safe(os.date, "%m-%d %H:%M:%S")) .. ") ==",
        "그림 폴더 " .. dir .. " / 그림 오류 " .. imageErrors .. " / curl 꼴 " .. tostring(curlOpts) .. " / 폰 " .. phoneSeen,
        "마지막 열기 답: " .. lastAnswer, "", "[값 키]" }
    for _, key in ipairs(keyOrder) do
        lines[#lines + 1] = key .. " = " .. tostring(keyState[key]) .. " / 받은 횟수 " .. (keyCount[key] or 0) .. " / 받은 값 "
            .. table.concat(keyRaw[key] or {}, " ")
    end
    lines[#lines + 1] = ""
    lines[#lines + 1] = "[한 일]"
    for _, text in ipairs(notes) do lines[#lines + 1] = text end
    lines[#lines + 1] = ""
    lines[#lines + 1] = "[화면 신호: 최근] " .. table.concat(events, " ")
    return table.concat(lines, "\n") .. "\n"
end

---------------------------------------------------------------- 폰과 말하기
local function curlRaw(arguments, respName)
    local resp = tmp .. (respName or "rw6-face-resp.txt")
    os.remove(resp)
    safe(os.execute, "curl " .. arguments .. " -o " .. resp)
    local got = readAll(resp, 300) or ""
    os.remove(resp)
    return got
end
-- 폰이 없을 때 오래 멈추지 않게 시간 제한을 붙인다. 워치의 curl이 그 옵션을 모르면 옵션 없이.
local function askPhone(path)
    if curlOpts == nil then
        local got = curlRaw("--connect-timeout 2 -m 5 " .. phone .. path)
        if #got > 0 then curlOpts = "--connect-timeout 2 -m 5 " return got end
        got = curlRaw(phone .. path)
        if #got > 0 then curlOpts = "" end
        return got
    end
    return curlRaw(curlOpts .. phone .. path)
end
local function uploadReport()
    local path = tmp .. "rw6-face.txt"
    if not writeAll(path, buildReport()) then return false end
    local got = curlRaw((curlOpts or "") .. "-T " .. path .. " " .. phone .. "/r/face.txt")
    return string.match(got, "^ok") ~= nil
end

---------------------------------------------------------------- 화면 바탕
-- 화면 전체를 덮되(아래 깔린 패처 버튼이 눌리지 않게) 신호는 위로 넘긴다 → 길게 누르기 등 워치 기본 동작이 살아 있게.
local panel = lvgl.Object(root, { x = 0, y = 0, w = W, h = H, border_width = 0, pad_all = 0, radius = 0, bg_color = "#071B33" })
panel:clear_flag(lvgl.FLAG.SCROLLABLE)
if lvgl.FLAG.EVENT_BUBBLE then panel:add_flag(lvgl.FLAG.EVENT_BUBBLE) end
local statusLabel
local function setStatus(text)
    if statusLabel then statusLabel:set { text = text } end
end
local function image(x, y, name)
    local object = safe(function() return panel:Image { x = x, y = y } end)
    if not object then imageErrors = imageErrors + 1 return nil end
    if name and not pcall(function() object:set { src = dir .. name .. ".png" } end) then imageErrors = imageErrors + 1 end
    return object
end

---------------------------------------------------------------- 숫자 칸
local function slots(prefix, x, y, w, count)
    local group = { prefix = prefix, x = x, w = w, count = count, images = {}, shown = {} }
    for i = 1, count do
        group.images[i] = image(x + (i - 1) * w, y, nil)
        if group.images[i] then group.images[i]:add_flag(lvgl.FLAG.HIDDEN) end
        group.shown[i] = false
    end
    return group
end
-- text: 숫자 글자(음수는 '-'), align: "r" 오른쪽 / "l" 왼쪽 / "c" 가운데. text 가 nil 이면 비움.
local function show(group, text, align)
    text = text or ""
    if #text > group.count then text = string.sub(text, -group.count) end
    local offset = 0
    if align == "r" then offset = (group.count - #text) * group.w
    elseif align == "c" then offset = ((group.count - #text) * group.w) // 2 end
    for i = 1, group.count do
        local object, ch = group.images[i], string.sub(text, i, i)
        if object then
            if ch == "" then
                if group.shown[i] ~= false then object:add_flag(lvgl.FLAG.HIDDEN) group.shown[i] = false end
            else
                local x = group.x + offset + (i - 1) * group.w
                local want = (ch == "-" and "minus" or ch) .. "@" .. x
                if group.shown[i] ~= want then
                    pcall(function()
                        object:set { x = x }
                        object:set { src = dir .. group.prefix .. "_" .. (ch == "-" and "minus" or ch) .. ".png" }
                    end)
                    if group.shown[i] == false then object:clear_flag(lvgl.FLAG.HIDDEN) end
                    group.shown[i] = want
                end
            end
        end
    end
end
local function single(x, y)       -- 그림 한 장짜리 칸 (이름이 바뀔 때만 다시 그림)
    local slot = { object = image(x, y, nil), name = false }
    if slot.object then slot.object:add_flag(lvgl.FLAG.HIDDEN) end
    return slot
end
local function showOne(slot, name)
    if not slot.object or slot.name == name then return end
    if not name then slot.object:add_flag(lvgl.FLAG.HIDDEN) slot.name = false return end
    pcall(function() slot.object:set { src = dir .. name .. ".png" } end)
    if slot.name == false then slot.object:clear_flag(lvgl.FLAG.HIDDEN) end
    slot.name = name
end

---------------------------------------------------------------- 값 (시계·날짜는 os.date, 나머지는 받은 값)
local face = nil
local values = {}            -- battery / steps / heart / temp (숫자 또는 nil)
local function weekday(y, m, d)   -- 0 = 일요일 (계산으로 — 워치의 %w 지원 여부를 모르므로)
    local t = { 0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4 }
    if m < 3 then y = y - 1 end
    return (y + y // 4 - y // 100 + y // 400 + t[m] + d) % 7
end
local batteryLevels = { 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100 }
local lastMinute = nil
local function drawClock(force)
    if not face then return end
    local text = safe(os.date, "%Y %m %d %H %M") or ""
    local y, mo, d, h, mi = string.match(text, "^(%d+) (%d+) (%d+) (%d+) (%d+)")
    if not y then return end
    if not force and lastMinute == text then return end
    lastMinute = text
    show(face.hour, h, "l")
    show(face.minute, mi, "l")
    showOne(face.month, "date_m" .. tonumber(mo))
    showOne(face.day, "date_d" .. tonumber(d))
    showOne(face.week, "date_w" .. weekday(tonumber(y), tonumber(mo), tonumber(d)))
end
local function whole(value)
    if type(value) ~= "number" then return nil end
    return string.format("%d", math.floor(value + 0.5))
end
local function drawValues()
    if not face then return end
    local battery = values.battery
    if battery and battery >= 0 and battery <= 100 then
        local text = whole(battery)
        local level = 100
        for _, p in ipairs(batteryLevels) do if battery <= p then level = p break end end
        showOne(face.batteryIcon, "bi_" .. level)
        show(face.battery, text, "l")
        showOne(face.batteryPct, "bpct_" .. #text)
    else
        showOne(face.batteryIcon, "bi_100") show(face.battery, nil) showOne(face.batteryPct, nil)
    end
    show(face.steps, values.steps and values.steps >= 0 and values.steps <= 99999 and whole(values.steps) or nil, "c")
    show(face.heart, values.heart and values.heart > 0 and values.heart < 256 and whole(values.heart) or nil, "l")
    show(face.temp, values.temp and whole(values.temp) or nil, "r")
end

---------------------------------------------------------------- 값 받기 (dataman — 사용자가 누를 때만 새 키를 시험)
local dataman = nil
local valueName = { systemStatusBattery = "battery", healthStepCount = "steps", healthHeartRate = "heart" }
local function onValue(key, raw)
    keyCount[key] = (keyCount[key] or 0) + 1
    local list = keyRaw[key] or {}
    keyRaw[key] = list
    if #list < 6 then list[#list + 1] = tostring(raw) end
    if keyCount[key] <= 2 and not uploadAt then uploadAt = now() + 5 end      -- 처음 받은 값은 폰으로 알려 줌
    if type(raw) ~= "number" then return end
    local value = raw
    if raw == 2147483647 then value = nil                                  -- 값 없음
    elseif raw >= 256 and raw % 256 == 0 then value = raw // 256 end      -- 256배로 오는 꼴(밴드 사례) — 받은 값은 기록에 그대로 남김
    values[valueName[key]] = value
    pcall(drawValues)
end
-- 한 가지 키를 듣기 시작. 부르기 전에 '시험 중'이라고 적어 두므로, 여기서 워치가 재시작되면 다음부터 그 키는 건너뜀.
local function subscribe(key)
    if not dataman then dataman = safe(require, "dataman") end
    if type(dataman) ~= "table" or type(dataman.subscribe) ~= "function" then keyState[key] = "none" return "dataman 없음" end
    keyState[key] = "trying"
    saveKeys()
    local ok, err = pcall(dataman.subscribe, key, panel, function(_, raw) onValue(key, raw) end)
    keyState[key] = ok and "ok" or "error"
    saveKeys()
    note("값 듣기 " .. key .. " -> " .. (ok and "O" or ("X " .. tostring(err))))
    return ok and "O" or "X"
end

---------------------------------------------------------------- 용두 → RW6 알림 (폰을 거쳐서)
local crownOn = true
local lastPair, lastLaunch = -100, -100        -- 62·63 한 쌍이 온 때 / 마지막으로 연 때
local function launchNow()
    -- '열어 줘'는 이 함수의 맨 마지막 일. 이 뒤에 오래 걸리는 일을 하면 워치가 재시작됨(2026-11-27 사고).
    lastAnswer = string.gsub(askPhone("/do/launch-noti"), "%s+", " ")
end
local function requestLaunch(why)
    local t = now()
    if t - lastLaunch < 8 then return end
    lastLaunch = t
    note("열기 요청 (" .. why .. ")")
    uploadAt = t + 20
    __rw6FaceLaunch = lvgl.Timer { period = 150, repeat_count = 1, cb = function() __rw6FaceLaunch = nil launchNow() end }
end
local eventNames = {}
pcall(function() for k, v in pairs(lvgl.EVENT) do eventNames[v] = tostring(k) end end)
local function onEvent(code)
    if code ~= 62 and code ~= 63 and code ~= 145 and code ~= 149 then return end
    local t = now()
    if #events >= 60 then table.remove(events, 1) end
    events[#events + 1] = code .. "@" .. (t - startTime)
    if code == 62 or code == 63 then lastPair = t return end
    -- 145 = 용두를 돌린 뒤에만 옴. 터치(62·63뿐)나, 기본 알림 목록이 닫힐 때 혼자 오는 145 와 가리려고 '62·63 뒤 2초 안의 145'만 침.
    if code == 145 and crownOn and face and t - lastPair <= 2 then requestLaunch("용두") end
end
local all = safe(function() return lvgl.EVENT.ALL end)
if all ~= nil then pcall(function() root:onevent(all, function(_, code) if type(code) == "number" then onEvent(code) end end) end) end

---------------------------------------------------------------- 버튼 (시험용, 맨 아래 줄)
local function button(x, text, color)
    local object = lvgl.Object(panel, { x = x, y = 456, w = 92, h = 40, radius = 10, border_width = 1, border_color = color,
        bg_color = "#252830", pad_all = 0 })
    object:clear_flag(lvgl.FLAG.SCROLLABLE)
    object:add_flag(lvgl.FLAG.CLICKABLE)
    local label = lvgl.Label(object, { text = text, align = lvgl.ALIGN.CENTER, text_color = color, text_font = font })
    return object, label
end
local lastPress = -100
local function pressOnce()          -- 같은 누름이 두 번 오는 것 막기
    local t = now()
    if t - lastPress < 2 then return false end
    lastPress = t
    return true
end
local function nextKey()
    for _, key in ipairs(keyOrder) do if keyState[key] == nil then return key end end
    return nil
end
local function keysLine()
    local out = {}
    for _, key in ipairs(keyOrder) do
        local state = keyState[key]
        out[#out + 1] = keyTitle[key] .. " " .. ((state == "ok" and ((keyCount[key] or 0) > 0 and "O" or "대기")) or (state == nil and "-") or "X")
    end
    return table.concat(out, "  ")
end

---------------------------------------------------------------- 화면 만들기 (그림이 준비된 뒤)
local tick
local function buildFace()
    image(0, 0, "bg")
    local f = {}
    f.batteryIcon = single(40, 30)
    f.battery = slots("bat", 94, 27, 16, 3)
    f.batteryPct = single(94, 27)
    f.month, f.day, f.week = single(143, 90), single(204, 90), single(265, 90)
    f.hour, f.minute = slots("time", 80, 122, 62, 2), slots("time", 228, 122, 62, 2)
    f.temp = slots("val", 104, 272, 20, 2)
    f.heart = slots("val", 288, 272, 20, 3)
    f.steps = slots("st", 248, 370, 29, 5)
    face = f
    statusLabel = lvgl.Label(panel, { x = 190, y = 30, w = 222, h = 26, text = "", text_color = "#ffd166", text_font = font })
    local keyButton, keyLabel = button(70, "값 시험", "#78d99b")
    local crownButton, crownLabel = button(170, "용두 켬", "#71b7ff")
    local exitButton = button(270, "나가기", "#ff9a91")
    keyButton:onevent(lvgl.EVENT.CLICKED, function()
        if not pressOnce() then return end
        local key = nextKey()
        if not key then setStatus(keysLine()) return end
        local result = subscribe(key)
        setStatus(keyTitle[key] .. " 듣기 " .. result)
        if not nextKey() then keyLabel:set { text = "값 상태" } end
        uploadAt = now() + 5
    end)
    crownButton:onevent(lvgl.EVENT.CLICKED, function()
        if not pressOnce() then return end
        crownOn = not crownOn
        crownLabel:set { text = crownOn and "용두 켬" or "용두 끔" }
        note("용두 열기 " .. (crownOn and "켬" or "끔"))
    end)
    exitButton:onevent(lvgl.EVENT.CLICKED, function()
        if not pressOnce() then return end
        -- 시험: navigator.finish() — 워치에서 처음 부르는 기능. 부르기 전에 기록을 폰으로 올려 둔다.
        local navigator = safe(require, "navigator")
        if type(navigator) ~= "table" or type(navigator.finish) ~= "function" then setStatus("나가기 기능 없음") return end
        note("지금 navigator.finish() 를 부름")
        safe(uploadReport)
        local ok, err = pcall(navigator.finish)
        note("navigator.finish 결과 " .. (ok and "O" or ("X " .. tostring(err))))
        setStatus(ok and "나가기 불렀음" or "나가기 X")
        uploadAt = now() + 3
    end)
    pcall(drawClock, true)
    pcall(drawValues)
    -- 지난번에 됐던 키는 바로 듣기 시작 (재시작을 일으킨 키는 'trying' 으로 남아 있어 건너뜀)
    for _, key in ipairs(keyOrder) do
        if keyState[key] == "ok" then subscribe(key) elseif keyState[key] == "trying" then keyState[key] = "bad" saveKeys() end
    end
    if not nextKey() then keyLabel:set { text = "값 상태" } end
    note("화면 만듦, 그림 오류 " .. imageErrors)
end

---------------------------------------------------------------- 1초마다
local weatherAt = 0           -- 다음에 기온을 물어볼 때
tick = function()
    local t = now()
    pcall(drawClock, false)
    if face and t >= weatherAt and t - lastLaunch > 6 then
        weatherAt = t + 1800
        local got = askPhone("/do/weather")
        local temp = string.match(got, "^temp (%-?%d+)")
        phoneSeen = (#got > 0) and ("답 " .. string.gsub(string.sub(got, 1, 30), "%s+", " ")) or "답 없음"
        if temp then values.temp = tonumber(temp) pcall(drawValues) elseif #got == 0 then weatherAt = t + 600 end
        note("기온 물음: " .. phoneSeen)
        if not uploadAt then uploadAt = t + 3 end
    elseif uploadAt and t >= uploadAt and t - lastLaunch > 6 then
        uploadAt = nil
        safe(uploadReport)
    end
    __rw6FaceTimer = lvgl.Timer { period = 1000, repeat_count = 1, cb = function() __rw6FaceTimer = nil tick() end }
end

---------------------------------------------------------------- 그림 묶음 받기·풀기
local waitLabel = lvgl.Label(panel, { x = 40, y = 200, w = W - 80, h = 120, text = "", text_color = "#e2e5eb", text_font = font })
local function start()
    waitLabel:add_flag(lvgl.FLAG.HIDDEN)
    loadKeys()
    local ok, err = pcall(buildFace)
    if not ok then      -- 그림 위에 보이도록 새 글자 칸을 만든다
        lvgl.Label(panel, { x = 40, y = 200, w = W - 80, h = 120, text = "화면 만들기 X\n" .. tostring(err), text_color = "#ffd166", text_font = font })
        note("화면 만들기 X " .. tostring(err))
    end
    weatherAt = now() + 2
    tick()
end
local function ready()
    return readAll(dir .. "ver.txt", 16) == PAK_VER and readAll(dir .. "bg.png", 4) == "\137PNG"
end
local function unpack(data)
    local count = tonumber(string.match(data, "^RW6PAK 1 (%d+)\n"))
    if not count then return nil, "묶음 꼴이 다름 (" .. #data .. "바이트)" end
    local pos = string.find(data, "\n", 1, true) + 1
    local done = 0
    local function step()
        for _ = 1, 12 do
            if done >= count then
                if not writeAll(dir .. "ver.txt", PAK_VER) or not ready() then waitLabel:set { text = "그림 저장 X\n" .. dir } return end
                start()
                return
            end
            local lineEnd = string.find(data, "\n", pos, true)
            local name, size = string.match(string.sub(data, pos, (lineEnd or pos) - 1), "^(%S+) (%d+)$")
            size = tonumber(size)
            if not lineEnd or not name or lineEnd + size > #data then waitLabel:set { text = "묶음이 잘림 X\n" .. done .. "/" .. count } return end
            if not writeAll(dir .. name, string.sub(data, lineEnd + 1, lineEnd + size)) then
                waitLabel:set { text = "그림 저장 X\n" .. dir .. name } return
            end
            pos = lineEnd + size + 1
            done = done + 1
        end
        waitLabel:set { text = "그림 푸는 중 " .. done .. "/" .. count }
        __rw6FaceTimer = lvgl.Timer { period = 30, repeat_count = 1, cb = function() __rw6FaceTimer = nil step() end }
    end
    step()
    return true
end
if ready() then
    start()
else
    waitLabel:set { text = "그림을 받는 중입니다\n(처음 한 번, 10초쯤)" }
    __rw6FaceTimer = lvgl.Timer { period = 300, repeat_count = 1, cb = function()
        __rw6FaceTimer = nil
        local path = tmp .. "rw6-face.pak"
        os.remove(path)
        safe(os.execute, "curl " .. PAK_URL .. " -o " .. path)
        local data = readAll(path)
        os.remove(path)
        if not data or #data < 1000 then waitLabel:set { text = "그림을 못 받음 X\n인터넷(폰 Mi Manager 연결)을 확인하세요" } return end
        local ok, err = unpack(data)
        if not ok then waitLabel:set { text = "그림 풀기 X\n" .. tostring(err) } end
    end }
end
log("Lua 워치페이스 시험판을 띄웠습니다.")
