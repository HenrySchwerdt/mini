import mini.bridge.Cli
import mini.frontend.driver.countLines
import mini.frontend.driver.describeFile
import mini.frontend.parseKotlin
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
 * Bootstrap B2, self-compilation check: `minic` compiles the pure-Kotlin frontend (all of `compiler/frontend/src`)
 * plus the driver `compiler/frontend/driver` into a native executable (clang `-Werror`). For every Kotlin file of the
 * repository the native executable must print exactly what the JVM run of the same driver code prints (errors, lossless
 * check, counters, declaration outline with every expression), and the build must be sanitizer clean.
 * The last test measures the parse speed (lines per second) on the JVM and natively.
 */
class FrontendSelfHostTest {
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val runtime = File(repoRoot, "runtime")
    private val work = Files.createTempDirectory("minic-frontend-").toFile().also { it.deleteOnExit() }

    private fun kt(dir: String): List<String> =
        File(repoRoot, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.map { it.path }.toList()

    private fun sources(): List<String> =
        kt("compiler/frontend/driver") + kt("compiler/frontend/src")

    private fun build(exe: File, extra: List<String>, cc: String? = null): String {
        val errBuf = ByteArrayOutputStream()
        val oldErr = System.err
        val args = ArrayList<String>()
        args += listOf("build") + sources() + listOf(
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
        assertEquals(0, rc, "minic could not compile the frontend:\n$err")
        assertTrue("warning" !in err, "warnings while compiling the frontend natively:\n$err")
        return err
    }

    private class Result(val out: String, val err: String, val code: Int)

    private fun run(exe: File, args: List<String>, env: Map<String, String> = emptyMap()): Result {
        val pb = ProcessBuilder(listOf(exe.path) + args)
        pb.directory(repoRoot)
        pb.environment().putAll(env)
        val errFile = File(work, exe.name + ".stderr")
        pb.redirectError(errFile)
        val outFile = File(work, exe.name + ".stdout")
        pb.redirectOutput(outFile)
        val proc = pb.start()
        assertTrue(proc.waitFor(600, TimeUnit.SECONDS), "${exe.name} timed out")
        return Result(outFile.readText(), errFile.readText(), proc.exitValue())
    }

    private fun relFiles(): List<String> = Corpus.files.map { Corpus.rel(it) }

    private fun jvmOutput(files: List<String>): String {
        val sb = StringBuilder()
        for (f in files) sb.append(describeFile(f, File(repoRoot, f).readText()))
        return sb.toString()
    }

    @Test
    fun nativeFrontendEqualsJvmFrontendOnAllRepoSources() {
        val files = relFiles()
        val expected = jvmOutput(files)
        assertTrue(expected.lines().size > 2_000, "the driver should print a lot")
        val outLines = expected.lines()
        assertTrue(outLines.none { it == "lossless: FAILED" }, "a repository file is not lossless")
        assertTrue(outLines.any { it == "errors: 0" })
        assertTrue(outLines.none { it.startsWith("errors: ") && it != "errors: 0" }, "syntax errors in a repository file")
        val exe = File(work, "frontend-driver")
        build(exe, listOf("--cflag", "-Werror"))
        val r = run(exe, files)
        assertEquals(0, r.code, "native frontend driver failed:\n${r.err}")
        assertEquals("", r.err, "native frontend driver wrote to stderr")
        assertEquals(expected.length, r.out.length, "output length")
        // report the first differing line, not two 3 MB strings
        val a = expected.lines()
        val b = r.out.lines()
        for (i in 0 until minOf(a.size, b.size)) assertEquals(a[i], b[i], "line ${i + 1} of the driver output differs")
        assertEquals(a.size, b.size)
        println("SELF-COMPILATION: native frontend output identical to the JVM on ${files.size} files (${expected.length} bytes, ${a.size} lines)")
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

    @Test
    fun nativeFrontendIsSanitizerClean() {
        val cc = sanitizerCc
        assumeTrue(cc != null, "no C compiler with sanitizer runtimes")
        val files = Corpus.files.filter { it.path.contains("testdata") }.map { Corpus.rel(it) }
        val expected = jvmOutput(files)
        val exe = File(work, "frontend-driver.asan")
        build(
            exe,
            listOf(
                "--cflag", "-fsanitize=address,undefined", "--cflag", "-fno-sanitize-recover=undefined",
                "--cflag", "-g", "--cflag", "-fno-omit-frame-pointer",
            ),
            cc,
        )
        val leaks = if (System.getProperty("os.name").lowercase().contains("mac")) 0 else 1
        val r = run(exe, files, mapOf("ASAN_OPTIONS" to "detect_leaks=$leaks:exitcode=99"))
        assertEquals(expected, r.out)
        assertTrue("ERROR: AddressSanitizer" !in r.err && "runtime error" !in r.err, "memory error or UB:\n${r.err}")
        assertTrue("Direct leak" !in r.err, "the native frontend leaks (not a reference cycle):\n${r.err}")
        assertTrue(r.code == 0 || ("Indirect leak" in r.err && r.code == 99), "unexpected exit ${r.code}:\n${r.err}")
    }

    /** Parse speed on all repository sources: JVM (warm, in process) against the native executable. */
    @Test
    fun parseSpeedJvmVersusNative() {
        val files = Corpus.files
        val texts = files.map { it.readText() }
        val lines = texts.sumOf { countLines(it) }.toLong()
        // JVM: first pass cold, then warm
        val t0 = System.nanoTime()
        for (t in texts) parseKotlin(t)
        val cold = (System.nanoTime() - t0) / 1e9
        repeat(15) { for (t in texts) parseKotlin(t) }
        val rounds = 20
        val t1 = System.nanoTime()
        repeat(rounds) { for (t in texts) parseKotlin(t) }
        val warm = (System.nanoTime() - t1) / 1e9
        val jvmWarm = lines * rounds / warm
        val jvmCold = lines / cold

        val exe = File(work, "frontend-driver.bench")
        build(exe, listOf("--cflag", "-O2"))
        val args = listOf("--repeat", "20") + relFiles()
        run(exe, listOf("--repeat", "1") + relFiles()) // warm the file cache
        val n0 = System.nanoTime()
        val r = run(exe, args)
        val nat = (System.nanoTime() - n0) / 1e9
        assertEquals(0, r.code, r.err)
        val nativeRate = lines * 20 / nat
        println(
            "PARSE SPEED on ${files.size} files / $lines lines (lexing + CST + AST, no outline): " +
                "JVM cold ${"%.0f".format(jvmCold)} lines/s, JVM warm ${"%.0f".format(jvmWarm)} lines/s, " +
                "native ${"%.0f".format(nativeRate)} lines/s (${r.out.trim()})",
        )
        assertTrue(nativeRate > 1000)
    }
}
