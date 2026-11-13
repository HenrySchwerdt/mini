fun main() {
    val doubles = listOf(
        0.0, -0.0, 1.0, -1.0, 0.5, 1.5, 2.5, 0.05, 0.005, 0.0005, 9.995, 99.5, 999999.5, 0.1, 0.2, 0.3, 1.0 / 3, 2.0 / 3, 1e-5, 1.5e-7, 123456.789,
        1234567.0, 12345678.9, 1e7, 1e-4, 9.9999e-5, 100.0, 1e15, 1e16, 1.7976931348623157e308, 4.9e-324, 3.141592653589793, 2.718281828459045,
        -123.456, 0.999999, 0.9999995, 5e-5, 0.00012345, 65536.0, 1e21, 1e22, 123e-20
    )
    val dspecs = listOf("%f", "%.0f", "%.1f", "%.3f", "%10.2f", "%-10.2f|", "%010.2f", "%+.2f", "% .2f", "%(.2f", "%,.2f", "%e", "%.0e", "%.3e", "%12.4E",
        "%g", "%.0g", "%.1g", "%.2g", "%.4g", "%G", "%12.3g", "%-12.3g|", "%+g", "%s")
    for (d in doubles) {
        val sb = StringBuilder()
        for (sp in dspecs) {
            sb.append(sp.format(d)).append(' ')
        }
        println(sb)
    }
    val floats = listOf(0.1f, 1.5f, 3.14159f, 1e10f, 16777216f, 0.3f, -2.5f)
    for (f in floats) {
        println("%f %.3f %e %g %s".format(f, f, f, f, f))
    }
    val longs = listOf(0L, 1L, -1L, 7L, 255L, 1000L, -1000L, 123456789L, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(), Long.MAX_VALUE, Long.MIN_VALUE)
    val lspecs = listOf("%d", "%5d", "%-5d|", "%05d", "%+d", "% d", "%(d", "%,d", "%+,d", "%012d", "%x", "%X", "%#x", "%o", "%#o", "%020x", "%-20x|", "%s")
    for (l in longs) {
        val sb = StringBuilder()
        for (sp in lspecs) sb.append(sp.format(l)).append(' ')
        println(sb)
    }
    val ints = listOf(0, 1, -1, 42, -42, 255, 65535, Int.MAX_VALUE, Int.MIN_VALUE)
    for (i in ints) {
        println("%d %5d %-5d| %05d %+d %,d %x %X %#x %o %08X %c".format(i, i, i, i, i, i, i, i, i, i, i, if (i in 32..126) i else 65))
    }
    val bytes = listOf<Byte>(0, 1, -1, 127, -128)
    for (b in bytes) println("%d %x %o %5d %03d".format(b, b, b, b, b))
    val shorts = listOf<Short>(0, 1, -1, 32767, -32768)
    for (s in shorts) println("%d %x %o %6d %,d".format(s, s, s, s, s))
    val strs = listOf("", "a", "hello", "héllo wörld", "tab\there")
    for (s in strs) println("[%s] [%5s] [%-8s] [%.3s] [%8.2s] [%S] [%b] [%h]".format(s, s, s, s, s, s, s, s))
    println("%s %s %s %s".format(1, 2L, 3.0f, 4.0))
    println("%s %s".format(listOf(1, 2), mapOf("a" to 1)))
    println("%s".format(arrayOf(1, 2).toList()))
    println("%b %b %b".format(true, null as Any?, 0))
    println("%h %h %h".format(0, 255, "abc"))
    println("%c%c%c".format('a', 'b', 'c'))
    println("%08.3f|%-8.3f|%+8.3f|%(8.3f".format(-1.5, -1.5, -1.5, -1.5))
    println("%1\$s-%1\$s-%2\$s-%<s".format("x", "y"))
    println("%3\$s %2\$s %1\$s".format("a", "b", "c"))
    println("%-3s|%3s|".format("abcdef", "abcdef"))
    println("%.0f %.0f %.0f %.0f".format(0.5, 1.5, 2.5, -0.5))
    println("%d%%".format(50))
    println("%n".format().length)
    println("%5%|%-5%|".format())
}
