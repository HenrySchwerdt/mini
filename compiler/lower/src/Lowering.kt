package mini.lower

import mini.backend.MirModule
import mini.backend.memory.MemoryChecker
import mini.backend.memory.MemoryReport
import mini.frontend.sema.*

/** A source file handed to the lowering: user files first, then the standard library files. */
class LowerInput(val path: String, val text: String, val isStdlib: Boolean)

/** A problem found while analysing the sources (syntax or type error), as `file:line:col: error: message`. */
class LowerDiagnostics(val messages: List<String>, val diags: List<FrontDiag> = emptyList()) : Exception(messages.joinToString("\n"))

/** One such problem with its position (1-based line and column) and the text of its source line, for rendering with a caret. */
class FrontDiag(val path: String, val line: Int, val col: Int, val message: String, val sourceLine: String)

/**
 * The IR of a set of sources: analysis (parser + semantic analysis) and the K2-shaped tree of [Ast2Ir]; throws
 * [LowerDiagnostics] for syntax and type errors in the user files.
 */
fun buildIr(inputs: List<LowerInput>): IrModule {
    val srcs = ArrayList<SourceInput>()
    val user = HashSet<String>()
    // K2 lists all files (program and stdlib) in path order whatever order they were given in
    val sorted = ArrayList<LowerInput>(inputs)
    sorted.sortBy { it.path }
    for (i in sorted) {
        srcs.add(SourceInput(i.path, i.text))
        if (!i.isStdlib) user.add(i.path)
    }
    val sema = analyzeSources(srcs)
    val errors = ArrayList<String>()
    val structured = ArrayList<FrontDiag>()
    for (d in sema.diags) {
        if (!user.contains(d.path)) continue
        var text = ""
        for (i in inputs) if (i.path == d.path) text = i.text
        errors.add(fileName(d.path) + ":" + lineCol(text, d.start) + ": error: " + d.message)
        structured.add(frontDiag(d.path, text, d.start, d.message))
    }
    if (errors.isNotEmpty()) throw LowerDiagnostics(errors, structured)
    val builder = Ast2Ir(sema, user)
    return builder.build()
}

/**
 * Runs the Strict-mode ownership checker (docs/memory-model.md) over every user file of [module]; the files that
 * provide declarations only (the stdlib, `--extra-src`) are not analysed.
 */
fun checkMemory(module: IrModule): MemoryReport {
    val facts = MemoryFacts { f -> !f.isStdlib }
    val m = facts.lower(module)
    val diags = MemoryChecker.check(m)
    return MemoryReport.of(m, diags, facts.failures, facts.strictFailures)
}

/** Analyses [inputs] and runs the memory checker on the user files (`minic check --frontend=mini`). */
fun checkMemory(inputs: List<LowerInput>): MemoryReport = checkMemory(buildIr(inputs))

/**
 * The whole pipeline of the own frontend: analysis (parser + semantic analysis), IR (the shape of K2's IR), pruning of
 * the stdlib, and lowering to MIR. The result equals what the K2 path produces. [memoryGate] (when given) receives the
 * result of the memory checker before the code generation and may throw to stop the build.
 */
fun lowerToMir(inputs: List<LowerInput>, sourceName: String, lineDirectives: Boolean = false, memoryGate: ((MemoryReport) -> Unit)? = null): MirModule {
    val module = buildIr(inputs)
    if (memoryGate != null) memoryGate(checkMemory(module))
    Reachability.prune(module)
    val lowering = Ir2Mir()
    lowering.lineDirectives = lineDirectives
    return lowering.convert(module, sourceName)
}

private fun frontDiag(path: String, text: String, offset: Int, message: String): FrontDiag {
    var line = 1
    var lineStart = 0
    var i = 0
    while (i < offset && i < text.length) {
        if (text[i] == '\n') {
            line++
            lineStart = i + 1
        }
        i++
    }
    var end = lineStart
    while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
    return FrontDiag(path, line, (if (offset < text.length) offset else text.length) - lineStart + 1, message, text.substring(lineStart, end))
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
