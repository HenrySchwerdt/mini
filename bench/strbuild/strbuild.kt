// string-builder / concat: (A) one big StringBuilder filled with ints, chars and literals, (B) many short strings made by
// concatenation. Kotlin/JVM and Mini Managed compile this very file. Output: lengths and checksums.
const val APPENDS = 12000000
const val CONCATS = 6000000
const val MOD = 1000000007L

fun main() {
    val sb = StringBuilder()
    for (i in 0 until APPENDS) {
        sb.append(i)
        sb.append(',')
        if (i % 5 == 0) sb.append("item")
    }
    var chkA = 0L
    var idx = 0
    while (idx < sb.length) {
        chkA = (chkA * 31 + sb[idx].code) % MOD
        idx += 7
    }
    var sumB = 0L
    var chkB = 0L
    for (i in 0 until CONCATS) {
        val s = "item-" + i + ":" + (i * 7)
        sumB += s.length
        chkB = (chkB * 31 + s[s.length - 1].code + s[5].code) % MOD
    }
    println("${sb.length} $chkA $sumB $chkB")
}
