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
    /** Width of the token that starts at [col] on [line]: an identifier or number, otherwise 1 (the caret underlines it). */
    fun tokenWidth(sourceLines: List<String>?, line: Int, col: Int): Int {
        if (sourceLines == null || line < 1 || line > sourceLines.size) return 1
        return tokenWidthIn(sourceLines[line - 1], col)
    }

    /** [tokenWidth] for the text of the line itself. */
    fun tokenWidthIn(text: String, col: Int): Int {
        var i = col - 1
        if (i < 0) return 1
        if (i < text.length && text[i] == '"') {
            // a string literal underlines up to its closing quote
            var j = i + 1
            while (j < text.length && text[j] != '"') j += if (text[j] == '\\') 2 else 1
            return if (j < text.length) j - i + 1 else 1
        }
        var n = 0
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) {
            n++
            i++
        }
        return if (n == 0) 1 else n
    }

    /** The source line with a caret underline below the token at [col]; `""` when the line is unknown. */
    fun snippet(sourceLines: List<String>?, line: Int, col: Int, style: DiagStyle, sev: Severity): String {
        if (sourceLines == null || line < 1 || line > sourceLines.size) return ""
        return snippetOf(sourceLines[line - 1], line, col, style, sev)
    }

    /** [snippet] for the text of the line itself. */
    fun snippetOf(text: String, line: Int, col: Int, style: DiagStyle, sev: Severity): String {
        val num = line.toString()
        val sb = StringBuilder()
        sb.append("   ").append(style.blue(num + " |")).append(' ').append(text).append('\n')
        sb.append("   ").append(" ".repeat(num.length)).append(' ').append(style.blue("|")).append(' ')
        sb.append(" ".repeat((col - 1).coerceAtLeast(0))).append(style.severity(sev, "^".repeat(tokenWidthIn(text, col)))).append('\n')
        return sb.toString()
    }

    /** rustc-like rendering, spec 7.0. [sourceLines] (1-based lines at index line-1) adds a caret snippet when known. */
    fun render(d: Diag, file: String, sourceLines: List<String>? = null, style: DiagStyle = DiagStyle.PLAIN): String {
        val sb = StringBuilder()
        sb.append(style.severity(d.severity, d.severity.label + "[" + d.code + "]")).append(style.bold(": " + d.message)).append('\n')
        sb.append("  ").append(style.blue("-->")).append(' ').append(file).append(':').append(d.pos.line).append(':').append(d.pos.col)
        if (d.primaryLabel.isNotEmpty()) sb.append("   ").append(d.primaryLabel)
        sb.append('\n')
        sb.append(snippet(sourceLines, d.pos.line, d.pos.col, style, d.severity))
        for ((p, l) in d.secondary) {
            sb.append("  ").append(style.blue("-->")).append(' ').append(file).append(':').append(p.line).append(':').append(p.col)
            if (l.isNotEmpty()) sb.append("   ").append(l)
            sb.append('\n')
        }
        for (h in d.help) sb.append("  ").append(style.cyan("help:")).append(' ').append(h).append('\n')
        return sb.toString()
    }

    /** `note:` lines that name each code once with its title and documentation page; `""` for no known code. */
    fun codeNotes(codes: List<String>, style: DiagStyle): String {
        val sb = StringBuilder()
        for (c in codes) {
            val t = DiagCodes.title(c) ?: continue
            sb.append(style.cyan("note:")).append(' ').append(c).append(" (").append(t).append("): ").append(DiagCodes.docsUrl(c)).append('\n')
        }
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

    /**
     * rustc-like text; [nameOf] maps the file of a diagnostic to the name shown. At most [limit] diagnostics are shown
     * (0 = all). Every code that was shown is explained once at the end with its documentation link.
     */
    fun render(style: DiagStyle = DiagStyle.PLAIN, limit: Int = 0, nameOf: (String) -> String = { it }): String {
        val sb = StringBuilder()
        val codes = ArrayList<String>()
        var shown = 0
        for (d in diags) {
            if (limit > 0 && shown >= limit) break
            if (shown > 0) sb.append('\n')
            shown++
            sb.append(DiagRender.render(d, nameOf(d.file), sources[d.file], style))
            if (!codes.contains(d.code)) codes.add(d.code)
        }
        if (shown > 0) {
            val notes = DiagRender.codeNotes(codes, style)
            if (notes.isNotEmpty()) sb.append('\n').append(notes)
        }
        return sb.toString()
    }

    /** The diagnostics as JSON lines (one object per diagnostic), at most [limit] (0 = all). */
    fun json(limit: Int = 0, nameOf: (String) -> String = { it }): List<String> {
        val out = ArrayList<String>()
        for (d in diags) {
            if (limit > 0 && out.size >= limit) break
            out.add(DiagJson.of(d, nameOf(d.file), sources[d.file]))
        }
        return out
    }

    val warningCount: Int get() = diags.count { it.severity == Severity.Warning }

    /** `line:col: CODE` per diagnostic (the corpus format). */
    fun brief(): List<String> = diags.map { DiagRender.brief(it) }

    companion object {
        fun of(module: MModule, diags: List<Diag>, failures: List<String>, strictFailures: List<String>): MemoryReport =
            MemoryReport(diags, module.sources, failures, strictFailures)
    }
}
