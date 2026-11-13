// libFuzzer target: the text functions of the standard library (case mapping, trimming, searching, splitting, padding, replace, lines,
// StringBuilder, UTF-8 encode/decode of String, char categories).

fun fuzzText(s: String, a: Int, b: Int): Int {
    var h = 0
    val cut = s.indexOf('\u0000')
    val x = if (cut < 0) s else s.substring(0, cut)
    val y = if (cut < 0) s.take(3) else s.substring(cut + 1)
    h += x.lowercase().length + x.uppercase().length + x.trim().length + x.trimStart().length + x.trimEnd().length
    h += x.capitalize().length + x.decapitalize().length
    h += x.reversed().length
    h += x.indexOf(y) + x.lastIndexOf(y) + x.indexOf(y, b) + x.lastIndexOf(y, b - 100)
    h += x.indexOf(y, a, true) + x.lastIndexOf(y, a, true)
    if (x.contains(y)) check(x.indexOf(y) >= 0) else check(x.indexOf(y) == -1)
    h += x.startsWith(y).compareTo(x.endsWith(y)) + x.startsWith(y, a % 5).compareTo(false)
    h += x.split(y).size + x.split(y, ignoreCase = true).size + x.split(y, limit = 2).size
    check(x.split(y).joinToString(y) == x || y.isEmpty())
    h += x.split(',', ' ').size + x.lines().size + x.lineSequence().count()
    h += x.replace(y, "[]").length + x.replace(y, "", true).length + x.replace('a', 'b').length + x.replaceFirst(y, "!").length
    h += x.padStart(b % 64, '*').length + x.padEnd(b % 64).length
    h += x.take(a % 9).length + x.drop(a % 9).length + x.takeLast(a % 9).length + x.dropLast(a % 9).length
    h += x.substringBefore(y).length + x.substringAfter(y).length + x.substringBeforeLast(y).length + x.substringAfterLast(y).length
    h += x.removePrefix(y).length + x.removeSuffix(y).length + x.removeSurrounding(y).length
    h += x.compareTo(y).coerceIn(-1, 1) + x.compareTo(y, true).coerceIn(-1, 1) + (if (x.equals(y, true)) 1 else 0)
    h += x.repeat(b % 4).length + x.chunked(1 + a % 5).size + x.windowed(1 + a % 4, 1 + b % 3, true).size
    h += x.trimIndent().length + x.trimMargin().length + x.prependIndent("  ").length + x.replaceIndent("> ").length
    h += x.hashCode() and 1
    h += x.commonPrefixWith(y).length + x.commonSuffixWith(y).length
    h += x.toCharArray().size + x.count { it.isLetter() } + x.filter { it.isDigit() }.length + x.map { it.uppercaseChar() }.length
    h += x.indexOfAny(y.toCharArray()) + x.lastIndexOfAny(y.toCharArray()) + x.indexOfFirst { it == 'a' } + x.indexOfLast { it == 'a' }
    h += x.toList().size + x.toSet().size + x.groupBy { it }.size + x.asSequence().distinct().count()
    for (c in x) {
        h += c.category.ordinal + c.digitToIntOrNull().let { it ?: 0 } + (if (c.isWhitespace()) 1 else 0) + c.lowercaseChar().code + c.uppercaseChar().code
        h += c.titlecaseChar().code + (if (c.isLetterOrDigit()) 1 else 0) + (if (c.isHighSurrogate()) 1 else 0)
    }
    // UTF-8
    val bytes = x.encodeToByteArray()
    val back = bytes.decodeToString()
    check(back.encodeToByteArray().contentEquals(bytes))
    h += bytes.size + x.toByteArray(Charsets.UTF_8).size + String(bytes, Charsets.UTF_8).length
    h += bytes.decodeToString(a % (bytes.size + 1), bytes.size).length
    try { h += bytes.decodeToString(throwOnInvalidSequence = true).length } catch (e: CharacterCodingException) { h += 1 }
    h += x.codePointCount(0, x.length) + (if (x.isNotEmpty()) x.codePointAt(b % x.length) else 0)
    h += x.encodeToByteArray(0, x.length).size
    // StringBuilder
    val sb = StringBuilder()
    sb.append(x).append(y).append(a).append(b.toLong()).append('c').append(1.5).insert(0, y).reverse()
    h += sb.length + sb.indexOf(y) + sb.toString().length
    if (sb.isNotEmpty()) { sb.deleteCharAt(0); sb.setCharAt(0, 'z') }
    sb.replace(0, minOf(sb.length, 2), y).delete(0, minOf(sb.length, 1))
    h += sb.length + sb.substring(minOf(sb.length, 1)).length
    sb.setLength(minOf(sb.length, a % 7))
    h += buildString { append(x); appendLine(y); append(x, 0, minOf(x.length, 2)) }.length
    // joining and comparing
    h += listOf(x, y, x + y).joinToString("|", "<", ">").length + (x + y).length
    h += x.toCharArray().concatToString().length + CharArray(b % 5) { x.firstOrNull() ?: 'q' }.concatToString().length
    h += "$x$y".length + "${x.length}".length
    return h
}

fun main() {
    println(fuzzText("hello world", 1, 2))
}
