import mini.frontend.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LosslessTest {
    @Test
    fun lexerIsLosslessOnAllRepoSources() {
        var n = 0
        for (f in Corpus.files) {
            val text = f.readText()
            val r = lexKotlin(text)
            val sb = StringBuilder()
            var pos = 0
            for (t in r.tokens) {
                assertEquals(pos, t.start, "gap before token $t in ${Corpus.rel(f)}")
                sb.append(text, t.start, t.end)
                pos = t.end
            }
            assertEquals(text.length, pos)
            assertEquals(text, sb.toString())
            assertEquals(emptyList(), r.errors.map { it.toString() }, "lexical errors in ${Corpus.rel(f)}")
            n++
        }
        println("lexed $n files")
        assertTrue(n > 200)
    }

    @Test
    fun parserIsLosslessAndErrorFreeOnAllRepoSources() {
        var n = 0
        val failures = ArrayList<String>()
        for (f in Corpus.files) {
            val text = f.readText()
            val r = parseKotlin(text)
            val printed = printCst(r.root, r.tokens, text)
            if (printed != text) failures.add("${Corpus.rel(f)}: CST print differs from the source")
            if (r.errors.isNotEmpty()) failures.add("${Corpus.rel(f)}: ${r.errors.take(3).joinToString("; ")}")
            n++
        }
        println("parsed $n files")
        assertEquals(emptyList(), failures.take(30), "${failures.size} failures")
    }
}
