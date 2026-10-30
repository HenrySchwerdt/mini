package mini.minicnative

import mini.backend.CEmitter
import mini.backend.MirModule
import mini.backend.MirWriter
import mini.backend.llvm.LlvmEmitter
import mini.backend.llvm.LlvmTarget
import mini.io.readTextFile
import mini.io.writeTextFile
import mini.lower.LowerDiagnostics
import mini.backend.memory.DiagJson
import mini.backend.memory.DiagRender
import mini.backend.memory.DiagStyle
import mini.backend.memory.MemoryReport
import mini.backend.memory.Severity
import mini.internal.exitProcess
import mini.internal.stderrWrite
import mini.lower.LowerInput
import mini.lower.checkMemory
import mini.lower.UnsupportedConstruct
import mini.lower.lowerToMir

/*
 *   minic-native [-o out.c] [--emit-mir out.mir] [--emit-ll out.ll] [--target=wasm32] [--emit-c out.c] [--no-rc-opt] [-Werror] <user.kt>... [--stdlib <stdlib.kt>...]
 *   minic-native --backend=llvm-api -o out.o [--exe prog [--link-arg a]...] [--opt=O1] ...       (LLVM C API build only)
 *   minic-native --backend=llvm-api --run [--opt=O1] <user.kt>... --stdlib <std.kt>... [-- program args]
 *   minic-native --check [--brief] [-Werror] <user.kt>... [--stdlib <stdlib.kt>...]
 *   diagnostics: [--color=auto|always|never] [--error-format=human|json] [--error-limit=N]
 *   debugging:   [--line-directives]   `#line` directives in the C file, so debuggers and crash reports point to the .kt source
 *
 * Diagnostics go to standard error. Exit status: 0 success, 1 compile error (diagnostics were printed), 2 usage error,
 * 3 internal compiler error (a bug in the compiler: unexpected exception).
 *
 * The compiler without K2: our own parser and semantic analysis (mini.frontend), the lowering to MIR (mini.lower) and the C
 * backend (mini.backend) behind one driver. Files before `--stdlib` are the program (the first one names the module),
 * files after it are the standard library sources, in the order they are given (compiled together with the program,
 * only the reachable part ends up in the C file). `--user` switches back to program files. Pure Kotlin on top of
 * the other pure modules and mini.io; the same source runs on the JVM and, compiled by minic, as a native binary.
 *
 * `--backend=llvm-api` needs the build of the compiler that has the LLVM C API bound in (compiler/llvm-c, docs/plan-kotlin.md section
 * 10g phase 2): [runDriver] gets an [LlvmApi] there, `null` in the plain build.
 */

const val USAGE = "usage: minic-native [--color=auto|always|never] [--error-format=human|json] [--error-limit=N] [-o <out.c>] [--emit-mir <out.mir>] [--emit-ll <out.ll>] [--target=wasm32] [--emit-c <out.c>] [--no-rc-opt] [-Werror] <user.kt>... [--stdlib <stdlib.kt>...]\n       minic-native --backend=llvm-api -o <out.o> [--exe <prog> [--link-arg <arg>]...] [--opt=O0..O3] [--no-rc-opt] <user.kt>... --stdlib <stdlib.kt>...\n       minic-native --backend=llvm-api --run [--time] [--opt=O0..O3] <user.kt>... --stdlib <stdlib.kt>... [-- <program args>]\n       minic-native --check [--brief] [-Werror] <user.kt>... [--stdlib <stdlib.kt>...]"

/** What the build of the compiler with the LLVM C API bound in (compiler/llvm-c) adds: object files, linking, the ORC JIT. */
interface LlvmApi {
    /** Builds the LLVM module of [module] in memory, optimizes it (`default<O[optLevel]>`) and writes an object file; [llPath] (nullable) gets the module as text. */
    fun emitObject(module: MirModule, path: String, llPath: String?, optLevel: Int, rcOpt: Boolean)

    /** Links [objects] and the libraries the program asked for (`@file:CLink`) with the system C compiler [cc] into the executable [exe]; the exit status. */
    fun link(objects: List<String>, exe: String, libs: List<String>, linkArgs: List<String>, cc: String): Int

    /** Compiles [module] in memory, adds it to an ORC JIT and runs its `main` in this process; the exit status of the program. */
    fun run(module: MirModule, programArgs: List<String>, optLevel: Int, rcOpt: Boolean, timing: Boolean): Int
}

/** The command line is wrong (exit status 2). */
class UsageError(message: String) : Exception(message)

/** The diagnostics were printed already; the process ends with [status] (1 = compile error). */
class DriverExit(val status: Int) : Exception("exit " + status)

/** How diagnostics are printed: ANSI [style], `human` or `json` format, at most [limit] of them (0 = all). */
class DiagOptions(var style: DiagStyle = DiagStyle.PLAIN, var json: Boolean = false, var limit: Int = 20)

private fun errln(text: String) {
    stderrWrite(text + "\n")
}

fun runDriver(args: Array<String>, llvm: LlvmApi?) {
    val diag = DiagOptions()
    var status = 0
    try {
        driverMain(args, llvm, diag)
    } catch (e: DriverExit) {
        status = e.status
    } catch (e: UsageError) {
        errln(DiagStyle.PLAIN.red("error") + ": " + e.message)
        status = 2
    } catch (e: UnsupportedConstruct) {
        printGeneral(diag, e.message ?: "unsupported construct")
        status = 1
    } catch (e: Throwable) {
        printGeneral(diag, "internal compiler error: " + e.toString())
        if (!diag.json) {
            errln("note: this is a bug in minic, not in your program. Please report it at https://github.com/HenrySchwerdt/mini/issues")
            errln("      with the command line and, if you can, the source file (minic build --emit-c keeps the generated C).")
        }
        status = 3
    }
    if (status != 0) exitProcess(status)
}

private fun printGeneral(diag: DiagOptions, message: String) {
    if (diag.json) errln(DiagJson.general("error", message)) else errln(diag.style.red("error") + diag.style.bold(": " + message))
}

private fun driverMain(args: Array<String>, llvm: LlvmApi?, diag: DiagOptions) {
    var output: String? = null
    var mirOut: String? = null
    var llOut: String? = null
    var cOut: String? = null
    var rcOpt = true
    var wasm32 = false
    var check = false
    var brief = false
    var werror = false
    var lineDirs = false
    var backend = "c"
    var optLevel = 1
    var exe: String? = null
    var cc = "cc"
    var run = false
    var timing = false
    val linkArgs = ArrayList<String>()
    val programArgs = ArrayList<String>()
    val inputs = ArrayList<LowerInput>()
    var stdlib = false
    var firstName: String? = null
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a == "-o") {
            if (i + 1 >= args.size) throw UsageError("missing value for -o\n" + USAGE)
            output = args[i + 1]
            i += 2
        } else if (a == "--emit-mir") {
            if (i + 1 >= args.size) throw UsageError("missing value for --emit-mir\n" + USAGE)
            mirOut = args[i + 1]
            i += 2
        } else if (a == "--emit-ll") {
            if (i + 1 >= args.size) throw UsageError("missing value for --emit-ll\n" + USAGE)
            llOut = args[i + 1]
            i += 2
        } else if (a == "--target=wasm32") {
            wasm32 = true
            i += 1
        } else if (a == "--emit-c") {
            if (i + 1 >= args.size) throw UsageError("missing value for --emit-c\n" + USAGE)
            cOut = args[i + 1]
            i += 2
        } else if (a.startsWith("--backend=")) {
            backend = a.substring("--backend=".length)
            if (backend != "c" && backend != "llvm" && backend != "llvm-api") throw UsageError("unknown backend " + backend + "\n" + USAGE)
            i += 1
        } else if (a.startsWith("--opt=")) {
            var v = a.substring("--opt=".length)
            if (v.startsWith("O")) v = v.substring(1)
            if (v.length != 1 || v[0] < '0' || v[0] > '3') throw UsageError("--opt takes O0, O1, O2 or O3\n" + USAGE)
            optLevel = v[0].code - '0'.code
            i += 1
        } else if (a == "--exe") {
            if (i + 1 >= args.size) throw UsageError("missing value for --exe\n" + USAGE)
            exe = args[i + 1]
            i += 2
        } else if (a == "--link-arg") {
            if (i + 1 >= args.size) throw UsageError("missing value for --link-arg\n" + USAGE)
            linkArgs.add(args[i + 1])
            i += 2
        } else if (a == "--cc") {
            if (i + 1 >= args.size) throw UsageError("missing value for --cc\n" + USAGE)
            cc = args[i + 1]
            i += 2
        } else if (a.startsWith("--color=")) {
            val v = a.substring("--color=".length)
            if (v != "auto" && v != "always" && v != "never") throw UsageError("--color takes auto, always or never (got '" + v + "')\n" + USAGE)
            // `auto` is decided by the wrapper (it knows whether stderr is a terminal and whether NO_COLOR is set): plain here
            diag.style = DiagStyle(v == "always")
            i += 1
        } else if (a.startsWith("--error-format=")) {
            val v = a.substring("--error-format=".length)
            if (v != "human" && v != "json") throw UsageError("--error-format takes human or json (got '" + v + "')\n" + USAGE)
            diag.json = v == "json"
            i += 1
        } else if (a.startsWith("--error-limit=")) {
            val v = a.substring("--error-limit=".length)
            val n = v.toIntOrNull() ?: -1
            if (n < 0) throw UsageError("--error-limit takes a number, 0 for no limit (got '" + v + "')\n" + USAGE)
            diag.limit = n
            i += 1
        } else if (a == "--line-directives") {
            lineDirs = true
            i += 1
        } else if (a == "--help" || a == "-h") {
            println(USAGE)
            return
        } else if (a == "--time") {
            timing = true
            i += 1
        } else if (a == "--run") {
            run = true
            i += 1
        } else if (a == "--") {
            for (k in i + 1 until args.size) programArgs.add(args[k])
            i = args.size
        } else if (a == "--no-rc-opt") {
            rcOpt = false
            i += 1
        } else if (a == "--check") {
            check = true
            i += 1
        } else if (a == "--brief") {
            brief = true
            i += 1
        } else if (a == "-Werror") {
            werror = true
            i += 1
        } else if (a == "--stdlib") {
            stdlib = true
            i += 1
        } else if (a == "--user") {
            stdlib = false
            i += 1
        } else {
            if (a.startsWith("-")) throw UsageError("unknown option " + a + "\n" + USAGE)
            if (!stdlib && firstName == null) firstName = fileNameOf(a)
            val text = try {
                readTextFile(a)
            } catch (e: IllegalStateException) {
                printGeneral(diag, "cannot read " + a + ": no such file or not readable")
                throw DriverExit(1)
            }
            inputs.add(LowerInput(a, text, stdlib))
            i += 1
        }
    }
    val api = backend == "llvm-api"
    if (api && llvm == null) throw UsageError("--backend=llvm-api needs the build of minic-native with the LLVM C API (compiler/llvm-c)")
    if ((run || exe != null) && !api) throw UsageError("--run and --exe need --backend=llvm-api\n" + USAGE)
    if (firstName == null && args.size > 0 && inputs.isEmpty()) throw UsageError("no input files\n" + USAGE)
    if (firstName == null || (!check && output == null && mirOut == null && llOut == null && exe == null && cOut == null && !run)) throw UsageError(USAGE)
    val name: String = firstName
    if (check) {
        val report = try {
            checkMemory(inputs)
        } catch (e: LowerDiagnostics) {
            reportFrontend(e, name, diag)
            throw DriverExit(1)
        }
        reportMemory(report, name, werror, brief, diag)
        return
    }
    val module = try {
        lowerToMir(inputs, name, lineDirs) { report -> reportMemory(report, name, werror, false, diag) }
    } catch (e: LowerDiagnostics) {
        reportFrontend(e, name, diag)
        throw DriverExit(1)
    }
    if (mirOut != null) writeTextFile(mirOut, MirWriter.write(module))
    if (cOut != null) writeTextFile(cOut, CEmitter.emit(module, rcOpt, lineFile = if (lineDirs) cOut else null))
    if (api && llvm != null) {
        if (run) {
            val status = llvm.run(module, programArgs, optLevel, rcOpt, timing)
            if (status != 0) {
                errln("error: the program exited with status " + status)
                throw DriverExit(1)
            }
            return
        }
        // -o is the object file; with --exe the object is a temporary one next to the executable unless -o names it
        val obj: String = output ?: (exe + ".o")
        llvm.emitObject(module, obj, llOut, optLevel, rcOpt)
        if (exe != null) {
            val status = llvm.link(listOf(obj), exe, module.links, linkArgs, cc)
            if (status != 0) {
                errln("error: linking " + exe + " failed (status " + status + ")")
                throw DriverExit(1)
            }
        }
        return
    }
    if (output != null) writeTextFile(output, CEmitter.emit(module, rcOpt, lineFile = if (lineDirs) output else null))
    if (llOut != null) writeTextFile(llOut, LlvmEmitter.emit(module, rcOpt, target = if (wasm32) LlvmTarget.WASM32 else LlvmTarget.HOST))
}

private fun fileNameOf(path: String): String {
    val i = path.lastIndexOf('/')
    return if (i < 0) path else path.substring(i + 1)
}

private fun chomp(s: String): String {
    var e = s.length
    while (e > 0 && s[e - 1] == '\n') e--
    return s.substring(0, e)
}

private fun plural(n: Int, word: String): String = n.toString() + " " + word + (if (n == 1) "" else "s")

private fun countsLine(errors: Int, warnings: Int): String = plural(errors, "error") + ", " + plural(warnings, "warning")

/** Syntax and type errors: `file:line:col: error: message`, the source line with a caret, and the count line. */
private fun reportFrontend(e: LowerDiagnostics, program: String, diag: DiagOptions) {
    val st = diag.style
    val total = if (e.diags.isEmpty()) e.messages.size else e.diags.size
    if (e.diags.isEmpty()) {
        for (m in e.messages) errln(m)
    } else {
        var shown = 0
        for (d in e.diags) {
            if (diag.limit > 0 && shown >= diag.limit) break
            shown++
            if (diag.json) {
                errln(DiagJson.frontend(d.path, d.line, d.col, d.col + DiagRender.tokenWidthIn(d.sourceLine, d.col), d.message))
            } else {
                if (shown > 1) errln("")
                errln(st.bold(d.path + ":" + d.line + ":" + d.col + ":") + " " + st.red("error:") + " " + st.bold(d.message))
                errln(chomp(DiagRender.snippetOf(d.sourceLine, d.line, d.col, st, Severity.Error)))
            }
        }
        if (!diag.json && shown < total) {
            errln("")
            errln(st.red("error:") + " too many errors: showing the first " + shown + " of " + total + " (--error-limit=0 shows all)")
        }
    }
    if (!diag.json) {
        errln("")
        errln(st.red("error:") + " could not compile " + program + " (" + countsLine(total, 0) + ")")
    }
}

/** Prints the diagnostics and fails (status 1) when the report fails the build. */
private fun reportMemory(report: MemoryReport, program: String, werror: Boolean, brief: Boolean, diag: DiagOptions) {
    val st = diag.style
    if (brief) {
        for (l in report.brief()) println(l)
    } else if (diag.json) {
        for (l in report.json(limit = diag.limit)) errln(l)
    } else if (report.diags.isNotEmpty()) {
        errln(chomp(report.render(style = st, limit = diag.limit)))
        if (diag.limit > 0 && report.diags.size > diag.limit) {
            errln("")
            errln(st.red("error:") + " too many diagnostics: showing the first " + diag.limit + " of " + report.diags.size + " (--error-limit=0 shows all)")
        }
    }
    if (report.strictFailures.isNotEmpty()) {
        printGeneral(diag, "the memory checker could not analyse Strict code: " + report.strictFailures.joinToString("; "))
        throw DriverExit(1)
    }
    val warnings = report.warningCount
    if (report.failed(werror)) {
        val n = report.errorCount(werror)
        if (!diag.json && !brief) {
            errln("")
            errln(st.red("error:") + " could not compile " + program + " (" + plural(n, "error") + ", " + plural(if (werror) 0 else warnings, "warning") + ")")
        }
        throw DriverExit(1)
    }
    if (warnings > 0 && !diag.json && !brief) {
        errln("")
        errln(st.yellow("warning:") + " " + program + " generated " + plural(warnings, "warning"))
    }
}
