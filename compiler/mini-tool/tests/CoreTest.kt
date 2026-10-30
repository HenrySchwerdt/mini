package mini.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandLineTest {
    @Test
    fun parsesFlagsPositionalsAndPassthrough() {
        val p = CommandLine.parse(listOf("run", "--release", "-p", "app", "--color=never", "--", "--port", "8080"))
        assertEquals("run", p.command.name)
        assertTrue(p.has("release"))
        assertEquals("app", p.value("package"))
        assertEquals("never", p.value("color"))
        assertEquals(listOf("--port", "8080"), p.rest)
    }

    @Test
    fun globalFlagsMayComeFirst() {
        val p = CommandLine.parse(listOf("-q", "build", "-Werror"))
        assertTrue(p.has("quiet")); assertTrue(p.has("Werror"))
    }

    @Test
    fun testTakesAFilterAndPassthrough() {
        val p = CommandLine.parse(listOf("test", "parse", "--nocapture"))
        assertEquals(listOf("parse"), p.positionals)
        assertTrue(p.has("nocapture"))
    }

    @Test
    fun suggestsCommands() {
        val e = assertFailsWith<UsageError> { CommandLine.parse(listOf("bild")) }
        assertEquals("unknown command `bild`", e.msg)
        assertEquals("did you mean `build`?", e.help)
        assertEquals("did you mean `test`?", assertFailsWith<UsageError> { CommandLine.parse(listOf("tset")) }.help)
        assertEquals("did you mean `explain`?", assertFailsWith<UsageError> { CommandLine.parse(listOf("explian")) }.help)
        assertTrue(assertFailsWith<UsageError> { CommandLine.parse(listOf("zzzzzz")) }.help!!.contains("mini help"))
    }

    @Test
    fun suggestsFlags() {
        val e = assertFailsWith<UsageError> { CommandLine.parse(listOf("build", "--releas")) }
        assertEquals("unknown option `--releas` for `mini build`", e.msg)
        assertEquals("did you mean `--release`?", e.help)
        assertEquals("did you mean `--message-format`?", assertFailsWith<UsageError> { CommandLine.parse(listOf("check", "--mesage-format", "json")) }.help)
    }

    @Test
    fun valueProblems() {
        assertTrue(assertFailsWith<UsageError> { CommandLine.parse(listOf("build", "-p")) }.msg.contains("needs a value"))
        val e = assertFailsWith<UsageError> { CommandLine.parse(listOf("build", "--message-format", "jsno")) }
        assertEquals("did you mean `json`?", e.help)
        assertTrue(assertFailsWith<UsageError> { CommandLine.parse(listOf("new")) }.msg.contains("needs"))
        assertTrue(assertFailsWith<UsageError> { CommandLine.parse(listOf("build", "extra")) }.msg.contains("unexpected argument"))
        assertTrue(assertFailsWith<UsageError> { CommandLine.parse(listOf("build", "--", "x")) }.msg.contains("does not take arguments"))
    }

    @Test
    fun helpTextMentionsEveryCommandAndFlag() {
        val top = CommandLine.topHelp(Style.Plain, "0.1.0")
        for (c in CommandLine.commands) assertTrue(top.contains(c.name), c.name)
        for (c in CommandLine.commands) {
            val h = CommandLine.commandHelp(c, Style.Plain)
            assertTrue(h.contains("Usage: mini ${c.name}"))
            for (f in c.flags) assertTrue(h.contains(f.long), "${c.name} --${f.long}")
        }
    }

    @Test
    fun noArgumentsMeansHelp() {
        assertEquals("help", CommandLine.parse(emptyList()).command.name)
        assertTrue(CommandLine.parse(listOf("--version")).has("version"))
    }
}

class SupportTest {
    @Test
    fun editDistanceAndSuggest() {
        assertEquals(0, editDistance("a", "a"))
        assertEquals(2, editDistance("build", "biuld"))
        assertEquals("build", suggest("biuld", listOf("build", "test")))
        assertNull(suggest("xyzxyz", listOf("build", "test")))
        assertEquals("build", suggest("bui", listOf("build", "test")))
    }

    @Test
    fun durations() {
        assertEquals("85ms", formatDuration(85)); assertEquals("1.2s", formatDuration(1234)); assertEquals("2m 03s", formatDuration(123_000))
    }

    @Test
    fun hashIsStableAndSeparatesFields() {
        assertEquals(Hasher.of("a"), Hasher.of("a"))
        assertNotEquals(Hasher.of("a"), Hasher.of("b"))
        assertNotEquals(Hasher().add("ab").add("c").hex(), Hasher().add("a").add("bc").hex())
        assertEquals(32, Hasher.of("x").length)
    }

    @Test
    fun paths() {
        assertEquals("/a/c", Paths.normalize("/a/b/../c/./"))
        assertEquals("../x", Paths.normalize("a/../../x"))
        assertEquals("/a", Paths.parent("/a/b"))
        assertEquals("/", Paths.parent("/a"))
        assertEquals("../lib", Paths.relativize("/w/app", "/w/lib"))
        assertEquals("src/main.kt", Paths.relativize("/w/app", "/w/app/src/main.kt"))
        assertEquals(".", Paths.relativize("/w", "/w"))
        assertEquals("/w/lib", Paths.resolve("/w/app", "../lib"))
        assertEquals("kt", Paths.extension("/a/b.kt"))
    }

    @Test
    fun styleIsNoopWhenDisabled() {
        assertEquals("x", Style(false).red("x", true))
        assertEquals("\u001b[1;31mx\u001b[0m", Style(true).red("x", true))
    }

    @Test
    fun lockfileRoundTrip() {
        val l = Lockfile(mapOf("b" to LockEntry("u2", "tag:v1", "2".repeat(40)), "a" to LockEntry("u\"1", "default", "1".repeat(40))))
        val text = l.render()
        assertTrue(text.indexOf("[git.a]") < text.indexOf("[git.b]"), "sorted for determinism")
        val back = Lockfile.parse(text)
        assertEquals(text, back.render())
        assertEquals("u\"1", back.git["a"]!!.url)
        assertEquals(0, Lockfile.parse("garbage = = =").git.size)
    }
}

class DiagnosticsTest {
    private val sample = """
        error[M0102]: allocation from `Heap` is never released
          --> /w/app/src/main.kt:11:15   allocated here; path: early return
           11 |     val buf = Buffer(n)
              |               ^
          help: call `buf.free()` on every path

        main.kt:3:5: error: unresolved reference 'x'
    """.trimIndent()

    @Test
    fun parsesMiniAndKotlinDiagnostics() {
        val d = Diagnostics.parse(sample)
        assertEquals(2, d.size)
        assertEquals("M0102", d[0].code)
        assertEquals(11, d[0].spans[0].line); assertEquals(15, d[0].spans[0].column)
        assertEquals("allocated here; path: early return", d[0].spans[0].label)
        assertEquals(1, d[0].help.size)
        assertEquals("error", d[1].level); assertEquals("unresolved reference 'x'", d[1].message)
    }

    @Test
    fun dropsTheSummaryOfTheNativeCompiler() {
        val raw = "$sample\n\nnote: M0102 (Leak): https://example.org/M0102\n\nerror: could not compile main.kt (2 errors, 1 warning)\nwarning: main.kt generated 2 warnings"
        val t = Diagnostics.clean(raw)
        assertTrue(!t.contains("could not compile") && !t.contains("generated 2 warnings"), t)
        assertTrue(t.contains("error[M0102]") && t.contains("main.kt:3:5: error:"), t)
    }

    @Test
    fun parsesGeneralErrorsWithoutPosition() {
        val d = Diagnostics.parse("error: internal compiler error: boom\nnote: this is a bug in minic\n")
        assertEquals(1, d.size)
        assertEquals("error", d[0].level); assertEquals("internal compiler error: boom", d[0].message)
    }

    @Test
    fun remapAndClean() {
        val t = Diagnostics.remap(Diagnostics.clean("Picked up JAVA_TOOL_OPTIONS: x\n$sample\nminic: could not compile main.kt due to 1 memory error"), listOf("/w/app/" to ""))
        assertTrue(t.contains("--> src/main.kt:11:15"))
        assertTrue(!t.contains("Picked up") && !t.contains("minic: could not compile"))
    }

    @Test
    fun jsonIsOneLineAndEscaped() {
        val j = Diagnostics.toJson(Diagnostics.parse(sample)[0], "app")
        assertTrue(!j.contains("\n"))
        assertTrue(j.contains("\"code\":\"M0102\"") && j.contains("\"line\":11") && j.contains("\\n"))
        assertEquals("{\"reason\":\"build-finished\",\"package\":\"a\",\"success\":true,\"millis\":5}", Diagnostics.finishedJson(true, "a", 5))
    }
}

class ExplainTest {
    @Test
    fun normalizesCodes() {
        assertEquals("M0102", Explain.normalize("m102")); assertEquals("M0102", Explain.normalize("0102")); assertEquals("M0102", Explain.normalize(" M0102 "))
    }

    @Test
    fun everyCodeOfTheCatalogHasAnExplanation() {
        val codes = ExplainData.overview.map { it.first }
        assertTrue(codes.size >= 20, "overview table: $codes")
        for (c in codes) assertTrue(Explain.render(c, Style.Plain) != null, "no explanation for $c")
        assertTrue(Explain.render("M0102", Style.Plain)!!.contains("Leak"))
        assertTrue(Explain.render("M0101", Style.Plain)!!.contains("Use after free"))
        assertTrue(Explain.render("M0202", Style.Plain) != null) // lints share a section
        assertNull(Explain.render("M9999", Style.Plain))
    }

    @Test
    fun listShowsSeverities() {
        val l = Explain.list(Style.Plain)
        assertTrue(l.contains("M0102") && l.contains("leak") && l.contains("warning"))
    }
}

class CompletionsTest {
    @Test
    fun scriptsMentionCommandsAndFlags() {
        for (shell in Completions.shells) {
            val s = Completions.generate(shell)
            for (c in CommandLine.commands) assertTrue(s.contains(c.name), "$shell: ${c.name}")
            assertTrue(s.contains("release") && s.contains("message-format"), shell)
        }
        assertTrue(Completions.generate("bash").contains("complete -F _mini mini"))
        assertTrue(Completions.generate("zsh").startsWith("#compdef mini"))
        assertTrue(Completions.generate("fish").contains("complete -c mini"))
        assertEquals("did you mean `bash`?", assertFailsWith<UsageError> { Completions.generate("bsh") }.help)
    }
}
