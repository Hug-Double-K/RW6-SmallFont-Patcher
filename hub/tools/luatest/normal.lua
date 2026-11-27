-- 패처의 원래 길이 그대로인지: 취소(nil)와 보통 이름을 골랐을 때 조사용 고리를 타지 않아야 함
local mock = dofile("mock.lua")
package.preload["lvgl"] = function() return mock end
local curls = {}
os.execute = function(cmd) curls[#curls + 1] = cmd return 0 end
local repo = "/home/claude/rw6-smallfont-patcher/"
local src = io.open(repo .. "lua/rw6-font.lua", "rb"):read("*a") .. "\n_G.__selectFont = selectFont\n"
assert(load(src, "rw6-font.lua"))()
local logView
for _, o in ipairs(mock.created) do if o.kind == "Textarea" then logView = o end end
__selectFont(nil)
assert(string.find(logView.props.text, "패치 혹은 복원 버튼을 누르세요", 1, true))
for _, c in ipairs(curls) do assert(not string.find(c, "/lua/", 1, true), "취소인데 조사 스크립트를 받음") end
local ok = pcall(__selectFont, "MapTest")     -- 'Test'로 시작하지 않으므로 고리를 타면 안 됨
mock.pump()
for _, c in ipairs(curls) do assert(not string.find(c, "/lua/", 1, true), "MapTest가 조사 고리를 탐: " .. c) end
assert(string.find(logView.props.text, "선택: MapTest", 1, true))
print("원래 길 그대로 통과")
