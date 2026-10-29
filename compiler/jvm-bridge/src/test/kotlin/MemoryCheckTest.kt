import mini.bridge.MemoryCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Runs the Strict-mode ownership checker (`minic check`) on every program of the memory-model corpus
 * (compiler/testdata/memory, docs/memory-model.md section 11):
 *  - `<name>.diag` lists the expected diagnostics (`line:col: CODE`, warnings included); the multiset must match exactly.
 *  - programs without `.diag` are accepted: there must be no diagnostics at all.
 * The mini.memory API sources are compiled together with each program (`--extra-src`).
 * A per-code pass rate is written to build/reports/memory-check-summary.txt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryCheckTest {
    private val corpus = File(System.getProperty("mini.memoryCorpus") ?: error("mini.memoryCorpus not set"))
    private val api = File(System.getProperty("mini.memoryApi") ?: error("mini.memoryApi not set"))
    private val apiFiles = api.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }
    // additional programs written for the checker itself (the spec corpus above is read-only)
    private val extra = File(System.getProperty("mini.memoryExtra") ?: error("mini.memoryExtra not set"))
    private val programs = (corpus.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name } +
        extra.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name })

    /** code -> (found, expected) over the diagnostics expected by the corpus; "accept" counts clean accepted programs. */
    private val perCode = sortedMapOf<String, IntArray>()
    private val failed = mutableListOf<String>()

    private fun expected(src: File): List<String> {
        val d = File(src.parentFile, src.nameWithoutExtension + ".diag")
        return if (d.isFile) d.readLines().map { it.trim() }.filter { it.isNotEmpty() }.sorted() else emptyList()
    }

    private fun writeSummary() {
        val out = File("build/reports/memory-check-summary.txt")
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("per-code pass rate (expected diagnostics found exactly / expected):")
                for ((k, v) in perCode) appendLine("  $k: ${v[0]}/${v[1]}")
                appendLine("failing programs: " + failed.joinToString(", "))
            },
        )
    }

    @TestFactory
    fun corpus(): List<DynamicTest> = programs.map { src ->
        DynamicTest.dynamicTest(src.nameWithoutExtension) {
            val want = expected(src)
            val r = MemoryCheck.check(src, apiFiles)
            val got = r.brief().sorted()
            val ok = want == got
            synchronized(perCode) {
                if (want.isEmpty()) {
                    val c = perCode.getOrPut("accept") { IntArray(2) }
                    c[1]++
                    if (got.isEmpty()) c[0]++
                }
                for (w in want) {
                    val code = w.substringAfter(": ")
                    val c = perCode.getOrPut(code) { IntArray(2) }
                    c[1]++
                    if (ok) c[0]++
                }
                if (!ok) failed += src.nameWithoutExtension
                writeSummary()
            }
            assertEquals(want, got, "diagnostics of ${src.name}\n" + r.render() + r.failures.joinToString("\n"))
        }
    }

    @Test
    fun checkerCliRejectsAndAccepts() {
        val bad = File(corpus, "uaf_simple.kt")
        val good = File(corpus, "ok_heap_basic.kt")
        val rc1 = mini.bridge.Cli.run(listOf("check", bad.path) + apiFiles.flatMap { listOf("--extra-src", it.path) })
        val rc2 = mini.bridge.Cli.run(listOf("check", good.path) + apiFiles.flatMap { listOf("--extra-src", it.path) })
        assertEquals(1, rc1)
        assertEquals(0, rc2)
    }
}
