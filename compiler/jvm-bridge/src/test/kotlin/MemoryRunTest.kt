import mini.bridge.Cli
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Accepted programs of the memory corpus (the `.out` files in compiler/testdata/memory) are built natively with
 * `minic build` (checker first, mini.memory API passed with `--extra-src`) and run; stdout must equal the `.out` file
 * that was produced by the JVM. Programs that use constructs the C backend cannot lower yet (context parameters,
 * lambdas, generics, ...) fail the build with `unsupported construct`; they are skipped (reported in
 * build/reports/memory-run-summary.txt), every other build failure is a test failure.
 */
class MemoryRunTest {
    private val corpus = File(System.getProperty("mini.memoryCorpus") ?: error("mini.memoryCorpus not set"))
    private val api = File(System.getProperty("mini.memoryApi") ?: error("mini.memoryApi not set"))
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val runtime = File(repoRoot, "runtime")
    private val work = Files.createTempDirectory("minic-memrun-").toFile().also { it.deleteOnExit() }
    private val apiFiles = api.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }
    private val ran = mutableListOf<String>()
    private val skipped = mutableListOf<String>()

    private fun summary() {
        val f = File("build/reports/memory-run-summary.txt")
        f.parentFile.mkdirs()
        f.writeText("built and run natively: ${ran.joinToString(", ")}\nnot compilable by the C backend yet: ${skipped.joinToString(", ")}\n")
    }

    @TestFactory
    fun acceptedProgramsRunNatively(): List<DynamicTest> {
        val programs = corpus.listFiles { f -> f.isFile && f.extension == "kt" && File(f.parentFile, f.nameWithoutExtension + ".out").isFile }!!
            .sortedBy { it.name }
        return programs.map { src ->
            DynamicTest.dynamicTest(src.nameWithoutExtension) {
                val name = src.nameWithoutExtension
                val exe = File(work, name)
                val errBuf = ByteArrayOutputStream()
                val oldErr = System.err
                val rc = try {
                    System.setErr(PrintStream(errBuf, true, "UTF-8"))
                    Cli.run(
                        listOf(
                            "build", src.path, "-o", exe.path,
                            "--runtime-src", File(runtime, "src").path,
                            "--runtime-include", File(runtime, "include").path,
                        ) + apiFiles.flatMap { listOf("--extra-src", it.path) },
                        repoRoot,
                    )
                } finally {
                    System.setErr(oldErr)
                }
                val err = errBuf.toString("UTF-8")
                if (rc != 0) {
                    val unsupported = err.contains("unsupported construct")
                    synchronized(skipped) { if (unsupported) skipped += name; summary() }
                    assumeTrue(!unsupported, "not compilable by the C backend yet: " + err.lines().firstOrNull { it.contains("unsupported") })
                    assertEquals(0, rc, "build failed:\n$err")
                }
                val p = ProcessBuilder(exe.path).redirectErrorStream(false).start()
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                val finished = p.waitFor(60, TimeUnit.SECONDS)
                assertTrue(finished, "timeout")
                synchronized(ran) { ran += name; summary() }
                assertEquals(0, p.exitValue(), "exit code of $name")
                assertEquals(File(src.parentFile, "$name.out").readText(), out, "stdout of $name")
            }
        }
    }
}
