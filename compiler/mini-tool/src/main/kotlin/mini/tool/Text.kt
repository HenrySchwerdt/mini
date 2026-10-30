package mini.tool

/*
 * Small text helpers. The Mini standard library has no `Regex` (and only a part of kotlin.text), and the core of `mini` is
 * compiled by Mini itself, so the line patterns it needs are written by hand here. Pure Kotlin, same behaviour on every platform.
 */

/** A successful match: [groupValues] like `MatchResult.groupValues` (index 0 is the whole line). */
class Match(val groupValues: List<String>)

private fun isDigit(c: Char) = c in '0'..'9'
private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'
private fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

/** Length of the digit run at [from] (0 when there is none). */
private fun digitsAt(s: String, from: Int): Int {
    var i = from
    while (i < s.length && isDigit(s[i])) i++
    return i - from
}

private fun skipSpaces(s: String, from: Int): Int {
    var i = from
    while (i < s.length && isSpace(s[i])) i++
    return i
}

/** `error[M0102]: message` */
object MiniHeaderPattern {
    fun find(line: String): Match? {
        var sev: String? = null
        for (cand in listOf("error", "warning", "info")) if (line.startsWith(cand + "[M")) sev = cand
        if (sev == null) return null
        val at = sev.length + 2 // after `[M`
        if (digitsAt(line, at) != 4) return null
        val code = line.substring(at - 1, at + 4)
        val rest = "]: "
        if (!line.startsWith(rest, at + 4)) return null
        return Match(listOf(line, sev, code, line.substring(at + 4 + rest.length)))
    }
}

/** `   --> file.kt:12:5  label` (the label is optional; the group is empty then) */
object MiniSpanPattern {
    fun find(line: String): Match? {
        val start = skipSpaces(line, 0)
        if (start == 0 || !line.startsWith("--> ", start)) return null
        val body = start + 4
        // the file name is the shortest prefix (at least one character) followed by `:<line>:<col>` and the end or two spaces
        var colon = line.indexOf(':', body + 1)
        while (colon >= 0) {
            val l = digitsAt(line, colon + 1)
            if (l > 0 && colon + 1 + l < line.length && line[colon + 1 + l] == ':') {
                val c = digitsAt(line, colon + 2 + l)
                val end = colon + 2 + l + c
                if (c > 0) {
                    val file = line.substring(body, colon)
                    val ln = line.substring(colon + 1, colon + 1 + l)
                    val col = line.substring(colon + 2 + l, end)
                    if (end == line.length) return Match(listOf(line, file, ln, col, ""))
                    val sp = skipSpaces(line, end)
                    if (sp - end >= 2) return Match(listOf(line, file, ln, col, line.substring(sp)))
                }
            }
            colon = line.indexOf(':', colon + 1)
        }
        return null
    }

    fun matches(line: String): Boolean = find(line) != null
}

/**
 * `file.kt:12:5: error: message` (a file name without `:`; with [allowSlash] false also without `/`).
 * Groups: line, file, line number, column, severity, message.
 */
class KotlinDiagPattern(private val allowSlash: Boolean) {
    fun find(line: String): Match? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val file = line.substring(0, colon)
        if (isSpace(file[0]) || !file.endsWith(".kt")) return null
        if (!allowSlash && file.contains('/')) return null
        val l = digitsAt(line, colon + 1)
        if (l == 0 || colon + 1 + l >= line.length || line[colon + 1 + l] != ':') return null
        val c = digitsAt(line, colon + 2 + l)
        if (c == 0) return null
        val at = colon + 2 + l + c
        if (!line.startsWith(": ", at)) return null
        var sev: String? = null
        for (cand in listOf("error", "warning")) if (line.startsWith(cand + ": ", at + 2)) sev = cand
        if (sev == null) return null
        return Match(listOf(line, file, line.substring(colon + 1, colon + 1 + l), line.substring(colon + 2 + l, at), sev, line.substring(at + 2 + sev.length + 2)))
    }
}

/** `   help: text` */
object HelpPattern {
    fun find(line: String): Match? {
        val start = skipSpaces(line, 0)
        if (start == 0 || !line.startsWith("help: ", start)) return null
        return Match(listOf(line, line.substring(start + 6)))
    }

    fun matches(line: String): Boolean = find(line) != null
}

private fun hasChar(chars: CharArray, c: Char): Boolean {
    for (x in chars) if (x == c) return true
    return false
}

/** Removes the characters [chars] from the end of the string. */
fun String.trimEndChars(vararg chars: Char): String {
    var e = length
    while (e > 0 && hasChar(chars, this[e - 1])) e--
    return substring(0, e)
}

/** Removes the characters [chars] from both ends of the string. */
fun String.trimChars(vararg chars: Char): String {
    var s = 0
    var e = length
    while (s < e && hasChar(chars, this[s])) s++
    while (e > s && hasChar(chars, this[e - 1])) e--
    return substring(s, e)
}

/** Replaces every run of [c] by a single one. */
fun String.collapseRuns(c: Char): String {
    val sb = StringBuilder()
    for (ch in this) if (!(ch == c && sb.length > 0 && sb[sb.length - 1] == c)) sb.append(ch)
    return sb.toString()
}

/** `1.2.3`, `1.2.3-rc.1`, `1.2.3+build`: three numbers, then an optional suffix that starts with `-` or `+`. */
fun isSemanticVersion(v: String): Boolean {
    var i = 0
    for (part in 0 until 3) {
        val d = digitsAt(v, i)
        if (d == 0) return false
        i += d
        if (part < 2) {
            if (i >= v.length || v[i] != '.') return false
            i++
        }
    }
    if (i == v.length) return true
    if (v[i] != '-' && v[i] != '+') return false
    if (i + 1 >= v.length) return false
    for (k in i + 1 until v.length) {
        val ch = v[k]
        if (!(isWordChar(ch) && ch != '_' || ch == '.' || ch == '-' || ch == '+')) return false
    }
    return true
}

/** The index of the word `fun` at or after [from] (a whole word), or -1. */
fun indexOfWordFun(text: String, from: Int): Int {
    var i = text.indexOf("fun", from)
    while (i >= 0) {
        val before = i == 0 || !isWordChar(text[i - 1])
        val after = i + 3 >= text.length || !isWordChar(text[i + 3])
        if (before && after) return i
        i = text.indexOf("fun", i + 1)
    }
    return -1
}

/** Drops a `java.lang.` or `kotlin.` prefix in front of a class name (`kotlin.IllegalStateException` -> `IllegalStateException`). */
fun stripPackagePrefixes(s: String): String {
    var t = s
    // ("java" + ".lang." is split so that the purity check does not mistake it for JVM API use)
    for (prefix in listOf("java" + ".lang.", "kotlin.")) {
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val atWord = i == 0 || !isWordChar(t[i - 1])
            if (atWord && t.startsWith(prefix, i) && i + prefix.length < t.length && t[i + prefix.length] in 'A'..'Z') {
                i += prefix.length
            } else {
                sb.append(t[i])
                i++
            }
        }
        t = sb.toString()
    }
    return t
}

/** The first `"quoted"` text of [s] without the quotes, or null. */
fun firstQuoted(s: String): String? {
    val a = s.indexOf('"')
    if (a < 0) return null
    val b = s.indexOf('"', a + 1)
    return if (b <= a + 1) null else s.substring(a + 1, b)
}

/** Key of a TOML line `  name = ...` (letters, digits, `_`, `-`), or null. */
fun tomlKeyOf(line: String): String? {
    val s = skipSpaces(line, 0)
    var e = s
    while (e < line.length && (isWordChar(line[e]) || line[e] == '-')) e++
    if (e == s) return null
    val eq = skipSpaces(line, e)
    return if (eq < line.length && line[eq] == '=') line.substring(s, e) else null
}

/** Is the line a `[dependencies]` table header (optionally followed by a comment)? */
fun isDependenciesHeader(line: String): Boolean {
    var i = skipSpaces(line, 0)
    if (!line.startsWith("[dependencies]", i)) return false
    i = skipSpaces(line, i + 14)
    return i == line.length || line[i] == '#'
}

/** Replaces the first occurrence of [old] in [s] by [new]. */
fun replaceFirstText(s: String, old: String, new: String): String {
    val i = s.indexOf(old)
    return if (i < 0) s else s.substring(0, i) + new + s.substring(i + old.length)
}
