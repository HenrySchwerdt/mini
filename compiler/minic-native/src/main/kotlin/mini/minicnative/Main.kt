package mini.minicnative

import mini.backend.CEmitter
import mini.backend.MirWriter
import mini.backend.llvm.LlvmEmitter
import mini.io.readTextFile
import mini.io.writeTextFile
import mini.lower.LowerDiagnostics
import mini.lower.LowerInput
import mini.lower.UnsupportedConstruct
import mini.lower.lowerToMir

/*
 *   minic-native [-o out.c] [--emit-mir out.mir] [--emit-ll out.ll] [--no-rc-opt] <user.kt>... [--stdlib <stdlib.kt>...]
 *
 * The compiler without K2: our own parser and semantic analysis (mini.frontend), the lowering to MIR (mini.lower) and the C
 * backend (mini.backend) behind one driver. Files before `--stdlib` are the program (the first one names the module),
 * files after it are the standard library sources, in the order they are given (compiled together with the program,
 * only the reachable part ends up in the C file). `--user` switches back to program files. Pure Kotlin on top of
 * the other pure modules and mini.io; the same source runs on the JVM and, compiled by minic, as a native binary.
 */

const val USAGE = "usage: minic-native [-o <out.c>] [--emit-mir <out.mir>] [--emit-ll <out.ll>] [--no-rc-opt] <user.kt>... [--stdlib <stdlib.kt>...]"

fun main(args: Array<String>) {
    var output: String? = null
    var mirOut: String? = null
    var llOut: String? = null
    var rcOpt = true
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
        } else if (a == "--no-rc-opt") {
            rcOpt = false
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
    if (firstName == null || (output == null && mirOut == null && llOut == null)) throw IllegalArgumentException(USAGE)
    val module = try {
        lowerToMir(inputs, firstName)
    } catch (e: LowerDiagnostics) {
        throw IllegalStateException(e.message)
    } catch (e: UnsupportedConstruct) {
        throw IllegalStateException(e.message)
    }
    if (mirOut != null) writeTextFile(mirOut, MirWriter.write(module))
    if (output != null) writeTextFile(output, CEmitter.emit(module, rcOpt))
    if (llOut != null) writeTextFile(llOut, LlvmEmitter.emit(module, rcOpt))
}

private fun fileNameOf(path: String): String {
    val i = path.lastIndexOf('/')
    return if (i < 0) path else path.substring(i + 1)
}
