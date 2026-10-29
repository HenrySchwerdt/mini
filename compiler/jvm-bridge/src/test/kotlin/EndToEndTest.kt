import mini.bridge.Cli
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
 * Builds each .kt file in compiler/testdata with the CLI against the real runtime (runtime)
 * and checks it:
 *  - `<name>.cerr` present: the build must fail (exit 1) and stderr must contain every line of the file.
 *  - otherwise `<name>.out` is the expected stdout; `<name>.err` (optional) lists substrings
 *    expected on stderr, in which case the program must also exit non-zero (a panic).
 */
class EndToEndTest {
    private val testdata = File(System.getProperty("mini.testdata") ?: error("mini.testdata not set"))
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val runtime = File(repoRoot, "runtime")
    private val work = Files.createTempDirectory("minic-e2e-").toFile().also { it.deleteOnExit() }

    @TestFactory
    fun testdata(): List<DynamicTest> {
        val sources = testdata.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }
        assertTrue(sources.size >= 10, "expected the test programs under $testdata")
        return sources.map { src -> DynamicTest.dynamicTest(src.nameWithoutExtension) { check(src) } }
    }

    private fun sibling(src: File, ext: String) = File(src.parentFile, src.nameWithoutExtension + "." + ext)

    /** A C compiler that can link AddressSanitizer (clang here often cannot; gcc is the fallback), or null. */
    private val sanitizerCc: String? by lazy {
        listOf("clang", "gcc").firstOrNull { cc ->
            try {
                val probe = File(work, "probe.c").also { it.writeText("int main(void){return 0;}\n") }
                val p = ProcessBuilder(cc, "-fsanitize=address,undefined", probe.path, "-o", File(work, "probe").path)
                    .redirectErrorStream(true).start()
                p.inputStream.readBytes()
                p.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Leak check: every program that terminates normally is built with AddressSanitizer +
     * LeakSanitizer + UBSan and must produce the same output, exit 0 and report nothing.
     * (Reference cycles would leak by design; no test program builds one.)
     */
    @TestFactory
    fun leakCheck(): List<DynamicTest> {
        // the Strict-mode programs of the memory corpus (docs/memory-model.md) are leak-checked like everything else:
        // arenas, heap and pool objects, `free()` and the free glue must leave nothing behind and touch nothing twice
        val memory = File(testdata, "memory")
        val dirs = listOf(testdata) + (if (memory.isDirectory) listOf(memory) else emptyList())
        val sources = dirs.flatMap { dir -> dir.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name } }
            .filter { sibling(it, "out").isFile && !sibling(it, "cerr").isFile && !sibling(it, "err").isFile && !sibling(it, "noleakcheck").isFile }
        return sources.map { src ->
            DynamicTest.dynamicTest("leak-free ${if (src.parentFile == testdata) "" else src.parentFile.name + "/"}${src.nameWithoutExtension}") {
                val cc = sanitizerCc
                org.junit.jupiter.api.Assumptions.assumeTrue(cc != null, "no C compiler with sanitizer runtimes")
                val name = src.nameWithoutExtension
                val exe = File(work, "$name.asan")
                val errBuf = ByteArrayOutputStream()
                val oldErr = System.err
                val rc = try {
                    System.setErr(PrintStream(errBuf, true, "UTF-8"))
                    Cli.run(
                        listOf(
                            "build", src.path, *CInteropTestData.cArgs(src), "-o", exe.path, "--cc", cc!!,
                            "--runtime-src", File(runtime, "src").path,
                            "--runtime-include", File(runtime, "include").path,
                            "--cflag", "-fsanitize=address,undefined", "--cflag", "-fno-sanitize-recover=undefined",
                            "--cflag", "-g", "--cflag", "-fno-omit-frame-pointer",
                        ),
                        repoRoot,
                    )
                } finally {
                    System.setErr(oldErr)
                }
                assertEquals(0, rc, "sanitizer build of $name failed:\n${errBuf.toString("UTF-8")}")
                val pb = ProcessBuilder(exe.path)
                // LeakSanitizer is unsupported on macOS (ASan aborts if asked for it); there the run
                // still checks memory errors and UB, and leak coverage comes from Linux.
                val leaks = if (System.getProperty("os.name").lowercase().contains("mac")) 0 else 1
                pb.environment()["ASAN_OPTIONS"] = "detect_leaks=$leaks:exitcode=99"
                val proc = pb.start()
                val stdout = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                val stderr = proc.errorStream.readBytes().toString(Charsets.UTF_8)
                assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "$name timed out")
                assertEquals(0, proc.exitValue(), "$name failed under sanitizers (leak or memory error):\n$stderr")
                assertEquals(sibling(src, "out").readText(), stdout)
            }
        }
    }

    private fun check(src: File) {
        val name = src.nameWithoutExtension
        val exe = File(work, name)
        val cOut = File(work, "$name.c")

        val errBuf = ByteArrayOutputStream()
        val oldErr = System.err
        val rc = try {
            System.setErr(PrintStream(errBuf, true, "UTF-8"))
            Cli.run(
                listOf(
                    "build", src.path, *CInteropTestData.cArgs(src), "-o", exe.path, "--emit-c", cOut.path,
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
            assertTrue(!exe.exists(), "no executable must be produced for $name")
            return
        }

        assertEquals(0, rc, "build of $name failed:\n$compileErr")
        assertTrue(compileErr.isBlank() || !compileErr.contains("warning"), "C compiler warnings for $name:\n$compileErr")

        val pb = ProcessBuilder(exe.path).redirectErrorStream(false)
        val proc = pb.start()
        val stdout = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = proc.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "$name timed out")
        val code = proc.exitValue()

        val expectedOut = sibling(src, "out").readText()
        assertEquals(expectedOut, stdout, "stdout of $name differs (generated C: $cOut)")

        val err = sibling(src, "err")
        if (err.isFile) {
            assertTrue(code != 0, "$name should have failed at run time")
            for (line in err.readLines().filter { it.isNotBlank() }) {
                assertTrue(line in stderr, "runtime stderr of $name should contain: $line\nactual:\n$stderr")
            }
        } else {
            assertEquals(0, code, "$name exited with $code\n$stderr")
        }
    }
}
