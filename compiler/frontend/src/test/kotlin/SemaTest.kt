import mini.frontend.sema.Sema
import mini.frontend.sema.SourceInput
import mini.frontend.sema.analyzeSources
import mini.frontend.sema.dumpText
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests of the semantic analysis (the agreement with K2 is tested by `SemaOracleTest` in frontend-oracle). */
class SemaTest {
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))

    private val stdlib: List<SourceInput> by lazy {
        File(repoRoot, "stdlib").walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }
            .map { SourceInput(it.path, it.readText()) }.toList()
    }

    private class Run(val sema: Sema, val dump: String, val diags: List<String>)

    private fun analyze(code: String): Run {
        val sema = analyzeSources(stdlib + SourceInput("test.kt", code))
        val f = sema.files.last()
        return Run(sema, sema.dumpText(f), sema.diags.filter { it.path == "test.kt" }.map { it.message })
    }

    @Test
    fun resolvesCallsAndInfersTypes() {
        val r = analyze(
            """
            fun main() {
                println("x")
                val l = listOf(1, 2).map { it * 2 }
                val s = l.size
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), r.diags)
        assertTrue("kotlin.Unit => kotlin.io.println(kotlin.Any?)" in r.dump, r.dump)
        assertTrue("kotlin.collections.List<kotlin.Int> => kotlin.collections.map(" in r.dump, r.dump)
        assertTrue("kotlin.Int => kotlin.collections.List.size" in r.dump, r.dump)
    }

    @Test
    fun smartCastsAndNullability() {
        val r = analyze(
            """
            fun f(x: Any?, y: String?): Int {
                if (y == null) return 0
                return if (x is String) x.length + y.length else y.length
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), r.diags)
        assertTrue("kotlin.Int => kotlin.String.length" in r.dump, r.dump)
        assertTrue("SmartCast :: kotlin.String" in r.dump, r.dump)
    }

    @Test
    fun overloadResolutionPicksMostSpecific() {
        val r = analyze("fun f(a: Int, b: Long) = a + 1 + b")
        assertEquals(emptyList(), r.diags)
        assertTrue("kotlin.Long => kotlin.Int.plus(kotlin.Long)" in r.dump, r.dump)
        assertTrue("kotlin.Int => kotlin.Int.plus(kotlin.Int)" in r.dump, r.dump)
    }

    @Test
    fun implicitReturnTypesAndCycles() {
        val r = analyze(
            """
            fun a() = b() + 1
            fun b() = 2
            fun c() = d()
            fun d() = c()
            """.trimIndent(),
        )
        assertTrue(r.diags.any { "recursive" in it }, r.diags.toString())
        assertTrue("kotlin.Int => b()" in r.dump, r.dump)
    }

    @Test
    fun unresolvedReferencesAndMismatchesAreReported() {
        val r = analyze(
            """
            fun f(): Int {
                val a: Int = "s"
                return zzz
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.any { "type mismatch" in it }, r.diags.toString())
        assertTrue(r.diags.any { "unresolved reference 'zzz'" in it }, r.diags.toString())
    }

    @Test
    fun unsupportedConstructsAreReportedNotGuessed() {
        val r = analyze(
            """
            fun f() {
                val u = 1u
                typealias Local = Int
            }
            """.trimIndent(),
        )
        assertTrue(r.diags.any { it.startsWith("unsupported: unsigned literal") }, r.diags.toString())
    }

    @Test
    fun generatedMembersOfDataAndEnumClasses() {
        val r = analyze(
            """
            data class P(val x: Int, val y: String)
            enum class C { A, B }
            fun f(p: P) {
                val (a, b) = p
                val q = p.copy(x = 1)
                val e = C.valueOf("A")
                val all = C.values()
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), r.diags)
        assertTrue("DestructEntry :: kotlin.String => local b@" in r.dump, r.dump)
        assertTrue("P.copy(kotlin.Int,kotlin.String)" in r.dump, r.dump)
        assertTrue("C.valueOf(kotlin.String)" in r.dump, r.dump)
    }
}
