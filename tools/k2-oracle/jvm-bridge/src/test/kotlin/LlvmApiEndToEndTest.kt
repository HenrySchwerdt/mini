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
 * The in-process LLVM backend (docs/plan-kotlin.md 10g phase 2): `minic-native --backend=llvm-api`, the LLVM C API bound in Mini
 * (compiler/llvm-c). The API compiler is built once by `minic` (own frontend) from minic-native + llvm-c; then every program of
 * compiler/testdata goes through it to an object file (no text IR, no clang for the code), is linked with libminirt and must behave
 * exactly like the C build: same stdout, same stderr lines, same exit behaviour, same compile errors. Skipped without LLVM headers
 * (`llvm-config`).
 */
class LlvmApiEndToEndTest {
    private val testdata = File(System.getProperty("mini.testdata") ?: error("mini.testdata not set"))
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val compilerDir = File(System.getProperty("mini.compilerDir") ?: error("mini.compilerDir not set"))
    private val work = Files.createTempDirectory("minic-llvmapi-").toFile().also { it.deleteOnExit() }

    private fun capture(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        if (p.waitFor() == 0) out else null
    } catch (e: Exception) {
        null
    }

    private val llvmInclude: String? by lazy { capture("llvm-config", "--includedir")?.takeIf { File(it, "llvm-c/Core.h").isFile } }
    private val llvmLib: String? by lazy { capture("llvm-config", "--libdir") }

    private fun kt(dir: String, skip: String? = null): List<String> =
        File(compilerDir, "$dir/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != skip }.map { it.path }.sorted().toList()

    private val stdlib: List<String> by lazy {
        File(repoRoot, "stdlib").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.path }.sorted().toList()
    }

    /** The API compiler, built once; null when LLVM is not installed. */
    private val apiCompiler: File? by lazy {
        val inc = llvmInclude ?: return@lazy null
        val exe = File(work, "minic-native-llvm")
        val srcs = kt("minic-native", skip = "Main.kt") + kt("lower") + kt("frontend") + kt("backend") + kt("llvm-c")
        val errBuf = ByteArrayOutputStream()
        val oldErr = System.err
        val rc = try {
            System.setErr(PrintStream(errBuf, true, "UTF-8"))
            Cli.run(
                listOf("build") + srcs + listOf(
                    "-o", exe.path, "--frontend=mini", "--cflag", "-I$inc", "--cflag", "-L$llvmLib", "--cflag", "-rdynamic", "--cflag", "-w",
                    "--runtime-src", File(repoRoot, "runtime/src").path, "--runtime-include", File(repoRoot, "runtime/include").path,
                ),
                repoRoot,
            )
        } finally {
            System.setErr(oldErr)
        }
        assertEquals(0, rc, "could not build the LLVM API compiler:\n$errBuf")
        exe
    }

    /** libminirt as object files, compiled once. */
    private val runtimeObjects: List<File> by lazy {
        File(repoRoot, "runtime/src").listFiles { f -> f.extension == "c" }!!.sortedBy { it.name }.map { c ->
            val o = File(work, "rt_" + c.nameWithoutExtension + ".o")
            assertTrue(capture("clang", "-std=c17", "-O1", "-w", "-I", File(repoRoot, "runtime/include").path, "-c", c.path, "-o", o.path) != null, "cannot compile ${c.name}")
            o
        }
    }

    private fun sibling(src: File, ext: String) = File(src.parentFile, src.nameWithoutExtension + "." + ext)

    @TestFactory
    fun testdata(): List<DynamicTest> {
        val sources = testdata.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }
        return sources.map { src -> DynamicTest.dynamicTest("llvm-api " + src.nameWithoutExtension) { check(src) } }
    }

    private fun check(src: File) {
        assumeTrue(llvmInclude != null, "no LLVM headers (llvm-config)")
        val compiler = apiCompiler!!
        val name = src.nameWithoutExtension
        val obj = File(work, "$name.o")
        val compile = ProcessBuilder(listOf(compiler.path, src.path, "--backend=llvm-api", "-o", obj.path, "--stdlib") + stdlib).redirectErrorStream(true).start()
        val compileOut = compile.inputStream.readBytes().toString(Charsets.UTF_8)
        val rc = if (compile.waitFor(120, TimeUnit.SECONDS)) compile.exitValue() else -1
        val cerr = sibling(src, "cerr")
        if (cerr.isFile) {
            assertTrue(rc != 0, "expected a compile error for $name")
            for (line in cerr.readLines().filter { it.isNotBlank() }) assertTrue(line in compileOut, "output of $name should contain: $line\nactual:\n$compileOut")
            return
        }
        assertEquals(0, rc, "API build of $name failed:\n$compileOut")
        val exe = File(work, name)
        val cc = mutableListOf("clang", obj.path)
        if (src.readText().contains("cinterop_lib.h")) {
            val lib = File(work, "cinterop_lib.o")
            if (!lib.isFile) assertTrue(capture("clang", "-c", "-O1", "-w", File(testdata, "cinterop_lib.c").path, "-o", lib.path) != null)
            cc += lib.path
        }
        cc += runtimeObjects.map { it.path }
        cc += listOf("-o", exe.path, "-lm")
        assertTrue(capture(*cc.toTypedArray()) != null, "link of $name failed")
        val proc = ProcessBuilder(exe.path).directory(testdata).start()
        val stdout = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = proc.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "$name timed out")
        assertEquals(sibling(src, "out").readText(), stdout, "stdout of $name differs")
        val err = sibling(src, "err")
        if (err.isFile) {
            assertTrue(proc.exitValue() != 0, "$name should have failed at run time")
            for (line in err.readLines().filter { it.isNotBlank() }) assertTrue(line in stderr, "stderr of $name should contain: $line\nactual:\n$stderr")
        } else {
            assertEquals(0, proc.exitValue(), "$name exited with ${proc.exitValue()}\n$stderr")
        }
    }
}
