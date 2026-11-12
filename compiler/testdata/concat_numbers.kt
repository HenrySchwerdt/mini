// String concatenation with Int / Long / Char pieces: `a + b + c` chains and templates write the digits straight into the result (mk_string_concat_mN)
// instead of making a string per number. Narrow and wide strings, null, negative and extreme values, evaluation order.
var trace = ""

fun t(tag: String, v: Int): Int {
    trace += tag
    return v
}

fun s(tag: String, v: String?): String? {
    trace += tag
    return v
}

fun main() {
    val i = 42
    val n = -7
    val l = 1234567890123L
    val m = Long.MIN_VALUE
    val c = 'x'
    val w = "é中"        // wide characters in the result
    val nul: String? = null
    println("item-" + i + ":" + (i * 7))
    println("a" + n + "b" + l + "c")
    println("min " + m + " " + Long.MAX_VALUE + " " + Int.MIN_VALUE + " " + Int.MAX_VALUE)
    println("c=" + c + c + 'y' + 0 + 'z')
    println("w " + w + i + c + '中' + l)
    println("n " + nul + i + nul)
    println("$i,$n,$l,$c,$w,$nul")
    println("${i}${n}${l}")
    println(i.toString() + n + l)
    println("" + 0 + 0L + '0')
    println("x" + 10 + 99 + 100 + 1000 + 99999 + 100000)
    println("big " + 9999999999L + " " + 10000000000L + " " + 99999999999999999L + " " + 1000000000000000000L)
    // order of evaluation: left to right, once each
    val r = "p" + t("1", 1) + s("2", "q") + t("3", 3) + s("4", null) + t("5", 5) + t("6", 6) + s("7", "!")
    println(r + " " + trace)
    var acc = ""
    for (k in 0 until 12) acc += "[" + k + "/" + (k * k) + "]"
    println(acc)
    val sb = StringBuilder()
    for (k in 0 until 5) sb.append("k").append(k).append(',')
    sb.append("end" as String?)
    sb.append(null as String?)
    sb.append("x" as CharSequence)
    println(sb.toString())
}
