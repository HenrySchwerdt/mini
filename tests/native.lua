-- End-to-end tests exercise the public CLI, generated C, LLVM IR and executables.
function main(target)
    local compiler = path.absolute(target:targetfile())
    local directory = os.tmpfile() .. " mini native"
    os.mkdir(directory)
    local suffix = target:is_plat("windows", "mingw") and ".exe" or ""
    local function fixture(name)
        return path.join(os.projectdir(), name)
    end
    local function build(name, sources, expected, extra)
        local output = path.join(directory, name .. suffix)
        local arguments = {"build"}
        for _, source in ipairs(sources) do table.insert(arguments, fixture(source)) end
        table.join2(arguments, extra or {}, {"-o", output})
        os.iorunv(compiler, arguments)
        local actual = os.iorunv(output, {}, {timeout = 30000}):gsub("\r\n", "\n")
        assert(actual == expected, "%s: expected %s, got %s", name, expected, actual)
    end
    local ok, failure
    try {
        function ()
            local collection_dependencies = {
                "--dep", "collections=" .. fixture("std/mini/collections/list.mini"),
                "--dep", "collections=" .. fixture("std/mini/collections/array_list.mini"),
                "--dep", "memory=" .. fixture("std/mini/memory/allocator.mini"),
                "--depends", "collections=memory"
            }
            build("collections", {"examples/collections.mini"}, "42\n", collection_dependencies)
            build("collections-errors", {"examples/collections_errors.mini"}, "42\n", collection_dependencies)
            local function reject_collection(name, body)
                local source = path.join(directory, name .. ".mini")
                io.writefile(source, body)
                local arguments = {"check", source}
                table.join2(arguments, collection_dependencies)
                local accepted = try {function () os.iorunv(compiler, arguments); return true end}
                assert(not accepted, "%s was accepted", name)
            end
            reject_collection("readonly-list-add", [[
                import mini.collections.List
                class Main {
                    static invalid(values: *List<i32>): void { values.add(42) }
                }
            ]])
            reject_collection("private-list-buffer", [[
                import mini.collections.ArrayList
                class Main {
                    static invalid(values: *ArrayList<i32>): void { values.buffer = Optional.None }
                }
            ]])
            build("objects", {"examples/objects.mini"}, "42\n", {
                "--dep", "memory=" .. fixture("std/mini/memory/allocator.mini")
            })
            build("lambdas", {"examples/lambdas.mini"}, "42\n", {
                "--dep", "core=" .. fixture("std/mini/core/functions.mini")
            })
            build("optional-memory", {"examples/optional_memory.mini"}, "42\n")
            local concurrency_dependencies = {
                "--dep", "concurrent=" .. fixture("std/mini/concurrent/thread.mini"),
                "--dep", "concurrent=" .. fixture("std/mini/concurrent/mutex.mini")
            }
            build("threads", {"examples/threads.mini"}, "42\n", concurrency_dependencies)
            local net_dependencies = {
                "--dep", "net=" .. fixture("std/mini/net/socket.mini"),
                "--dep", "concurrent=" .. fixture("std/mini/concurrent/thread.mini"),
                "--dep", "concurrent=" .. fixture("std/mini/concurrent/mutex.mini")
            }
            build("net", {"examples/net.mini"}, "42\n", net_dependencies)
            build("math", {"examples/math.mini"}, "42\n", {
                "--dep", "math=" .. fixture("std/mini/math/math.mini")
            })
            build("pairs", {"examples/pairs.mini"}, "42\n", {
                "--dep", "core=" .. fixture("std/mini/core/pair.mini")
            })
            build("ranges", {"examples/ranges.mini"}, "42\n", {
                "--dep", "core=" .. fixture("std/mini/core/ranges.mini")
            })
            local bad_uv = {"build", fixture("examples/threads.mini")}
            table.join2(bad_uv, concurrency_dependencies, {
                "--libuv-root", path.join(directory, "missing libuv prefix"),
                "-o", path.join(directory, "invalid-uv" .. suffix)
            })
            local accepted_uv = try {function () os.iorunv(compiler, bad_uv); return true end}
            assert(not accepted_uv, "invalid explicit libuv prefix was accepted")
            build("kotlin-syntax", {"examples/kotlin.mini"}, "42\n2\n")
            build("generics", {"examples/generics.mini"}, "42\n")
            build("generic-methods", {"examples/generic_methods.mini"}, "42\n7\n10\n5\n")
            build("inheritance", {"examples/inheritance.mini"}, "42\n42\n")
            build("variants", {"examples/variants.mini"}, "42\n8\n9\n")
            build("annotations", {"examples/annotations.mini"}, "42\n")
            build("packages", {"examples/packages/main.mini", "examples/packages/answer.mini"}, "42\n")
            build("stdlib", {"examples/stdlib.mini"}, "42\n", {
                "--dep", "core=" .. fixture("std/mini/core/value.mini"),
                "--dep", "collections=" .. fixture("std/mini/collections/cell.mini"),
                "--dep", "memory=" .. fixture("std/mini/memory/allocator.mini"),
                "--dep", "io=" .. fixture("std/mini/io/console.mini"),
                "--depends", "collections=core"
            })
            local source = path.join(directory, "control.mini")
            io.writefile(source, [[
                class Main {
                    static next(value: *i32) -> i32 { *value = *value + 1; return *value; }
                    static main() -> i32 {
                        let count: i32 = 0;
                        let total: i32 = 0;
                        while count < 4 { total = total + Main.next(&count); }
                        if total == 9 { print_i32(0); }
                        else if Main.next(&count) == 5 { print_i32(total); }
                        else { print_i32(-1); }
                        let skipped: bool = false && Main.next(&count) > 0;
                        print_i32(count);
                        return 0;
                    }
                }
            ]])
            local executable = path.join(directory, "control" .. suffix)
            os.iorunv(compiler, {"build", source, "-o", executable})
            assert(os.iorunv(executable, {}):gsub("\r\n", "\n") == "10\n5\n", "control flow or short-circuit failed")
            local ir = path.join(directory, "output.ll")
            os.iorunv(compiler, {"emit-llvm", fixture("examples/variants.mini"), "-o", ir})
            assert(io.readfile(ir):find("define", 1, true), "LLVM IR was not emitted")
            local cfile = path.join(directory, "output.c")
            os.iorunv(compiler, {"emit-c", fixture("examples/variants.mini"), "-o", cfile})
            assert(io.readfile(cfile):find("int main", 1, true), "C entry point was not emitted")
            local bad = path.join(directory, "bad.mini")
            io.writefile(bad, "main() -> i32 { return 0; }")
            local accepted = try {function () os.iorunv(compiler, {"check", bad}); return true end}
            assert(not accepted, "top-level function was accepted")
            ok = true
        end,
        catch {function (err) failure = err end}
    }
    os.rm(directory)
    assert(ok, "%s", failure or "native integration test failed")
    return true
end
