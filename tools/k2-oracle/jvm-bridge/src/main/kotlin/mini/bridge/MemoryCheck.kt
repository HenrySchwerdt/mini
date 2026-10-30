package mini.bridge

import mini.backend.memory.MemoryChecker
import mini.backend.memory.MemoryReport
import java.io.File

/**
 * Runs the Strict-mode ownership checker (docs/memory-model.md) on the IR of the user source files.
 * Pipeline: K2 -> Kotlin IR -> [MemoryFacts] (MemIr) -> `mini.backend.memory` (CFG + dataflow) -> diagnostics.
 * With `--frontend=mini` the same checker runs on the own IR (`mini.lower.checkMemory`); [resultOf] wraps its report.
 */
object MemoryCheck {
    class Result(private val report: MemoryReport, private val names: Map<String, String>) {
        val diags get() = report.diags
        val failures: List<String> get() = report.failures
        /** Failures in Strict functions (the build must not silently accept those). */
        val strictFailures: List<String> get() = report.strictFailures
        val hasErrors: Boolean get() = report.hasErrors
        fun failed(werror: Boolean): Boolean = report.failed(werror)
        fun errorCount(werror: Boolean): Int = report.errorCount(werror)
        fun render(): String = report.render { names[it] ?: it }
        fun brief(): List<String> = report.brief()
    }

    /** Checks [module] (already produced by the frontend); only the [sources] are analysed, other files provide declarations. */
    fun checkModule(module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment, sources: List<File>): Result {
        val canon = sources.map { it.canonicalFile }.toSet()
        // diagnostics name the files as they were given on the command line
        val names = HashMap<String, String>()
        for (f in module.files) {
            val c = File(f.fileEntry.name).canonicalFile
            val given = sources.firstOrNull { it.canonicalFile == c }
            if (given != null) names[f.fileEntry.name] = given.path
        }
        val facts = MemoryFacts { f -> File(f.fileEntry.name).canonicalFile in canon }
        val m = facts.lower(module)
        val diags = MemoryChecker.check(m)
        return Result(MemoryReport.of(m, diags, facts.failures, facts.strictFailures), names)
    }

    fun check(source: File, extraSources: List<File>, stdlib: List<File>): Result = check(listOf(source), extraSources, stdlib)

    fun check(sources: List<File>, extraSources: List<File>, stdlib: List<File>): Result {
        var result: Result? = null
        FrontendDriver.runFrontend(sources, stdlib + extraSources) { module, _ -> result = checkModule(module, sources) }
        return result ?: Result(MemoryReport(emptyList(), emptyMap(), listOf("the frontend produced no IR"), emptyList()), emptyMap())
    }

    /**
     * The build hook: runs the checker on [module]; prints warnings and errors to stderr and throws
     * [Cli.BuildFailure] when there is an error (or when a Strict function could not be analysed).
     */
    fun gate(module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment, sources: List<File>, werror: Boolean = false) {
        val r = try {
            checkModule(module, sources)
        } catch (e: Exception) {
            if (System.getenv("MINIC_DEBUG") != null) e.printStackTrace()
            System.err.println("minic: warning: the memory checker failed internally: ${e.message}")
            return
        }
        gate(r, sources.first().name, werror)
    }

    /** Prints [r] and throws [Cli.BuildFailure] when it fails the build; shared by both frontends. */
    fun gate(r: Result, programName: String, werror: Boolean) {
        if (r.diags.isNotEmpty()) System.err.println(r.render())
        if (r.strictFailures.isNotEmpty()) {
            throw Cli.BuildFailure("the memory checker could not analyse Strict code: " + r.strictFailures.joinToString("; "))
        }
        if (r.failed(werror)) {
            val n = r.errorCount(werror)
            throw Cli.BuildFailure("could not compile $programName due to $n memory error${if (n == 1) "" else "s"}")
        }
    }

    /** Wraps the report of the own frontend like a K2 result; the files are named as they were given. */
    fun resultOf(report: MemoryReport): Result = Result(report, emptyMap())

    /** Removes [files] (declaration-only extra sources) from [module] before code generation. */
    fun dropFiles(module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment, files: List<File>) {
        if (files.isEmpty()) return
        val drop = files.map { it.canonicalFile }.toSet()
        module.files.removeAll { File(it.fileEntry.name).canonicalFile in drop }
    }
}
