// libFuzzer target: String.format. The input is the format string; the arguments are fixed (and some taken from the input's characters).
// IllegalArgumentException (the java.util.*Format*Exception family) is the documented answer to a bad format; anything else is a bug.

// A width or precision between 100000 and Int.MAX_VALUE asks for a result of that many characters (`%1111111110d` is a 2 GB string on the JVM
// as well): resource use by the request, not a defect, and it would only make the fuzzer time out. Larger numbers (NumberFormatException
// path) and smaller ones are still tried.
private fun hugeNumber(s: String): Boolean {
    var v = 0L
    var run = false
    for (c in s) {
        if (c in '0'..'9') {
            run = true
            if (v < 100000000000L) v = v * 10 + (c - '0')
        } else {
            if (run && v >= 100000L && v <= 2147483647L) return true
            v = 0L; run = false
        }
    }
    return run && v >= 100000L && v <= 2147483647L
}

fun fuzzFormat(s: String, a: Int, b: Int): Int {
    var h = 0
    if (hugeNumber(s)) return 0
    val n = if (s.isEmpty()) 0L else s[0].code.toLong() * 977L - (if (a > 127) 1L shl 40 else 0L)
    val dv = when (b % 8) {
        0 -> 3.14159265
        1 -> -0.0
        2 -> 1.0e300
        3 -> Double.NaN
        4 -> Double.POSITIVE_INFINITY
        5 -> 123456789.987654321
        6 -> 5.0e-324
        else -> n.toDouble() / 7.0
    }
    try { h += s.format(n.toInt(), n, dv, "str", 'c', true, null).length } catch (e: IllegalArgumentException) { h += 1 }
    try { h += s.format(dv, dv, dv, dv).length } catch (e: IllegalArgumentException) { h += 1 }
    try { h += s.format("a", "b", "c", "d").length } catch (e: IllegalArgumentException) { h += 1 }
    try { h += s.format(n.toByte(), n.toShort(), n.toInt(), n).length } catch (e: IllegalArgumentException) { h += 1 }
    try { h += s.format().length } catch (e: IllegalArgumentException) { h += 1 }
    // the specifier built from the input, with every conversion
    val spec = "%" + s.take(6)
    for (conv in "dsSbBhHcCfeEgGxXo%n") {
        try { h += (spec + conv).format(n, n.toInt(), dv, "x").length } catch (e: IllegalArgumentException) { h += 1 }
    }
    return h
}

fun main() {
    println(fuzzFormat("%5.2f|%x", 1, 0))
}
