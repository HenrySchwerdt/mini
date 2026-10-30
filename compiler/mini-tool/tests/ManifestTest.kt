package mini.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManifestTest {
    private fun parse(text: String) = ManifestParser.parse("/p/mini.toml", text)
    private fun fail(text: String): ManifestException = assertFailsWith { parse(text) }

    @Test
    fun cSourcesAreListedRelativeToThePackage() {
        val m = parse("[package]\nname = \"host\"\nc-sources = [\"native/a.c\", \"b.c\"]\n")
        assertEquals(listOf("native/a.c", "b.c"), m.pkg!!.cSources)
        assertEquals(emptyList(), parse("[package]\nname = \"x\"\n").pkg!!.cSources)
        assertTrue(fail("[package]\nname = \"x\"\nc-sources = \"a.c\"\n").error.msg.contains("array"))
    }

    @Test
    fun parsesAFullManifest() {
        val m = parse(
            """
            [package]
            name = "hello-app"
            version = "1.2.3"
            kind = "lib"
            memory = "strict"

            [dependencies]
            geometry = { path = "../geometry" }
            json = { git = "https://example.com/json.git", tag = "v1.0" }
            fast = { git = "../fast", rev = "abc1234" }
            live = { git = "https://example.com/live", branch = "main" }
            tip = { git = "https://example.com/tip" }
            """.trimIndent(),
        )
        val p = m.pkg!!
        assertEquals("hello-app", p.name); assertEquals("1.2.3", p.version)
        assertEquals(Kind.Lib, p.kind); assertEquals(MemoryDefault.Strict, p.memory)
        assertEquals(5, m.deps.size)
        assertEquals("../geometry", (m.deps[0] as PathDep).path)
        assertEquals("tag:v1.0", (m.deps[1] as GitDep).ref.spec())
        assertEquals("rev:abc1234", (m.deps[2] as GitDep).ref.spec())
        assertEquals("branch:main", (m.deps[3] as GitDep).ref.spec())
        assertEquals("default", (m.deps[4] as GitDep).ref.spec())
        assertEquals("/p", m.dir)
    }

    @Test
    fun defaultsAreAppManagedAndVersion() {
        val p = parse("[package]\nname = \"x\"\n").pkg!!
        assertEquals(Kind.App, p.kind); assertEquals(MemoryDefault.Managed, p.memory); assertEquals("0.1.0", p.version)
    }

    @Test
    fun workspace() {
        val m = parse("[workspace]\nmembers = [\"app\", \"libs/*\"]\n")
        assertNotNull(m.workspace)
        assertEquals(listOf("app", "libs/*"), m.workspace!!.members)
        assertEquals(null, m.pkg)
    }

    @Test
    fun unknownKeySuggestsTheClosestOne() {
        val e = fail("[package]\nname = \"x\"\nknd = \"app\"\n")
        assertEquals("unknown key `knd` in [package]", e.error.msg)
        assertEquals("did you mean `kind`?", e.error.hint)
        assertEquals(3, e.error.span.line)
    }

    @Test
    fun unknownSection() {
        val e = fail("[pakage]\nname = \"x\"\n")
        assertEquals("did you mean `package`?", e.error.hint)
    }

    @Test
    fun invalidKindAndMemory() {
        assertEquals("did you mean `lib`?", fail("[package]\nname = \"x\"\nkind = \"libb\"\n").error.hint)
        val e = fail("[package]\nname = \"x\"\nmemory = \"stric\"\n")
        assertEquals("invalid memory mode `stric`", e.error.msg)
        assertEquals("did you mean `strict`?", e.error.hint)
    }

    @Test
    fun invalidNameAndVersion() {
        val e = fail("[package]\nname = \"My App\"\n")
        assertTrue(e.error.msg.contains("invalid package name"))
        assertTrue(e.error.hint!!.contains("try `my-app`"), e.error.hint)
        assertTrue(fail("[package]\nname = \"x\"\nversion = \"1.0\"\n").error.msg.contains("invalid version"))
    }

    @Test
    fun missingNameAndEmptyManifest() {
        assertTrue(fail("[package]\nversion = \"1.0.0\"\n").error.msg.contains("needs a `name`"))
        assertTrue(fail("# nothing\n").error.msg.contains("neither"))
    }

    @Test
    fun dependencyProblems() {
        val base = "[package]\nname = \"x\"\n[dependencies]\n"
        assertTrue(fail(base + "a = \"../a\"\n").error.hint!!.contains("{ path = \"../a\" }"))
        assertTrue(fail(base + "a = { }\n").error.msg.contains("has no source"))
        assertTrue(fail(base + "a = { path = \"x\", git = \"y\" }\n").error.msg.contains("both `path` and `git`"))
        assertTrue(fail(base + "a = { git = \"y\", tag = \"1\", rev = \"2\" }\n").error.msg.contains("more than once"))
        assertTrue(fail(base + "a = { path = \"x\", tag = \"1\" }\n").error.msg.contains("only applies to git"))
        assertEquals("did you mean `path`?", fail(base + "a = { pth = \"x\" }\n").error.hint)
        assertTrue(fail(base + "A = { path = \"x\" }\n").error.msg.contains("invalid dependency name"))
    }

    @Test
    fun syntaxErrorsAreReportedWithFileLineAndCaret() {
        val text = "[package]\nname = hello\n"
        val e = fail(text)
        val out = e.render(Style.Plain, "mini.toml")
        assertTrue(out.startsWith("error: unquoted value `hello`"), out)
        assertTrue(out.contains("--> mini.toml:2:8"), out)
        assertTrue(out.contains("^^^^^"), out)
        assertTrue(out.contains("help: strings need quotes"), out)
    }

    @Test
    fun sanitizeAndIdentifiers() {
        assertEquals("my-app", ManifestParser.sanitizeName("My App!"))
        assertEquals("app", ManifestParser.sanitizeName("123"))
        assertEquals("my_lib", "my-lib".toIdentifier())
    }

    @Test
    fun manifestEditAddsAndRemovesDependencies() {
        val text = "[package]\nname = \"x\"\n\n[dependencies]\n# c\na = { path = \"../a\" }\n\n[workspace]\nmembers = []\n"
        val (added, replaced) = ManifestEdit.addDependency(text, "b", "{ path = \"../b\" }")
        assertEquals(false, replaced)
        assertEquals("[package]\nname = \"x\"\n\n[dependencies]\n# c\na = { path = \"../a\" }\nb = { path = \"../b\" }\n\n[workspace]\nmembers = []\n", added)
        val (again, rep2) = ManifestEdit.addDependency(added, "a", "{ git = \"u\" }")
        assertEquals(true, rep2)
        assertTrue(again.contains("a = { git = \"u\" }"))
        assertEquals(listOf("a", "b"), ManifestEdit.dependencyNames(added))
        assertEquals(text, ManifestEdit.removeDependency(added, "b"))
        assertEquals(null, ManifestEdit.removeDependency(added, "zzz"))
        val (fresh, _) = ManifestEdit.addDependency("[package]\nname = \"x\"\n", "a", "{ path = \"../a\" }")
        assertEquals("[package]\nname = \"x\"\n\n[dependencies]\na = { path = \"../a\" }\n", fresh)
        // every edit result is still a valid manifest
        parse(added); parse(again); parse(fresh)
    }
}
