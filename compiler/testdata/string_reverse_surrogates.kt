// Found by the fuzzer: StringBuilder.reverse (and String.reversed) must keep surrogate pairs in order, as java.lang.StringBuilder.reverse does
fun main() {
    val s = "😀x"
    println(s.reversed().length)
    println(s.reversed().map { it.code })
    val sb = StringBuilder("a😀b")
    println(sb.reverse().map { it.code })
    println(StringBuilder("\uD83Dx\uDE00").reverse().map { it.code })
    println(StringBuilder("\uDE00\uD83D").reverse().map { it.code })
}
