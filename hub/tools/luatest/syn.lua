for _, f in ipairs({ "/home/claude/rw6-smallfont-patcher/lua/rw6-font.lua", "/home/claude/rw6-smallfont-patcher/lua/Test-Crown.lua" }) do
    local c, e = loadfile(f) print(f:match("[^/]+$"), c and "문법 OK" or e)
end
