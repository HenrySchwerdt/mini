// String formatting and text layout: format specifiers, padding, number bases, table rendering with alignment, word wrapping,
// escaping, StringBuilder editing, char classification and unicode-safe iteration.

enum class Align { LEFT, RIGHT, CENTER }

class Column(val title: String, val align: Align = Align.LEFT)

fun pad(s: String, w: Int, a: Align): String = when (a) {
    Align.LEFT -> s.padEnd(w)
    Align.RIGHT -> s.padStart(w)
    Align.CENTER -> {
        val total = w - s.length
        " ".repeat(total / 2) + s + " ".repeat(total - total / 2)
    }
}

fun table(cols: List<Column>, rows: List<List<Any?>>): String {
    val cells = rows.map { r -> r.map { it?.toString() ?: "-" } }
    val widths = cols.mapIndexed { i, c -> maxOf(c.title.length, cells.maxOfOrNull { it[i].length } ?: 0) }
    val sep = widths.joinToString("+", "+", "+") { "-".repeat(it + 2) }
    val sb = StringBuilder()
    sb.appendLine(sep)
    sb.appendLine(cols.indices.joinToString("|", "|", "|") { " " + pad(cols[it].title, widths[it], Align.CENTER) + " " })
    sb.appendLine(sep)
    for (r in cells) sb.appendLine(cols.indices.joinToString("|", "|", "|") { " " + pad(r[it], widths[it], cols[it].align) + " " })
    sb.append(sep)
    return sb.toString()
}

fun wrap(text: String, width: Int): List<String> {
    val lines = ArrayList<String>()
    var cur = StringBuilder()
    for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        if (cur.isNotEmpty() && cur.length + 1 + word.length > width) {
            lines.add(cur.toString())
            cur = StringBuilder()
        }
        if (cur.isNotEmpty()) cur.append(' ')
        cur.append(word)
    }
    if (cur.isNotEmpty()) lines.add(cur.toString())
    return lines
}

fun justify(line: String, width: Int): String {
    val ws = line.split(' ')
    if (ws.size == 1) return line
    val gaps = ws.size - 1
    val spaces = width - ws.sumOf { it.length }
    return ws.withIndex().joinToString("") { (i, w) -> if (i < gaps) w + " ".repeat(spaces / gaps + if (i < spaces % gaps) 1 else 0) else w }
}

fun humanBytes(n: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i == 0) "$n B" else "%.1f %s".format(v, units[i])
}

fun duration(seconds: Long): String {
    val d = seconds / 86400
    val h = seconds % 86400 / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return listOfNotNull(d.takeIf { it > 0 }?.let { "${it}d" }, h.takeIf { it > 0 }?.let { "${it}h" }, m.takeIf { it > 0 }?.let { "${it}m" }, "${s}s".takeIf { s > 0 || seconds == 0L }).joinToString(" ")
}

fun main() {
    println("%5d|%-5d|%05d|%+d|%x|%X|%o|%b".format(42, 42, 42, 42, 255, 255, 8, true))
    println("%8.3f|%-8.2f|%.0f|%e|%10s|%-10s|%c|%%".format(3.14159, 2.5, 2.5, 12345.678, "right", "left", 'k'))
    println("%s has %d items costing %.2f total".format("cart", 3, 59.97))
    println("%,d".format(1234567) + " " + "%08.3f".format(-3.14159) + " " + "%3\$s %1\$s %2\$s".format("a", "b", "c"))
    println(255.toString(2) + " " + 255.toString(16) + " " + "ff".toInt(16) + " " + 0b1011 + " " + 0xFF + " " + 1_000_000 + " " + Long.MAX_VALUE + " " + Int.MIN_VALUE)
    println(1.0 / 3 to 2.0f / 3 to 1e10 to 1.5e-7 to 100.0 to Double.NaN to -1.0 / 0)
    println(table(
        listOf(Column("Name"), Column("Qty", Align.RIGHT), Column("Price", Align.RIGHT), Column("Note", Align.CENTER)),
        listOf(listOf("Widget", 12, "3.50", "ok"), listOf("Gadget with a long name", 7, "129.99", null), listOf("Nut", 1000, "0.02", "bulk")),
    ))
    val text = "Kotlin is a modern programming language that makes developers happier. It is concise, safe, interoperable and tool friendly."
    val lines = wrap(text, 30)
    println(lines.joinToString("\n") { "|" + it.padEnd(30) + "|" })
    println(lines.dropLast(1).joinToString("\n") { "|" + justify(it, 30) + "|" } + "\n|" + lines.last().padEnd(30) + "|")
    println(listOf(0L, 1023L, 1024L, 1536L, 5_000_000L, 3_221_225_472L, 1L shl 42).map { humanBytes(it) })
    println(listOf(0L, 59L, 61L, 3600L, 3661L, 90061L, 172800L).map { duration(it) })
    val sb = StringBuilder("hello world")
    sb.insert(0, ">> ").append(" <<").setCharAt(3, 'H')
    sb.replace(9, 14, "WORLD").deleteCharAt(sb.length - 1)
    println("$sb ${sb.length} ${sb.indexOf("WORLD")} ${sb.reversed()}")
    val s = "naïve café – ünïcödé"
    println("${s.length} ${s.uppercase()} ${s.filter { it.isLetter() }.length} ${s.count { it.code > 127 }}")
    println("tab\there \"quoted\" back\\slash \$dollar A ${'é'}")
    println("""raw "string" with \n no escapes and ${1 + 1} template""")
    println("a,b,,c".split(",") to "a,b,,c".split(",", limit = 2) to " padded ".trim() to "xxhixx".trim('x'))
    println("Hello".map { it.code }.joinToString(" ") + " " + "Hello".map { it.code.toChar() }.joinToString("") + " " + ('a'..'e').joinToString("") + " " + ('a' + 3) + " " + ('z' - 1))
    println("abc".compareTo("abd") to ("b" > "a") to "abc".equals("ABC", ignoreCase = true) to "kotlin".startsWith("kot") to "kotlin".endsWith("in"))
    println("one two  three".split(" ") to "one two  three".split(Regex(" +")) to "a-b_c".split('-', '_'))
    println("x".padStart(3, '*') + "x".padEnd(3, '*') + "toolong".take(3) + "toolong".takeLast(2) + "toolong".drop(4) + "toolong".dropLast(4))
    println("l33t".replace('3', 'e').replace("l", "L") + " " + "a.b.c".replace(".", "") + " " + "aaa".replaceFirst("a", "b") + " " + "Hello".lowercase().replaceFirstChar { it.uppercase() })
    println("subs: " + "kotlin".substring(1, 4) + " " + "kotlin".substringBefore('l') + " " + "a=b=c".substringAfterLast('=') + " " + "kotlin"[0] + "kotlin".last() + " " + "kotlin".indexOf('t') + "kotlin".lastIndexOf('n'))
    println("chars: " + "a1 B_".map { when { it.isDigit() -> 'D'; it.isUpperCase() -> 'U'; it.isLowerCase() -> 'l'; it.isWhitespace() -> '_'; else -> '?' } }.joinToString(""))
    println(buildString { for (i in 1..5) append(i).append(if (i < 5) "," else "") } + " " + (1..5).joinToString("-", "<", ">") + " " + listOf("a", "b").joinToString(limit = 1, truncated = "..."))
    println("%5s|%-5s|".format(true, 'c') + " " + "${null} ${listOf(1, null)} ${arrayOf(1, 2).toList()} ${mapOf(1 to "a")}")
}
