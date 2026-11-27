-- 가짜 lvgl: 워치 없이 스크립트를 끝까지 돌려 보기 위한 것
local M = {}
local SCEN = os.getenv("SCEN") or "A"
M.created, M.timers = {}, {}
local function obj(kind, parent, props)
    local o = { kind = kind, props = props or {}, handlers = {}, flags = {} }
    function o:set(p) for k, v in pairs(p) do self.props[k] = v end end
    function o:add_flag(f) assert(f ~= nil, "nil flag") self.flags[f] = true end
    function o:clear_flag(f) assert(f ~= nil, "nil flag") self.flags[f] = nil end
    function o:onevent(code, fn) assert(code ~= nil, "nil event code") assert(type(fn) == "function") self.handlers[#self.handlers + 1] = { code = code, fn = fn } end
    function o:fire(code, passCode)
        for _, h in ipairs(self.handlers) do
            if h.code == code or h.code == M.EVENT.ALL then
                if passCode == false then h.fn(self) else h.fn(self, code) end
            end
        end
    end
    M.created[#M.created + 1] = o
    return o
end
M.Object = function(p, props) return obj("Object", p, props) end
M.Label = function(p, props) assert(p, "Label needs parent") return obj("Label", p, props) end
M.Textarea = function(p, props) return obj("Textarea", p, props) end
M.Font = function(name, size) return { name = name, size = size } end
M.Timer = function(t) M.timers[#M.timers + 1] = t return t end
M.HOR_RES = function() return 432 end
M.VER_RES = function() return 514 end
M.FLAG = { SCROLLABLE = 1, CLICKABLE = 2, HIDDEN = 3, EVENT_BUBBLE = 4 }
M.ALIGN = { CENTER = 9 }
M.EVENT = { CLICKED = 7, PRESSED = 1, RELEASED = 8, KEY = 13, GESTURE = 12, SCROLL = 11, DRAW_MAIN = 21, STYLE_CHANGED = 40, FOCUSED = 14 }
if SCEN == "A" or SCEN == "C" then
    M.EVENT.ALL = 0
    local function dev(t) return { get_type = function() return t end, get_key = function() return 19 end, get_gesture_dir = function() return 2 end } end
    local list = { dev(1), dev(4) }
    M.indev = {
        get_next = function(cur) if cur == nil then return list[1] end for i, d in ipairs(list) do if d == cur then return list[i + 1] end end end,
        get_act = function() return list[2] end,
    }
    M.group = { create = function() end, get_default = function() end }
elseif SCEN == "B" then
    -- ALL 없음, indev/group 없음
elseif SCEN == "E" then
    -- 표가 아닌 것처럼(이름을 나열할 수 없음) 보이는 EVENT·indev
    local real = M.EVENT
    M.EVENT = setmetatable({}, { __index = real, __pairs = function() error("no pairs") end })
    local d = setmetatable({}, { __index = { get_type = function() return 4 end, get_key = function() return 20 end, get_gesture_dir = function() return 0 end } })
    M.indev = setmetatable({}, { __index = { get_act = function() return d end } })
elseif SCEN == "D" then
    M.EVENT.ALL = 0
    M.indev = { get_act = function() error("boom") end, get_next = function() error("boom") end }   -- 부르면 오류 나는 경우
    M.group = "userdata?"
end
function M.pump() local t = M.timers M.timers = {} for _, x in ipairs(t) do x.cb() end end
return M
