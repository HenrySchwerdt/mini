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
    val key: String get() = "$code@$pos"
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
