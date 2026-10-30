package mini.tool

/** `mini explain <code>`: the diagnostic catalog of docs/memory-model.md section 7 (compiled in by the build). */
object Explain {
    fun codes(): List<String> = ExplainData.sections.flatMap { it.first }.distinct().sorted()

    /** `m102`, `0102`, `102` and `M0102` all mean M0102. */
    fun normalize(input: String): String {
        val t = input.trim().uppercase()
        val digits = if (t.startsWith("M")) t.substring(1) else t
        return if (digits.isNotEmpty() && digits.all { it in '0'..'9' } && digits.length <= 4) "M" + digits.padStart(4, '0') else t
    }

    fun render(code: String, s: Style): String? {
        val sec = ExplainData.sections.firstOrNull { code in it.first } ?: return null
        val sb = StringBuilder()
        val title = sec.second
        sb.append(s.bold(title)).append('\n')
        sb.append(s.dim("-".repeat(minOf(title.length, 72)))).append("\n\n")
        var inCode = false
        for (l in sec.third.lines()) {
            if (l.trimStart().startsWith("```")) { inCode = !inCode; continue }
            when {
                inCode -> sb.append("    ").append(s.cyan(l)).append('\n')
                l.startsWith("**") && l.contains(":**") -> {
                    val label = l.substringBefore(":**").removePrefix("**")
                    sb.append(s.green("$label:", true)).append(l.substringAfter(":**").replace("`", "")).append('\n')
                }
                else -> sb.append(l.replace("**", "")).append('\n')
            }
        }
        sb.append('\n').append(s.dim("Reference: docs/memory-model.md, section 7. In a project: `mini check` shows these diagnostics.")).append('\n')
        return sb.toString()
    }

    fun list(s: Style): String {
        val sb = StringBuilder()
        for ((code, sev, short) in ExplainData.overview) {
            val col = when (sev.substringBefore(' ')) { "error" -> s.red(sev.padEnd(8)); "warning" -> s.yellow(sev.padEnd(8)); else -> s.cyan(sev.padEnd(8)) }
            sb.append(s.bold(code)).append("  ").append(col).append(' ').append(short).append('\n')
        }
        sb.append("\nRun `mini explain <CODE>` for the full explanation.\n")
        return sb.toString()
    }
}
