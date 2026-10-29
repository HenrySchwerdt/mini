import mini.frontend.*
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.io.File
import java.time.Duration
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Malformed inputs: errors at the right positions, no hangs, no crashes, and the CST stays lossless. */
class NegativeTest {
    private fun <T> timed(msg: String, f: () -> T): T = assertTimeoutPreemptively(Duration.ofSeconds(20), ThrowingSupplier { f() }, msg)

    private fun firstError(src: String): String {
        val r = parseKotlin(src)
        assertEquals(src, printCst(r.root, r.tokens, src), "CST must stay lossless for malformed input")
        val e = r.errors.firstOrNull() ?: return "no error"
        return "${e.line}:${e.col} ${e.message}"
    }

    /** (source, expected first error as "line:col message") */
    private val cases = listOf(
        "fun f() {\n  val x = (1 + 2\n}\n" to "2:17 Expecting ')'",
        "fun f() {\n  foo(1, 2\n}\n" to "2:11 Expecting ')'",
        "class A {\n  fun f() {}\n" to "2:13 Expecting '}'",
        "fun f() {\n" to "1:10 Expecting '}'",
        "fun f(\n" to "1:7 Expecting ')'",
        "val x =\n" to "1:8 Expecting an element",
        "fun f() { if (x }\n" to "1:16 Expecting ')'",
        "fun f() { val = 1 }\n" to "1:14 Expecting property name",
        "class { }\n" to "1:6 Expecting name",
        "fun f() { 1 2 }\n" to "1:13 Unexpected tokens (use ';' to separate expressions on the same line)",
        "fun f() {}\n}\n" to "2:1 Unmatched closing brace",
        "fun f() { when (x) { 1 -> } }\n" to "1:26 Expecting an element",
        "fun f() { when (x) { 1 2 } }\n" to "1:23 Expecting '->'",
        "fun f(a: ) {}\n" to "1:9 Expecting a type",
        "fun f(a Int) {}\n" to "1:8 Expecting ')'",
        "val s = \"abc\nval t = 1\n" to "1:13 unterminated string literal",
        "val s = \"abc\${1 + }\"\n" to "1:18 Expecting an element",
        "/* never closed\nfun f() {}\n" to "1:1 unterminated block comment",
        "val c = 'ab'\n" to "1:9 unterminated character literal",
        "package\nfun f() {}\n" to "1:8 Expecting package name",
        "import\nfun f() {}\n" to "1:7 Expecting qualified name",
        "fun f() { a.() }\n" to "1:14 Expecting an element",
        "fun f() { a[1 }\n" to "1:14 Expecting ']'",
        "class A(val x Int)\n" to "1:14 Expecting ')'",
        "fun f() { else }\n" to "1:10 Expecting an element",
        "fun f() { try { } }\n" to "1:18 Expecting 'catch' or 'finally'",
        "fun f() { for (i 1..2) {} }\n" to "1:17 Expecting 'in'",
        "fun f() { do { } }\n" to "1:17 Expecting 'while'",
        "interface I { fun f( }\n" to "1:21 Expecting parameter name",
        "enum class E { A B }\n" to "1:18 Expecting member declaration",
        "fun f() = \n" to "1:10 Expecting an element",
        "object : A { }\n" to "1:7 Expecting name",
        "private\n" to "1:1 Expecting a top level declaration",
        "class A { private }\n" to "1:11 Expecting member declaration",
        "@\nfun f() {}\n" to "1:2 Expecting annotation name",
    )

    @Test
    fun errorsAreReportedAtTheExpectedPositions() {
        val bad = ArrayList<String>()
        for ((src, expected) in cases) {
            val got = firstError(src)
            if (got != expected) bad.add("${src.trim().replace("\n", "\\n")}: expected '$expected' but got '$got'")
        }
        assertEquals(emptyList(), bad)
    }

    @Test
    fun validInputHasNoErrors() {
        assertEquals("no error", firstError("fun f() { val x = (1 + 2) }\n"))
    }

    // ---- mutation fuzzing: terminates, never throws, stays lossless ------------------------------------------------------

    private fun significant(lex: LexResult): List<Token> = lex.tokens.filter { !it.kind.isTrivia() && it.kind != Tk.EOF }

    private val junk = listOf("{", "}", "(", ")", "[", "]", "<", ">", ",", ".", "?", "!", "=", "->", "::", "@", "\"", "'", "`", "\${", "fun", "class", "val", "if", "else", "when", "in", "is", "as", ";", ":", "\n")

    private fun mutate(src: String, r: Random): String {
        val lex = lexKotlin(src)
        val toks = significant(lex)
        if (toks.isEmpty()) return src
        var s = src
        val n = 1 + r.nextInt(3)
        for (k in 0 until n) {
            val l = lexKotlin(s)
            val ts = significant(l)
            if (ts.isEmpty()) break
            val t = ts[r.nextInt(ts.size)]
            s = when (r.nextInt(5)) {
                0 -> s.removeRange(t.start, t.end)
                1 -> s.substring(0, t.start) + junk[r.nextInt(junk.size)] + " " + s.substring(t.start)
                2 -> s.substring(0, t.end) + " " + junk[r.nextInt(junk.size)] + s.substring(t.end)
                3 -> s.substring(0, t.start) + junk[r.nextInt(junk.size)] + s.substring(t.end)
                else -> s.substring(0, t.start + r.nextInt(t.end - t.start + 1))
            }
        }
        return s
    }

    private fun files(): List<File> {
        val dir = File(System.getProperty("mini.testdata") ?: error("mini.testdata not set"))
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    }

    @Test
    fun randomTokenDeletionsAndInsertionsTerminate() {
        val r = Random(42)
        val all = files()
        assertTrue(all.size > 50)
        var runs = 0
        var withErrors = 0
        for (round in 0 until 6) {
            for (f in all) {
                val text = f.readText()
                val m = mutate(text, r)
                val res = timed("parser did not terminate on a mutation of ${f.name}") {
                    parseKotlin(m)
                }
                assertEquals(m, printCst(res.root, res.tokens, m), "lossless after mutation of ${f.name}")
                runs++
                if (res.errors.isNotEmpty()) withErrors++
            }
        }
        println("MUTATION FUZZ: $runs mutated files parsed, $withErrors with syntax errors, no hang, no crash, always lossless")
        assertTrue(withErrors > runs / 2)
    }

    @Test
    fun truncatedFilesTerminate() {
        val r = Random(7)
        var runs = 0
        for (f in files()) {
            val text = f.readText()
            for (k in 0 until 4) {
                val cut = text.substring(0, r.nextInt(text.length + 1))
                val res = timed("truncation of ${f.name}") { parseKotlin(cut) }
                assertEquals(cut, printCst(res.root, res.tokens, cut))
                runs++
            }
        }
        println("TRUNCATION FUZZ: $runs truncated files parsed")
    }

    @Test
    fun deeplyNestedInputDoesNotBlowUp() {
        // reasonable nesting is fine
        val ok = "val x = " + "(".repeat(200) + "1" + ")".repeat(200) + "\n"
        assertEquals(emptyList(), timed("nesting") { parseKotlin(ok) }.errors.map { it.toString() })
        val ok2 = "val x = " + "foo { ".repeat(60) + "1" + " }".repeat(60) + "\n"
        assertEquals(emptyList(), timed("nesting") { parseKotlin(ok2) }.errors.map { it.toString() })
        // hostile nesting is an error, not a stack overflow
        val depth = 5000
        for (src in listOf(
            "val x = " + "(".repeat(depth) + "1" + ")".repeat(depth) + "\n",
            "val x = " + "foo { ".repeat(depth) + "1" + " }".repeat(depth) + "\n",
            "val x: " + "List<".repeat(depth) + "Int" + ">".repeat(depth) + "\n",
            "fun f() { " + "if (a) ".repeat(depth) + "1\n}\n",
            "fun f() { " + "foo(".repeat(300) + "\n",
            "val x = " + "a < b ".repeat(2000) + "\n",
            "class A { ".repeat(depth) + "}".repeat(depth) + "\n",
            "val x = " + "-".repeat(depth) + "1\n",
            "val x = " + "a ?: ".repeat(depth) + "1\n",
            "val x = " + "a.b".repeat(depth) + "\n",
        )) {
            val r = timed("hostile nesting") { parseKotlin(src) }
            assertEquals(src, printCst(r.root, r.tokens, src))
        }
    }
}
