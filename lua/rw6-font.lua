local lvgl = require("lvgl")

----------------------------- config -----------------------------
local W, H = lvgl.HOR_RES(), lvgl.VER_RES()
local margin, gap, buttonHeight = 20, 8, 60
local contentWidth = W - margin * 2
local buttonY = H - 50 - buttonHeight
local buttonWidth = (contentWidth - gap * 2) // 3

local workDir = "/tmp/patch"
local downloadDir = "/data/tmp-patch"
local readbackPath = downloadDir .. "/readback.tmp"
local statePath = "/data/rw6patch.state"
local baseUrl = "https://raw.githubusercontent.com/Hug-Double-K/RW6-SmallFont-Patcher/main/patches/"
local subPatch = "/font/"
local subOperation = "font-patch"
os.remove(readbackPath)

-- 기기 및 작업 상태
local device, version, productId, operation, packageUrl, currentName
local selectedFont, listView
local itemManifests = {
    "MiSans-Medium/manifest.json",
    "MiSans-Demibold/manifest.json",
    "MiSans-Regular/manifest.json",
}
local phase, itemIndex, fileCount, installPosition = "need-info", 1, #itemManifests, 0
local busy, timer, downloadState = false, nil, nil

-- 최상위 function부 선언
local newButton, log, read, size, exec, jsonString, jsonNumber, chunkInfo, statusComplete, actionName,
    operationId, saveProgress, loadProgress, clearTemporary, rebootWatch, later, runOperation, getInfo,
    checkAvailability, checkStorage, downloadBatch, installCurrent
    , showFonts, selectFont -- 폰트관련추가

local logText = table.concat({
    "레드미워치6 폰트패치 (by. 룽즈)",
    " - 패치방법 : 정보확인 -> 패치",
    " - 복원방법 : 정보확인 -> 복원",
    "",
    " * 내수용의 경우 먼저 '한글패치'가 필요합니다.",
    " * 펌웨어 업데이트를 위한 복원시 내수용의 경우 '한글패치' 복원만 실행하시면 됩니다.",
    "",
    "- 다운로드, 패치중 재부팅이 발생할 수 있으나 당황하지 마시고 이어서 진행해주세요.",
    "- 개발자는 이 툴의 사용으로 발생한 기기 손상 등에 대한 책임을 지지 않습니다.",
}, "\n")

local root = lvgl.Object(nil, {
    w = W, h = H, align = lvgl.ALIGN.CENTER,
    border_width = 0, pad_all = 0, bg_color = "#16181d",
})
root:clear_flag(lvgl.FLAG.SCROLLABLE)
root:add_flag(lvgl.FLAG.EVENT_BUBBLE)

local warning = lvgl.Label(root, {
    x = margin+10, y = 50, w = contentWidth, h = 50,
    text = "* 긴 화면꺼짐시간, 빠른 네트워크 환경으로 작업시 중단위험이 적습니다.",
    text_color = "#ffd166", text_font = lvgl.Font("MiSans-Medium", 18),
})

local logView = lvgl.Textarea(root, {
    x = margin, y = 100, w = contentWidth, h = buttonY - 108,
    text = logText, radius = 10, border_width = 1,
    border_color = "#454a55", bg_color = "#252830",
    text_color = "#e2e5eb", text_font = lvgl.Font("MiSans-Medium", 18),
    pad_all = 12, pad_bottom = 50,
})
logView:add_flag(lvgl.FLAG.SCROLLABLE)

function newButton(index, text, color)
    local object = lvgl.Object(root, {
        x = margin + index * (buttonWidth + gap), y = buttonY,
        w = buttonWidth, h = buttonHeight, radius = 10,
        border_width = 1, border_color = color,
        bg_color = "#252830", pad_all = 0,
    })
    object:clear_flag(lvgl.FLAG.SCROLLABLE)
    object:add_flag(lvgl.FLAG.CLICKABLE)
    local label = lvgl.Label(object, {
        text = text, align = lvgl.ALIGN.CENTER, border_width = 0,
        bg_opa = 0, text_color = color,
        text_font = lvgl.Font("MiSans-Medium", 18),
    })
    return { object = object, label = label }
end

----------------------------- 버튼 -----------------------------

local infoButton = newButton(0, "정보확인", "#71b7ff")
local patchButton = newButton(1, "패치", "#78d99b")
local restoreButton = newButton(2, "복원", "#ff9a91")

-- 정보확인 버튼
infoButton.object:onevent(lvgl.EVENT.CLICKED, function()
    if busy then return end
    log("\n정보확인 중입니다.")
    later(getInfo)
end)

-- 패치 버튼
patchButton.object:onevent(lvgl.EVENT.CLICKED, function()
    runOperation(operation or "patch")
end)

-- 복원 버튼
restoreButton.object:onevent(lvgl.EVENT.CLICKED, function()
    runOperation("restore")
end)

----------------------------- function -----------------------------

-- 로그작성
function log(message)
    logText = logText .. "\n" .. message
    logView:set { text = logText }
end

-- 파일 전체 or 첫 줄을 읽고 필요시 삭제
function read(path, firstLine, remove)
    local file = io.open(path, "rb")
    if not file then return nil end
    local value = file:read(firstLine and "*l" or "*a")
    file:close()
    if remove then os.remove(path) end
    return value
end

-- 파일 크기 바이트 단위 리턴
function size(path)
    local file = io.open(path, "rb")
    if not file then return nil end
    local value = file:seek("end")
    file:close()
    return value
end

-- 쉘 명령 실행후 성공 여부 리턴
function exec(command)
    local result, _, status = os.execute(command)
    return result == 0 or (result == true and (status == nil or status == 0))
end

-- 다운로드 시작 전 /data 여유 공간 확인 (15MiB 초과 필요)
function checkStorage()
    local file = workDir .. "/df"
    os.remove(file)
    local ok = exec("df >" .. file)
    local output = read(file, false, true) or ""
    if ok then
        for line in string.gmatch(output, "[^\r\n]+") do
            local blockSize, available = string.match(line,
                "^%s*(%d+)%s+%d+%s+%d+%s+(%d+)%s+/data%s*$")
            if blockSize then
                if tonumber(blockSize) * tonumber(available) > 15 * 1024 * 1024 then
                    return true
                end
                log("저장공간부족, 워치페이스나 음성녹음의 삭제가 필요합니다.")
                return false
            end
        end
    end
    log("저장공간을 확인하지 못했습니다. 다시 시도해주세요.")
    return false
end

-- manifest json에서 문자, 숫자 확인
function jsonString(json, key) return string.match(json, '"' .. key .. '"%s*:%s*"([^"]+)"') end
function jsonNumber(json, key)
    local value = string.match(json, '"' .. key .. '"%s*:%s*(%d+)')
    return value and tonumber(value) or nil
end

-- 청크 하나의 크기와 기록 위치를 manifest에서 읽기
function chunkInfo(manifest, prefix, index)
    local name = prefix .. ".part" .. string.format("%03d", index)
    local body = string.match(manifest, '"name":"' .. name
        .. '".-"blockSize":%d+,"seek":%d+,"count":%d+')
    if not body then return nil end
    return name, jsonNumber(body, "size"), jsonNumber(body, "blockSize"),
        jsonNumber(body, "seek"), jsonNumber(body, "count")
end

-- 쉘 상태 파일에 모든 청크의 성공 표시가 있는지 확인
function statusComplete(path, prefix, count)
    local status = read(path)
    if not status then return false end
    for index = 0, count - 1 do
        local marker = prefix .. string.format("%03d", index) .. "_OK"
        if not string.find(status, marker, 1, true) then return false end
    end
    return true
end

-- 현재 작업 모드 리턴
function actionName() return operation == "restore" and "복원" or "패치" end

-- 현재 패치 또는 복원의 고유 이름
function operationId()
    return operation == "restore" and subOperation .. ".restore"
        or subOperation .. "." .. selectedFont .. ".patch"
end

-- 재부팅 후 이어갈 작업 정보와 0부터 시작하는 다음 파일 번호 저장
function saveProgress(index)
    local file = io.open(statePath, "wb")
    if not file then return false end
    file:write(operationId(), "\n", productId, "\n", version, "\n", index, "\n")
    file:close()
    return true
end

-- 같은 작업은 이어가고 다른 패치·제품·버전이면 0부터 다시 시작
function loadProgress()
    local state = read(statePath) or ""
    local savedId, savedProduct, savedVersion, savedIndex =
        string.match(state, "([^\n]+)\n([^\n]+)\n([^\n]+)\n(%d+)")
    local index = tonumber(savedIndex)

    if savedId ~= operationId() or savedProduct ~= productId or savedVersion ~= version
        or not index or index < 0 or index > fileCount then
        index = 0
        if not saveProgress(index) then error("진행 상태를 저장할 수 없습니다.") end
    end
    return index
end

-- NuttX 셸의 glob에 의존하지 않고 이전 단계 임시 파일 삭제
function clearTemporary()
    for index = 0, 99 do
        local number = string.format("%03d", index)
        os.remove(downloadDir .. "/patch.part" .. number)
        os.remove(downloadDir .. "/restore.part" .. number)
        os.remove(readbackPath .. index)
    end
    for _, name in ipairs({
        "patch.sh", "patch-verify.sh", "restore.sh", "restore-verify.sh",
        "install.status", "verify.status", "medium.status", "readback.tmp",
    }) do
        os.remove(downloadDir .. "/" .. name)
    end
end

-- 상태 저장 후 화면 로그를 갱신하고 워치 재부팅
function rebootWatch(message)
    log(message .. "\nRebooting in 5 seconds.")
    busy = true
    timer = lvgl.Timer {
        period = 5000, repeat_count = 1,
        cb = function()
            timer = nil
            os.execute("reboot")
        end,
    }
end

-- 로그 먼저 갱신 후 작업실행, 중복 입력 방지
function later(action)
    busy = true
    timer = lvgl.Timer {
        period = 100, repeat_count = 1,
        -- 예약작업의 성공,실패와 비동기 종료 여부 처리
        cb = function()
            timer = nil
            local ok, asynchronous = pcall(action)
            if not ok then
                busy = false
                log("작업에 실패하였습니다. 다시 시도해주세요.\n오류: " .. tostring(asynchronous))
            elseif asynchronous ~= true then
                busy = false
            end
        end,
    }
end

----------------------------- 작업 시작 -----------------------------

-- 전체 작업 제어부
function runOperation(requested)
    if busy then return end
    if phase == "need-info" then
        log("\n정보확인 버튼을 먼저 눌러주세요")
        return
    end
    if operation and operation ~= requested then
        log("\n이미 " .. actionName() .. " 작업에 진입했습니다.")
        return
    end
    operation = operation or requested

    if phase == "ready" then
        if operation == "patch" and not selectedFont then
            log("\n폰트 확인중입니다.")
            later(showFonts)
            return
        end
        log("\n" .. actionName() .. " 확인중입니다.")
        later(checkAvailability)
    elseif phase == "download" then
        log("\n" .. actionName() .. " 다운로드 ("
            .. itemIndex .. "/" .. fileCount .. ")")
        later(downloadBatch)
    elseif phase == "install" then
        log("\n" .. actionName() .. " 진행중 ("
            .. itemIndex .. "/" .. fileCount .. ")")
        later(installCurrent)
    end
end

-- 1 정보확인: 기기명과 펌웨어 버전을 읽어 지원 모델 확인
function getInfo()
    if operation then
        log("\n이미 " .. actionName() .. " 작업이 진행중입니다.")
        return
    end
    if not exec("test -d " .. workDir) and not exec("mkdir " .. workDir) then
        log("\n정보확인에 실패했습니다. 다시 시도해주세요.")
        return
    end
    if not exec("test -d " .. downloadDir) and not exec("mkdir " .. downloadDir) then
        log("\n정보확인에 실패했습니다. 다시 시도해주세요.")
        return
    end

    os.execute("uname -n >" .. workDir .. "/device")
    os.execute("getprop ro.build.version >" .. workDir .. "/version")
    device = read(workDir .. "/device", true, true)
    version = read(workDir .. "/version", true, true)
    productId = device and string.find(device, "REDMI Watch 6", 1, true)
        and "miwear.watch.p65" or nil

    if not productId or not version or version == "" then
        phase = "need-info"
        log("\n정보확인에 실패했습니다. 다시 시도해주세요.")
        return
    end
    phase = "ready"
    log("\ndevice: " .. device .. "\nversion: " .. version)
    log("패치 혹은 복원 버튼을 누르세요")
end

----------------------------- 폰트패치 전용 시작 -----------------------------
-- 1) 폰트 목록 표시 (진행중이던 폰트설치가 있다면 재개)
function showFonts()
    local url = baseUrl .. productId .. subPatch .. version .. "/"
    local listFile = workDir .. "/fonts.txt"
    os.remove(listFile)
    local downloaded = exec("curl " .. url .. "list.txt -o" .. listFile)
    local fonts = {}
    for name in string.gmatch(read(listFile, false, true) or "", "[^\r\n]+") do
        name = string.match(name, "^%s*(.-)%s*$")
        if string.match(name, "^[%w_-][%w_.-]*$") and name ~= "restore" then
            fonts[#fonts + 1] = name
        end
    end
    if not downloaded or #fonts == 0 then
        log("휴대폰과의 연결이 불안정하거나 현재 작업이 불가능한 버전입니다. 다시 시도해보거나 네이버 카페에 문의해주세요.")
        return
    end

    local id, savedProduct, savedVersion, index =
        string.match(read(statePath) or "", "([^\n]+)\n([^\n]+)\n([^\n]+)\n(%d+)")
    if savedProduct == productId and savedVersion == version
        and tonumber(index) and tonumber(index) < fileCount then
        for _, name in ipairs(fonts) do
            if id == subOperation .. "." .. name .. ".patch" then return selectFont(name) end
        end
    end

    logView:add_flag(lvgl.FLAG.HIDDEN)
    infoButton.object:add_flag(lvgl.FLAG.HIDDEN)
    patchButton.object:add_flag(lvgl.FLAG.HIDDEN)
    restoreButton.object:add_flag(lvgl.FLAG.HIDDEN)
    listView = lvgl.Object(root, {
        x = margin, y = 100, w = contentWidth, h = H - 150,
        pad_all = 0, border_width = 0, bg_opa = 0,
    })
    listView:add_flag(lvgl.FLAG.SCROLLABLE)
    table.insert(fonts, 1, "취소")
    for index, name in ipairs(fonts) do
        local button = lvgl.Object(listView, {
            x = 0, y = (index - 1) * (buttonHeight + gap),
            w = contentWidth - 8, h = buttonHeight, radius = 10,
            border_width = 1, border_color = "#71b7ff", bg_color = "#252830", pad_all = 0,
        })
        button:clear_flag(lvgl.FLAG.SCROLLABLE)
        button:add_flag(lvgl.FLAG.CLICKABLE)
        lvgl.Label(button, {
            text = name, align = lvgl.ALIGN.CENTER,
            text_color = "#e2e5eb", text_font = lvgl.Font("MiSans-Medium", 18),
        })
        button:onevent(lvgl.EVENT.CLICKED, function()
            if not busy then selectFont(index ~= 1 and name or nil) end
        end)
    end
end

-- 2) 폰트선택 (취소 시 메인화면 복귀)
function selectFont(name)
    selectedFont = name
    phase = "ready"
    if listView then listView:add_flag(lvgl.FLAG.HIDDEN) end
    logView:clear_flag(lvgl.FLAG.HIDDEN)
    infoButton.object:clear_flag(lvgl.FLAG.HIDDEN)
    patchButton.object:clear_flag(lvgl.FLAG.HIDDEN)
    restoreButton.object:clear_flag(lvgl.FLAG.HIDDEN)
    if not name then
        operation = nil
        log("패치 혹은 복원 버튼을 누르세요")
        return
    end
    log("\n선택: " .. name .. "\n패치 확인중입니다.")
    later(checkAvailability)
    return true
end
----------------------------- 폰트패치 전용 끝 -----------------------------

-- 2 패치확인: 대상 파일 manifest를 각각 내려받아 검증
function checkAvailability()
    packageUrl = baseUrl .. productId .. subPatch .. version .. "/"
        .. (operation == "restore" and "restore" or selectedFont) .. "/"
    for index, manifestPath in ipairs(itemManifests) do
        itemIndex = index
        local path = workDir .. "/file-" .. index .. ".json"
        os.remove(path)
        os.execute("curl " .. packageUrl .. manifestPath .. " -o" .. path)
        local manifest = read(path)
        if not manifest
            or not string.find(manifest, '"format":"rw6-romfs-entry-patch-v1"', 1, true) then
            itemIndex = 1
            phase = "ready"
            log("휴대폰과의 연결이 불안정하거나 현재 작업이 불가능한 버전입니다. 다시 시도해보거나 네이버 카페에 문의해주세요.")
            return
        end
    end
    local progress = loadProgress()
    if progress == fileCount then
        phase = "done"
        log("이미 모든 " .. actionName() .. " 작업이 완료된 상태입니다.")
        return
    end
    itemIndex = progress + 1
    phase = "download"
    infoButton.object:add_flag(lvgl.FLAG.HIDDEN)
    restoreButton.object:add_flag(lvgl.FLAG.HIDDEN)
    patchButton.object:set { x = margin, w = contentWidth }
    patchButton.label:set { text = actionName() }
    log("해당버전 " .. actionName() .. " 작업이 존재합니다. 한번 더 "
        .. actionName() .. "버튼을 눌러주세요.")
end

-- 3 다운로드: 현재 파일의 청크를 한 번에 최대 5개씩 받기
function downloadBatch()
    local manifest = read(workDir .. "/file-" .. itemIndex .. ".json")
    if not manifest then error("manifest를 읽을 수 없습니다.") end
    currentName = string.match(jsonString(manifest, "path") or "", "([^/]+)$")

    local prefix = operation == "restore" and "restore" or "patch"
    local chunkCount = jsonNumber(manifest, prefix .. "ChunkCount")
    if jsonString(manifest, "transfer") ~= "chunks" or not chunkCount then
        error("manifest 정보가 올바르지 않습니다.")
    end

    local signature = operation .. "/" .. itemIndex
    if not downloadState or downloadState.signature ~= signature then
        clearTemporary()
        if not checkStorage() then return end
        downloadState = { signature = signature, index = 0 }
    end
    if downloadState.index == 0 then
        log(currentName .. " 파일을 다운로드 중입니다.")
    end

    local completed = 0

    -- 다음 청크 하나를 예약하고 완료 상태를 갱신
    local function nextChunk()
        local index = downloadState.index
        if index >= chunkCount then
            local installName = jsonString(manifest, prefix .. "Script")
            local installSize = jsonNumber(manifest, prefix .. "ScriptSize")
            local verifyName = jsonString(manifest, prefix .. "VerifyScript")
            local verifySize = jsonNumber(manifest, prefix .. "VerifyScriptSize")
            local directory = string.match(itemManifests[itemIndex], "^(.*)/manifest.json$")
            local installPath = installName and downloadDir .. "/" .. installName or ""
            local verifyPath = verifyName and downloadDir .. "/" .. verifyName or ""
            if not installName or not installSize or not verifyName or not verifySize or not directory
                or not exec("curl " .. packageUrl .. directory .. "/" .. installName .. " -o" .. installPath)
                or size(installPath) ~= installSize
                or not exec("curl " .. packageUrl .. directory .. "/" .. verifyName .. " -o" .. verifyPath)
                or size(verifyPath) ~= verifySize then
                busy = false
                log("실행 파일 다운로드에 실패했습니다. 다시 시도해주세요.")
                return
            end
            phase = "install"
            installPosition = 0
            busy = false
            log("다운로드 완료. " .. actionName() .. "버튼을 눌러주세요.")
            return
        end

        timer = lvgl.Timer {
            period = 100, repeat_count = 1,
            -- 예약된 청크를 패치 전용 임시 폴더에 내려받기
            cb = function()
                timer = nil
                local filename, expected = chunkInfo(manifest, prefix, index)
                local part = downloadDir .. "/" .. (filename or "invalid")
                local directory = string.match(itemManifests[itemIndex], "^(.*)/manifest.json$")
                os.remove(part)
                if not filename or not directory
                    or not exec("curl " .. packageUrl .. directory .. "/" .. filename .. " -o" .. part)
                    or size(part) ~= expected then
                    os.remove(part)
                    busy = false
                    log((filename or "청크") .. " 다운로드에 실패했습니다. 다시 시도해주세요.")
                    return
                end
                downloadState.index = index + 1
                completed = completed + 1
                log("다운로드중 " .. downloadState.index .. "/" .. chunkCount)
                if downloadState.index >= chunkCount then
                    nextChunk()
                elseif completed >= 5 then
                    busy = false
                    log("다운로드를 계속하려면 " .. actionName() .. "버튼을 눌러주세요.")
                else
                    nextChunk()
                end
            end,
        }
    end

    nextChunk()
    return true
end

-- 4 설치: JS가 생성한 쉘로 전체 기록 후 다음 타이머에서 전체 검증
function installCurrent()
    local manifest = read(workDir .. "/file-" .. itemIndex .. ".json")
    log(currentName .. " 파일을 " .. actionName() .. "중입니다.")

    local prefix = operation == "restore" and "restore" or "patch"
    local chunkCount = manifest and jsonNumber(manifest, prefix .. "ChunkCount")
    local installName = manifest and jsonString(manifest, prefix .. "Script")
    local verifyName = manifest and jsonString(manifest, prefix .. "VerifyScript")
    if jsonString(manifest or "", "transfer") ~= "chunks"
        or not chunkCount or not installName or not verifyName then
        error("manifest가 올바르지 않습니다.")
    end

    for index = 0, chunkCount - 1 do
        local filename, expected = chunkInfo(manifest, prefix, index)
        local part = filename and downloadDir .. "/" .. filename or ""
        if not filename or size(part) ~= expected then error("분할 파일이 올바르지 않습니다.") end
    end

    local installPath = downloadDir .. "/" .. installName
    local verifyPath = downloadDir .. "/" .. verifyName
    local installStatus = downloadDir .. "/install.status"
    local verifyStatus = downloadDir .. "/verify.status"
    os.remove(installStatus)
    os.execute("sh " .. installPath)
    if not statusComplete(installStatus, "W", chunkCount) then
        error("전체 기록에 실패했습니다.")
    end

    timer = lvgl.Timer {
        period = 100, repeat_count = 1,
        cb = function()
            timer = nil
            os.remove(verifyStatus)
            os.execute("sh " .. verifyPath)
            if not statusComplete(verifyStatus, "V", chunkCount) then
                busy = false
                log("전체 검증에 실패했습니다. 다시 시도해주세요.")
                return
            end
            clearTemporary()
            downloadState = nil
            if not saveProgress(itemIndex) then
                busy = false
                log("진행 상태를 저장할 수 없습니다.")
                return
            end
            if not exec("rmdir " .. downloadDir) then
                log("임시 폴더를 삭제하지 못했습니다.")
            end
            phase = "reboot"
            if itemIndex == fileCount then
                rebootWatch("All patches are complete.")
            else
                rebootWatch(currentName .. " file " .. operation .. " is complete.")
            end
        end,
    }
    return true
end
