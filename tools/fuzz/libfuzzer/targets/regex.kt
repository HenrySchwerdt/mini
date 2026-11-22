// libFuzzer target: the regular expression engine (stdlib/kotlin/text/Regex.kt). The input is split at its first NUL into the pattern and the
// text. A pattern the engine rejects throws IllegalArgumentException (the documented answer); every other exception is a bug.

fun fuzzRegex(s: String, a: Int, b: Int): Int {
    var h = 0
    val cut = s.indexOf('\u0000')
    val pat = if (cut < 0) s else s.substring(0, cut)
    val text = if (cut < 0) pat else s.substring(cut + 1)
    val opts = HashSet<RegexOption>()
    if (a and 1 != 0) opts.add(RegexOption.IGNORE_CASE)
    if (a and 2 != 0) opts.add(RegexOption.MULTILINE)
    if (a and 4 != 0) opts.add(RegexOption.DOT_MATCHES_ALL)
    if (a and 8 != 0) opts.add(RegexOption.COMMENTS)
    if (a and 16 != 0) opts.add(RegexOption.LITERAL)
    val re = try { Regex(pat, opts) } catch (e: IllegalArgumentException) { return 1 }
    h += if (re.matches(text)) 1 else 0
    h += if (re.containsMatchIn(text)) 1 else 0
    val m = re.find(text)
    if (m != null) {
        check(m.value == text.substring(m.range.first, m.range.last + 1))
        h += m.groupValues.size
    }
    var count = 0
    var prevEnd = -1
    for (r in re.findAll(text)) {
        check(r.range.first >= prevEnd)
        prevEnd = r.range.last + 1
        if (++count > 10000) break
    }
    h += count
    h += re.split(text).size
    h += re.split(text, 3).size
    h += re.replace(text, "<$0>").length
    h += re.replace(text) { it.value.length.toString() }.length
    h += re.replaceFirst(text, "").length
    try { h += re.replace(text, pat.take(5)).length } catch (e: IllegalArgumentException) { h += 1 } catch (e: IndexOutOfBoundsException) { h += 2 }
    h += re.matchAt(text, b % (text.length + 1))?.value?.length ?: 0
    h += Regex.escape(pat).length
    check(Regex(Regex.escape(text)).matches(text))
    return h
}

fun main() {
    println(fuzzRegex("a+b\u0000aaab", 0, 0))
}
