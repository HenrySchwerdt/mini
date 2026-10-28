
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
