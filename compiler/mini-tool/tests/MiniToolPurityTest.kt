package mini.tool

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-hosting rule (docs/plan-kotlin.md section 10a/10f): the core of `mini` is pure Kotlin so that, after B3, it can
 * be compiled by `minic` itself. Everything outside `mini/tool/jvm` must not use `java.*`, `javax.*`, `kotlin.jvm`, `@Jvm*`,
 * JVM reflection or `System.*`; the JVM is reachable only through the `Platform` interface.
 */
class MiniToolPurityTest {
    private val forbidden = listOf(
        Regex("""\bjava\."""),
        Regex("""\bjavax\."""),
        Regex("""\bkotlin\.jvm\b"""),
        Regex("""\bkotlin\.reflect\b"""),
        Regex("""\bkotlin\.system\b"""),
        Regex("""@Jvm[A-Z]"""),
        Regex("""::class\.java\b"""),
        Regex("""\.javaClass\b"""),
        Regex("""\bClass\.forName\b"""),
        Regex("""\bSystem\."""),
        Regex("""\bProcessBuilder\b"""),
        Regex("""\bkotlin\.io\.path\b"""),
    )

    private fun violations(fileName: String, text: String): List<String> {
        val out = ArrayList<String>()
        text.lines().forEachIndexed { i, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return@forEachIndexed // comments may mention java.*
            val code = line.substringBefore("//")
            for (r in forbidden) if (r.containsMatchIn(code)) out += "$fileName:${i + 1}: matches ${r.pattern}: ${line.trim()}"
        }
        return out
    }

    private fun coreFiles(): List<File> {
        val dir = File(System.getProperty("mini.toolMain") ?: error("mini.toolMain not set"))
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.relativeTo(dir).path.startsWith("jvm") }.toList()
    }

    @Test
    fun coreIsFreeOfJvmApis() {
        val files = coreFiles()
        assertTrue(files.size >= 10, "core sources not found")
        val found = files.flatMap { violations(it.name, it.readText()) }
        assertEquals(emptyList(), found, "JVM API use in the core of mini (only mini/tool/jvm may touch the JVM):\n" + found.joinToString("\n"))
    }

    @Test
    fun jvmPackageIsTheOnlyPlaceWithJavaImports() {
        val dir = File(System.getProperty("mini.toolMain"))
        val jvm = File(dir, "jvm").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(jvm.isNotEmpty())
        assertTrue(jvm.any { violations(it.name, it.readText()).isNotEmpty() }, "the JVM platform is expected to use java.*")
    }

    @Test
    fun theCheckerDetectsViolations() {
        assertTrue(violations("x.kt", "import java.io.File").isNotEmpty())
        assertTrue(violations("x.kt", "import kotlin.jvm.JvmStatic").isNotEmpty())
        assertTrue(violations("x.kt", "System.getenv(\"A\")").isNotEmpty())
        assertTrue(violations("x.kt", "val p = ProcessBuilder()").isNotEmpty())
        assertTrue(violations("x.kt", "val x = 1 // java.io is fine in a comment").isEmpty())
        assertTrue(violations("x.kt", "val x = 1 + 2").isEmpty())
    }

    @Test
    fun onlyThePureFrontendIsADependency() {
        val text = File(System.getProperty("mini.toolMain")).resolve("../../../../../build.gradle.kts").normalize().readText()
        val deps = text.lines().map { it.trim() }.filter { it.startsWith("implementation(") }
        assertEquals(
            listOf("implementation(project(\":frontend\"))", "implementation(\"org.jetbrains.kotlin:kotlin-stdlib:\${property(\"kotlinVersion\")}\")"),
            deps,
            "compiler/mini-tool may only depend on the pure frontend and the Kotlin stdlib",
        )
    }
}
