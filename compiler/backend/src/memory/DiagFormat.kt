package mini.backend.memory

/*
 * Presentation helpers shared by every driver (JVM bridge and the native compiler): ANSI colors, the one-line
 * explanation and documentation link of a diagnostic code, and JSON for editors (`--error-format=json`).
 */

/** ANSI styling for diagnostics; [PLAIN] (the default everywhere) adds no escape sequences. */
class DiagStyle(val color: Boolean) {
    private fun wrap(code: String, s: String): String = if (color) "\u001b[" + code + "m" + s + "\u001b[0m" else s

    fun bold(s: String): String = wrap("1", s)
    fun red(s: String): String = wrap("1;31", s)
    fun yellow(s: String): String = wrap("1;33", s)
    fun cyan(s: String): String = wrap("1;36", s)
    fun blue(s: String): String = wrap("1;34", s)
    fun green(s: String): String = wrap("1;32", s)

    fun severity(sev: Severity, s: String): String = when (sev) {
        Severity.Error -> red(s)
        Severity.Warning -> yellow(s)
        Severity.Info -> cyan(s)
    }

    companion object {
        val PLAIN = DiagStyle(false)
    }
}

/** Codes of the memory checker with their one-line titles (the same words as the pages under website/reference/diagnostics). */
object DiagCodes {
    const val DOCS_BASE = "https://henryschwerdt.github.io/mini/reference/diagnostics/"

    fun title(code: String): String? = when (code) {
        "M0101" -> "Use after free"
        "M0102" -> "Leak: allocation is never released"
        "M0103" -> "Double free"
        "M0104" -> "Use after move"
        "M0105" -> "Escape from a Scoped allocator"
        "M0106" -> "Individual free of a Scoped object"
        "M0107" -> "Release of a non-owned value"
        "M0108" -> "Wrong allocator when freeing"
        "M0109" -> "Loan is stored or returned as owned"
        "M0110" -> "Violation of the Strict/Managed boundary"
        "M0111" -> "Invalid ownership annotation"
        "M0112" -> "Owning value in an escaping lambda"
        "M0113" -> "Owned field stays moved"
        "M0114" -> "Ambiguous allocator"
        "M0115" -> "Arena object owns a foreign reference"
        "M0201" -> "Allocation moved to the stack"
        "M0202" -> "Allocation in an arena loop"
        "M0203" -> "Large object on the stack"
        "M0204" -> "No allocator for an escaping Strict value"
        "M0205" -> "Allocation in a @NoAlloc function"
        "M0206" -> "Object leaves the thread but its allocator is not ThreadSafe"
        else -> null
    }

    fun docsUrl(code: String): String = DOCS_BASE + code
}

object DiagJson {
    fun str(s: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (c in s) {
            if (c == '"') sb.append("\\\"")
            else if (c == '\\') sb.append("\\\\")
            else if (c == '\n') sb.append("\\n")
            else if (c == '\r') sb.append("\\r")
            else if (c == '\t') sb.append("\\t")
            else if (c.code < 0x20) {
                val hex = "0123456789abcdef"
                sb.append("\\u00").append(hex[c.code / 16]).append(hex[c.code % 16])
            } else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    /** One memory-checker diagnostic as a single-line JSON object. */
    fun of(d: Diag, file: String, sourceLines: List<String>?): String {
        val sb = StringBuilder()
        sb.append("{\"severity\":").append(str(d.severity.label))
        sb.append(",\"code\":").append(str(d.code))
        sb.append(",\"message\":").append(str(d.message))
        sb.append(",\"file\":").append(str(file))
        sb.append(",\"line\":").append(d.pos.line).append(",\"col\":").append(d.pos.col)
        sb.append(",\"endCol\":").append(d.pos.col + DiagRender.tokenWidth(sourceLines, d.pos.line, d.pos.col))
        sb.append(",\"label\":").append(str(d.primaryLabel))
        sb.append(",\"secondary\":[")
        var first = true
        for ((p, l) in d.secondary) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"file\":").append(str(file)).append(",\"line\":").append(p.line).append(",\"col\":").append(p.col)
            sb.append(",\"label\":").append(str(l)).append('}')
        }
        sb.append("],\"help\":[")
        first = true
        for (h in d.help) {
            if (!first) sb.append(',')
            first = false
            sb.append(str(h))
        }
        sb.append("],\"docs\":").append(str(DiagCodes.docsUrl(d.code))).append('}')
        return sb.toString()
    }

    /** One syntax or type error of the frontend (no code) as a single-line JSON object. */
    fun frontend(file: String, line: Int, col: Int, endCol: Int, message: String): String {
        val sb = StringBuilder()
        sb.append("{\"severity\":\"error\",\"code\":null,\"message\":").append(str(message))
        sb.append(",\"file\":").append(str(file))
        sb.append(",\"line\":").append(line).append(",\"col\":").append(col).append(",\"endCol\":").append(endCol)
        sb.append(",\"label\":\"\",\"secondary\":[],\"help\":[],\"docs\":null}")
        return sb.toString()
    }

    /** A problem without a position (usage, I/O or unsupported construct). */
    fun general(severity: String, message: String): String =
        "{\"severity\":" + str(severity) + ",\"code\":null,\"message\":" + str(message) + ",\"file\":null,\"line\":0,\"col\":0,\"endCol\":0,\"label\":\"\",\"secondary\":[],\"help\":[],\"docs\":null}"
}
