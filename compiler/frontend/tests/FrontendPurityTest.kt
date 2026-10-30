import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-hosting rule (docs/plan-kotlin.md section 10a): the frontend is pure common Kotlin.
 * Its main sources must not use `java.*`, `javax.*`, `kotlin.jvm.*`, `@Jvm*` annotations or
 * JVM reflection. This test lives in the test source set, which may use the JVM.
 */
class FrontendPurityTest {
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
    fun frontendMainSourcesAreFreeOfJvmApis() {
        val dir = File(System.getProperty("mini.frontendMain") ?: error("mini.frontendMain not set"))
        val files = dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue(files.isNotEmpty(), "no sources found under $dir")
        assertTrue(files.none { it.extension == "java" }, "the frontend must not contain Java sources")
        val found = files.flatMap { violations(it.name, it.readText()) }
        assertEquals(emptyList(), found, "JVM API use in compiler/frontend:\n" + found.joinToString("\n"))
    }

    @Test
    fun frontendDependsOnNothingButTheStandardLibrary() {
        val text = File(System.getProperty("mini.frontendBuildFile") ?: error("mini.frontendBuildFile not set")).readText()
        val deps = text.lines().map { it.trim() }.filter {
            !it.startsWith("//") && (it.startsWith("implementation") || it.startsWith("api(") || it.startsWith("compileOnly") || it.startsWith("runtimeOnly"))
        }
        assertEquals(emptyList(), deps, "compiler/frontend must not declare main dependencies")
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
