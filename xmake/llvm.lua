option("llvm_config")
    set_showmenu(true)
    set_description("Path to llvm-config for the LLVM installation to use")
option_end()

option("llvm_link")
    set_default("shared")
    set_values("shared", "static")
    set_showmenu(true)
    set_description("LLVM library linkage (must be available in the selected installation)")
option_end()

rule("mini.llvm")
    on_load(function (target)
        import("lib.detect.find_tool")

        local configured = get_config("llvm_config") or os.getenv("LLVM_CONFIG")
        local llvm
        if configured then
            -- An explicit installation must never silently fall back to another one.
            assert(os.isfile(configured), "llvm-config does not exist: %s", configured)
            llvm = {program = configured}
        else
            local root = os.getenv("LLVM_ROOT")
            if root then
                local executable = is_host("windows") and "llvm-config.exe" or "llvm-config"
                local program = path.join(root, "bin", executable)
                assert(os.isfile(program), "LLVM_ROOT has no llvm-config: %s", program)
                llvm = {program = program}
            else
                llvm = find_tool("llvm-config")
                if not llvm and is_host("macosx") then
                    local brew = find_tool("brew")
                    if brew then
                        local prefix = try {function ()
                            return os.iorunv(brew.program, {"--prefix", "llvm"})
                        end}
                        if prefix then
                            local program = path.join(prefix:trim(), "bin", "llvm-config")
                            if os.isfile(program) then
                                llvm = {program = program}
                            end
                        end
                    end
                end
            end
        end
        assert(llvm, "LLVM not found. Set --llvm_config=/path/to/llvm-config, LLVM_CONFIG or LLVM_ROOT; or configure --llvm=n for frontend/runtime only.")

        local function query(args)
            local output = os.iorunv(llvm.program, args)
            return output:trim()
        end

        local version = query({"--version"})
        local includedir = query({"--includedir"})
        assert(os.isfile(path.join(includedir, "llvm-c", "Core.h")),
            "LLVM C headers not found in %s", includedir)

        local linkage = "--link-" .. get_config("llvm_link")
        local components = {"core", "analysis", "bitwriter", "native", "nativecodegen"}
        local libraries = os.argv(query(table.join(
            {linkage, "--quote-paths", "--libfiles"}, components)))
        assert(#libraries > 0, "llvm-config returned no LLVM libraries")
        for _, library in ipairs(libraries) do
            assert(os.isfile(library), "LLVM library not found: %s", library)
        end

        target:add("sysincludedirs", includedir, {public = true})
        target:add("links", libraries, {public = true})
        local linker_flags = os.argv(query({"--quote-paths", "--ldflags"}))
        if #linker_flags > 0 then
            target:add("ldflags", linker_flags, {force = true, public = true})
        end
        local system_flags = os.argv(query(table.join(
            {linkage, "--quote-paths", "--system-libs"}, components)))
        if #system_flags > 0 then
            target:add("ldflags", system_flags, {force = true, public = true})
        end

        if get_config("llvm_link") == "static" then
            -- Homebrew's llvm-config names dependencies such as -lzstd without
            -- their search directory. Scope the discovered prefix to this target.
            if is_host("macosx") then
                local brew = find_tool("brew")
                if brew then
                    local prefix = os.iorunv(brew.program, {"--prefix"}):trim()
                    if llvm.program:startswith(prefix .. path.sep()) then
                        target:add("linkdirs", path.join(prefix, "lib"), {public = true})
                    end
                end
            end
            -- llvm-config does not report the C++ standard library needed by a C client.
            if target:is_plat("macosx", "bsd") then
                target:add("syslinks", "c++", {public = true})
            elseif target:is_plat("linux", "mingw") then
                target:add("syslinks", "stdc++", {public = true})
            end
        elseif target:is_plat("macosx", "linux", "bsd") then
            target:add("rpathdirs", query({"--libdir"}), {public = true})
        end
        cprint("${dim}Mini LLVM: %s (%s, %s)", version, llvm.program, get_config("llvm_link"))
    end)
rule_end()
