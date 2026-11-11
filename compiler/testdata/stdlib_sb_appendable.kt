fun fill(a: Appendable): Appendable {
    a.append('c')
    a.append("str")
    a.append(StringBuilder("sb"))
    a.append("abcdef", 1, 3)
    return a
}

fun main() {
    val sb = StringBuilder()
    val s: String? = null
    val cs: CharSequence = StringBuilder("cs")
    sb.append("x").append(s).append(cs).append(null as CharSequence?).append(cs, 0, 1)
    println(sb)
    println(fill(StringBuilder()))
    println(buildString { append("a"); append('b'); append(1); append("cdef", 2, 4) })
}
