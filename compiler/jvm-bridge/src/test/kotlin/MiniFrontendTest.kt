import mini.backend.CEmitter
import mini.backend.MirWriter
import mini.bridge.Cli
import mini.bridge.Stdlib
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * B2-3: the own frontend (parser + sema + Ast2Ir, no K2) must produce exactly the MIR (and therefore the C) of the K2
 * path for every program in compiler/testdata, and the programs built from it must behave identically.
 */
class MiniFrontendTest {
    private val testdata = File(System.getProperty("mini.testdata") ?: error("mini.testdata not set"))
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val runtime = File(repoRoot, "runtime")
    private val work = Files.createTempDirectory("minic-mini-").toFile().also { it.deleteOnExit() }
    private val stdlib: List<File> by lazy { Stdlib.sources(Stdlib.findDir(null, repoRoot)!!) }

    private fun sources() = testdata.listFiles { f -> f.isFile && f.extension == "kt" && !File(f.parentFile, f.nameWithoutExtension + ".nok2").exists() }!!.sortedBy { it.name }
    private fun sibling(src: File, ext: String) = File(src.parentFile, src.nameWithoutExtension + "." + ext)

    /** a short description of how two MIR texts differ */
    private fun diffSummary(a: String, b: String): String {
        val x = a.lines()
        val y = b.lines()
        var i = 0
        while (i < x.size && i < y.size && x[i] == y[i]) i++
        val sb = StringBuilder()
        sb.append("K2 has ${x.size} lines, the own frontend ${y.size}; first difference at line ${i + 1}\n")
        var k = i
        var shown = 0
        while (shown < 3 && (k < x.size || k < y.size)) {
            sb.append("  K2:   ").append(if (k < x.size) x[k].take(300) else "<end>").append('\n')
            sb.append("  mini: ").append(if (k < y.size) y[k].take(300) else "<end>").append('\n')
            k++
            shown++
        }
        return sb.toString()
    }

    @TestFactory
    fun mirEqualsK2(): List<DynamicTest> = sources().filter { !sibling(it, "cerr").isFile }.map { src ->
        DynamicTest.dynamicTest(src.nameWithoutExtension) {
            val k2 = Cli.frontendToMir(listOf(src), stdlib, emptyList(), false, false)
            val mini = Cli.frontendToMir(listOf(src), stdlib, emptyList(), false, true)
            val a = MirWriter.write(k2)
            val b = MirWriter.write(mini)
            if (a != b) throw AssertionError("MIR of ${src.name} differs:\n" + diffSummary(a, b))
            // identical MIR gives identical C (same backend); checked anyway
            assertEquals(CEmitter.emit(k2, rcOpt = true), CEmitter.emit(mini, rcOpt = true), "C of ${src.name}")
        }
    }

    @TestFactory
    fun programsRun(): List<DynamicTest> = sources().map { src -> DynamicTest.dynamicTest(src.nameWithoutExtension) { check(src) } }

    private fun check(src: File) {
        val name = src.nameWithoutExtension
        val exe = File(work, name)
        val errBuf = ByteArrayOutputStream()
        val oldErr = System.err
        val rc = try {
            System.setErr(PrintStream(errBuf, true, "UTF-8"))
            Cli.run(
                listOf(
                    "build", src.path, *CInteropTestData.cArgs(src), "-o", exe.path, "--frontend=mini",
                    "--runtime-src", File(runtime, "src").path,
                    "--runtime-include", File(runtime, "include").path, "--cflag", "-Werror",
                ),
                repoRoot,
            )
        } finally {
            System.setErr(oldErr)
        }
        val compileErr = errBuf.toString("UTF-8")
        val cerr = sibling(src, "cerr")
        if (cerr.isFile) {
            assertEquals(1, rc, "expected a compile error for $name, got exit $rc\n$compileErr")
            for (line in cerr.readLines().filter { it.isNotBlank() }) {
                assertTrue(line in compileErr, "stderr of $name should contain: $line\nactual:\n$compileErr")
            }
            return
        }
        assertEquals(0, rc, "build of $name with the own frontend failed:\n$compileErr")
        val proc = ProcessBuilder(exe.path).start()
        val stdout = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = proc.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "$name timed out")
        assertEquals(sibling(src, "out").readText(), stdout, "stdout of $name differs")
        val err = sibling(src, "err")
        if (err.isFile) {
            assertTrue(proc.exitValue() != 0, "$name should have failed at run time")
            for (line in err.readLines().filter { it.isNotBlank() }) assertTrue(line in stderr, "stderr of $name should contain: $line")
        } else assertEquals(0, proc.exitValue(), "$name exited with ${proc.exitValue()}\n$stderr")
    }
}
