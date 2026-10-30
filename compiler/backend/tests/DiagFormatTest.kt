import mini.backend.CEmitter
import mini.backend.Mangling
import mini.backend.MirFunction
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.TUnit
import mini.backend.memory.Diag
import mini.backend.memory.DiagCodes
import mini.backend.memory.DiagJson
import mini.backend.memory.DiagRender
import mini.backend.memory.DiagStyle
import mini.backend.memory.MemoryReport
import mini.backend.memory.Pos
import mini.backend.memory.Severity
import mini.io.readTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Colors, code notes, JSON and `#line` directives of the diagnostics every driver prints. */
class DiagFormatTest {
    private val src = listOf("fun main() {", "    val s = \"a\\\"b\" + 1", "    leak(buffer)", "}")

    private fun diag(code: String = "M0102", line: Int = 3, col: Int = 5) =
        Diag(code, Severity.Error, "allocation is \"never\" released", Pos(line, col), "allocated here", help = listOf("call `free()`"))

    @Test
    fun underlineCoversTheToken() {
        assertEquals(4, DiagRender.tokenWidthIn("    leak(buffer)", 5))
        assertEquals(6, DiagRender.tokenWidthIn(src[1], 13)) // the string literal "a\"b" with its escape
        assertEquals(1, DiagRender.tokenWidthIn("    (x)", 5))
        val text = DiagRender.render(diag(), "a.kt", src)
        assertTrue("   3 |     leak(buffer)\n     |     ^^^^\n" in text, text)
    }

    @Test
    fun plainOutputHasNoEscapeCodesAndColorOutputHas() {
        assertFalse('\u001b' in DiagRender.render(diag(), "a.kt", src))
        val colored = DiagRender.render(diag(), "a.kt", src, DiagStyle(true))
        assertTrue("\u001b[1;31merror[M0102]" in colored, colored)
    }

    @Test
    fun reportNamesEveryCodeOnceWithItsPage() {
        val report = MemoryReport(listOf(diag(), diag(line = 2, col = 5), diag("M0101")), mapOf("" to src), emptyList(), emptyList())
        val text = report.render()
        assertEquals(1, occurrences(text, "note: M0102 "), text)
        assertTrue("note: M0101 (Use after free): https://henryschwerdt.github.io/mini/reference/diagnostics/M0101" in text, text)
        assertEquals(2, report.render(limit = 2).lines().count { it.startsWith("error[") })
        assertEquals(2, report.json(limit = 2).size)
    }

    @Test
    fun jsonIsOneLineAndEscaped() {
        val j = DiagJson.of(diag(), "dir/a.kt", src)
        assertFalse('\n' in j)
        assertTrue("\"message\":\"allocation is \\\"never\\\" released\"" in j, j)
        assertTrue("\"line\":3,\"col\":5,\"endCol\":9" in j, j)
        assertTrue("\"docs\":\"https://henryschwerdt.github.io/mini/reference/diagnostics/M0102\"" in j, j)
        assertEquals("\"a\\nb\\u0001\"", DiagJson.str("a\nb\u0001"))
    }

    @Test
    fun codeTitlesMatchTheDocumentationPages() {
        // `mini test` runs in the package directory (compiler/backend)
        val meta = readTextFile("../../website/scripts/diagnostics-meta.json")
        val codes = ArrayList<Pair<String, String>>()
        var at = 0
        while (true) {
            val k = meta.indexOf("\"M", at)
            if (k < 0) break
            at = k + 2
            val code = meta.substring(k + 1, minOf(k + 6, meta.length))
            if (code.length != 5 || !code.substring(1).all { it in '0'..'9' } || !meta.startsWith("\": {", k + 6)) continue
            val t = meta.indexOf("\"title\": \"", k)
            if (t < 0) break
            val from = t + 10
            codes.add(Pair(code, meta.substring(from, meta.indexOf('"', from))))
        }
        assertTrue(codes.size >= 20)
        for ((code, title) in codes) assertEquals(title, DiagCodes.title(code), code)
    }

    @Test
    fun lineDirectivesPointIntoTheKotlinSourceAndBack() {
        val stmt = MirStmt.Return(null)
        stmt.srcLine = 7
        stmt.srcFile = "dir/prog.kt"
        val module = MirModule("prog.kt", listOf(MirFunction("main", Mangling.MAIN, emptyList(), TUnit, listOf(stmt))))
        val plain = CEmitter.emit(module)
        assertFalse("#line" in plain)
        val c = CEmitter.emit(module, lineFile = "out/prog.c")
        val lines = c.split('\n')
        val at = lines.indexOf("#line 7 \"dir/prog.kt\"")
        assertTrue(at >= 0, c)
        val back = lines.drop(at).first { it.startsWith("#line") && it.contains("out/prog.c") }
        // the line after the directive is the one that follows it in the file
        val n = back.removePrefix("#line ").substringBefore(' ').toInt()
        assertEquals(lines.indexOf(back) + 2, n)
    }
}
