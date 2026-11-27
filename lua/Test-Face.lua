-- DK_07(물결)을 Lua 워치페이스로 옮기는 첫 시험판. 패처 목록의 'Test-Face'로 실행(설치 없음).
-- 하는 일: 그림 묶음을 한 번 받아 풀고, 시간·날짜를 그리고, 용두를 돌리면(어느 쪽이든) 폰을 거쳐 RW6 알림을 연다.
-- 워치에서 이미 돈 기능만 씀: 화면 물체·그림(Image, 깔려 있는 Lua 워치페이스와 같은 꼴)·신호 듣기·타이머·파일·curl.
-- 아직 워치에서 안 돌려 본 것은 사용자가 버튼을 누를 때만, 한 번에 하나씩, 부르기 직전에 기록을 남기고 부른다:
--   '값 시험' = dataman.subscribe(키, 물체, 함수) 한 가지 키. 나가기는 화면 길게 누르기(워치페이스 바꾸기)로 — 사용자 확인 2026-11-27
local lvgl, root, log, mode = ...
local FACE_VER = 4
-- 같은 파일을 두 가지로 씀: 패처 목록의 Test-Face(시험 버튼 있음) / 정식 워치페이스(lua/face7/main.lua 가 "face" 로 부름: 버튼 없음, 스스로 새 판 확인)
local isFace = (mode == "face")
local FACE_BASE = "https://raw.githubusercontent.com/Hug-Double-K/RW6-SmallFont-Patcher/main/lua/face7/"
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
if isFace then curlOpts = "--connect-timeout 2 -m 5 " end   -- 이 워치의 curl 이 아는 옵션(2026-11-27 확인). 정식판은 옵션 없는 꼴(오래 멈출 수 있음)을 쓰지 않음
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
-- 2026-11-27 사용자 결정: 용두는 안 써도 됨. 화면을 위에서 아래로 내리면 기본 알림 센터처럼 우리 알림을 보여 주기.
-- 방법: 시계 화면을 '위아래로 밀리는 틀' 안에 넣고 그 바로 위(y = -H)에 알림 쪽을 둔다 → 아래로 밀면 알림 쪽이 내려옴(앱을 켜지 않으니 바로 보임).
-- 틀이 스스로 밀릴 수 있는 동안에는 그 밀기가 워치(기본 알림 목록)로 넘어가지 않을 것으로 기대 — 워치에서 확인 필요.
local scroller = lvgl.Object(root, { x = 0, y = 0, w = W, h = H, border_width = 0, pad_all = 0, radius = 0, bg_color = "#071B33" })
if lvgl.FLAG.EVENT_BUBBLE then scroller:add_flag(lvgl.FLAG.EVENT_BUBBLE) end
local scrollNotes = {}
local function constant(group, name)          -- lvgl.<group>.<name> 이 있으면 그 값 (없으면 nil)
    return safe(function() return lvgl[group][name] end)
end
local function tryFlag(object, name, on)
    local flag = constant("FLAG", name)
    if flag == nil then scrollNotes[#scrollNotes + 1] = name .. " 없음" return end
    local ok = pcall(function() if on then object:add_flag(flag) else object:clear_flag(flag) end end)
    scrollNotes[#scrollNotes + 1] = name .. (ok and (on and " 켬" or " 끔") or " X")
end
local function trySet(object, key, group, name)
    local value = constant(group, name)
    if value == nil then scrollNotes[#scrollNotes + 1] = group .. "." .. name .. " 없음" return end
    local ok = pcall(function() object:set { [key] = value } end)
    scrollNotes[#scrollNotes + 1] = key .. (ok and " O" or " X")
end
tryFlag(scroller, "SCROLL_ELASTIC", false)       -- 끝에서 더 밀면 튕기지 않고 워치 쪽으로 넘어가게(시계에서 위로 밀기 등)
tryFlag(scroller, "SCROLL_ONE", true)            -- 한 번에 한 쪽씩
trySet(scroller, "scroll_snap_y", "SCROLL_SNAP", "START")
trySet(scroller, "scrollbar_mode", "SCROLLBAR_MODE", "OFF")
trySet(scroller, "scroll_dir", "DIR", "VER")
local panel = lvgl.Object(scroller, { x = 0, y = 0, w = W, h = H, border_width = 0, pad_all = 0, radius = 0, bg_color = "#071B33" })
panel:clear_flag(lvgl.FLAG.SCROLLABLE)
if lvgl.FLAG.EVENT_BUBBLE then panel:add_flag(lvgl.FLAG.EVENT_BUBBLE) end
local listPage = lvgl.Object(scroller, { x = 0, y = -H, w = W, h = H, border_width = 0, pad_all = 0, radius = 0, bg_color = "#101114" })
listPage:clear_flag(lvgl.FLAG.SCROLLABLE)
if lvgl.FLAG.EVENT_BUBBLE then listPage:add_flag(lvgl.FLAG.EVENT_BUBBLE) end
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
    else value = raw // 256 end      -- 값은 256배로 옴 (워치 확인 2026-11-27: 배터리 24064 = 94)
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
local armed, tickCount, lastNoiseTick, lastTickTime = true, 0, 0, nil
local lastStamp, lastStampCount = nil, 0
local function launchNow()
    -- '열어 줘'는 이 함수의 맨 마지막 일. 이 뒤에 오래 걸리는 일을 하면 워치가 재시작됨(2026-11-27 사고).
    lastAnswer = string.gsub(askPhone("/do/launch-noti"), "%s+", " ")
end
local function requestLaunch(why)
    local t = now()
    if t - lastLaunch < 8 then return end
    lastLaunch = t
    armed = false
    note("열기 요청 (" .. why .. ") 직전 신호: " .. table.concat(events, " ", math.max(1, #events - 5)))
    uploadAt = t + 20
    __rw6FaceLaunch = lvgl.Timer { period = 40, repeat_count = 1, cb = function() __rw6FaceLaunch = nil launchNow() end }
end
local EV_PRESSED, EV_RELEASED = lvgl.EVENT.PRESSED, lvgl.EVENT.RELEASED
local eventNames = {}
pcall(function() for k, v in pairs(lvgl.EVENT) do eventNames[v] = tostring(k) end end)
local function onEvent(code)
    -- 누름(P)·뗌(R)도 기록만 함: 터치와 용두를 62·63 만으로 가를 수 있는지 알아보려고(더 빨리 열기 위한 조사)
    local shown = (code == EV_PRESSED and "P") or (code == EV_RELEASED and "R") or nil
    if not shown and code ~= 62 and code ~= 63 and code ~= 145 and code ~= 149 then return end
    local t = now()
    lastNoiseTick = tickCount
    local stamp = (shown or code) .. "@" .. (t - startTime)
    if lastStamp == stamp then
        lastStampCount = lastStampCount + 1
        events[#events] = stamp .. "x" .. lastStampCount
    else
        if #events >= 80 then table.remove(events, 1) end
        events[#events + 1] = stamp
        lastStamp, lastStampCount = stamp, 1
    end
    if code == 62 or code == 63 then lastPair = t return end
    -- 145 = 용두를 돌린 뒤에 옴. 터치(62·63뿐)나, 기본 알림 목록이 닫힐 때 혼자 오는 145 와 가리려고 '62·63 뒤 2초 안의 145'만 침.
    -- armed: 한 번 연 뒤에는 화면이 4초쯤 조용해질 때까지 다시 열지 않음(앱에서 돌아올 때 오는 신호로 되풀이해 여는 것 막기).
    -- (용두로 열기는 뺌 — 사용자 결정 2026-11-27. 신호 기록만 남김)
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
    -- 말풍선 그림 자리를 누르면 RW6 알림 (그림 방식 DK_07 의 253번 버튼 자리). 다른 앱(날씨·심박·타이머 등)은 Lua 에서 열 길이 없음.
    local chatTap = lvgl.Object(panel, { x = 22, y = 366, w = 96, h = 96, radius = 0, border_width = 0, bg_opa = 0, pad_all = 0 })
    chatTap:clear_flag(lvgl.FLAG.SCROLLABLE)
    chatTap:add_flag(lvgl.FLAG.CLICKABLE)
    if lvgl.FLAG.EVENT_BUBBLE then chatTap:add_flag(lvgl.FLAG.EVENT_BUBBLE) end
    chatTap:onevent(lvgl.EVENT.CLICKED, function() requestLaunch("터치") end)
    local keyLabel = nil
    if not isFace then
        statusLabel = lvgl.Label(panel, { x = 190, y = 30, w = 222, h = 26, text = "", text_color = "#ffd166", text_font = font })
        local keyButton
        keyButton, keyLabel = button(170, "값 시험", "#78d99b")
        keyButton:onevent(lvgl.EVENT.CLICKED, function()
            if not pressOnce() then return end
            local key = nextKey()
            if not key then setStatus(keysLine()) return end
            local result = subscribe(key)
            setStatus(keyTitle[key] .. " 듣기 " .. result)
            if not nextKey() then keyLabel:set { text = "값 상태" } end
            uploadAt = now() + 5
        end)
    end
    pcall(drawClock, true)
    pcall(drawValues)
    -- 지난번에 됐던 키는 바로 듣기 시작 (재시작을 일으킨 키는 'trying' 으로 남아 있어 건너뜀)
    for _, key in ipairs(keyOrder) do
        if keyState[key] == "trying" then keyState[key] = "bad" saveKeys()
        elseif keyState[key] == "ok" or (isFace and keyState[key] == nil) then subscribe(key) end
    end
    if keyLabel and not nextKey() then keyLabel:set { text = "값 상태" } end
    note("화면 만듦, 그림 오류 " .. imageErrors)
end

---------------------------------------------------------------- 새 판 확인 (정식 워치페이스일 때만) — 다시 설치하지 않고 고칠 수 있게
local function checkUpdate()
    if curlOpts == nil then return end                      -- 폰과 한 번도 말이 안 됐으면 인터넷도 없음
    local got = curlRaw(curlOpts .. FACE_BASE .. "version.txt", "rw6-face-ver.txt")
    local remote = tonumber(string.match(got, "^(%d+)"))
    note("새 판 확인: 지금 " .. FACE_VER .. " / 저장소 " .. tostring(remote))
    if not remote or remote <= FACE_VER then return end
    local path = tmp .. "rw6-face-new.lua"
    os.remove(path)
    safe(os.execute, "curl " .. (curlOpts ~= "" and "--connect-timeout 3 -m 40 " or "") .. FACE_BASE .. "face.lua -o " .. path)
    local text = readAll(path)
    local good = text and #text > 1000 and string.find(text, "-- RW6FACE END", 1, true)
        and string.find(text, "local FACE_VER = " .. remote .. "\n", 1, true) and loadfile(path)
    os.remove(path)
    if good and writeAll(dir .. "face.lua", text) then note("새 판 " .. remote .. " 받음 (다음에 켜질 때부터)") else note("새 판 받기 X") end
    uploadAt = uploadAt or (now() + 3)
end

---------------------------------------------------------------- 알림 쪽: RW6 알림 앱이 워치에 적어 둔 목록 파일을 직접 읽어 그림 (앱을 안 켬)
local function sh(command, limit)
    local out = tmp .. "rw6-face-sh.txt"
    os.remove(out)
    safe(os.execute, command .. " > " .. out)
    local text = readAll(out, limit or 3000) or ""
    os.remove(out)
    return (string.gsub(text, "%s+", " "))
end
-- 작은 JSON 풀이 (목록 파일 = [{name, kind, a 앱, s 보낸 사람, b 내용, w 받은 시각(ms) …}], 최신이 앞)
local function encodeUtf8(code)          -- (워치의 Lua 에 utf8 묶음이 없을 수도 있어 직접 만듦)
    if code < 0x80 then return string.char(code) end
    if code < 0x800 then return string.char(0xC0 + code // 64, 0x80 + code % 64) end
    if code < 0x10000 then return string.char(0xE0 + code // 4096, 0x80 + (code // 64) % 64, 0x80 + code % 64) end
    return string.char(0xF0 + code // 262144, 0x80 + (code // 4096) % 64, 0x80 + (code // 64) % 64, 0x80 + code % 64)
end
local function decodeJson(text)
    local pos = 1
    local value
    local function skip() pos = string.find(text, "%S", pos) or (#text + 1) end
    local function str()
        local out = {}
        pos = pos + 1
        while true do
            local c = string.sub(text, pos, pos)
            if c == "" then error("끝나지 않은 글") end
            if c == '"' then pos = pos + 1 break end
            if c == "\\" then
                local n = string.sub(text, pos + 1, pos + 1)
                if n == "u" then
                    local code = tonumber(string.sub(text, pos + 2, pos + 5), 16) or 63
                    pos = pos + 6
                    if code >= 0xD800 and code <= 0xDBFF and string.sub(text, pos, pos + 1) == "\\u" then      -- 두 칸짜리 글자(그림 문자 등)
                        local low = tonumber(string.sub(text, pos + 2, pos + 5), 16) or 0xDC00
                        code = 0x10000 + (code - 0xD800) * 0x400 + (low - 0xDC00)
                        pos = pos + 6
                    end
                    out[#out + 1] = encodeUtf8(code)
                else
                    out[#out + 1] = ({ n = "\n", t = " ", r = "", b = "", f = "" })[n] or n
                    pos = pos + 2
                end
            else
                local stop = string.find(text, '["\\]', pos) or (#text + 1)
                out[#out + 1] = string.sub(text, pos, stop - 1)
                pos = stop
            end
        end
        return table.concat(out)
    end
    value = function()
        skip()
        local c = string.sub(text, pos, pos)
        if c == "{" then
            local t = {}
            pos = pos + 1 skip()
            if string.sub(text, pos, pos) == "}" then pos = pos + 1 return t end
            while true do
                skip()
                if string.sub(text, pos, pos) ~= '"' then error("칸 이름 자리 " .. pos) end
                local key = str()
                skip()
                if string.sub(text, pos, pos) ~= ":" then error(": 자리 " .. pos) end
                pos = pos + 1
                t[key] = value()
                skip()
                local d = string.sub(text, pos, pos)
                pos = pos + 1
                if d == "}" then return t elseif d ~= "," then error("} 자리 " .. pos) end
            end
        elseif c == "[" then
            local t = {}
            pos = pos + 1 skip()
            if string.sub(text, pos, pos) == "]" then pos = pos + 1 return t end
            while true do
                t[#t + 1] = value()
                skip()
                local d = string.sub(text, pos, pos)
                pos = pos + 1
                if d == "]" then return t elseif d ~= "," then error("] 자리 " .. pos) end
            end
        elseif c == '"' then return str()
        else
            local word = string.match(text, "^[%w%.%-%+]+", pos)
            if not word then error("모르는 글자 자리 " .. pos) end
            pos = pos + #word
            if word == "true" then return true elseif word == "false" then return false elseif word == "null" then return nil end
            return tonumber(word)
        end
    end
    return value()
end
-- 글자 수로 자르기 (워치 글꼴이 못 그리는 4바이트 글자 = 그림 문자는 뺌)
local function cut(text, count)
    local plain = string.gsub(tostring(text or ""), "[\240-\244][\128-\191]*", "")
    plain = string.gsub(string.gsub(plain, "%s+", " "), "^ ", "")
    local out, n = {}, 0
    for ch in string.gmatch(plain, "[%z\1-\127\194-\239][\128-\191]*") do
        n = n + 1
        if n > count then out[#out + 1] = ".." break end
        out[#out + 1] = ch
    end
    return table.concat(out)
end
local notiPath = readAll(dir .. "notipath.txt", 300)
local searchedAt = -1000
local function findIndex()
    if notiPath and readAll(notiPath, 1) then return notiPath end
    local t = now()
    if t - searchedAt < 300 then return nil end           -- 못 찾았으면 5분에 한 번만 다시 찾음
    searchedAt = t
    notiPath = nil
    for base in string.gmatch(safe(os.getenv, "RW6NOTI_BASES") or "/data/quickapp/mass /data/quickapp/files", "%S+") do
        local top = sh("ls " .. base)
        note("찾기 " .. base .. ": " .. string.sub(top, 1, 400))
        for name in string.gmatch(top, "%S+") do
            name = string.gsub(name, "/$", "")
            if string.find(name, "wechat", 1, true) or string.find(name, "rw6noti", 1, true) then
                local path = base .. "/" .. name .. "/rw6noti_meta/index.json"
                if readAll(path, 1) then
                    notiPath = path
                    writeAll(dir .. "notipath.txt", path)
                    note("목록 파일 O " .. path)
                    return path
                end
                note("찾기 " .. base .. "/" .. name .. ": " .. string.sub(sh("ls " .. base .. "/" .. name), 1, 300))
            end
        end
    end
    note("목록 파일 X")
    uploadAt = uploadAt or (now() + 3)
    return nil
end
local listTitle = lvgl.Label(listPage, { x = 60, y = 34, w = W - 120, h = 26, text = "알림", text_color = "#ffd166", text_font = font })
local ROW_W, ROW_H, MAX_ROWS = W - 32, 104, 12
local listBox = lvgl.Object(listPage, { x = 16, y = 70, w = ROW_W, h = H - 70 - 76, border_width = 0, pad_all = 0, radius = 0, bg_opa = 0 })
tryFlag(listBox, "SCROLL_ELASTIC", false)        -- 목록 끝에서 더 밀면 바깥 틀로 넘어가 시계로 돌아가게
trySet(listBox, "scrollbar_mode", "SCROLLBAR_MODE", "OFF")
trySet(listBox, "scroll_dir", "DIR", "VER")
local backButton = lvgl.Object(listPage, { x = 136, y = H - 66, w = W - 272, h = 40, radius = 10, border_width = 1, border_color = "#71b7ff",
    bg_color = "#252830", pad_all = 0 })
backButton:clear_flag(lvgl.FLAG.SCROLLABLE)
backButton:add_flag(lvgl.FLAG.CLICKABLE)
lvgl.Label(backButton, { text = "시계로", align = lvgl.ALIGN.CENTER, text_color = "#71b7ff", text_font = font })
local scrollToNoted = false
backButton:onevent(lvgl.EVENT.CLICKED, function()
    local ok, err = pcall(function() scroller:scroll_to { y = 0, anim = true } end)
    if not scrollToNoted then scrollToNoted = true note("시계로 버튼: scroll_to " .. (ok and "O" or ("X " .. tostring(err)))) uploadAt = uploadAt or (now() + 3) end
end)
local rows = {}
local function rowAt(index)
    local row = rows[index]
    if row then return row end
    local box = lvgl.Object(listBox, { x = 0, y = (index - 1) * ROW_H, w = ROW_W, h = ROW_H - 8, radius = 14, border_width = 0, bg_color = "#1C1E24", pad_all = 0 })
    box:clear_flag(lvgl.FLAG.SCROLLABLE)
    box:add_flag(lvgl.FLAG.CLICKABLE)
    if lvgl.FLAG.EVENT_BUBBLE then box:add_flag(lvgl.FLAG.EVENT_BUBBLE) end
    row = { box = box, shown = true,
        head = lvgl.Label(box, { x = 14, y = 8, w = ROW_W - 28, h = 22, text = "", text_color = "#9aa0aa", text_font = font }),
        sender = lvgl.Label(box, { x = 14, y = 34, w = ROW_W - 28, h = 24, text = "", text_color = "#ffffff", text_font = font }),
        body = lvgl.Label(box, { x = 14, y = 62, w = ROW_W - 28, h = 24, text = "", text_color = "#c8ccd4", text_font = font }) }
    box:onevent(lvgl.EVENT.CLICKED, function() requestLaunch("목록") end)       -- 누르면 RW6 알림 앱(자세히 보기)
    rows[index] = row
    return row
end
local function timeText(entry)
    local ms = tonumber(entry.w) or 0
    if ms <= 0 then ms = tonumber(entry.name) or 0 end
    if ms <= 0 then return "" end
    local secs = ms // 1000
    local day, today = safe(os.date, "%m/%d", secs), safe(os.date, "%m/%d")
    local clock = safe(os.date, "%H:%M", secs) or ""
    return (day and day ~= today) and (day .. " " .. clock) or clock
end
local lastListText, listShown = nil, 0
local function refreshList()
    local path = findIndex()
    if not path then listTitle:set { text = "알림 목록을 못 찾음" } return end
    local text = readAll(path, 400000) or ""
    if text == lastListText then return end
    lastListText = text
    local ok, list = pcall(decodeJson, text)
    if not ok or type(list) ~= "table" then
        listTitle:set { text = "알림 목록 읽기 X" }
        note("목록 풀기 X " .. tostring(list))
        uploadAt = uploadAt or (now() + 3)
        return
    end
    listTitle:set { text = (#list > 0) and ("알림 " .. #list) or "수신된 알림이 없습니다" }
    for index = 1, math.max(listShown, math.min(#list, MAX_ROWS)) do
        local entry = (index <= MAX_ROWS) and list[index] or nil
        if entry or rows[index] then
            local row = rowAt(index)
            if type(entry) == "table" then
                local when = timeText(entry)
                row.head:set { text = cut(entry.a ~= "" and entry.a or "알림", 16) .. (when ~= "" and ("  " .. when) or "") }
                row.sender:set { text = cut(entry.kind == "img" and (entry.s ~= "" and entry.s or "사진 알림") or entry.s, 20) }
                row.body:set { text = cut(entry.b, 24) }
                if not row.shown then row.box:clear_flag(lvgl.FLAG.HIDDEN) row.shown = true end
            elseif row.shown then
                row.box:add_flag(lvgl.FLAG.HIDDEN)
                row.shown = false
            end
        end
    end
    if listShown == 0 then note("목록 그림: " .. #list .. "개 / 틀 설정: " .. table.concat(scrollNotes, ", ")) uploadAt = uploadAt or (now() + 3) end
    listShown = math.max(listShown, math.min(#list, MAX_ROWS))
end
-- 밀기 시작할 때마다 목록을 새로 읽음 (2초에 한 번까지). 밀기 신호가 없는 워치면 아래 1초 일에서 30초마다.
local scrollBegin, lastRefresh = constant("EVENT", "SCROLL_BEGIN"), -100
local sawScroll = false
pcall(function()
    scroller:onevent(lvgl.EVENT.ALL, function(_, code)
        if scrollBegin ~= nil and code == scrollBegin and now() - lastRefresh >= 2 then
            lastRefresh = now()
            if not sawScroll then sawScroll = true note("밀기 신호 받음") end
            pcall(refreshList)
        end
    end)
end)

---------------------------------------------------------------- 1초마다
local weatherAt = 0           -- 다음에 기온을 물어볼 때
local updateAt = now() + 40   -- 다음에 새 판을 확인할 때
tick = function()
    local t = now()
    tickCount = tickCount + 1
    -- 1초마다 도는 이 일이 3초 넘게 건너뛰었으면 = 화면이 가려져 있었음(앱이 떠 있었거나 화면 꺼짐) → 기록하고, 조용해질 때까지 열지 않음
    if lastTickTime and t - lastTickTime >= 3 then
        note("쉬었다 옴 " .. (t - lastTickTime) .. "초")
        armed = false
        lastNoiseTick = tickCount
    end
    lastTickTime = t
    if not armed and tickCount - lastNoiseTick >= 4 and t - lastLaunch >= 8 then armed = true note("다시 열 준비 됨") end
    pcall(drawClock, false)
    if face and t >= weatherAt and t - lastLaunch > 6 then
        weatherAt = t + 1800
        local got = askPhone("/do/weather")
        local temp = string.match(got, "^temp (%-?%d+)")
        phoneSeen = (#got > 0) and ("답 " .. string.gsub(string.sub(got, 1, 30), "%s+", " ")) or "답 없음"
        if temp then values.temp = tonumber(temp) pcall(drawValues) elseif #got == 0 then weatherAt = t + 600 end
        note("기온 물음: " .. phoneSeen)
        if not uploadAt then uploadAt = t + 3 end
    elseif face and t - lastRefresh >= 30 and t - lastLaunch > 6 then
        lastRefresh = t
        pcall(refreshList)
    elseif isFace and face and t >= updateAt and t - lastLaunch > 6 then
        updateAt = t + 6 * 3600
        safe(checkUpdate)
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
    pcall(refreshList)
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
-- RW6FACE END
