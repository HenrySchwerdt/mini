// kotlin.text.StringBuilder of the Mini stdlib (native buffer behind mini.internal.sb* intrinsics).
fun repeatInto(sb: StringBuilder, s: String, n: Int) {
    for (i in 0 until n) sb.append(s)
}

fun commaSeparated(n: Int): String {
    val sb = StringBuilder()
    for (i in 1..n) {
        if (i > 1) sb.append(", ")
        sb.append(i)
    }
    return sb.toString()
}

fun main() {
    val sb = StringBuilder()
    sb.append("abc").append('d').append(1).append(2L).append(true).append(1.5)
    println(sb.toString())
    println(sb.length)
    sb.clear()
    println(sb.isEmpty())
    sb.append("hello")
    println(sb[1])
    sb[0] = 'J'
    println(sb.toString())
    sb.insert(1, "--")
    println(sb.toString())
    sb.deleteAt(1)
    println(sb.toString())
    sb.delete(0, 2)
    println(sb.toString())
    sb.reverse()
    println(sb.toString())
    sb.setLength(2)
    println(sb.toString())
    sb.append('!')
    println(sb.toString())
    println(StringBuilder("x").appendLine().toString().length)
    println(StringBuilder("hello").substring(1, 4))
    println(StringBuilder("hello").substring(2))
    println(commaSeparated(5))
    val big = StringBuilder()
    repeatInto(big, "0123456789", 1000)
    println(big.length)
    println(big[9999])
    println(big.substring(9990))
    val s: String? = null
    println(StringBuilder().append(s).append(null as Any?).toString())
    println(StringBuilder("abc").toString() == "abc")
}
