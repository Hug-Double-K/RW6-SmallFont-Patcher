local lvgl = require("lvgl")

local W, H = lvgl.HOR_RES(), lvgl.VER_RES()
local sourcePath = "https://raw.githubusercontent.com/Hug-Double-K/RW6-SmallFont-Patcher/main/lua/rw6-font.lua"
local remotePath = "/tmp/rw6-main.lua"
local busy, timer = false, nil

local root = lvgl.Object(nil, {
    w = W - 40, h = 260, align = lvgl.ALIGN.CENTER,
    border_width = 1, border_color = "#454a55",
    radius = 10, pad_all = 0, bg_color = "#252830",
})
root:clear_flag(lvgl.FLAG.SCROLLABLE)
root:clear_flag(lvgl.FLAG.CLICKABLE)

local status = lvgl.Label(root, {
    x = 12, y = 30, w = W - 64, h = 130,
    text = "최신 패치 프로그램을 불러오는 중입니다.",
    text_color = "#e2e5eb", text_font = lvgl.Font("MiSans-Medium", 18),
})

local retryButton = lvgl.Object(root, {
    x = 12, y = 175, w = W - 64, h = 60,
    radius = 10, border_width = 1, border_color = "#71b7ff",
    bg_color = "#252830", pad_all = 0,
})
retryButton:clear_flag(lvgl.FLAG.SCROLLABLE)
retryButton:add_flag(lvgl.FLAG.CLICKABLE)
retryButton:add_flag(lvgl.FLAG.HIDDEN)

lvgl.Label(retryButton, {
    text = "새로고침", align = lvgl.ALIGN.CENTER,
    text_color = "#71b7ff", text_font = lvgl.Font("MiSans-Medium", 18),
})

local function fail(message)
    busy = false
    status:set {
        text = "프로그램을 불러오지 못했습니다.\n\n" .. message,
        text_color = "#ff9a91",
    }
    retryButton:clear_flag(lvgl.FLAG.HIDDEN)
end

local function loadRemote()
    if busy then return end
    busy = true
    retryButton:add_flag(lvgl.FLAG.HIDDEN)
    status:set {
        text = "최신 패치 프로그램을 불러오는 중입니다.",
        text_color = "#e2e5eb",
    }

    timer = lvgl.Timer {
        period = 100, repeat_count = 1,
        cb = function()
            timer = nil
            os.remove(remotePath)
            os.execute("curl " .. sourcePath .. " -o " .. remotePath)

            local file = io.open(remotePath, "rb")
            if not file then
                fail("휴대폰 연결 후 새로고침을 눌러주세요.")
                return
            end
            local fileSize = file:seek("end")
            file:close()
            if not fileSize or fileSize == 0 then
                fail("빈 파일을 받았습니다. 새로고침을 눌러주세요.")
                return
            end

            local chunk, loadError = loadfile(remotePath)
            if not chunk then
                fail("Lua 로드 오류: " .. tostring(loadError))
                return
            end
            local ok, runError = pcall(chunk)
            if not ok then
                fail("Lua 실행 오류: " .. tostring(runError))
                return
            end

            root:add_flag(lvgl.FLAG.HIDDEN)
            busy = false
        end,
    }
end

retryButton:onevent(lvgl.EVENT.CLICKED, function()
    loadRemote()
end)

loadRemote()
