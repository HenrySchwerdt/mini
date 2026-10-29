package mini.nativedriver

import mini.backend.CEmitter
import mini.backend.MirReader
import mini.io.readTextFile
import mini.io.writeTextFile

/*
 * Driver of the native `minic-backend` (bootstrap stage B1): reads the MIR text written by
 * `minic emit-mir`, runs the C emitter and writes the C file.
 *
 *   native-driver <in.mir> -o <out.c>
 *
 * Pure Kotlin on top of the backend and mini.io; the same source runs on the JVM (Gradle `run`) and,
 * compiled by minic, as a native binary. Errors are reported by throwing (an uncaught exception ends the
 * program with status 1 in both worlds).
 */

const val USAGE = "usage: native-driver <in.mir> -o <out.c> [--no-rc-opt] [--no-strict] [--no-conv]"

/** MIR text to C text; [rcOpt] runs the reference-count elision pass (default). */
fun mirToC(mirText: String, rcOpt: Boolean = true, strict: Boolean = true, conventions: Boolean = true): String =
    CEmitter.emit(MirReader.read(mirText), rcOpt, strict, conventions)

fun main(args: Array<String>) {
    var input: String? = null
    var output: String? = null
    var rcOpt = true
    var strict = true
    var conventions = true
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a == "-o") {
            if (i + 1 >= args.size) throw IllegalArgumentException("missing value for -o\n" + USAGE)
            output = args[i + 1]
            i += 2
        } else if (a == "--no-rc-opt") {
            rcOpt = false
            i += 1
        } else if (a == "--no-strict") {
            strict = false
            i += 1
        } else if (a == "--no-conv") {
            conventions = false
            i += 1
        } else {
            if (input != null) throw IllegalArgumentException("more than one input file\n" + USAGE)
            input = a
            i += 1
        }
    }
    if (input == null || output == null) throw IllegalArgumentException(USAGE)
    writeTextFile(output, mirToC(readTextFile(input), rcOpt, strict, conventions))
}
