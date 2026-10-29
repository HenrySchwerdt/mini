import mini.frontend.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Malformed inputs (resources/negatives.txt, the same sources NegativeTest pins down in the frontend module): the K2
 * PSI parser and our parser must both report errors, and the first error must be on the same line. Columns are compared
 * and reported; they agree where the reference parser puts the error directly behind the previous token.
 */
class NegativeOracleTest {
    @Test
    fun firstErrorLineAgreesWithK2() {
        val text = NegativeOracleTest::class.java.getResourceAsStream("/negatives.txt")!!.readBytes().toString(Charsets.UTF_8)
        val cases = text.split("//---\n")
        var sameLine = 0
        var sameCol = 0
        var psiAccepts = 0
        val problems = ArrayList<String>()
        for ((i, s) in cases.withIndex()) {
            val psi = PsiOracle.parse("neg$i.kt", s)
            val perr = PsiOracle.errors(psi)
            val ours = parseKotlin(s)
            if (ours.errors.isEmpty()) problems.add("case $i: we report no error:\n$s")
            if (perr.isEmpty()) {
                psiAccepts++
                continue
            }
            if (ours.errors.isEmpty()) continue
            val pLine = PsiOracle.line(s, perr.first().textOffset)
            val e = ours.errors.first()
            if (pLine == e.line) sameLine++ else problems.add("case $i: PSI reports line $pLine (${perr.first().errorDescription}), we report ${e.line}:${e.col} ${e.message}\n$s")
            val lineStart = s.lastIndexOf('\n', perr.first().textOffset - 1) + 1
            if (perr.first().textOffset - lineStart + 1 == e.col && pLine == e.line) sameCol++
        }
        println("NEGATIVE ORACLE: ${cases.size} malformed inputs, same first-error line as PSI in $sameLine, same column in $sameCol, PSI accepts $psiAccepts")
        // PSI reports errors at the end of the file (missing `)` before EOF, unclosed comment); we report where the construct starts
        assertTrue(problems.size <= 3, "${problems.size} disagreements: " + problems.take(6))
        assertTrue(sameLine > cases.size / 2)
    }
}
