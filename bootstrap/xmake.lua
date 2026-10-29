
includes("../xmake/llvm.lua")

option("llvm")
    set_default(true)
    set_showmenu(true)
    set_description("Build the LLVM backend (disable for frontend/runtime development)")
option_end()

-- Compiler library
target("mini_frontend")
    set_kind("static")

    add_includedirs("include", {public = true})

    add_files(
        "src/core/*.c",
        "src/frontend/*.c",
        "src/lowering/*.c"
    )

if has_config("llvm") then
    target("mini_llvm")
        set_kind("static")
        add_deps("mini_frontend")
        add_files("src/backend/*.c")
        add_rules("mini.llvm")

    target("test_llvm_link")
        set_kind("binary")
        set_default(false)
        add_files("tests/llvm/test_link.c")
        add_rules("mini.llvm")
        add_tests("default", {group = "llvm"})
end

target("mini_bootstrap")
    set_kind("static")
    add_deps("mini_frontend", {public = true})
    add_files("src/driver/*.c")
    add_packages("libuv")
    on_config(function (target)
        import("xmake.libuv_config", {rootdir = os.projectdir()})(target)
    end)
    if has_config("llvm") then
        add_deps("mini_llvm", {public = true})
    end

-- Bootstrap CLI
target("mini0")
    set_kind("binary")
    -- Consumers generate fixtures with the freshly linked compiler.
    set_policy("build.fence", true)
    set_rundir("$(projectdir)")

    add_deps("mini_bootstrap")
    add_files("src/cli/*.c")
    add_tests("native", {group = "integration"})
    on_test(function (target, opt)
        import("tests.native", {rootdir = os.projectdir()})(target, opt)
        return true
    end)

-- Unit tests
for _, file in ipairs(os.files("tests/test_*.c")) do
    local name = path.basename(file)

    target(name)
        set_kind("binary")
        set_default(false)

        add_deps("mini_frontend")
        add_packages("unity_test")

        add_files(file)
        add_tests("default", {
            group = "bootstrap"
        })
end

-- Exercise the emitted adapter itself, including deterministic allocation and
-- thread/condition creation failures; no duplicate runtime implementation.
target("test_uv_runtime")
    set_kind("binary")
    set_default(false)
    add_deps("mini0")
    add_packages("libuv")
    add_files("../tests/uv_runtime.c")
    add_tests("default", {group = "integration"})
    on_load(function (target)
        target:add("includedirs", path.join(target:autogendir(), "uv-fixture"))
    end)
    before_build(function (target)
        local directory = path.join(target:autogendir(), "uv-fixture")
        os.mkdir(directory)
        os.iorunv(path.absolute(target:dep("mini0"):targetfile()), {
            "emit-c", path.join(os.projectdir(), "tests/fixtures/uv_runtime.mini"),
            "-o", path.join(directory, "mini_uv_fixture.c")
        })
    end)
