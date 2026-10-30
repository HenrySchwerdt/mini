package mini.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScaffoldTest {
    @Test
    fun newAppCreatesAProjectThatParses() {
        val p = TestPlatform.temp()
        assertEquals(0, p.mini("new", "hello", "--no-git"))
        for (f in listOf("mini.toml", "src/main.kt", "src/greeting.kt", "tests/greeting_test.kt", ".gitignore", "README.md")) assertTrue(p.file("hello/$f").isFile, f)
        val m = ManifestParser.parse("x", p.file("hello/mini.toml").readText())
        assertEquals("hello", m.pkg!!.name); assertEquals(Kind.App, m.pkg!!.kind)
        assertTrue(p.file("hello/.gitignore").readText().contains("/build/"))
        assertTrue(p.err.contains("Created app `hello`") && p.err.contains("mini run") && p.err.contains("cd hello"), p.err.toString())
    }

    @Test
    fun newWasmLibTemplate() {
        val p = TestPlatform.temp()
        assertEquals(0, p.mini("new", "mathlib", "--template", "wasm-lib", "--no-git"))
        for (f in listOf("mini.toml", "src/lib.kt", "tests/lib_test.kt", "index.html", "run.mjs")) assertTrue(p.file("mathlib/$f").isFile, f)
        assertEquals(Kind.WasmLib, ManifestParser.parse("x", p.file("mathlib/mini.toml").readText()).pkg!!.kind)
        assertTrue(p.file("mathlib/src/lib.kt").readText().contains("@WasmExport"))
        assertTrue(p.file("mathlib/run.mjs").readText().contains("./build/wasm/mathlib.mjs"))
        assertTrue(p.err.contains("Created WebAssembly library `mathlib`"), p.err.toString())
        assertEquals(2, p.mini("new", "other", "--lib", "--template", "wasm-lib", "--no-git"))
    }

    @Test
    fun newLibUsesAPackage() {
        val p = TestPlatform.temp()
        assertEquals(0, p.mini("new", "geo-metry", "--lib", "--no-git"))
        assertTrue(p.file("geo-metry/src/lib.kt").readText().startsWith("package geo_metry"))
        assertTrue(p.file("geo-metry/tests/lib_test.kt").readText().contains("import geo_metry.*"))
        assertEquals(Kind.Lib, ManifestParser.parse("x", p.file("geo-metry/mini.toml").readText()).pkg!!.kind)
        assertFalse(p.file("geo-metry/src/main.kt").exists())
    }

    @Test
    fun strictFlagWritesTheMemoryMode() {
        val p = TestPlatform.temp()
        assertEquals(0, p.mini("new", "s", "--strict", "--no-git"))
        assertEquals(MemoryDefault.Strict, ManifestParser.parse("x", p.file("s/mini.toml").readText()).pkg!!.memory)
    }

    @Test
    fun newRefusesExistingDirectoriesAndBadNames() {
        val p = TestPlatform.temp()
        p.file("taken").mkdirs()
        assertEquals(1, p.mini("new", "taken", "--no-git"))
        assertTrue(p.err.contains("already exists") && p.err.contains("mini init"), p.err.toString())
        p.reset()
        assertEquals(1, p.mini("new", "My App", "--no-git"))
        assertTrue(p.err.contains("invalid package name `My App`") && p.err.contains("try `mini new my-app`"), p.err.toString())
    }

    @Test
    fun initKeepsExistingFiles() {
        val p = TestPlatform.temp()
        p.dir = File(p.root, "proj").also { it.mkdirs() }
        p.write("src/main.kt", "fun main() { println(1) }\n")
        assertEquals(0, p.mini("init", "--no-git"))
        assertEquals("fun main() { println(1) }\n", p.file("src/main.kt").readText())
        assertTrue(p.err.contains("Skipped") && p.err.contains("src/main.kt"))
        assertEquals("proj", ManifestParser.parse("x", p.file("mini.toml").readText()).pkg!!.name)
        p.reset()
        assertEquals(1, p.mini("init", "--no-git"))
        assertTrue(p.err.contains("already exists"))
    }

    @Test
    fun badManifestIsReportedWithAPointer() {
        val p = TestPlatform.temp()
        p.write("mini.toml", "[package]\nname = hello\n")
        assertEquals(1, p.mini("tree"))
        assertTrue(p.err.contains("error: unquoted value `hello`") && p.err.contains("--> mini.toml:2:8") && p.err.contains("^^^^^"), p.err.toString())
    }

    @Test
    fun noManifestHasAHelpfulMessage() {
        val p = TestPlatform.temp()
        assertEquals(1, p.mini("build"))
        assertTrue(p.err.contains("could not find `mini.toml`") && p.err.contains("mini new"), p.err.toString())
    }
}

class ProjectTest {
    private fun pkg(p: TestPlatform, dir: String, name: String, kind: String = "lib", deps: String = "") {
        p.write("$dir/mini.toml", "[package]\nname = \"$name\"\nkind = \"$kind\"\n[dependencies]\n$deps")
        p.write("$dir/src/${if (kind == "app") "main" else "lib"}.kt", if (kind == "app") "fun main() {}\n" else "fun $name${"X"}() {}\n")
    }

    @Test
    fun treeShowsPathDependenciesWithDedup() {
        val p = TestPlatform.temp()
        pkg(p, "util", "util")
        pkg(p, "geo", "geo", deps = "util = { path = \"../util\" }\n")
        pkg(p, "app", "app", "app", "geo = { path = \"../geo\" }\nutil = { path = \"../util\" }\n")
        p.dir = File(p.root, "app")
        assertEquals(0, p.mini("tree"))
        val lines = p.out.toString().trim().lines()
        assertEquals("app v0.1.0 (.)", lines[0])
        assertEquals("├── geo v0.1.0 (../geo)", lines[1])
        assertEquals("│   └── util v0.1.0 (../util)", lines[2])
        assertEquals("└── util v0.1.0 (../util)", lines[3])
    }

    @Test
    fun dependencyErrorsPointAtTheManifest() {
        val p = TestPlatform.temp()
        pkg(p, "other", "other")
        pkg(p, "app", "app", "app", "wrong = { path = \"../other\" }\n")
        p.dir = File(p.root, "app")
        assertEquals(1, p.mini("tree"))
        assertTrue(p.err.contains("dependency `wrong` is called `other`") && p.err.contains("--> mini.toml:5:1") && p.err.contains("use `other = { ... }` here"), p.err.toString())

        p.reset()
        p.write("mini.toml", "[package]\nname = \"app\"\nkind = \"app\"\n[dependencies]\nmissing = { path = \"../nowhere\" }\n")
        assertEquals(1, p.mini("tree"))
        assertTrue(p.err.contains("dependency `missing` (../nowhere) has no mini.toml") && p.err.contains("relative to"), p.err.toString())
    }

    @Test
    fun appsCannotBeDependenciesAndCyclesAreDetected() {
        val p = TestPlatform.temp()
        pkg(p, "tool", "tool", "app")
        pkg(p, "app", "app", "app", "tool = { path = \"../tool\" }\n")
        p.dir = File(p.root, "app")
        assertEquals(1, p.mini("tree"))
        assertTrue(p.err.contains("`tool` is an app, not a library") && p.err.contains("kind = \"lib\""), p.err.toString())

        p.reset()
        p.dir = p.root
        pkg(p, "a", "a", deps = "b = { path = \"../b\" }\n")
        pkg(p, "b", "b", deps = "a = { path = \"../a\" }\n")
        p.dir = File(p.root, "app")
        p.write("mini.toml", "[package]\nname = \"app\"\nkind = \"app\"\n[dependencies]\na = { path = \"../a\" }\n")
        assertEquals(1, p.mini("tree"))
        assertTrue(p.err.contains("dependency cycle: a -> b -> a"), p.err.toString())
    }

    @Test
    fun workspaceAppliesToAllMembersAndSelectsWithP() {
        val p = TestPlatform.temp()
        p.write("mini.toml", "[workspace]\nmembers = [\"crates/*\", \"server\"]\n")
        pkg(p, "crates/core", "core")
        pkg(p, "crates/json", "json", deps = "core = { path = \"../core\" }\n")
        pkg(p, "server", "server", "app", "json = { path = \"../crates/json\" }\n")
        assertEquals(0, p.mini("tree"))
        val out = p.out.toString()
        assertTrue(out.contains("core v0.1.0 (crates/core)") && out.contains("json v0.1.0 (crates/json)") && out.contains("server v0.1.0 (server)"), out)

        p.reset()
        assertEquals(0, p.mini("tree", "-p", "server"))
        assertFalse(p.out.toString().contains("(crates/core)\njson"), p.out.toString())
        assertTrue(p.out.toString().startsWith("server"))

        p.reset()
        assertEquals(1, p.mini("tree", "-p", "serv"))
        assertTrue(p.err.contains("no package named `serv`") && p.err.contains("did you mean `server`?"), p.err.toString())

        // inside a member directory only that member is selected, but the workspace root owns build/ and mini.lock
        p.reset()
        p.dir = File(p.root, "crates/core")
        assertEquals(0, p.mini("tree"))
        assertEquals("core v0.1.0 (.)", p.out.toString().trim())
    }

    @Test
    fun cleanRemovesBuildAtTheWorkspaceRoot() {
        val p = TestPlatform.temp()
        p.write("mini.toml", "[workspace]\nmembers = [\"a\"]\n")
        pkg(p, "a", "a", "app")
        p.write("build/debug/a", "x")
        p.dir = File(p.root, "a")
        assertEquals(0, p.mini("clean"))
        assertFalse(File(p.root, "build").exists())
        assertTrue(p.err.contains("Removed"))
        p.reset()
        assertEquals(0, p.mini("clean"))
        assertTrue(p.err.contains("nothing to remove"))
    }

    @Test
    fun addAndRemovePathDependencies() {
        val p = TestPlatform.temp()
        pkg(p, "geo", "geo")
        pkg(p, "app", "app", "app")
        p.dir = File(p.root, "app")
        assertEquals(0, p.mini("add", "--path", "../geo"))
        assertTrue(p.err.contains("Adding geo (path ../geo)"), p.err.toString())
        assertTrue(p.file("mini.toml").readText().contains("geo = { path = \"../geo\" }"))
        p.reset()
        assertEquals(0, p.mini("tree"))
        assertTrue(p.out.contains("geo v0.1.0"))
        p.reset()
        assertEquals(1, p.mini("add", "other", "--path", "../geo"))
        assertTrue(p.err.contains("is called `geo`, not `other`"), p.err.toString())
        p.reset()
        assertEquals(1, p.mini("remove", "ge"))
        assertTrue(p.err.contains("did you mean `geo`?"), p.err.toString())
        p.reset()
        assertEquals(0, p.mini("remove", "geo"))
        assertFalse(p.file("mini.toml").readText().contains("geo = "))
        p.reset()
        assertEquals(2, p.mini("add", "x"))
        assertTrue(p.err.contains("exactly one of `--path"), p.err.toString())
    }

    @Test
    fun testListFindsTestFunctionsAndClassMethodsWithoutBuilding() {
        val p = TestPlatform.temp()
        p.write("mini.toml", "[package]\nname = \"t\"\n")
        p.write("src/main.kt", "fun main() {}\n")
        p.write("src/math.kt", "package m\n\nfun twice(x: Int) = x * 2\n\nimport_placeholder\n".replace("import_placeholder\n", ""))
        p.write("tests/a_test.kt", "import kotlin.test.*\n\n@Test\nfun first() {}\n\n@Test @Ignore\nfun second() {}\n\nfun helper() {}\n")
        p.write("tests/b_test.kt", "package suite\n\nimport kotlin.test.Test\n\nclass K { @Test fun inClass() {} }\n\n@Test fun third() {}\n")
        assertEquals(0, p.mini("test", "--list"))
        assertEquals(listOf("tests/a_test.kt::first", "tests/a_test.kt::second (ignored)", "tests/b_test.kt::K.inClass", "tests/b_test.kt::third"), p.out.toString().trim().lines())
        p.reset()
        assertEquals(0, p.mini("test", "--list", "thi"))
        assertEquals("tests/b_test.kt::third", p.out.toString().trim())
    }

    @Test
    fun testWithoutTheImportExplainsWhatToDo() {
        val p = TestPlatform.temp()
        p.write("mini.toml", "[package]\nname = \"t\"\n")
        p.write("src/main.kt", "fun main() {}\n")
        p.write("tests/a_test.kt", "@Test\nfun first() {}\n")
        assertEquals(1, p.mini("test", "--list"))
        assertTrue(p.err.contains("tests/a_test.kt:2") && p.err.contains("import kotlin.test"), p.err.toString())
    }

    @Test
    fun runnerSourceCallsEveryTest() {
        val src = TestDiscovery.runnerSource(listOf(TestCase("tests/a.kt::x", "`x`", "tests/a.kt", 1, false), TestCase("tests/b.kt::y", "`p`.`y`", "tests/b.kt", 1, false)))
        assertTrue(src.contains("\"tests/a.kt::x\" -> `x`()") && src.contains("\"tests/b.kt::y\" -> `p`.`y`()") && src.contains("fun main(args: Array<String>)"))
    }

    @Test
    fun doctorAndExplainRunWithoutAProject() {
        val p = TestPlatform.temp()
        assertEquals(0, p.mini("explain", "M0102"))
        assertTrue(p.out.contains("Leak"))
        p.reset()
        assertEquals(1, p.mini("explain", "M0999"))
        assertTrue(p.err.contains("no explanation for `M0999`"))
        p.reset()
        assertEquals(0, p.mini("explain", "--list"))
        assertTrue(p.out.contains("M0201"))
        p.reset()
        assertEquals(0, p.mini("completions", "bash"))
        assertTrue(p.out.contains("_mini"))
        p.reset()
        assertEquals(0, p.mini("help", "build"))
        assertTrue(p.out.contains("Usage: mini build"))
        assertEquals(0, p.mini("--version"))
        // the toolchain version (the VERSION file of the installation), not a version of the tool's own
        assertTrue(p.out.contains("mini " + Mini.version(p)))
        assertTrue(!p.out.contains("mini 0.1.0"), p.out.toString())
        assertTrue(!p.out.contains("mini " + Mini.UNKNOWN_VERSION), "the installation under test has no VERSION file: " + p.out.toString())
        p.reset()
        assertEquals(2, p.mini("buld"))
        assertTrue(p.err.contains("did you mean `build`?"))
        assertNotEquals(-1, p.mini("doctor"))
    }
}
