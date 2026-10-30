/*
 * Small text helpers of the backend tests. The Mini standard library has no Regex, so the emitted-C checks use
 * plain string scans.
 */

/** How often any of [needles] occurs in [text] (matches do not overlap; at each position the first needle that fits wins). */
fun occurrences(text: String, vararg needles: String): Int {
    var n = 0
    var i = 0
    while (i < text.length) {
        var hit = 0
        for (nd in needles) if (nd.isNotEmpty() && text.startsWith(nd, i)) { hit = nd.length; break }
        if (hit > 0) { n++; i += hit } else i++
    }
    return n
}

/** Releases of local [v] in the forms the emitter produces: `mk_release(v)`, `MKH_REL(v)`, with optional parentheses and `(MkObject *)` cast. */
fun releasesLocal(text: String, v: String): Boolean {
    for (head in listOf("MKH_REL", "mk_release")) for (open in listOf("(", "")) for (cast in listOf("(MkObject *)", "")) for (close in listOf(")", "")) {
        if (text.contains("$head($open$cast$v$close)")) return true
    }
    return false
}

/** Some line of [text] has [first] followed (later on the same line) by [second]. */
fun lineHasInOrder(text: String, first: String, second: String): Boolean {
    for (line in text.lines()) {
        val i = line.indexOf(first)
        if (i >= 0 && line.indexOf(second, i + first.length) >= 0) return true
    }
    return false
}

/** True when some line of [c] starts with whitespace and then `[name] = NULL;`. */
fun clearsLocal(c: String, name: String): Boolean {
    for (line in c.lines()) {
        val t = line.trimStart()
        if (t.length < line.length && t.startsWith("$name = NULL;")) return true
    }
    return false
}
