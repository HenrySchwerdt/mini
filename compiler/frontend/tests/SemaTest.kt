import mini.frontend.sema.Sema
import mini.frontend.sema.SourceInput
import mini.frontend.sema.analyzeSources
import mini.frontend.sema.dumpText
import mini.io.readTextFile
import mini.io.walkFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// `mini test` runs a test in the package directory (compiler/frontend)
private const val repoRoot = "../.."

/** Unit tests of the semantic analysis (the agreement with K2 is tested by `SemaOracleTest` in frontend-oracle). */
class SemaTest {

    private val stdlib: List<SourceInput> by lazy {
        walkFiles("$repoRoot/stdlib", ".kt").map { SourceInput(it, readTextFile(it)) }
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

    /** a file's result may not depend on which unrelated files are analysed with it */
    @Test
    fun resultsDoNotDependOnUnrelatedFiles() {
        val files = listOf("compiler/backend/src", "compiler/frontend/src").flatMap { d ->
            walkFiles("$repoRoot/$d", ".kt").map { SourceInput(it, readTextFile(it)) }
        }
        val base = stdlib + files
        val extra = SourceInput("extra/Unrelated.kt", "package extra\nclass Zed<T>(val v: T) { fun f() = listOf(v).map { it.hashCode() } }\nfun <T> id(x: T): T = x\n")
        val a = analyzeSources(base)
        val b = analyzeSources(listOf(extra) + base)
        val da = HashMap<String, String>()
        for (f in a.files) da[f.path] = a.dumpText(f)
        for (f in b.files) {
            if (f.path == extra.path) continue
            assertEquals(da[f.path], b.dumpText(f), f.path)
        }
    }

    @Test
    fun matmulOperatorResolvesToOperatorFunMatmul() {
        val ok = analyze(
            """
            class M(val v: Int) {
                operator fun matmul(o: M): M = M(v * o.v)
            }
            operator fun M.matmul(k: Int): M = M(v * k)
            class A(var v: Int) { operator fun matmulAssign(k: Int) { v += k } }
            fun main() {
                val a = M(2)
                val b = a @ a @ 3
                var c = a
                c @= a
                A(1) @= 2
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), ok.diags)
        assertTrue("M.matmul(" in ok.dump, ok.dump)
        assertTrue("A.matmulAssign(" in ok.dump, ok.dump)
    }

    @Test
    fun matmulNeedsAnOperatorFunction() {
        val missing = analyze("class P\nfun main() { val x = P() @ P() }\n")
        assertTrue(missing.diags.any { "no operator matmul for P" in it }, missing.diags.toString())
        val notOperator = analyze("class P { fun matmul(o: P): P = o }\nfun main() { val x = P() @ P() }\n")
        assertTrue(notOperator.diags.any { "no operator matmul for P" in it }, notOperator.diags.toString())
        val noDefault = analyze("fun main() { val x = 1 @ 2 }\n")
        assertTrue(noDefault.diags.any { "no operator matmul for" in it }, noDefault.diags.toString())
    }

    @Test
    fun matmulOperatorChecksArityAndReturnType() {
        val arity = analyze("class P { operator fun matmul(a: P, b: P): P = a }\n")
        assertTrue(arity.diags.any { "'matmul' must have exactly one parameter" in it }, arity.diags.toString())
        val none = analyze("class P { operator fun matmul(): P = this }\n")
        assertTrue(none.diags.any { "exactly one parameter" in it }, none.diags.toString())
        val ret = analyze("class P { operator fun matmulAssign(a: P): P = a }\n")
        assertTrue(ret.diags.any { "'matmulAssign' must return Unit" in it }, ret.diags.toString())
    }

    @Test
    fun matmulAssignWithMatmulOnAVarIsAmbiguous() {
        val code = """
            class P {
                operator fun matmul(o: P): P = o
                operator fun matmulAssign(o: P) {}
            }
            fun main() {
                val v = P()
                v @= P()
                var w = P()
                w @= P()
            }
        """.trimIndent()
        val r = analyze(code)
        assertEquals(1, r.diags.count { "ambiguous assignment" in it }, r.diags.toString())
    }
}
