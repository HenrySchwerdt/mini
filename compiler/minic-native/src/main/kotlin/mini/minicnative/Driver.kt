package mini.minicnative

import mini.backend.CEmitter
import mini.backend.MirModule
import mini.backend.MirWriter
import mini.backend.llvm.LlvmEmitter
import mini.io.readTextFile
import mini.io.writeTextFile
import mini.lower.LowerDiagnostics
import mini.backend.memory.MemoryReport
import mini.lower.LowerInput
import mini.lower.checkMemory
import mini.lower.UnsupportedConstruct
import mini.lower.lowerToMir

/*
 *   minic-native [-o out.c] [--emit-mir out.mir] [--emit-ll out.ll] [--no-rc-opt] [-Werror] <user.kt>... [--stdlib <stdlib.kt>...]
 *   minic-native --backend=llvm-api -o out.o [--exe prog [--link-arg a]...] [--opt=O1] ...       (LLVM C API build only)
 *   minic-native --backend=llvm-api --run [--opt=O1] <user.kt>... --stdlib <std.kt>... [-- program args]
 *   minic-native --check [--brief] [-Werror] <user.kt>... [--stdlib <stdlib.kt>...]
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

const val USAGE = "usage: minic-native [-o <out.c>] [--emit-mir <out.mir>] [--emit-ll <out.ll>] [--no-rc-opt] [-Werror] <user.kt>... [--stdlib <stdlib.kt>...]\n       minic-native --backend=llvm-api -o <out.o> [--exe <prog> [--link-arg <arg>]...] [--opt=O0..O3] [--no-rc-opt] <user.kt>... --stdlib <stdlib.kt>...\n       minic-native --backend=llvm-api --run [--opt=O0..O3] <user.kt>... --stdlib <stdlib.kt>... [-- <program args>]\n       minic-native --check [--brief] [-Werror] <user.kt>... [--stdlib <stdlib.kt>...]"

/** What the build of the compiler with the LLVM C API bound in (compiler/llvm-c) adds: object files, linking, the ORC JIT. */
interface LlvmApi {
    /** Builds the LLVM module of [module] in memory, optimizes it (`default<O[optLevel]>`) and writes an object file; [llPath] (nullable) gets the module as text. */
    fun emitObject(module: MirModule, path: String, llPath: String?, optLevel: Int, rcOpt: Boolean)

    /** Links [objects] and the libraries the program asked for (`@file:CLink`) with the system C compiler [cc] into the executable [exe]; the exit status. */
    fun link(objects: List<String>, exe: String, libs: List<String>, linkArgs: List<String>, cc: String): Int

    /** Compiles [module] in memory, adds it to an ORC JIT and runs its `main` in this process; the exit status of the program. */
    fun run(module: MirModule, programArgs: List<String>, optLevel: Int, rcOpt: Boolean): Int
}

fun runDriver(args: Array<String>, llvm: LlvmApi?) {
    var output: String? = null
    var mirOut: String? = null
    var llOut: String? = null
    var rcOpt = true
    var check = false
    var brief = false
    var werror = false
    var backend = "c"
    var optLevel = 1
    var exe: String? = null
    var cc = "cc"
    var run = false
    val linkArgs = ArrayList<String>()
    val programArgs = ArrayList<String>()
    val inputs = ArrayList<LowerInput>()
    var stdlib = false
    var firstName: String? = null
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a == "-o") {
            if (i + 1 >= args.size) throw IllegalArgumentException("missing value for -o\n" + USAGE)
            output = args[i + 1]
            i += 2
        } else if (a == "--emit-mir") {
            if (i + 1 >= args.size) throw IllegalArgumentException("missing value for --emit-mir\n" + USAGE)
            mirOut = args[i + 1]
            i += 2
        } else if (a == "--emit-ll") {
            if (i + 1 >= args.size) throw IllegalArgumentException("missing value for --emit-ll\n" + USAGE)
            llOut = args[i + 1]
            i += 2
        } else if (a.startsWith("--backend=")) {
            backend = a.substring("--backend=".length)
            if (backend != "c" && backend != "llvm" && backend != "llvm-api") throw IllegalArgumentException("unknown backend " + backend + "\n" + USAGE)
            i += 1
        } else if (a.startsWith("--opt=")) {
            var v = a.substring("--opt=".length)
            if (v.startsWith("O")) v = v.substring(1)
            if (v.length != 1 || v[0] < '0' || v[0] > '3') throw IllegalArgumentException("--opt takes O0, O1, O2 or O3\n" + USAGE)
            optLevel = v[0].code - '0'.code
            i += 1
        } else if (a == "--exe") {
            if (i + 1 >= args.size) throw IllegalArgumentException("missing value for --exe\n" + USAGE)
            exe = args[i + 1]
            i += 2
        } else if (a == "--link-arg") {
            if (i + 1 >= args.size) throw IllegalArgumentException("missing value for --link-arg\n" + USAGE)
            linkArgs.add(args[i + 1])
            i += 2
        } else if (a == "--cc") {
            if (i + 1 >= args.size) throw IllegalArgumentException("missing value for --cc\n" + USAGE)
            cc = args[i + 1]
            i += 2
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
            if (a.startsWith("-")) throw IllegalArgumentException("unknown option " + a + "\n" + USAGE)
            if (!stdlib && firstName == null) firstName = fileNameOf(a)
            inputs.add(LowerInput(a, readTextFile(a), stdlib))
            i += 1
        }
    }
    val api = backend == "llvm-api"
    if (api && llvm == null) throw IllegalArgumentException("--backend=llvm-api needs the build of minic-native with the LLVM C API (compiler/llvm-c)")
    if ((run || exe != null) && !api) throw IllegalArgumentException("--run and --exe need --backend=llvm-api\n" + USAGE)
    if (firstName == null || (!check && output == null && mirOut == null && llOut == null && exe == null && !run)) throw IllegalArgumentException(USAGE)
    val name: String = firstName
    if (check) {
        val report = try {
            checkMemory(inputs)
        } catch (e: LowerDiagnostics) {
            throw IllegalStateException(e.message)
        }
        reportMemory(report, name, werror, brief)
        return
    }
    val module = try {
        lowerToMir(inputs, name) { report -> reportMemory(report, name, werror, false) }
    } catch (e: LowerDiagnostics) {
        throw IllegalStateException(e.message)
    } catch (e: UnsupportedConstruct) {
        throw IllegalStateException(e.message)
    }
    if (mirOut != null) writeTextFile(mirOut, MirWriter.write(module))
    if (api && llvm != null) {
        if (run) {
            val status = llvm.run(module, programArgs, optLevel, rcOpt)
            if (status != 0) throw IllegalStateException("the program exited with status " + status)
            return
        }
        // -o is the object file; with --exe the object is a temporary one next to the executable unless -o names it
        val obj: String = output ?: (exe + ".o")
        llvm.emitObject(module, obj, llOut, optLevel, rcOpt)
        if (exe != null) {
            val status = llvm.link(listOf(obj), exe, module.links, linkArgs, cc)
            if (status != 0) throw IllegalStateException("linking " + exe + " failed (status " + status + ")")
        }
        return
    }
    if (output != null) writeTextFile(output, CEmitter.emit(module, rcOpt))
    if (llOut != null) writeTextFile(llOut, LlvmEmitter.emit(module, rcOpt))
}

private fun fileNameOf(path: String): String {
    val i = path.lastIndexOf('/')
    return if (i < 0) path else path.substring(i + 1)
}

/** Prints the diagnostics and fails (an uncaught exception ends the program with status 1) when the report fails the build. */
private fun reportMemory(report: MemoryReport, program: String, werror: Boolean, brief: Boolean) {
    if (brief) {
        for (l in report.brief()) println(l)
    } else if (report.diags.isNotEmpty()) {
        println(report.render())
    }
    if (report.strictFailures.isNotEmpty()) {
        throw IllegalStateException("the memory checker could not analyse Strict code: " + report.strictFailures.joinToString("; "))
    }
    if (report.failed(werror)) {
        val n = report.errorCount(werror)
        throw IllegalStateException("could not compile " + program + " due to " + n + " memory error" + (if (n == 1) "" else "s"))
    }
}
