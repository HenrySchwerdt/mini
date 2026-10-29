import mini.frontend.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural oracle: for every Kotlin file of the repository the declaration outline (kind, name, modifiers, type
 * parameters, parameters, types, nesting) and the fully parenthesised rendering of every expression of our AST must be
 * identical to the same rendering of the PSI tree of the K2 compiler.
 */
class OutlineOracleTest {
    private fun firstDiff(a: String, b: String): String {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        val from = maxOf(0, i - 100)
        return "at char $i:\n  ours: ..." + a.substring(from, minOf(a.length, i + 150)) + "\n  psi:  ..." + b.substring(from, minOf(b.length, i + 150))
    }

    @Test
    fun outlineAndExpressionsMatchK2OnAllRepoSources() {
        var files = 0
        var decls = 0
        var exprs = 0
        var types = 0
        var lines = 0L
        val failures = ArrayList<String>()
        for (f in Corpus.files) {
            val text = f.readText()
            val ours = parseKotlin(text)
            val psi = PsiOracle.parse(f.name, text)
            val psiErrs = PsiOracle.errors(psi)
            if (psiErrs.isNotEmpty()) failures.add("${Corpus.rel(f)}: PSI itself reports errors: ${psiErrs.first().errorDescription}")
            val o = Outline()
            val ourLines = o.file(ours.file)
            val psiLines = PsiOutline().file(psi)
            decls += o.decls
            exprs += o.exprs
            types += o.types
            lines += text.lines().size
            files++
            if (ourLines != psiLines) {
                val n = minOf(ourLines.size, psiLines.size)
                var k = 0
                while (k < n && ourLines[k] == psiLines[k]) k++
                val a = ourLines.getOrElse(k) { "<missing>" }
                val b = psiLines.getOrElse(k) { "<missing>" }
                failures.add("${Corpus.rel(f)}: outline line $k differs ${firstDiff(a, b)}")
            }
        }
        println("OUTLINE ORACLE: $files files, $lines lines, $decls declarations, $exprs expressions, $types types compared; ${failures.size} mismatching files")
        assertTrue(files > 200)
        assertEquals(emptyList(), failures.take(12), "${failures.size} mismatches")
    }
}
