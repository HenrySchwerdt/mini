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
 * The LLVM backend (`minic build --backend=llvm`, docs/plan-kotlin.md section 10g) against the same expectations
 * as the C backend ([EndToEndTest]): every program in compiler/testdata is built through MIR -> textual LLVM IR ->
 * clang + libminirt and must print exactly the expected stdout, exit like the C build, and (leak check) run clean
 * under ASan/LSan/UBSan. Parity is "every program passes", i.e. 47/47 build programs plus the compile-error test.
 */
class LlvmEndToEndTest {
    private val testdata = File(System.getProperty("mini.testdata") ?: error("mini.testdata not set"))
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val runtime = File(repoRoot, "runtime")
    private val work = Files.createTempDirectory("minic-llvm-e2e-").toFile().also { it.deleteOnExit() }

    private fun sources() = testdata.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }

    private fun sibling(src: File, ext: String) = File(src.parentFile, src.nameWithoutExtension + "." + ext)

    private fun tool(vararg cmd: String): Boolean = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor() == 0
    } catch (e: Exception) {
        false
    }

    /** The driver that links the sanitizer runtime (clang when it can, else gcc), or null. The IR itself is instrumented by clang. */
    private val sanitizerLinker: String? by lazy {
        if (!tool("clang", "--version")) return@lazy null
        val probe = File(work, "probe.c").also { it.writeText("int main(void){return 0;}\n") }
        listOf("clang", "gcc").firstOrNull { cc -> tool(cc, "-fsanitize=address,undefined", probe.path, "-o", File(work, "probe").path) }
    }

    private fun runCli(args: List<String>): Pair<Int, String> {
        val errBuf = ByteArrayOutputStream()
        val oldErr = System.err
        val rc = try {
            System.setErr(PrintStream(errBuf, true, "UTF-8"))
            Cli.run(args + listOf("--runtime-src", File(runtime, "src").path, "--runtime-include", File(runtime, "include").path), repoRoot)
        } finally {
            System.setErr(oldErr)
        }
        return Pair(rc, errBuf.toString("UTF-8"))
    }

    @TestFactory
    fun testdata(): List<DynamicTest> {
        val sources = sources()
        assertTrue(sources.size >= 10, "expected the test programs under $testdata")
        return sources.map { src -> DynamicTest.dynamicTest("llvm " + src.nameWithoutExtension) { check(src) } }
    }

    private fun check(src: File) {
        val name = src.nameWithoutExtension
        val exe = File(work, name)
        val ll = File(work, "$name.ll")
        val (rc, compileErr) = runCli(listOf("build", src.path, "-o", exe.path, "--backend=llvm", "--emit-ll", ll.path, "--cflag", "-Werror"))

        val cerr = sibling(src, "cerr")
        if (cerr.isFile) {
            assertEquals(1, rc, "expected a compile error for $name, got exit $rc\n$compileErr")
            for (line in cerr.readLines().filter { it.isNotBlank() }) {
                assertTrue(line in compileErr, "stderr of $name should contain: $line\nactual:\n$compileErr")
            }
            assertTrue(!exe.exists(), "no executable must be produced for $name")
            return
        }
        assertEquals(0, rc, "LLVM build of $name failed:\n$compileErr")
        assertTrue(compileErr.isBlank() || !compileErr.contains("warning"), "compiler warnings for $name:\n$compileErr")

        val proc = ProcessBuilder(exe.path).start()
        val stdout = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = proc.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "$name timed out")
        val code = proc.exitValue()
        assertEquals(sibling(src, "out").readText(), stdout, "stdout of $name differs (generated IR: $ll)")
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

    /** The same leak check as [EndToEndTest.leakCheck], on the LLVM build: ASan + LSan + UBSan (the runtime), ASan on the IR. */
    @TestFactory
    fun leakCheck(): List<DynamicTest> {
        val programs = sources()
            .filter { sibling(it, "out").isFile && !sibling(it, "cerr").isFile && !sibling(it, "err").isFile && !sibling(it, "noleakcheck").isFile }
        return programs.map { src ->
            DynamicTest.dynamicTest("llvm leak-free ${src.nameWithoutExtension}") {
                val linker = sanitizerLinker
                org.junit.jupiter.api.Assumptions.assumeTrue(linker != null, "no clang or no sanitizer runtime")
                val name = src.nameWithoutExtension
                val exe = File(work, "$name.asan")
                val (rc, err) = runCli(
                    listOf(
                        "build", src.path, "-o", exe.path, "--backend=llvm", "--cc", "clang", "--link-cc", linker!!,
                        "--cflag", "-fsanitize=address,undefined", "--cflag", "-fno-sanitize-recover=undefined",
                        "--cflag", "-g", "--cflag", "-fno-omit-frame-pointer",
                    ),
                )
                assertEquals(0, rc, "sanitizer build of $name failed:\n$err")
                val pb = ProcessBuilder(exe.path)
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

    /** `--stats`, `--no-rc-opt`, `--no-strict` and `--no-conv` with `--backend=llvm` still produce a working program (MK_STATS counters, baseline RC). */
    @TestFactory
    fun variants(): List<DynamicTest> {
        val src = File(testdata, "rc_stress.kt")
        return listOf("--stats", "--no-rc-opt", "--no-strict", "--no-conv").map { flag ->
            DynamicTest.dynamicTest("llvm rc_stress $flag") {
                val exe = File(work, "rc_stress" + flag.replace("-", "_"))
                val (rc, err) = runCli(listOf("build", src.path, "-o", exe.path, "--backend=llvm", flag))
                assertEquals(0, rc, "build with $flag failed:\n$err")
                val proc = ProcessBuilder(exe.path).start()
                val stdout = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                val stderr = proc.errorStream.readBytes().toString(Charsets.UTF_8)
                assertTrue(proc.waitFor(60, TimeUnit.SECONDS))
                assertEquals(0, proc.exitValue(), stderr)
                assertEquals(sibling(src, "out").readText(), stdout)
                if (flag == "--stats") assertTrue("mk-stats:" in stderr, "no MK_STATS summary on stderr:\n$stderr")
            }
        }
    }
}
