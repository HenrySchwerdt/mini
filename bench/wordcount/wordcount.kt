// word-count: generate a text of pseudo-random words, split it and count the words in a hash map.
// Kotlin/JVM and Mini Managed compile this very file. Output: total, distinct, max count, order-independent checksum.
const val WORDS = 20000000
const val MOD = 1000000007L

class Rng(var s: Long) {
    fun next(): Int {
        s = (s * 1103515245L + 12345L) and 0x7fffffffL
        return (s shr 8).toInt()
    }
}

fun generate(): String {
    val rng = Rng(42L)
    val sb = StringBuilder()
    for (i in 0 until WORDS) {
        val a = rng.next() % 50000
        val b = rng.next() % 50000
        var x = (a.toLong() * b / 50000).toInt()
        var n = 0
        do {
            sb.append('a' + (x % 26))
            x /= 26
            n++
        } while (x > 0 || n < 3)
        sb.append(' ')
    }
    return sb.toString()
}

fun hash(w: String): Long {
    var h = 0L
    for (i in 0 until w.length) h = (h * 31 + w[i].code) % MOD
    return h
}

fun main() {
    val text = generate()
    val counts = HashMap<String, Int>()
    var pos = 0
    var total = 0
    while (pos < text.length) {
        val e = text.indexOf(' ', pos)
        val w = text.substring(pos, e)
        val c = counts[w]
        if (c == null) counts[w] = 1 else counts[w] = c + 1
        total++
        pos = e + 1
    }
    var maxCount = 0
    var chk = 0L
    for ((w, c) in counts) {
        if (c > maxCount) maxCount = c
        chk = (chk + hash(w) * c) % MOD
    }
    println("$total ${counts.size} $maxCount $chk")
}
