import mini.frontend.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tricky snippets (resources/snippets.txt: precedence, associativity, newline rules, soft keywords, string templates,
 * numeric literals, annotations, labels, ...). Every snippet that the K2 PSI parser accepts must parse without errors
 * in our parser and give the identical outline.
 */
class SnippetOracleTest {
    private fun snippets(): List<String> {
        val out = ArrayList<String>()
        for (name in listOf("/snippets.txt", "/snippets2.txt", "/snippets3.txt")) {
            val text = SnippetOracleTest::class.java.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)
            val cur = StringBuilder()
            for (line in text.split("\n")) {
                if (line.startsWith("//---")) {
                    out.add(cur.toString())
                    cur.setLength(0)
                } else {
                    cur.append(line).append('\n')
                }
            }
            out.add(cur.toString())
        }
        return out
    }

    @Test
    fun snippetsMatchK2() {
        val failures = ArrayList<String>()
        var compared = 0
        var invalid = 0
        var exprs = 0
        val sn = snippets()
        for ((idx, s) in sn.withIndex()) {
            val psi = PsiOracle.parse("snippet$idx.kt", s)
            val perr = PsiOracle.errors(psi)
            val ours = parseKotlin(s)
            if (perr.isNotEmpty()) {
                invalid++
                println("snippet $idx: PSI reports errors (${perr.first().errorDescription} at line ${PsiOracle.line(s, perr.first().textOffset)}); ours: ${ours.errors.size} errors")
                continue
            }
            compared++
            val o = Outline()
            val a = o.file(ours.file)
            exprs += o.exprs
            val b = PsiOutline().file(psi)
            if (ours.errors.isNotEmpty()) failures.add("snippet $idx: our parser reports ${ours.errors.first()}\n$s")
            else if (a != b) {
                val n = minOf(a.size, b.size)
                var k = 0
                while (k < n && a[k] == b[k]) k++
                failures.add("snippet $idx: line $k differs\n  ours: ${a.getOrElse(k) { "<missing>" }}\n  psi:  ${b.getOrElse(k) { "<missing>" }}\n--- source:\n$s")
            }
        }
        println("SNIPPETS: ${sn.size} snippets, $compared compared ($exprs expressions), $invalid rejected by PSI itself, ${failures.size} mismatches")
        assertEquals(emptyList(), failures.take(10), "${failures.size} mismatching snippets")
        assertTrue(compared > 60)
    }
}
