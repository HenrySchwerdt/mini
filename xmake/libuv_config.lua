-- Record the resolved package toolchain as argv entries, never shell text.
function main(target)
    local dependency = assert(target:pkg("libuv"), "libuv package is missing")
    local config = {}
    for _, key in ipairs({"includedirs", "sysincludedirs", "defines", "linkdirs", "links", "syslinks", "frameworks"}) do
        config[key] = dependency:get(key)
    end
    local function strings(values)
        local result = {}
        for _, value in ipairs(values) do
            table.insert(result, '"' .. value:gsub('\\', '\\\\'):gsub('"', '\\"'):gsub('\n', '\\n'):gsub('\r', '\\r') .. '"')
        end
        table.insert(result, "NULL")
        return table.concat(result, ",\n")
    end
    local compile, link = {}, {}
    for _, value in ipairs(table.wrap(config.includedirs)) do table.insert(compile, "-I" .. value) end
    for _, value in ipairs(table.wrap(config.sysincludedirs)) do table.insert(compile, "-I" .. value) end
    for _, value in ipairs(table.wrap(config.defines)) do table.insert(compile, "-D" .. value) end
    for _, value in ipairs(table.wrap(config.linkdirs)) do table.insert(link, "-L" .. value) end
    for _, value in ipairs(table.wrap(config.links)) do table.insert(link, "-l" .. value) end
    for _, value in ipairs(table.wrap(config.syslinks)) do table.insert(link, "-l" .. value) end
    for _, value in ipairs(table.wrap(config.frameworks)) do
        table.insert(link, "-framework")
        table.insert(link, value)
    end
    assert(#compile > 0 and #link > 0, "libuv package has no compile/link configuration")
    local text = "/* Generated from xmake's resolved libuv package. */\n" ..
        "static const char* const mini_uv_compile_flags[] = {\n" .. strings(compile) .. "};\n" ..
        "static const char* const mini_uv_link_flags[] = {\n" .. strings(link) .. "};\n"
    local directory = path.join(target:autogendir(), "mini-config")
    os.mkdir(directory)
    local output = path.join(directory, "mini_uv_config.h")
    if not os.isfile(output) or io.readfile(output) ~= text then io.writefile(output, text) end
    target:add("includedirs", directory)
end
