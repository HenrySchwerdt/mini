package mini.bridge

import mini.backend.memory.Diag
import mini.backend.memory.DiagRender
import mini.backend.memory.MModule
import mini.backend.memory.MemoryChecker
import mini.backend.memory.Severity
import java.io.File

/**
 * Runs the Strict-mode ownership checker (docs/memory-model.md) on the IR of the user source file.
 * Pipeline: K2 -> Kotlin IR -> [MemoryFacts] (MemIr) -> `mini.backend.memory` (CFG + dataflow) -> diagnostics.
 */
object MemoryCheck {
    class Result(val file: String, val diags: List<Diag>, val sourceLines: List<String>?, val failures: List<String>) {
        val hasErrors: Boolean get() = diags.any { it.severity == Severity.Error }
        fun render(): String = diags.joinToString("\n") { DiagRender.render(it, file, sourceLines) }
        fun brief(): List<String> = diags.map { DiagRender.brief(it) }
    }

    /** Checks [module] (already produced by the frontend); only [source] is analysed, other files provide declarations. */
    fun checkModule(module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment, source: File): Result {
        val canon = source.canonicalFile
        val facts = MemoryFacts { f -> File(f.fileEntry.name).canonicalFile == canon }
        val m: MModule = facts.lower(module)
        val diags = MemoryChecker.check(m)
        return Result(m.fileName.ifEmpty { source.path }, diags, m.sources[m.fileName], facts.failures)
    }

    fun check(source: File, extraSources: List<File>): Result {
        var result: Result? = null
        FrontendDriver.runFrontend(source, extraSources) { module, _ -> result = checkModule(module, source) }
        return result ?: Result(source.path, emptyList(), null, listOf("the frontend produced no IR"))
    }
}
