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
    class Result(
        val file: String, val diags: List<Diag>, val sourceLines: List<String>?, val failures: List<String>,
        /** Failures in Strict functions (the build must not silently accept those). */
        val strictFailures: List<String> = emptyList(),
    ) {
        val hasErrors: Boolean get() = diags.any { it.severity == Severity.Error }
        fun failed(werror: Boolean): Boolean = hasErrors || (werror && diags.any { it.severity == Severity.Warning })
        fun render(): String = diags.joinToString("\n") { DiagRender.render(it, file, sourceLines) }
        fun brief(): List<String> = diags.map { DiagRender.brief(it) }
    }

    /** Checks [module] (already produced by the frontend); only [source] is analysed, other files provide declarations. */
    fun checkModule(module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment, source: File): Result {
        val canon = source.canonicalFile
        val facts = MemoryFacts { f -> File(f.fileEntry.name).canonicalFile == canon }
        val m: MModule = facts.lower(module)
        val diags = MemoryChecker.check(m)
        return Result(m.fileName.ifEmpty { source.path }, diags, m.sources[m.fileName], facts.failures, facts.strictFailures)
    }

    fun check(source: File, extraSources: List<File>): Result {
        var result: Result? = null
        FrontendDriver.runFrontend(source, extraSources) { module, _ -> result = checkModule(module, source) }
        return result ?: Result(source.path, emptyList(), null, listOf("the frontend produced no IR"))
    }

    /**
     * The build hook: runs the checker on [module]; prints warnings and errors to stderr and throws
     * [Cli.BuildFailure] when there is an error (or when a Strict function could not be analysed).
     */
    fun gate(module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment, source: File, werror: Boolean = false) {
        val r = try {
            checkModule(module, source)
        } catch (e: Exception) {
            if (System.getenv("MINIC_DEBUG") != null) e.printStackTrace()
            System.err.println("minic: warning: the memory checker failed internally: ${e.message}")
            return
        }
        if (r.diags.isNotEmpty()) System.err.println(r.render())
        if (r.strictFailures.isNotEmpty()) {
            throw Cli.BuildFailure("the memory checker could not analyse Strict code: " + r.strictFailures.joinToString("; "))
        }
        if (r.failed(werror)) {
            val n = r.diags.count { it.severity == Severity.Error || werror && it.severity == Severity.Warning }
            throw Cli.BuildFailure("could not compile ${source.name} due to $n memory error${if (n == 1) "" else "s"}")
        }
    }

    /** Drops every file except [source] from [module] (the extra sources are not translated to C). */
    fun keepOnly(module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment, source: File) {
        val canon = source.canonicalFile
        module.files.removeAll { File(it.fileEntry.name).canonicalFile != canon }
    }
}
