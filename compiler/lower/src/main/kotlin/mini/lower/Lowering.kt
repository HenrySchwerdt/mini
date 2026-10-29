package mini.lower

import mini.backend.MirModule
import mini.frontend.sema.*

/** A source file handed to the lowering: user files first, then the standard library files. */
class LowerInput(val path: String, val text: String, val isStdlib: Boolean)

/** A problem found while analysing the sources (syntax or type error), as `file:line:col: error: message`. */
class LowerDiagnostics(val messages: List<String>) : Exception(messages.joinToString("\n"))

/**
 * The whole pipeline of the own frontend: analysis (parser + semantic analysis), IR (the shape of K2's IR), pruning of
 * the stdlib, and lowering to MIR. The result equals what the K2 path produces.
 */
fun lowerToMir(inputs: List<LowerInput>, sourceName: String): MirModule {
    val srcs = ArrayList<SourceInput>()
    val user = HashSet<String>()
    for (i in inputs) {
        srcs.add(SourceInput(i.path, i.text))
        if (!i.isStdlib) user.add(i.path)
    }
    val sema = analyzeSources(srcs)
    val errors = ArrayList<String>()
    for (d in sema.diags) {
        if (!user.contains(d.path)) continue
        var text = ""
        for (i in inputs) if (i.path == d.path) text = i.text
        errors.add(fileName(d.path) + ":" + lineCol(text, d.start) + ": error: " + d.message)
    }
    if (errors.isNotEmpty()) throw LowerDiagnostics(errors)
    val builder = Ast2Ir(sema, user)
    val module = builder.build()
    Reachability.prune(module)
    return Ir2Mir().convert(module, sourceName)
}

fun fileName(path: String): String {
    val i = path.lastIndexOf('/')
    return if (i < 0) path else path.substring(i + 1)
}

fun lineCol(text: String, offset: Int): String {
    var line = 1
    var col = 1
    var i = 0
    while (i < offset && i < text.length) {
        if (text[i] == '\n') {
            line++
            col = 1
        } else col++
        i++
    }
    return line.toString() + ":" + col
}
