package mini.bridge

import mini.backend.CEmitter
import mini.backend.EmitError
import java.io.File

/**
 * `minic build <file.kt> -o <out> [--emit-c <path>] [--cc clang] [--runtime-src <dir>]
 *        [--runtime-include <dir>] [--cflag <flag>]...`
 *
 * Pipeline: K2 frontend -> Kotlin IR -> MIR (Ir2Mir) -> C (CEmitter) -> clang + libminirt.
 */
object Cli {
    private const val USAGE = """usage: minic build <file.kt> -o <output> [--emit-c <path>] [--cc <compiler>]
             [--runtime-src <dir>] [--runtime-include <dir>] [--cflag <flag>]..."""

    class Options(
        val source: File,
        val output: File,
        val emitC: File?,
        val cc: String,
        val runtimeSrc: File?,
        val runtimeInclude: File?,
        val cflags: List<String>,
    )

    /** Returns the process exit code. */
    fun run(args: List<String>, repoRoot: File? = findRepoRoot(File("."))): Int {
        if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
            System.err.println(USAGE)
            return if (args.isEmpty()) 2 else 0
        }
        if (args[0] != "build") {
            System.err.println("minic: unknown command '${args[0]}'\n$USAGE")
            return 2
        }
        val opts = parse(args.drop(1)) ?: return 2
        return try {
            build(opts, repoRoot)
        } catch (e: UnsupportedConstruct) {
            System.err.println(e.message)
            1
        } catch (e: FrontendDriver.FrontendFailure) {
            System.err.println(e.message)
            1
        } catch (e: BuildFailure) {
            System.err.println("minic: ${e.message}")
            1
        } catch (e: EmitError) {
            System.err.println("minic: internal error in C emitter: ${e.message}")
            3
        }
    }

    class BuildFailure(message: String) : Exception(message)

    private fun parse(a: List<String>): Options? {
        var source: File? = null
        var out: File? = null
        var emitC: File? = null
        var cc = "clang"
        var rtSrc: File? = null
        var rtInc: File? = null
        val cflags = mutableListOf<String>()
        var i = 0
        fun next(flag: String): String? {
            if (i + 1 >= a.size) { System.err.println("minic: missing value for $flag"); return null }
            return a[++i]
        }
        while (i < a.size) {
            when (val x = a[i]) {
                "-o" -> out = File(next(x) ?: return null)
                "--emit-c" -> emitC = File(next(x) ?: return null)
                "--cc" -> cc = next(x) ?: return null
                "--runtime-src" -> rtSrc = File(next(x) ?: return null)
                "--runtime-include" -> rtInc = File(next(x) ?: return null)
                "--cflag" -> cflags += next(x) ?: return null
                else -> {
                    if (x.startsWith("-")) { System.err.println("minic: unknown option $x\n$USAGE"); return null }
                    if (source != null) { System.err.println("minic: more than one input file"); return null }
                    source = File(x)
                }
            }
            i++
        }
        if (source == null || out == null) { System.err.println(USAGE); return null }
        return Options(source, out, emitC, cc, rtSrc, rtInc, cflags)
    }

    fun findRepoRoot(start: File): File? {
        var d: File? = start.absoluteFile.normalize()
        while (d != null) {
            if (File(d, "runtime/minirt/include/minirt.h").isFile) return d
            d = d.parentFile
        }
        return null
    }

    fun build(o: Options, repoRoot: File?): Int {
        if (!o.source.isFile) throw BuildFailure("no such file: ${o.source}")

        // 1. runtime location (fail early, before the slow frontend)
        val include = o.runtimeInclude ?: repoRoot?.let { File(it, "runtime/minirt/include") }
        if (include == null || !File(include, "minirt.h").isFile) {
            throw BuildFailure("cannot find minirt.h; pass --runtime-include <dir>")
        }
        val runtimeSrc = o.runtimeSrc ?: repoRoot?.let { File(it, "runtime/minirt/src") }?.takeIf { it.isDirectory }
            ?: throw BuildFailure("runtime sources not found (runtime/minirt/src); pass --runtime-src <dir>")
        val runtimeFiles = runtimeSrc.walkTopDown().filter { it.isFile && it.extension == "c" }.sortedBy { it.path }.toList()
        if (runtimeFiles.isEmpty()) throw BuildFailure("no .c files in $runtimeSrc")

        // 2. K2 -> IR -> MIR
        var mir: mini.backend.MirModule? = null
        FrontendDriver.runFrontend(o.source) { module, _ ->
            mir = Ir2Mir().convert(module, o.source.name)
        }
        val module = mir ?: throw BuildFailure("the Kotlin frontend produced no IR for ${o.source}")
        val mainCount = module.functions.count { it.cName == mini.backend.Mangling.MAIN }
        if (mainCount != 1) throw BuildFailure("${o.source}: expected exactly one `fun main()`, found $mainCount")

        // 3. MIR -> C
        val c = CEmitter.emit(module)
        val cFile = o.emitC ?: File.createTempFile("minic-", ".c")
        cFile.absoluteFile.parentFile?.mkdirs()
        cFile.writeText(c)

        // 4. clang
        o.output.absoluteFile.parentFile?.mkdirs()
        val cmd = mutableListOf(o.cc, "-std=c17", "-O1", "-Wall", "-Wno-unused-label", "-Wno-unused-variable", "-Wno-unused-but-set-variable", "-I", include.path)
        cmd += o.cflags
        cmd += cFile.path
        runtimeFiles.forEach { cmd += it.path }
        cmd += listOf("-o", o.output.path, "-lm")
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        if (o.emitC == null) cFile.delete()
        if (rc != 0) {
            throw BuildFailure("C compiler failed (exit $rc): ${cmd.joinToString(" ")}\n$text")
        }
        if (text.isNotBlank()) System.err.print(text)
        return 0
    }
}
