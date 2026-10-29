// StringBuilder appends of strings, chars and numbers.
fun build(n: Int): String {
    val sb = StringBuilder()
    for (i in 0 until n) {
        sb.append("item")
        sb.append(i)
        sb.append(',')
        sb.append(' ')
    }
    return sb.toString()
}

fun main() {
    var len = 0L
    for (round in 0 until 20) len += build(50000).length
    val sb = StringBuilder()
    for (i in 0 until 200000) sb.append('x')
    println(len)
    println(sb.length)
}
