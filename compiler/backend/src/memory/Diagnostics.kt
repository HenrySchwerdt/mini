package mini.backend.memory

enum class Severity(val label: String) { Error("error"), Warning("warning"), Info("info") }

/** One diagnostic (docs/memory-model.md section 7.0): stable code and primary position, free-form text. */
class Diag(
    val code: String,
    val severity: Severity,
    val message: String,
    val pos: Pos,
    val primaryLabel: String,
    val secondary: List<Pair<Pos, String>> = emptyList(),
    val help: List<String> = emptyList(),
    /** Owner slot the diagnostic is about; later M0102 leaks of the same slot are suppressed (one cause, one message). */
    val slot: Int = -1,
) {
    /** The source file the diagnostic is in (set by [MemoryChecker]; empty for a single-file module without names). */
    var file: String = ""
    val key: String get() = "$file:$code@$pos"
}

object DiagRender {
    /** rustc-like rendering, spec 7.0. [sourceLines] (1-based lines at index line-1) adds a caret snippet when known. */
    fun render(d: Diag, file: String, sourceLines: List<String>? = null): String {
        val sb = StringBuilder()
        sb.append(d.severity.label).append('[').append(d.code).append("]: ").append(d.message).append('\n')
        sb.append("  --> ").append(file).append(':').append(d.pos.line).append(':').append(d.pos.col)
        if (d.primaryLabel.isNotEmpty()) sb.append("   ").append(d.primaryLabel)
        sb.append('\n')
        if (sourceLines != null && d.pos.line in 1..sourceLines.size) {
            val text = sourceLines[d.pos.line - 1]
            val num = d.pos.line.toString()
            sb.append("   ").append(num).append(" | ").append(text).append('\n')
            sb.append("   ").append(" ".repeat(num.length)).append(" | ").append(" ".repeat((d.pos.col - 1).coerceAtLeast(0))).append("^\n")
        }
        for ((p, l) in d.secondary) {
            sb.append("  --> ").append(file).append(':').append(p.line).append(':').append(p.col)
            if (l.isNotEmpty()) sb.append("   ").append(l)
            sb.append('\n')
        }
        for (h in d.help) sb.append("  help: ").append(h).append('\n')
        return sb.toString()
    }

    /** The stable machine-readable form used by the corpus tests. */
    fun brief(d: Diag): String = "${d.pos.line}:${d.pos.col}: ${d.code}"
}

/**
 * The outcome of one run of the checker over the user files of a program: the diagnostics (each knows its file), the
 * source lines by file name for the snippets, and the functions the producer could not lower. Shared by both frontends
 * and the native driver, so every driver prints the same text and uses the same exit rule.
 */
class MemoryReport(
    val diags: List<Diag>,
    val sources: Map<String, List<String>>,
    val failures: List<String>,
    /** Failures in Strict functions (the build must not silently accept those). */
    val strictFailures: List<String>,
) {
    val hasErrors: Boolean get() = diags.any { it.severity == Severity.Error }

    fun failed(werror: Boolean): Boolean = hasErrors || strictFailures.isNotEmpty() || (werror && diags.any { it.severity == Severity.Warning })

    /** Number of diagnostics that fail the build. */
    fun errorCount(werror: Boolean): Int = diags.count { it.severity == Severity.Error || (werror && it.severity == Severity.Warning) }

    /** rustc-like text; [nameOf] maps the file of a diagnostic to the name shown. */
    fun render(nameOf: (String) -> String = { it }): String {
        val sb = StringBuilder()
        var first = true
        for (d in diags) {
            if (!first) sb.append('\n')
            first = false
            sb.append(DiagRender.render(d, nameOf(d.file), sources[d.file]))
        }
        return sb.toString()
    }

    /** `line:col: CODE` per diagnostic (the corpus format). */
    fun brief(): List<String> = diags.map { DiagRender.brief(it) }

    companion object {
        fun of(module: MModule, diags: List<Diag>, failures: List<String>, strictFailures: List<String>): MemoryReport =
            MemoryReport(diags, module.sources, failures, strictFailures)
    }
}
