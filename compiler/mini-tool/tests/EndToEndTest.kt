package mini.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `mini` against the real compiler: every test here compiles with minic-native and clang. Skipped when the native toolchain is not staged
 * (`scripts/build-mini.sh`, then it lives in `build/native`) or there is no C compiler.
 */
class EndToEndTest {
    private fun runBinary(p: TestPlatform, rel: String, vararg args: String): ExecResult =
        p.exec(Exec(listOf(p.file(rel).path) + args.toList()))

    @Test
    fun appWithPathLibraryBuildsRunsAndRebuildsIncrementally() {
        if (!(TestPlatform.toolchainAvailable())) return
        val p = TestPlatform.temp()
        assertEquals(0, p.mini("new", "geometry", "--lib", "--no-git"))
        assertEquals(0, p.mini("new", "hello", "--no-git"))
        p.dir = File(p.root, "hello")
        assertEquals(0, p.mini("add", "--path", "../geometry"))
        p.write("src/main.kt", "import geometry.*\n\nfun main(args: Array<String>) {\n    println(greeting(\"world\"))\n    println(\"2 + 3 = \${add(2, 3)}\")\n    println(\"args=\" + args.size)\n}\n")

        p.reset()
        assertEquals(0, p.mini("build"), p.all())
        assertTrue(p.err.contains("Compiling geometry v0.1.0 (../geometry)") && p.err.contains("Compiling hello v0.1.0 (.)"), p.err.toString())
        assertTrue(p.err.contains("Finished debug build in"), p.err.toString())
        val r = runBinary(p, "build/debug/hello", "a", "b")
        assertEquals(0, r.exitCode)
        assertEquals("Hello, world!\n2 + 3 = 5\nargs=2\n", r.stdout)

        // nothing changed: no compiler run
        p.reset()
        assertEquals(0, p.mini("build"), p.all())
        assertTrue(p.err.contains("Fresh hello v0.1.0 (no changes)") && !p.err.contains("Compiling"), p.err.toString())

        // a change in the dependency invalidates the fingerprint
        p.write("../geometry/src/lib.kt", "package geometry\n\nfun add(a: Int, b: Int): Int = a + b + 1\n")
        p.reset()
        assertEquals(0, p.mini("build"), p.all())
        assertTrue(p.err.contains("Compiling hello"), p.err.toString())
        assertEquals("Hello, world!\n2 + 3 = 6\nargs=0\n", runBinary(p, "build/debug/hello").stdout)

        // a library builds on its own and is verified by compilation
        p.dir = File(p.root, "geometry")
        p.reset()
        assertEquals(0, p.mini("build"), p.all())
        assertTrue(p.err.contains("Compiling geometry v0.1.0"), p.err.toString())

        // clean
        p.dir = File(p.root, "hello")
        assertEquals(0, p.mini("clean"))
        assertTrue(!File(p.dir, "build").exists())
    }

    @Test
    fun testRunsPassingAndFailingTestsAndReports() {
        if (!(TestPlatform.toolchainAvailable())) return
        val p = TestPlatform.temp()
        assertEquals(0, p.mini("new", "calc", "--no-git"))
        p.dir = File(p.root, "calc")
        p.write("tests/extra_test.kt", """
            import kotlin.test.*

            @Test
            fun passes() {
                assertTrue(1 < 2)
                assertFailsWith<IllegalStateException> { error("boom") }
            }

            @Test
            fun fails() {
                println("about to fail")
                assertEquals(3, 1 + 1, "math is hard")
            }
        """.trimIndent() + "\n")
        p.reset()
        assertEquals(1, p.mini("test"), p.all())
        val out = p.out.toString()
        assertTrue(out.contains("test tests/greeting_test.kt::greetsTheWorld ... ok"), out)
        assertTrue(out.contains("test tests/extra_test.kt::passes ... ok"), out)
        assertTrue(out.contains("test tests/extra_test.kt::fails ... FAILED"), out)
        assertTrue(out.contains("---- tests/extra_test.kt::fails (tests/extra_test.kt:10) ----") && out.contains("about to fail") && out.contains("math is hard. Expected <3>, actual <2>."), out)
        assertTrue(out.contains("test result: FAILED. 3 passed; 1 failed; 0 ignored;"), out)
        assertTrue((out.contains("... ok (") && out.contains("ms)")), out)

        // a filter selects tests by name and the binary is reused (no rebuild)
        p.reset()
        assertEquals(0, p.mini("test", "greets"), p.all())
        assertTrue(p.out.contains("test result: ok. 2 passed; 0 failed;") && p.err.contains("Fresh"), p.all())
        p.reset()
        assertEquals(1, p.mini("test", "fails"), p.all())
        assertTrue(p.out.contains("1 passed; 0 failed") || p.out.contains("0 passed; 1 failed"), p.out.toString())
    }

    @Test
    fun strictDefaultFromTheManifestProducesAnMDiagnostic() {
        if (!(TestPlatform.toolchainAvailable())) return
        val p = TestPlatform.temp()
        assertEquals(0, p.mini("new", "leaky", "--strict", "--no-git"))
        p.dir = File(p.root, "leaky")
        File(p.dir, "src/greeting.kt").delete(); File(p.dir, "tests/greeting_test.kt").delete()
        p.write("src/main.kt", File(TestPlatform.REPO_ROOT, "examples/memory_leak.kt").readText().lines().joinToString("\n") { if (it.startsWith("@file:")) "" else it })
        p.reset()
        assertEquals(1, p.mini("check"), p.all())
        assertTrue(p.err.contains("error[M0102]: allocation from `Heap` is never released"), p.err.toString())
        assertTrue(p.err.contains("--> src/main.kt:14:15"), p.err.toString()) // project-relative path, not the overlay copy
        assertTrue(p.err.contains("could not check `leaky`"), p.err.toString())
        assertTrue(!p.err.contains("could not compile main.kt") && !p.err.contains("1 error, 0 warnings"), p.err.toString()) // minic-native's own summary is dropped
        assertEquals(1, (p.err.toString().split("error[M0102]").size - 1), p.err.toString())

        // editors get the same as JSON lines
        p.reset()
        assertEquals(1, p.mini("check", "--message-format=json"), p.all())
        val lines = p.out.toString().trim().lines()
        assertTrue(lines[0].contains("\"code\":\"M0102\"") && lines[0].contains("\"file\":\"src/main.kt\",\"line\":14,\"column\":15"), lines[0])
        assertTrue(lines.last().contains("\"reason\":\"build-finished\"") && lines.last().contains("\"success\":false"), lines.last())
        assertEquals("", p.err.toString().trim())

        // the same code is fine in a managed project, and `--strict` turns the checker on from the command line
        p.write("mini.toml", "[package]\nname = \"leaky\"\nkind = \"app\"\n")
        p.reset()
        assertEquals(0, p.mini("check"), p.all())
        p.reset()
        assertEquals(1, p.mini("check", "--strict"), p.all())
        assertTrue(p.err.contains("M0102"), p.err.toString())
    }

    @Test
    fun gitDependencyIsBuiltFromTheCache() {
        if (!(TestPlatform.toolchainAvailable() && TestPlatform.gitAvailable())) return
        val p = TestPlatform.temp()
        val (_, url) = GitDependencyTest.makeRemote(p.root, "package json\n\nfun parse(s: String): Int = s.length * 10\n")
        assertEquals(0, p.mini("new", "app", "--no-git"))
        p.dir = File(p.root, "app")
        assertEquals(0, p.mini("add", "--git", url, "--tag", "v1.0"), p.err.toString())
        p.write("src/main.kt", "import json.*\n\nfun main() {\n    println(parse(\"abcd\"))\n}\n")
        p.reset()
        assertEquals(0, p.mini("build"), p.all())
        assertTrue(p.err.contains("Compiling json v1.0.0 (git "), p.err.toString())
        assertEquals("40\n", runBinary(p, "build/debug/app").stdout)
        // with the remote gone the cached checkout is enough
        File(url).deleteRecursively()
        p.reset()
        assertEquals(0, p.mini("build", "--offline", "--locked"), p.all())
        assertTrue(p.err.contains("Fresh"), p.err.toString())
    }

    @Test
    fun compileErrorsAreShownWithProjectPaths() {
        if (!(TestPlatform.toolchainAvailable())) return
        val p = TestPlatform.temp()
        assertEquals(0, p.mini("new", "bad", "--no-git"))
        p.dir = File(p.root, "bad")
        p.write("src/main.kt", "fun main() {\n    println(nope)\n}\n")
        p.reset()
        assertEquals(1, p.mini("build"), p.all())
        assertTrue(p.err.contains("src/main.kt:2:13: error: ") && p.err.contains("could not compile `bad`"), p.err.toString())
        assertTrue(!p.err.contains("could not compile main.kt") && !p.err.contains("0 warnings"), p.err.toString()) // minic-native's own summary is dropped
    }

    @Test
    fun checkLooksAtEverySourceFile() {
        if (!(TestPlatform.toolchainAvailable())) return
        val p = TestPlatform.temp()
        assertEquals(0, p.mini("new", "two", "--no-git"))
        p.dir = File(p.root, "two")
        p.write("src/main.kt", "fun main() {\n    println(helper())\n}\n")
        p.write("src/helper.kt", "fun helper(): Int {\n    val x: String = 5\n    return 1\n}\n")
        p.reset()
        assertEquals(1, p.mini("check"), p.all())
        assertTrue(p.err.contains("src/helper.kt:2:"), p.err.toString())
        p.write("src/helper.kt", "fun helper(): Int = 1\n")
        p.reset()
        assertEquals(0, p.mini("check"), p.all())
    }
}
