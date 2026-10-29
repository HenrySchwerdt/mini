import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `minic-native` is compiled by minic itself (docs/plan-kotlin.md section 10b), so its main sources are pure Kotlin: no
 * `java.*`, `kotlin.jvm.*`, reflection or `System`. Files are accessed through `mini.io`; the module depends only on
 * the other pure modules.
 */
class MinicNativePurityTest {
    private val forbidden = listOf(
        Regex("""\bjava\."""),
        Regex("""\bjavax\."""),
        Regex("""\bkotlin\.jvm\b"""),
        Regex("""\bkotlin\.reflect\b"""),
        Regex("""\bkotlin\.system\b"""),
        Regex("""@Jvm[A-Z]"""),
        Regex("""::class\.java\b"""),
        Regex("""\.javaClass\b"""),
        Regex("""\bSystem\."""),
    )

    @Test
    fun mainSourcesAreFreeOfJvmApis() {
        val dir = File(System.getProperty("mini.driverMain") ?: error("mini.driverMain not set"))
        val files = dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue(files.isNotEmpty())
        assertTrue(files.none { it.extension == "java" })
        val found = ArrayList<String>()
        for (f in files) f.readLines().forEachIndexed { i, line ->
            for (r in forbidden) if (r.containsMatchIn(line)) found += "${f.name}:${i + 1}: ${line.trim()}"
        }
        assertEquals(emptyList(), found, "JVM API use in compiler/minic-native:\n" + found.joinToString("\n"))
    }

    @Test
    fun dependsOnlyOnThePureModules() {
        val text = File(System.getProperty("mini.driverBuildFile") ?: error("mini.driverBuildFile not set")).readText()
        val deps = text.lines().map { it.trim() }.filter { !it.startsWith("//") && it.startsWith("implementation") }
        assertEquals(
            listOf("implementation(project(\":lower\"))", "implementation(project(\":frontend\"))", "implementation(project(\":backend\"))"),
            deps,
        )
    }
}
