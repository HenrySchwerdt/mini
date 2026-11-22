// libFuzzer target: String -> number parsing (Int/Long/Short/Byte/UInt/ULong with a radix, Double, Float) and the round trip of what parses.
// `a` and `b` are bytes of the input; the exceptions that are part of the contract (NumberFormatException) are caught here, anything else escapes
// and the driver treats it as a crash.

fun fuzzParse(s: String, a: Int, b: Int): Int {
    var h = 0
    val radix = 2 + a % 35
    val v = s.toIntOrNull(radix)
    if (v != null) {
        h += v
        check(v.toString(radix).toIntOrNull(radix) == v)
        check(s.toInt(radix) == v)
    } else {
        try { s.toInt(radix); error("toInt accepted what toIntOrNull rejected: $s") } catch (e: NumberFormatException) { h += 1 }
    }
    val l = s.toLongOrNull(radix)
    if (l != null) {
        h += l.toInt()
        check(l.toString(radix).toLongOrNull(radix) == l)
        check(s.toLong(radix) == l)
    }
    val l10 = s.toLongOrNull()
    if (l10 != null) check(l10.toString().toLong() == l10)
    val i10 = s.toIntOrNull()
    if (i10 != null) {
        check(i10.toString().toInt() == i10)
        check(l10 == i10.toLong())
    }
    val sh = s.toShortOrNull(radix)
    if (sh != null) check(sh.toString(radix).toShort(radix) == sh)
    val by = s.toByteOrNull(radix)
    if (by != null) check(by.toString(radix).toByte(radix) == by)
    val u = s.toUIntOrNull(radix)
    if (u != null) check(u.toString(radix).toUInt(radix) == u)
    val ul = s.toULongOrNull(radix)
    if (ul != null) check(ul.toString(radix).toULong(radix) == ul)
    val d = s.toDoubleOrNull()
    if (d != null) {
        h += d.toInt()
        if (!d.isNaN()) check(d.toString().toDouble() == d)
        check(s.toDouble().toRawBits() == d.toRawBits())
    } else {
        try { s.toDouble(); error("toDouble accepted what toDoubleOrNull rejected: $s") } catch (e: NumberFormatException) { h += 1 }
    }
    val f = s.toFloatOrNull()
    if (f != null && !f.isNaN()) check(f.toString().toFloat() == f)
    val bo = s.toBooleanStrictOrNull()
    if (bo != null) h += 1
    if (b > 127) {
        val t = s.trim()
        t.toIntOrNull(radix)
        t.toDoubleOrNull()
    }
    return h
}

fun main() {
    println(fuzzParse("12", 8, 0))
}
