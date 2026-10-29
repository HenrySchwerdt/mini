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
    private const val USAGE = """usage: minic emit-mir <file.kt>... -o <out.mir> [--stdlib <dir> | --no-stdlib] [--extra-src <file.kt>]...
       minic build <file.kt>... -o <output> [--emit-c <path>] [--cc <compiler>]
             [--runtime-src <dir>] [--runtime-include <dir>] [--cflag <flag>]...
             [--stdlib <dir> | --no-stdlib] [--stats] [--no-rc-opt] [--no-strict] [--no-conv] [-Wrc] [--frontend=k2|mini]
       minic mem-report <file.kt>... [--stdlib <dir> | --no-stdlib] [--extra-src <file.kt>]... [--top <n>] [--user-only]
             [--no-rc-opt] [--no-strict] [--no-conv]"""

    class Options(
        val sources: List<File>,
        val output: File,
        val emitC: File?,
        val cc: String,
        val runtimeSrc: File?,
        val runtimeInclude: File?,
        val cflags: List<String>,
        val stdlib: File?,
        val noStdlib: Boolean,
        /** Extra Kotlin sources compiled with the program (files already in the stdlib are skipped). */
        val extraSrc: List<File> = emptyList(),
        /** `-Werror`: memory-checker warnings fail the build. */
        val werror: Boolean = false,
        /** `--stats`: build with `-DMK_STATS` (allocation / retain / release counters, printed to stderr at exit). */
        val stats: Boolean = false,
        /** `--no-rc-opt`: skip the reference-count elision pass (RcOpt). */
        val noRcOpt: Boolean = false,
        /** `--frontend=mini`: the own frontend (parser + sema + Ast2Ir in `:lower`) instead of K2. */
        val miniFrontend: Boolean = false,
        /** `--no-strict`: no Strict code generation (reference counting everywhere, allocators and `free` ignored). */
        val noStrict: Boolean = false,
        /** `--no-conv`: no borrowed results / owned parameters for the stdlib hot paths (Conventions). */
        val noConv: Boolean = false,
        /** `--conv-report`: print the calling conventions that were kept / dropped (stderr). */
        val convReport: Boolean = false,
        /** `-Wrc`: M0207 warnings (reference counting remains in a function of the user files). */
        val wrc: Boolean = false,
        /** `mem-report --top n`: functions listed in detail. */
        val top: Int = 40,
        /** `mem-report --user-only`: list only functions of the user files (not the stdlib). */
        val userOnly: Boolean = false,
    )

    /** Returns the process exit code. */
    fun run(args: List<String>, repoRoot: File? = findRepoRoot(File("."))): Int {
        if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
            System.err.println(USAGE)
            return if (args.isEmpty()) 2 else 0
        }
        if (args[0] == "check") return check(args.drop(1), repoRoot)
        val emit = args[0] == "emit-mir"
        val memReport = args[0] == "mem-report"
        if (args[0] != "build" && !emit && !memReport) {
            System.err.println("minic: unknown command '${args[0]}'\n$USAGE")
            return 2
        }
        val opts = parse(args.drop(1), needOutput = !memReport) ?: return 2
        return try {
            if (emit) emitMir(opts, repoRoot) else if (memReport) memReport(opts, repoRoot) else build(opts, repoRoot)
        } catch (e: UnsupportedConstruct) {
            if (System.getenv("MINIC_DEBUG") != null) e.printStackTrace()
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

    /** The default Mini stdlib sources, or null when they cannot be found. */
    fun defaultStdlib(explicit: File?, repoRoot: File?): List<File>? =
        Stdlib.findDir(explicit, repoRoot)?.let { Stdlib.sources(it) }?.takeIf { it.isNotEmpty() }

    /** [extra] minus files that are already part of [stdlib] (the mini.memory API lives in both places). */
    fun withoutStdlib(extra: List<File>, stdlib: List<File>): List<File> {
        val inStdlib = stdlib.map { it.canonicalFile }.toSet()
        return extra.filter { it.canonicalFile !in inStdlib }
    }

    /**
     * `minic check <file.kt> [--extra-src <file.kt>]... [--brief] [-Werror]`: the Strict-mode ownership checker only.
     * Prints rustc-like diagnostics to stderr (or, with `--brief`, `line:col: CODE` lines to stdout);
     * exit 1 when there is an error, 0 otherwise (warnings do not fail).
     */
    private fun check(a: List<String>, repoRoot: File?): Int {
        var source: File? = null
        val extra = mutableListOf<File>()
        var brief = false
        var werror = false
        var i = 0
        while (i < a.size) {
            when (val x = a[i]) {
                "--extra-src" -> { if (i + 1 >= a.size) { System.err.println("minic: missing value for $x"); return 2 }; extra += File(a[++i]) }
                "--brief" -> brief = true
                "-Werror" -> werror = true
                else -> {
                    if (x.startsWith("-")) { System.err.println("minic: unknown option $x"); return 2 }
                    source = File(x)
                }
            }
            i++
        }
        if (source == null) { System.err.println("usage: minic check <file.kt> [--extra-src <file.kt>]... [--brief]"); return 2 }
        if (!source.isFile) { System.err.println("minic: no such file: $source"); return 1 }
        val stdlib = defaultStdlib(null, repoRoot)
            ?: run { System.err.println("minic: Mini stdlib sources not found (${Stdlib.RELATIVE_DIR})"); return 1 }
        return try {
            val r = MemoryCheck.check(source, withoutStdlib(extra, stdlib), stdlib)
            if (brief) r.brief().forEach { println(it) } else if (r.diags.isNotEmpty()) System.err.println(r.render())
            for (f in r.failures) System.err.println("minic: warning: memory checker skipped $f")
            if (r.failed(werror) || r.strictFailures.isNotEmpty()) 1 else 0
        } catch (e: FrontendDriver.FrontendFailure) {
            System.err.println(e.message)
            1
        }
    }

    private fun parse(a: List<String>, needOutput: Boolean = true): Options? {
        val sources = mutableListOf<File>()
        val extra = mutableListOf<File>()
        var werror = false
        var stats = false
        var noRcOpt = false
        var mini = false
        var noStrict = false
        var noConv = false
        var convReport = false
        var wrc = false
        var top = 40
        var userOnly = false
        var stdlib: File? = null
        var noStdlib = false
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
                "--stdlib" -> stdlib = File(next(x) ?: return null)
                "--no-stdlib" -> noStdlib = true
                "--extra-src" -> extra += File(next(x) ?: return null)
                "-Werror" -> werror = true
                "--stats" -> stats = true
                "--no-rc-opt" -> noRcOpt = true
                "--frontend=mini" -> mini = true
                "--frontend=k2" -> mini = false
                "--no-strict" -> noStrict = true
                "--no-conv" -> noConv = true
                "--conv-report" -> convReport = true
                "-Wrc" -> wrc = true
                "--top" -> top = (next(x) ?: return null).toIntOrNull() ?: run { System.err.println("minic: --top needs a number"); return null }
                "--user-only" -> userOnly = true
                else -> {
                    if (x.startsWith("-")) { System.err.println("minic: unknown option $x\n$USAGE"); return null }
                    sources += File(x)
                }
            }
            i++
        }
        if (sources.isEmpty() || (needOutput && out == null)) { System.err.println(USAGE); return null }
        return Options(sources, out ?: File("mem-report"), emitC, cc, rtSrc, rtInc, cflags, stdlib, noStdlib, extra, werror, stats, noRcOpt, miniFrontend = mini, noStrict = noStrict, noConv = noConv, convReport = convReport, wrc = wrc, top = top, userOnly = userOnly)
    }

    fun findRepoRoot(start: File): File? {
        var d: File? = start.absoluteFile.normalize()
        while (d != null) {
            if (File(d, "runtime/include/minirt.h").isFile) return d
            d = d.parentFile
        }
        return null
    }

    /** K2 -> IR -> MIR for [sources] plus [stdlibFiles] and declaration-only [extra] sources. */
    fun frontendToMir(sources: List<File>, stdlibFiles: List<File>, extra: List<File>, werror: Boolean, miniFrontend: Boolean = false): mini.backend.MirModule {
        val sourceName = sources.first().name
        if (miniFrontend) {
            val inputs = ArrayList<mini.lower.LowerInput>()
            for (f in sources) inputs.add(mini.lower.LowerInput(f.path, f.readText(), false))
            for (f in stdlibFiles + extra) inputs.add(mini.lower.LowerInput(f.path, f.readText(), true))
            try {
                return mini.lower.lowerToMir(inputs, sourceName)
            } catch (e: mini.lower.LowerDiagnostics) {
                throw BuildFailure(e.message ?: "compilation failed")
            }
        }
        var mir: mini.backend.MirModule? = null
        FrontendDriver.runFrontend(sources, stdlibFiles + extra) { module, _ ->
            // Strict-mode ownership checker (user files only): errors stop the build, warnings are printed
            MemoryCheck.gate(module, sources.first(), werror)
            MemoryCheck.dropFiles(module, extra) // extra sources only provide declarations
            mir = Ir2Mir().convert(module, sourceName)
        }
        return mir ?: throw BuildFailure("the Kotlin frontend produced no IR for ${sources.joinToString()}")
    }

    /** `minic emit-mir <file.kt>... -o out.mir [--stdlib <dir> | --no-stdlib] [--extra-src <file.kt>]... [-Werror]` */
    private fun emitMir(o: Options, repoRoot: File?): Int {
        for (src in o.sources) if (!src.isFile) throw BuildFailure("no such file: $src")
        val stdlibFiles = if (o.noStdlib) emptyList() else {
            val dir = Stdlib.findDir(o.stdlib, repoRoot)
                ?: throw BuildFailure("Mini stdlib sources not found (${Stdlib.RELATIVE_DIR}); pass --stdlib <dir> or --no-stdlib")
            Stdlib.sources(dir).also { if (it.isEmpty()) throw BuildFailure("no .kt files in $dir") }
        }
        val module = frontendToMir(o.sources, stdlibFiles, withoutStdlib(o.extraSrc, stdlibFiles), o.werror, o.miniFrontend)
        o.output.absoluteFile.parentFile?.mkdirs()
        o.output.writeText(mini.backend.MirWriter.write(module))
        return 0
    }

    /** Is [name] (a qualified function name) from the Mini stdlib rather than from a user file? */
    private fun isStdlibName(name: String) = name.startsWith("kotlin.") || name.startsWith("mini.") || name.startsWith("java.") ||
        name.startsWith("<dispose kotlin.") || name.startsWith("<dispose mini.") || name.contains("\$bridge")

    /**
     * `minic mem-report <file.kt>... [--top n] [--user-only]`: where reference counting remains after the Strict code
     * generation, RcOpt and the calling conventions, and why (plan 4.4a). Prints to stdout.
     */
    private fun memReport(o: Options, repoRoot: File?): Int {
        for (src in o.sources) if (!src.isFile) throw BuildFailure("no such file: $src")
        val stdlibFiles = if (o.noStdlib) emptyList() else {
            val dir = Stdlib.findDir(o.stdlib, repoRoot)
                ?: throw BuildFailure("Mini stdlib sources not found (${Stdlib.RELATIVE_DIR}); pass --stdlib <dir> or --no-stdlib")
            Stdlib.sources(dir).also { if (it.isEmpty()) throw BuildFailure("no .kt files in $dir") }
        }
        val module = frontendToMir(o.sources, stdlibFiles, withoutStdlib(o.extraSrc, stdlibFiles), o.werror)
        val r = CEmitter.emitDetailed(module, rcOpt = !o.noRcOpt, strict = !o.noStrict, conventions = !o.noConv)
        val report = mini.backend.MemReport.build(module, r)
        print(mini.backend.MemReport.text(report, o.top) { f -> !o.userOnly || !isStdlibName(f.name) })
        return 0
    }

    fun build(o: Options, repoRoot: File?): Int {
        for (src in o.sources) if (!src.isFile) throw BuildFailure("no such file: $src")
        val sourceName = o.sources.first().name
        val stdlibFiles = if (o.noStdlib) emptyList() else {
            val dir = Stdlib.findDir(o.stdlib, repoRoot)
                ?: throw BuildFailure("Mini stdlib sources not found (${Stdlib.RELATIVE_DIR}); pass --stdlib <dir> or --no-stdlib")
            Stdlib.sources(dir).also { if (it.isEmpty()) throw BuildFailure("no .kt files in $dir") }
        }

        // 1. runtime location (fail early, before the slow frontend)
        val include = o.runtimeInclude ?: repoRoot?.let { File(it, "runtime/include") }
        if (include == null || !File(include, "minirt.h").isFile) {
            throw BuildFailure("cannot find minirt.h; pass --runtime-include <dir>")
        }
        val runtimeSrc = o.runtimeSrc ?: repoRoot?.let { File(it, "runtime/src") }?.takeIf { it.isDirectory }
            ?: throw BuildFailure("runtime sources not found (runtime/src); pass --runtime-src <dir>")
        val runtimeFiles = runtimeSrc.walkTopDown().filter { it.isFile && it.extension == "c" }.sortedBy { it.path }.toList()
        if (runtimeFiles.isEmpty()) throw BuildFailure("no .c files in $runtimeSrc")

        // 2. K2 -> IR -> MIR
        val extra = withoutStdlib(o.extraSrc, stdlibFiles)
        val module = frontendToMir(o.sources, stdlibFiles, extra, o.werror, o.miniFrontend)
        val mainCount = module.functions.count { it.cName == mini.backend.Mangling.MAIN }
        if (mainCount != 1) throw BuildFailure("${module.sourceName}: expected exactly one `fun main()`, found $mainCount")

        // 3. MIR -> C
        val c = CEmitter.emitDetailed(module, rcOpt = !o.noRcOpt, strict = !o.noStrict, conventions = !o.noConv).also { r ->
            if (o.wrc) for (w in mini.backend.MemReport.m0207(mini.backend.MemReport.build(module, r)) { f -> !isStdlibName(f.name) }) System.err.print(w)
            if (o.convReport) r.plan?.conv?.summary()?.forEach { System.err.println("conv: $it") }
            val dbg = System.getenv("MINIC_RC_EXPLAIN")
            if (dbg != null) for (f in module.functions) if (f.qualifiedName.contains(dbg)) {
                System.err.println("rc: ${f.qualifiedName} effects: ${r.plan?.effectsOf(f.cName)}")
            }
        }.c
        val cFile = o.emitC ?: File.createTempFile("minic-", ".c")
        cFile.absoluteFile.parentFile?.mkdirs()
        cFile.writeText(c)

        // 4. clang
        o.output.absoluteFile.parentFile?.mkdirs()
        val cmd = mutableListOf(o.cc, "-std=c17", "-O1", "-Wall", "-Wno-unused-label", "-Wno-unused-variable", "-Wno-unused-but-set-variable", "-I", include.path)
        if (o.stats) cmd += "-DMK_STATS"
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
