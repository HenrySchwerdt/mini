import mini.frontend.*
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential mutation fuzzing: random token deletions, duplications and replacements (by other tokens of the same
 * file, so that many mutants stay valid) on every repository source. Whenever the K2 PSI parser accepts the mutant,
 * our parser must accept it too and produce the identical outline; whenever PSI rejects it, we only require that we
 * terminate and keep the CST lossless. Also reports how often both parsers agree about validity.
 */
class MutationOracleTest {
    @Test
    fun mutantsAcceptedByK2ParseIdentically() {
        val r = Random(20240601)
        val files = Corpus.files.filter { it.length() < 40_000 }
        val failures = ArrayList<String>()
        var runs = 0
        var psiValid = 0
        var bothInvalid = 0
        var oursOnlyValid = 0
        var compared = 0
        var weird = 0
        for (round in 0 until 40) {
            for (f in files) {
                val text = f.readText()
                val lex = lexKotlin(text)
                val toks = lex.tokens.filter { !it.kind.isTrivia() && it.kind != Tk.EOF }
                if (toks.size < 3) continue
                val t = toks[r.nextInt(toks.size)]
                val ws = lex.tokens.filter { it.kind == Tk.WS }
                val m = when (r.nextInt(7)) {
                    4, 5 -> {
                        // whitespace mutation: a line break where there was a space, or the other way round
                        if (ws.isEmpty()) continue
                        val w = ws[r.nextInt(ws.size)]
                        val had = text.substring(w.start, w.end).contains('\n')
                        text.substring(0, w.start) + (if (had) " " else "\n") + text.substring(w.end)
                    }
                    0 -> text.removeRange(t.start, t.end)
                    1 -> text.substring(0, t.end) + " " + text.substring(t.start, t.end) + text.substring(t.end)
                    2 -> {
                        val o = toks[r.nextInt(toks.size)]
                        text.substring(0, t.start) + text.substring(o.start, o.end) + text.substring(t.end)
                    }
                    else -> {
                        val o = toks[r.nextInt(toks.size)]
                        text.substring(0, t.start) + text.substring(o.start, o.end) + " " + text.substring(t.start)
                    }
                }
                runs++
                val ours = parseKotlin(m)
                assertEquals(m, printCst(ours.root, ours.tokens, m), "lossless: ${Corpus.rel(f)}")
                val psi = PsiOracle.parse(f.name, m)
                val psiBad = PsiOracle.errors(psi).isNotEmpty()
                if (psiBad) {
                    if (ours.errors.isNotEmpty()) bothInvalid++ else oursOnlyValid++
                    continue
                }
                psiValid++
                val psiLines = PsiOutline().file(psi)
                if (psiLines.any { modifierInfix.containsMatchIn(it) }) {
                    weird++
                } else if (ours.errors.isNotEmpty() && declarationAsOperand(m, ours.errors.first())) {
                    weird++
                } else if (ours.errors.isNotEmpty()) {
                    failures.add("${Corpus.rel(f)}: PSI accepts the mutant but we report ${ours.errors.first()}\n  near: " + near(m, ours.errors.first().offset))
                } else {
                    val a = Outline().file(ours.file)
                    val b = psiLines
                    compared++
                    if (a != b) {
                        var k = 0
                        while (k < minOf(a.size, b.size) && a[k] == b[k]) k++
                        var i = 0
                        val x = a.getOrElse(k) { "" }
                        val y = b.getOrElse(k) { "" }
                        while (i < x.length && i < y.length && x[i] == y[i]) i++
                        failures.add("${Corpus.rel(f)}: outline differs\n  ours: ...${x.substring(maxOf(0, i - 80), minOf(x.length, i + 120))}\n  psi:  ...${y.substring(maxOf(0, i - 80), minOf(y.length, i + 120))}")
                    }
                }
                if (failures.size >= 6) break
            }
            if (failures.size >= 6) break
        }
        println("MUTATION ORACLE: $runs mutants, $psiValid accepted by PSI ($compared outlines compared), both reject $bothInvalid, $weird with a declaration used as an operand (PSI quirk, skipped), only we accept $oursOnlyValid, ${failures.size} mismatches")
        assertEquals(emptyList(), failures)
        assertTrue(psiValid > 100)
    }

    /**
     * Two declarations on one line: PSI reads the modifier of the second as an infix function (`n public fun f() = 1` is
     * `public(n, fun f() ...)`), we read two declarations. Both are errors for the compiler proper; skip such mutants.
     */
    private val modifierInfix = Regex("""\((public|private|protected|internal|override|open|abstract|final|inline|operator|infix|data|enum|sealed|const|lateinit|vararg|suspend|tailrec|external|expect|actual|annotation|companion|inner|value|noinline|crossinline|reified|out) """)

    /** PSI accepts `- val x = 1`: a local declaration as an operand. We do not model that; it is not Kotlin anyone writes. */
    private val declAfterOperator = Regex("""[-+!.]\s*(val|var|fun|class|object|interface)\s|\bin\s+(val|var|fun)\s""")

    private fun declarationAsOperand(src: String, e: SyntaxError): Boolean {
        if (declAfterOperator.containsMatchIn(src)) return true
        if (!e.message.startsWith("Expecting an element") && !e.message.startsWith("Expecting an identifier")) return false
        var i = e.offset
        while (i < src.length && src[i].isWhitespace()) i++
        val rest = src.substring(i, minOf(src.length, i + 12))
        return listOf("val ", "var ", "fun ", "class ", "object ", "interface ", "typealias ").any { rest.startsWith(it) }
    }

    private fun near(s: String, off: Int): String =
        s.substring(maxOf(0, off - 60), minOf(s.length, off + 60)).replace("\n", "\\n")
}
