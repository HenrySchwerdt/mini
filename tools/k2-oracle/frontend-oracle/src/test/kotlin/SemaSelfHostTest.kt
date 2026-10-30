import mini.bridge.Cli
import mini.frontend.driver.describeSema
import mini.frontend.sema.SourceInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Bootstrap B2-2, self-compilation check of the semantic analysis: `minic` compiles the pure-Kotlin frontend including
 * `mini.frontend.sema` plus the driver into a native executable (clang `-Werror`). For the test programs of the repository
 * (analysed together with the standard library sources) the native executable must print exactly what the JVM run of the
 * same driver code prints: every resolved position with its type and callee, and the diagnostics.
 */
class SemaSelfHostTest {
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val runtime = File(repoRoot, "runtime")
    private val work = Files.createTempDirectory("minic-sema-").toFile().also { it.deleteOnExit() }

    private fun kt(dir: String): List<File> =
        File(repoRoot, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun sources(): List<String> =
        (kt("compiler/frontend/driver") + kt("compiler/frontend/src/main/kotlin")).map { it.path }

    private fun build(exe: File, extra: List<String>): String {
        val errBuf = ByteArrayOutputStream()
        val oldErr = System.err
        val args = ArrayList<String>()
        args += listOf("build") + sources() + listOf(
            "-o", exe.path,
            "--runtime-src", File(runtime, "src").path,
            "--runtime-include", File(runtime, "include").path,
        )
        args += extra
        val rc = try {
            System.setErr(PrintStream(errBuf, true, "UTF-8"))
            Cli.run(args, repoRoot)
        } finally {
            System.setErr(oldErr)
        }
        val err = errBuf.toString("UTF-8")
        assertEquals(0, rc, "minic could not compile the semantic analysis:\n${err.take(6000)}")
        assertTrue("warning" !in err, "warnings while compiling the semantic analysis natively:\n${err.take(3000)}")
        return err
    }

    private class Result(val out: String, val err: String, val code: Int)

    private fun run(exe: File, args: List<String>): Result {
        val pb = ProcessBuilder(listOf(exe.path) + args)
        pb.directory(repoRoot)
        val errFile = File(work, exe.name + ".stderr")
        pb.redirectError(errFile)
        val outFile = File(work, exe.name + ".stdout")
        pb.redirectOutput(outFile)
        val proc = pb.start()
        assertTrue(proc.waitFor(900, TimeUnit.SECONDS), "${exe.name} timed out")
        return Result(outFile.readText(), errFile.readText(), proc.exitValue())
    }

    @Test
    fun nativeSemaEqualsJvmSemaOnTestPrograms() {
        val stdlib = kt("stdlib")
        val wanted = System.getenv("MINI_SEMA_NATIVE_FILES")?.split(',')?.toSet()
        val programs = kt("compiler/testdata").filter {
            it.parentFile.name == "testdata" && !File(it.path.removeSuffix(".kt") + ".cerr").exists() &&
                (wanted == null || it.name in wanted)
        }
        val exe = File(work, "frontend-driver")
        build(exe, listOf("--cflag", "-Werror", "--cflag", "-O1"))
        var positions = 0
        for (p in programs) {
            val inputs = (stdlib + p).map { SourceInput(it.path, it.readText()) }
            val expected = describeSema(inputs, setOf(p.path))
            val r = run(exe, listOf("--sema") + stdlib.map { it.path } + listOf("--report", p.path))
            assertEquals(0, r.code, "native sema driver failed on ${p.name}:\n${r.err.take(2000)}")
            assertEquals("", r.err, "native sema driver wrote to stderr")
            val a = expected.lines()
            val b = r.out.lines()
            for (i in 0 until minOf(a.size, b.size)) assertEquals(a[i], b[i], "${p.name}: line ${i + 1} of the sema dump differs")
            assertEquals(a.size, b.size, "${p.name}: dump length")
            positions += a.size
        }
        println("SEMA SELF-COMPILATION: native output identical to the JVM on ${programs.size} programs ($positions dump lines)")
    }

    /** the standard library and the compiler's own sources (including this analysis) in one run */
    @Test
    fun nativeSemaEqualsJvmSemaOnStdlibAndCompilerSources() {
        if (System.getenv("MINI_SEMA_NATIVE_FILES") != null) return
        val files = kt("stdlib") + kt("compiler/backend/src/main") + kt("compiler/native-driver/src/main") + kt("compiler/frontend/src/main/kotlin")
        val exe = File(work, "frontend-driver2")
        build(exe, listOf("--cflag", "-Werror", "--cflag", "-O1"))
        val inputs = files.map { SourceInput(it.path, it.readText()) }
        val j0 = System.nanoTime()
        val expected = describeSema(inputs, files.map { it.path }.toSet())
        val jvmCold = (System.nanoTime() - j0) / 1e9
        val j1 = System.nanoTime()
        describeSema(inputs, files.map { it.path }.toSet())
        val jvmWarm = (System.nanoTime() - j1) / 1e9
        val n0 = System.nanoTime()
        val r = run(exe, listOf("--sema") + files.map { it.path } + listOf("--report") + files.map { it.path })
        val nat = (System.nanoTime() - n0) / 1e9
        val lines = files.sumOf { it.readText().count { c -> c == '\n' } }
        println("SEMA SPEED on ${files.size} files / $lines lines (parse + resolve + type check + dump): JVM cold ${"%.1f".format(jvmCold)} s, JVM warm ${"%.1f".format(jvmWarm)} s, native ${"%.1f".format(nat)} s")
        assertEquals(0, r.code, "native sema driver failed:\n${r.err.take(2000)}")
        assertEquals("", r.err, "native sema driver wrote to stderr")
        val a = expected.lines()
        val b = r.out.lines()
        for (i in 0 until minOf(a.size, b.size)) assertEquals(a[i], b[i], "line ${i + 1} of the sema dump differs")
        assertEquals(a.size, b.size, "dump length")
        println("SEMA SELF-COMPILATION: native output identical to the JVM on ${files.size} stdlib and compiler files (${a.size} dump lines)")
    }

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
     * ASan/UBSan build of the native analysis on a few programs: no memory error, no undefined behaviour, same output.
     * (Leak detection is off: the symbol graph has reference cycles, and the runtime has no cycle collector.)
     */
    @Test
    fun nativeSemaHasNoMemoryErrors() {
        org.junit.jupiter.api.Assumptions.assumeTrue(sanitizerCc != null, "no C compiler with sanitizer runtimes")
        if (System.getenv("MINI_SEMA_NATIVE_FILES") != null) return
        val cc = sanitizerCc!!
        val stdlib = kt("stdlib")
        val programs = listOf("closures.kt", "generics.kt", "patterns.kt").map { File(repoRoot, "compiler/testdata/$it") }
        val exe = File(work, "frontend-driver.asan")
        build(
            exe,
            listOf(
                "--cc", cc, "--cflag", "-fsanitize=address,undefined", "--cflag", "-fno-sanitize-recover=undefined",
                "--cflag", "-g", "--cflag", "-fno-omit-frame-pointer",
            ),
        )
        for (p in programs) {
            val expected = describeSema((stdlib + p).map { SourceInput(it.path, it.readText()) }, setOf(p.path))
            val pb = ProcessBuilder(listOf(exe.path, "--sema") + stdlib.map { it.path } + listOf("--report", p.path))
            pb.directory(repoRoot)
            pb.environment()["ASAN_OPTIONS"] = "detect_leaks=0:exitcode=99"
            val errFile = File(work, "asan.stderr")
            pb.redirectError(errFile)
            val outFile = File(work, "asan.stdout")
            pb.redirectOutput(outFile)
            val proc = pb.start()
            assertTrue(proc.waitFor(1800, TimeUnit.SECONDS), "sanitizer build timed out")
            val err = errFile.readText()
            assertTrue("ERROR: AddressSanitizer" !in err && "runtime error" !in err, "memory error or UB on ${p.name}:\n${err.take(3000)}")
            assertEquals(0, proc.exitValue(), "exit code on ${p.name}")
            assertEquals(expected, outFile.readText(), "sanitizer build output on ${p.name}")
        }
    }
}
