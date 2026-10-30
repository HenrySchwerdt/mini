package mini.tool

/** One diagnostic parsed from the text minic prints (rustc-like Mini diagnostics and the Kotlin frontend's `file:l:c: error: msg`). */
class Diag(
    val level: String,
    val code: String?,
    val message: String,
    val spans: MutableList<DiagSpan> = ArrayList(),
    val help: MutableList<String> = ArrayList(),
    val rendered: StringBuilder = StringBuilder(),
)

class DiagSpan(val file: String, val line: Int, val column: Int, val label: String, val primary: Boolean)

object Diagnostics {
    private val miniHeader = MiniHeaderPattern
    private val miniSpan = MiniSpanPattern
    private val kotlinDiag = KotlinDiagPattern(allowSlash = true)
    private val help = HelpPattern

    /** Lines that are environment noise, not compiler output. */
    fun isNoise(line: String): Boolean =
        line.startsWith("Picked up JAVA_TOOL_OPTIONS") || line.startsWith("Picked up _JAVA_OPTIONS") || line.startsWith("Picked up JDK_JAVA_OPTIONS")

    /** minic-native reports a failed compilation as an uncaught exception: `Exception in thread "main" java.lang.IllegalStateException: <message>`. */
    fun stripUncaught(line: String): String {
        val head = "Exception in thread \"main\" "
        if (!line.startsWith(head)) return line
        val rest = line.substring(head.length)
        val colon = rest.indexOf(": ")
        return if (colon > 0 && rest.substring(0, colon).endsWith("Exception")) rest.substring(colon + 2) else rest
    }

    /** Drops JVM noise and the `minic: could not compile ...` summary (mini prints its own). */
    fun clean(text: String): String =
        text.lines().map { stripUncaught(it) }.filter { !isNoise(it) && !it.startsWith("minic: could not compile") && !it.startsWith("could not compile ") }.joinToString("\n").trimEndChars('\n')

    /** Rewrites absolute paths in compiler output: [mappings] are (absolute prefix, replacement). Longest prefix first. */
    fun remap(text: String, mappings: List<Pair<String, String>>): String {
        var t = text
        for ((from, to) in mappings.sortedByDescending { it.first.length }) {
            if (from.isEmpty()) continue
            t = t.replace(from, to)
        }
        return t
    }

    fun parse(text: String): List<Diag> {
        val out = ArrayList<Diag>()
        var cur: Diag? = null
        for (line in text.lines()) {
            val m = miniHeader.find(line)
            val k = kotlinDiag.find(line)
            when {
                m != null -> { cur = Diag(m.groupValues[1], m.groupValues[2], m.groupValues[3]); out += cur; cur.rendered.append(line).append('\n') }
                k != null -> {
                    cur = Diag(k.groupValues[4], null, k.groupValues[5])
                    cur.spans += DiagSpan(k.groupValues[1], k.groupValues[2].toInt(), k.groupValues[3].toInt(), "", true)
                    cur.rendered.append(line).append('\n')
                    out += cur
                }
                line.startsWith("minic: ") && !line.startsWith("minic: warning") -> {
                    cur = Diag("error", null, line.removePrefix("minic: ")); out += cur; cur.rendered.append(line).append('\n')
                }
                cur != null -> {
                    val s = miniSpan.find(line)
                    val h = help.find(line)
                    if (s != null) cur.spans += DiagSpan(s.groupValues[1], s.groupValues[2].toInt(), s.groupValues[3].toInt(), s.groupValues[4], cur.spans.isEmpty())
                    else if (h != null) cur.help += h.groupValues[1]
                    if (line.isNotBlank()) cur.rendered.append(line).append('\n') else cur = null
                }
            }
        }
        return out
    }

    /** Colours `error[M0102]:`, `-->` and `help:` lines. */
    fun colorize(text: String, s: Style): String {
        if (!s.enabled) return text
        return text.lines().joinToString("\n") { l ->
            val m = miniHeader.find(l)
            val k = kotlinDiag.find(l)
            when {
                m != null -> {
                    val sev = m.groupValues[1]
                    val head = "$sev[${m.groupValues[2]}]:"
                    (if (sev == "error") s.red(head, true) else if (sev == "warning") s.yellow(head, true) else s.cyan(head, true)) + " " + s.bold(m.groupValues[3])
                }
                k != null -> l.substringBefore(": ${k.groupValues[4]}: ") + ": " +
                    (if (k.groupValues[4] == "error") s.red("error:", true) else s.yellow("warning:", true)) + " " + s.bold(k.groupValues[5])
                miniSpan.matches(l) -> replaceFirstText(l, "-->", s.blue("-->"))
                help.matches(l) -> replaceFirstText(l, "help:", s.cyan("help:", true))
                else -> l
            }
        }
    }

    // ---- JSON (for editors): one object per line --------------------------------------------------------------------

    fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun toJson(d: Diag, pkg: String): String {
        val spans = d.spans.joinToString(",") {
            """{"file":${jsonString(it.file)},"line":${it.line},"column":${it.column},"label":${jsonString(it.label)},"primary":${it.primary}}"""
        }
        val helps = d.help.joinToString(",") { jsonString(it) }
        return """{"reason":"compiler-message","package":${jsonString(pkg)},"level":${jsonString(d.level)},"code":${if (d.code == null) "null" else jsonString(d.code)},""" +
            """"message":${jsonString(d.message)},"spans":[$spans],"help":[$helps],"rendered":${jsonString(d.rendered.toString().trimEnd())}}"""
    }

    fun finishedJson(success: Boolean, pkg: String, millis: Long): String =
        """{"reason":"build-finished","package":${jsonString(pkg)},"success":$success,"millis":$millis}"""
}
