
target("mini_runtime")
    set_kind("static")

    add_includedirs("include", {public = true})

    add_files("src/*.c")

    set_default(true)

-- Runtime unit tests
for _, file in ipairs(os.files("tests/test_*.c")) do
    local name = "runtime_" .. path.basename(file)

    target(name)
        set_kind("binary")
        set_default(false)

        add_deps("mini_runtime")
        add_packages("unity_test")

        add_files(file)
        add_tests("default", {
            group = "runtime"
        })
end

-- libminirt: C17 runtime for Kotlin/Mini (see minirt/README.md)
target("minirt")
    set_kind("static")
    set_languages("c17")

    add_includedirs("minirt/include", {public = true})

    -- entry.c (C `main`) is kept out of the library so tests can define their own.
    add_files("minirt/src/*.c")
    remove_files("minirt/src/entry.c")

    set_default(false)

-- libminirt tests: dependency-free harness, one binary per minirt/tests/test_*.c
for _, file in ipairs(os.files("minirt/tests/test_*.c")) do
    local name = "minirt_" .. path.basename(file)

    -- TODO: test_threads uses pthreads; port it to libuv threads to cover the MSVC atomics path.
    if path.basename(file) == "test_threads" and is_plat("windows") then
        goto continue
    end

    target(name)
        set_kind("binary")
        set_languages("c17")
        set_default(false)

        add_deps("minirt")
        add_includedirs("minirt/src")

        add_files(file)
        -- test_entry relies on the runtime's own C `main`
        if path.basename(file) == "test_entry" then
            add_files("minirt/src/entry.c")
        end
        if is_plat("linux", "macosx") then
            add_syslinks("pthread", "m")
        end
        add_tests("default", {
            group = "minirt"
        })
    ::continue::
end
