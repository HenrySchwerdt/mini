package mini.tool

/**
 * A small TOML subset: `# comments`, `[table]` and `[a.b]` headers, `key = value` with bare, quoted and dotted keys,
 * strings (basic with escapes, literal), integers, booleans, arrays (also over several lines, trailing comma),
 * inline tables `{ a = 1 }`. Not supported (with a friendly error): multi-line strings, floats, dates, `[[arrays of tables]]`.
 *
 * Every value and key remembers its position so that manifest validation can point at the exact spot.
 */
class Span(val line: Int, val col: Int, val len: Int = 1)

sealed class TomlValue { abstract val span: Span }
class TStr(val value: String, override val span: Span) : TomlValue()
class TInt(val value: Long, override val span: Span) : TomlValue()
class TBool(val value: Boolean, override val span: Span) : TomlValue()
class TArray(val items: List<TomlValue>, override val span: Span) : TomlValue()
class TEntry(val key: String, val keySpan: Span, val value: TomlValue)
class TTable(val entries: LinkedHashMap<String, TEntry>, override val span: Span) : TomlValue() {
    operator fun get(key: String): TomlValue? = entries[key]?.value
    fun keys(): List<String> = entries.keys.toList()
}

/** A syntax or validation problem with an exact position, a label for the caret and an optional hint. */
class TomlError(
    val msg: String,
    val span: Span,
    val label: String? = null,
    val hint: String? = null,
    val related: Pair<Span, String>? = null,
) : Exception(msg)

/** Renders [e] like rustc: `error: ...`, a location line, the source line and a caret, then the hint. */
fun renderSourceError(e: TomlError, file: String, source: String, style: Style, severity: String = "error"): String {
    val lines = source.split('\n')
    val sb = StringBuilder()
    sb.append(style.red("$severity:", bold = true)).append(' ').append(style.bold(e.msg)).append('\n')
    val width = maxOf(e.span.line, e.related?.first?.line ?: 0).toString().length
    val pad = " ".repeat(width)
    sb.append(pad).append(style.blue("-->")).append(" $file:${e.span.line}:${e.span.col}\n")
    sb.append(pad).append(style.blue(" |")).append('\n')
    fun snippet(sp: Span, label: String?, primary: Boolean) {
        val text = lines.getOrElse(sp.line - 1) { "" }.trimEndChars('\r')
        sb.append(style.blue(sp.line.toString().padStart(width) + " |")).append(' ').append(text).append('\n')
        val len = sp.len.coerceIn(1, maxOf(1, text.length - sp.col + 1))
        val marks = (if (primary) "^" else "-").repeat(len)
        val colored = if (primary) style.red(marks, bold = true) else style.blue(marks)
        sb.append(pad).append(style.blue(" |")).append(" ".repeat(sp.col - 1 + 1)).append(colored)
        if (label != null) sb.append(' ').append(if (primary) style.red(label) else style.blue(label))
        sb.append('\n')
    }
    val rel = e.related
    if (rel != null && rel.first.line < e.span.line) { snippet(rel.first, rel.second, false); snippet(e.span, e.label, true) }
    else { snippet(e.span, e.label, true); if (rel != null) snippet(rel.first, rel.second, false) }
    if (e.hint != null) sb.append(pad).append(style.blue(" =")).append(' ').append(style.cyan("help:", bold = true)).append(' ').append(e.hint).append('\n')
    return sb.toString()
}

fun parseToml(text: String): TTable = TomlParser(text).parse()

private class TomlParser(val s: String) {
    var p = 0
    var line = 1
    var col = 1
    val root = TTable(LinkedHashMap(), Span(1, 1))

    fun peek(): Char = if (p < s.length) s[p] else '\u0000'
    fun eof(): Boolean = p >= s.length
    fun adv() {
        if (s[p] == '\n') { line++; col = 1 } else col++
        p++
    }
    fun span(len: Int = 1) = Span(line, col, len)
    fun err(msg: String, label: String? = null, hint: String? = null, at: Span = span(), related: Pair<Span, String>? = null): Nothing =
        throw TomlError(msg, at, label, hint, related)

    fun skipBlanks() { while (!eof() && (peek() == ' ' || peek() == '\t')) adv() }
    fun skipComment() { if (peek() == '#') while (!eof() && peek() != '\n') adv() }
    fun skipTrivia() { // blanks, comments and newlines
        while (!eof()) {
            val c = peek()
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') adv() else if (c == '#') skipComment() else break
        }
    }

    fun isBare(c: Char) = c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '-'

    fun parse(): TTable {
        var current = root
        val defined = HashSet<String>() // headers seen
        while (true) {
            skipTrivia()
            if (eof()) break
            val c = peek()
            if (c == '[') {
                val start = span()
                adv()
                if (peek() == '[') err("arrays of tables (`[[...]]`) are not supported", "here", "use a table per item, e.g. `[dependencies]` entries or the `members` array of `[workspace]`", start)
                skipBlanks()
                val path = parseKeyPath()
                skipBlanks()
                if (peek() != ']') err("expected `]` to close the table header", "expected `]`", "write the header as `[${path.joinToString(".") { it.first }}]`")
                adv()
                expectEndOfLine()
                val full = path.joinToString(".") { it.first }
                if (!defined.add(full)) err("table `[$full]` is defined more than once", "defined again here", "merge the two tables into one", path.first().second)
                current = descend(root, path, headerSpan = path.first().second)
            } else {
                val path = parseKeyPath()
                skipBlanks()
                if (peek() != '=') {
                    val shown = path.joinToString(".") { it.first }
                    err("expected `=` after the key `$shown`", "expected `=`", "write `$shown = <value>`", if (eof() || peek() == '\n') span() else span())
                }
                adv()
                skipBlanks()
                val v = parseValue()
                expectEndOfLine()
                val table = descend(current, path.dropLast(1), headerSpan = null)
                val (name, ks) = path.last()
                val prev = table.entries[name]
                if (prev != null) err("duplicate key `$name`", "second definition", "remove one of them", ks, Pair(prev.keySpan, "first defined here"))
                table.entries[name] = TEntry(name, ks, v)
            }
        }
        return root
    }

    /** Navigates (creating tables) along [path] from [from]. */
    fun descend(from: TTable, path: List<Pair<String, Span>>, headerSpan: Span?): TTable {
        var t = from
        for ((name, sp) in path) {
            val e = t.entries[name]
            t = when (val v = e?.value) {
                null -> TTable(LinkedHashMap(), sp).also { t.entries[name] = TEntry(name, sp, it) }
                is TTable -> v
                else -> err("`$name` is already defined as a value, not a table", "conflicts with the line above", null, sp, Pair(e.keySpan, "defined here"))
            }
        }
        return t
    }

    fun expectEndOfLine() {
        skipBlanks()
        skipComment()
        if (!eof() && peek() != '\n' && peek() != '\r') {
            err("unexpected `${peek()}` after the value", "expected end of line", "put one `key = value` pair per line")
        }
    }

    fun parseKeyPath(): List<Pair<String, Span>> {
        val out = ArrayList<Pair<String, Span>>()
        while (true) {
            skipBlanks()
            val start = span()
            val c = peek()
            val key: String
            if (c == '"' || c == '\'') {
                val str = parseString()
                key = str.value
                out += Pair(key, Span(start.line, start.col, str.span.len))
            } else {
                val b = p
                while (!eof() && isBare(peek())) adv()
                if (p == b) {
                    val shown = if (eof()) "end of file" else if (c == '\n') "end of line" else "`$c`"
                    err("expected a key, found $shown", "expected a key", if (c == '[') "tables are written `[name]` on their own line" else null)
                }
                key = s.substring(b, p)
                out += Pair(key, Span(start.line, start.col, p - b))
            }
            skipBlanks()
            if (peek() == '.') adv() else break
        }
        return out
    }

    fun parseValue(): TomlValue {
        val c = peek()
        return when {
            c == '"' || c == '\'' -> parseString()
            c == '[' -> parseArray()
            c == '{' -> parseInline()
            c == '-' || c == '+' || c.isDigit() -> parseNumber()
            c == '\n' || c == '\r' || eof() || c == '#' -> err("expected a value after `=`", "missing value", "write a string (`\"...\"`), number, `true`/`false`, array or inline table")
            else -> {
                val start = span()
                val b = p
                while (!eof() && isBare(peek())) adv()
                val word = s.substring(b, p)
                when (word) {
                    "true" -> TBool(true, Span(start.line, start.col, 4))
                    "false" -> TBool(false, Span(start.line, start.col, 5))
                    "" -> err("unexpected `$c`", "expected a value", null)
                    else -> err("unquoted value `$word`", "not a valid value", "strings need quotes: write \"$word\"", Span(start.line, start.col, word.length))
                }
            }
        }
    }

    fun parseNumber(): TomlValue {
        val start = span()
        val b = p
        if (peek() == '-' || peek() == '+') adv()
        while (!eof() && (peek().isDigit() || peek() == '_')) adv()
        if (peek() == '.' || peek() == 'e' || peek() == 'E' || peek() == ':' ) {
            while (!eof() && !(peek() == ' ' || peek() == '\n' || peek() == ',' || peek() == ']' || peek() == '}' || peek() == '#')) adv()
            err("floats and dates are not supported in mini.toml", "here", "use a string, e.g. \"${s.substring(b, p)}\"", Span(start.line, start.col, p - b))
        }
        val text = s.substring(b, p).replace("_", "")
        val v = text.toLongOrNull() ?: err("invalid number `${s.substring(b, p)}`", "here", null, Span(start.line, start.col, p - b))
        return TInt(v, Span(start.line, start.col, p - b))
    }

    fun parseString(): TStr {
        val start = span()
        val q = peek()
        if (s.startsWith("$q$q$q", p)) err("multi-line strings are not supported", "here", "use a single-line string with `\\n` escapes", Span(start.line, start.col, 3))
        adv()
        val sb = StringBuilder()
        while (true) {
            if (eof() || peek() == '\n') err("unterminated string", "starts here", "add a closing `$q` on the same line", Span(start.line, start.col, 1))
            val c = peek()
            if (c == q) { adv(); break }
            if (q == '"' && c == '\\') {
                val escSpan = span(2)
                adv()
                val e = peek()
                when (e) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    '"' -> sb.append('"'); '\\' -> sb.append('\\')
                    else -> err("unknown escape `\\$e`", "here", "valid escapes are \\n \\t \\r \\\" \\\\ (use a 'literal string' to keep backslashes)", escSpan)
                }
                adv()
            } else { sb.append(c); adv() }
        }
        return TStr(sb.toString(), Span(start.line, start.col, col - start.col))
    }

    fun parseArray(): TomlValue {
        val start = span()
        adv()
        val items = ArrayList<TomlValue>()
        while (true) {
            skipTrivia()
            if (eof()) err("unterminated array", "starts here", "add a closing `]`", Span(start.line, start.col, 1))
            if (peek() == ']') { adv(); break }
            items += parseValue()
            skipTrivia()
            if (peek() == ',') { adv(); continue }
            if (peek() == ']') { adv(); break }
            if (eof()) err("unterminated array", "starts here", "add a closing `]`", Span(start.line, start.col, 1))
            err("expected `,` or `]` in the array", "here", "separate array items with commas")
        }
        return TArray(items, Span(start.line, start.col, 1))
    }

    fun parseInline(): TomlValue {
        val start = span()
        adv()
        val table = TTable(LinkedHashMap(), Span(start.line, start.col, 1))
        skipBlanks()
        if (peek() == '}') { adv(); return table }
        while (true) {
            skipBlanks()
            val path = parseKeyPath()
            skipBlanks()
            if (peek() != '=') err("expected `=` after the key `${path.joinToString(".") { it.first }}`", "expected `=`", null)
            adv()
            skipBlanks()
            val v = parseValue()
            val t = descend(table, path.dropLast(1), null)
            val (name, ks) = path.last()
            val prev = t.entries[name]
            if (prev != null) err("duplicate key `$name`", "second definition", null, ks, Pair(prev.keySpan, "first defined here"))
            t.entries[name] = TEntry(name, ks, v)
            skipBlanks()
            if (peek() == ',') { adv(); skipBlanks(); if (peek() == '}') { adv(); break }; continue }
            if (peek() == '}') { adv(); break }
            if (eof() || peek() == '\n') err("unterminated inline table", "starts here", "inline tables must be on one line and end with `}`", Span(start.line, start.col, 1))
            err("expected `,` or `}` in the inline table", "here", null)
        }
        return table
    }
}
