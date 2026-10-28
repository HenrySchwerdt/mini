-- bootstrap/xmake.lua

local sources = {
    "src/core/*.c",
    "src/frontend/*.c",
    "src/backend/*.c",
    "src/driver/*.c"
}

local has_sources = false

for _, pattern in ipairs(sources) do
    if #os.files(path.join(os.scriptdir(), pattern)) > 0 then
        has_sources = true
        break
    end
end

if has_sources then
    target("mini_bootstrap")
        set_kind("static")
        add_includedirs("include", {public = true})
        add_files(table.unpack(sources))
end

target("mini0")
    set_kind("binary")
    add_includedirs("include")
    add_files("src/cli/*.c")

    if has_sources then
        add_deps("mini_bootstrap")
    end