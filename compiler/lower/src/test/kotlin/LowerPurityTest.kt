import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-hosting rule (docs/plan-kotlin.md section 10a): the lowering (typed AST to MIR, bootstrap stage B2-3) is pure
 * common Kotlin. Its main sources must not use `java.*`, `javax.*`, `kotlin.jvm.*`, `@Jvm*` annotations or JVM
 * reflection, and the module may only depend on the other pure modules (frontend, backend).
 */
class LowerPurityTest {
    private val forbidden = listOf(
        Regex("""\bjava\."""),
        Regex("""\bjavax\."""),
        Regex("""\bkotlin\.jvm\b"""),
        Regex("""\bkotlin\.reflect\b"""),
        Regex("""@Jvm[A-Z]"""),
        Regex("""::class\.java\b"""),
        Regex("""\.javaClass\b"""),
        Regex("""\bClass\.forName\b"""),
        Regex("""\bSystem\."""),
    )

    private fun violations(fileName: String, text: String): List<String> {
        val out = ArrayList<String>()
        text.lines().forEachIndexed { i, line ->
            for (r in forbidden) {
                if (r.containsMatchIn(line)) out += "$fileName:${i + 1}: matches ${r.pattern}: ${line.trim()}"
            }
        }
        return out
    }

    @Test
    fun lowerMainSourcesAreFreeOfJvmApis() {
        val dir = File(System.getProperty("mini.lowerMain") ?: error("mini.lowerMain not set"))
        val files = dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue(files.isNotEmpty(), "no sources found under $dir")
        assertTrue(files.none { it.extension == "java" }, "the lowering must not contain Java sources")
        val found = files.flatMap { violations(it.name, it.readText()) }
        assertEquals(emptyList(), found, "JVM API use in compiler/lower:\n" + found.joinToString("\n"))
    }

    @Test
    fun lowerDependsOnlyOnThePureModules() {
        val text = File(System.getProperty("mini.lowerBuildFile") ?: error("mini.lowerBuildFile not set")).readText()
        val deps = text.lines().map { it.trim() }.filter {
            !it.startsWith("//") && (it.startsWith("implementation") || it.startsWith("api(") || it.startsWith("compileOnly") || it.startsWith("runtimeOnly"))
        }
        assertEquals(listOf("implementation(project(\":frontend\"))", "implementation(project(\":backend\"))"), deps)
    }

    @Test
    fun theCheckerActuallyDetectsViolations() {
        assertTrue(violations("x.kt", "import java.io.File").isNotEmpty())
        assertTrue(violations("x.kt", "import kotlin.jvm.JvmStatic").isNotEmpty())
        assertTrue(violations("x.kt", "@JvmStatic fun f() {}").isNotEmpty())
        assertTrue(violations("x.kt", "val c = Foo::class.java").isNotEmpty())
        assertTrue(violations("x.kt", "System.currentTimeMillis()").isNotEmpty())
        assertTrue(violations("x.kt", "val x = 1 + 2").isEmpty())
    }
}
