import mini.backend.CEmitter
import mini.backend.MirReader
import mini.backend.MirWriter
import mini.bridge.Cli
import mini.bridge.Stdlib
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The v1 gate (docs/plan-kotlin.md 10b): `minic` compiles its own backend.
 *
 * `compiler/selfhost/SelfMain.kt` is a driver for the pure-Kotlin backend (`compiler/backend`): it builds MIR and
 * MemIr programs, runs `CEmitter` and the memory checker and prints everything. This test
 *
 *  1. runs that driver on the JVM (the backend classes compiled by the real Kotlin compiler),
 *  2. compiles the very same sources (driver + all of `compiler/backend/src/main`) with `minic` to a native
 *     executable (clang `-Werror`, so no C warnings either), and runs it,
 *  3. requires the two outputs to be byte-identical,
 *  4. builds the native program once more with AddressSanitizer/UBSan and requires a clean run: no memory error, no
 *     undefined behaviour and no *direct* leak. Reference cycles leak under reference counting by design (the
 *     stdlib's `LinkedHashMap` nodes link each other), LeakSanitizer reports those as "indirect leaks" only.
 */
class SelfHostTest {
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val runtime = File(repoRoot, "runtime")
    private val backendSrc = File(System.getProperty("mini.backendSrc") ?: error("mini.backendSrc not set"))
    private val selfhostSrc = File(System.getProperty("mini.selfhostSrc") ?: error("mini.selfhostSrc not set"))
    private val work = Files.createTempDirectory("minic-selfhost-").toFile().also { it.deleteOnExit() }

    private val testdata = File(System.getProperty("mini.testdata") ?: error("mini.testdata not set"))

    private fun backendSources(): List<String> =
        backendSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.map { it.path }.toList()

    private fun sources(): List<String> {
        val driver = selfhostSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.map { it.path }.toList()
        val backend = backendSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.map { it.path }.toList()
        return driver + backend
    }

    private fun jvmOutput(): String {
        val buf = ByteArrayOutputStream()
        val old = System.out
        try {
            System.setOut(PrintStream(buf, true, "UTF-8"))
            selfhost.main()
        } finally {
            System.setOut(old)
        }
        return buf.toString("UTF-8")
    }

    private fun build(exe: File, extra: List<String>, cc: String? = null, srcs: List<String> = sources()): String {
        val errBuf = ByteArrayOutputStream()
        val oldErr = System.err
        val args = ArrayList<String>()
        args += listOf("build") + srcs + listOf(
            "-o", exe.path,
            "--runtime-src", File(runtime, "src").path,
            "--runtime-include", File(runtime, "include").path,
        )
        if (cc != null) args += listOf("--cc", cc)
        args += extra
        val rc = try {
            System.setErr(PrintStream(errBuf, true, "UTF-8"))
            Cli.run(args, repoRoot)
        } finally {
            System.setErr(oldErr)
        }
        val err = errBuf.toString("UTF-8")
        assertEquals(0, rc, "minic could not compile its own backend:\n$err")
        assertTrue("warning" !in err, "warnings while compiling the backend natively:\n$err")
        return err
    }

    private class Result(val out: String, val err: String, val code: Int)

    private fun run(exe: File, env: Map<String, String> = emptyMap()): Result {
        val pb = ProcessBuilder(exe.path)
        pb.environment().putAll(env)
        // stderr goes to a file: reading two pipes one after the other can deadlock when one of them fills up
        val errFile = File(work, exe.name + ".stderr")
        pb.redirectError(errFile)
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "${exe.name} timed out")
        return Result(out, errFile.readText(), proc.exitValue())
    }

    @Test
    fun nativeBackendEqualsJvmBackend() {
        val expected = jvmOutput()
        assertTrue(expected.lines().size > 100, "the driver should print a lot")
        val exe = File(work, "selfhost")
        build(exe, listOf("--cflag", "-Werror"))
        val r = run(exe)
        assertEquals(0, r.code, "native selfhost failed:\n${r.err}")
        assertEquals("", r.err, "native selfhost wrote to stderr")
        assertEquals(expected, r.out, "the natively compiled backend must print exactly what the JVM backend prints")
    }

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

    @Test
    fun nativeBackendIsSanitizerClean() {
        val cc = sanitizerCc
        assumeTrue(cc != null, "no C compiler with sanitizer runtimes")
        val expected = jvmOutput()
        val exe = File(work, "selfhost.asan")
        build(
            exe,
            listOf(
                "--cflag", "-fsanitize=address,undefined", "--cflag", "-fno-sanitize-recover=undefined",
                "--cflag", "-g", "--cflag", "-fno-omit-frame-pointer",
            ),
            cc,
        )
        val leaks = if (System.getProperty("os.name").lowercase().contains("mac")) 0 else 1
        val r = run(exe, mapOf("ASAN_OPTIONS" to "detect_leaks=$leaks:exitcode=99"))
        assertEquals(expected, r.out)
        assertTrue("ERROR: AddressSanitizer" !in r.err && "runtime error" !in r.err, "memory error or UB:\n${r.err}")
        assertTrue("Direct leak" !in r.err, "the native backend leaks (not a reference cycle):\n${r.err}")
        // only reference cycles (indirect leaks) are tolerated
        assertTrue(r.code == 0 || ("Indirect leak" in r.err && r.code == 99), "unexpected exit ${r.code}:\n${r.err}")
    }

    /**
     * The B1 acceptance test (docs/plan-kotlin.md 10b): the backend plus `compiler/native-driver` are compiled by minic
     * into a native `minic-backend`. For every program of compiler/testdata, `minic emit-mir` writes the MIR text, the
     * native `minic-backend` turns it into C, and that C must be byte-identical to what the JVM `CEmitter` makes of the
     * same MIR.
     */
    @TestFactory
    fun nativeMinicBackendOnAllTestPrograms(): List<DynamicTest> {
        val driverSrc = File(repoRoot, "compiler/native-driver/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.map { it.path }.toList()
        val exe = File(work, "minic-backend")
        val built by lazy { build(exe, listOf("--cflag", "-Werror"), null, driverSrc + backendSources()); true }
        val stdlib = Stdlib.sources(Stdlib.findDir(null, repoRoot)!!)
        val programs = testdata.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }
        return programs.map { src ->
            DynamicTest.dynamicTest(src.nameWithoutExtension) {
                if (File(src.parentFile, src.nameWithoutExtension + ".cerr").isFile) return@dynamicTest
                assertTrue(built)
                val module = Cli.frontendToMir(listOf(src), stdlib, emptyList(), false)
                val text = MirWriter.write(module)
                val mir = File(work, src.nameWithoutExtension + ".mir").also { it.writeText(text) }
                val cFile = File(work, src.nameWithoutExtension + ".c")
                val pb = ProcessBuilder(exe.path, mir.path, "-o", cFile.path)
                val errFile = File(work, src.nameWithoutExtension + ".stderr")
                pb.redirectErrorStream(true).redirectOutput(errFile)
                val proc = pb.start()
                assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "minic-backend timed out on ${src.name}")
                assertEquals(0, proc.exitValue(), "native minic-backend failed on ${src.name}:\n${errFile.readText()}")
                assertEquals(CEmitter.emit(MirReader.read(text)), cFile.readText(), "C from the native backend differs for ${src.name}")
                // the LLVM backend (mini.backend.llvm) is part of the same native program: its IR must equal the JVM's, too
                val llFile = File(work, src.nameWithoutExtension + ".ll")
                val llProc = ProcessBuilder(exe.path, mir.path, "-o", llFile.path, "--backend=llvm").redirectErrorStream(true).redirectOutput(errFile).start()
                assertTrue(llProc.waitFor(120, TimeUnit.SECONDS), "minic-backend --backend=llvm timed out on ${src.name}")
                assertEquals(0, llProc.exitValue(), "native minic-backend --backend=llvm failed on ${src.name}:\n${errFile.readText()}")
                assertEquals(mini.backend.llvm.LlvmEmitter.emit(MirReader.read(text)), llFile.readText(), "LLVM IR from the native backend differs for ${src.name}")
                mir.delete()
                cFile.delete()
                llFile.delete()
            }
        }
    }
}
