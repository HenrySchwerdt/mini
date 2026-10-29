import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * Corpus of docs/memory-model.md (test first, the analysis does not exist yet).
 *
 *  - Every program in testdata/memory plus std/kotlin-mini/mini/memory compiles with the real
 *    kotlinc (K2): the principle "every Mini program is a valid Kotlin program".
 *  - `name.diag` lists the expected Mini diagnostics (`line:col: code`, one per line). The same
 *    information is written in the source as caret lines (`//   ^ M0102`, caret under the
 *    column); this test checks that both agree, so the columns cannot rot silently.
 *  - `name.out` is the expected stdout of accepted programs; they are executed on the JVM
 *    (where `free` and allocators are no-ops) and compared. `-Pregen` rewrites the files.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryCorpusTest {
    private val corpus = File(System.getProperty("mini.memoryCorpus") ?: error("mini.memoryCorpus not set"))
    private val api = File(System.getProperty("mini.memoryApi") ?: error("mini.memoryApi not set"))
    private val regen = System.getProperty("mini.regen") == "true"

    private val programs: List<File> =
        corpus.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }

    private val classes: File by lazy { compileAll() }

    private val codePattern = Regex("^M0[12]\\d\\d$")
    private val markerPattern = Regex("^(\\s*//\\s*)\\^\\s+(M\\d{4})\\b.*$")

    private fun compileAll(): File {
        val out = Files.createTempDirectory("memory-corpus-").toFile().also { it.deleteOnExit() }
        val sources = api.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() + programs
        val errors = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError) errors += "${location?.path}:${location?.line}:${location?.column}: $message"
            }
        }
        val compiler = K2JVMCompiler()
        val args = compiler.createArguments()
        args.freeArgs = sources.map { it.absolutePath }
        args.destination = out.absolutePath
        args.noStdlib = true
        args.noReflect = true
        args.classpath = File(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
        args.moduleName = "memory-corpus"
        args.jvmTarget = "21"
        val code = compiler.exec(collector, Services.EMPTY, args)
        assertTrue(errors.isEmpty() && code == ExitCode.OK, "kotlinc rejected the corpus ($code):\n" + errors.joinToString("\n"))
        return out
    }

    @Test
    fun corpusIsValidKotlin() {
        assertTrue(programs.size >= 40, "expected at least 40 programs, found ${programs.size}")
        classes // forces compilation; fails with the compiler messages
    }

    private fun sibling(src: File, ext: String) = File(src.parentFile, src.nameWithoutExtension + "." + ext)

    /** `line:col: code` entries from the caret lines of a source file. */
    private fun markers(src: File): List<String> {
        val lines = src.readLines()
        val result = mutableListOf<String>()
        var target = 0
        for ((i, line) in lines.withIndex()) {
            val m = markerPattern.matchEntire(line)
            if (m == null) {
                target = i + 1
            } else {
                result += "$target:${m.groupValues[1].length + 1}: ${m.groupValues[2]}"
            }
        }
        return result
    }

    @TestFactory
    fun expectations(): List<DynamicTest> = programs.map { src ->
        DynamicTest.dynamicTest(src.nameWithoutExtension) { checkExpectations(src) }
    }

    private fun checkExpectations(src: File) {
        val name = src.nameWithoutExtension
        val lines = src.readLines()
        assertTrue(lines.first().startsWith("//"), "$name: the first line must be a header comment")
        assertTrue(lines.any { it.contains("package memory.$name") }, "$name: must declare `package memory.$name`")
        val diag = sibling(src, "diag")
        val out = sibling(src, "out")
        assertTrue(diag.isFile || out.isFile || regen, "$name: needs a .diag (rejected) or .out (accepted) file")

        val marked = markers(src)
        if (diag.isFile) {
            val expected = diag.readLines().filter { it.isNotBlank() }
            for (e in expected) {
                assertTrue(Regex("^\\d+:\\d+: M\\d{4}$").matches(e), "$name.diag: bad line '$e'")
                assertTrue(codePattern.matches(e.substringAfterLast(' ')), "$name.diag: unknown code in '$e'")
            }
            assertTrue(expected.isNotEmpty(), "$name.diag must not be empty")
            assertEquals(expected, marked, "$name: .diag and the ^ markers in the source disagree")
        } else {
            assertTrue(marked.isEmpty(), "$name: accepted programs must not carry ^ markers")
        }

        if (out.isFile || (regen && !diag.isFile)) {
            val actual = runOnJvm(name)
            if (regen) out.writeText(actual)
            assertEquals(out.readText(), actual, "$name: stdout differs")
        }
    }

    private fun runOnJvm(name: String): String {
        val className = "memory.$name." + name.replaceFirstChar { it.uppercase() } + "Kt"
        val loader = URLClassLoader(arrayOf(classes.toURI().toURL()), javaClass.classLoader)
        val main = loader.loadClass(className).getDeclaredMethod("main")
        val buf = ByteArrayOutputStream()
        val old = System.out
        try {
            System.setOut(PrintStream(buf, true, "UTF-8"))
            main.invoke(null)
        } finally {
            System.out.flush()
            System.setOut(old)
        }
        return buf.toString("UTF-8")
    }
}
