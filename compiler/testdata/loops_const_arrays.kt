// plain `until` loops (CEmitter.plainLoops), constant array lengths (ArrayLen) and no loop versioning for them (RangeCheck):
// bounds, break / continue, empty ranges, Long loops, a bad index at the same iteration, arrays of a constant size next to arrays of an unknown size
const val N = 6

fun sumUntil(a: Int, b: Int): Int {
    var s = 0
    for (i in a until b) s += i
    return s
}

fun nearMax(): Int {
    var n = 0
    for (i in Int.MAX_VALUE - 3 until Int.MAX_VALUE) n++
    for (i in Int.MAX_VALUE until Int.MAX_VALUE) n += 100
    return n
}

fun longs(): Long {
    var s = 0L
    for (i in 5L until 9L) s += i
    for (i in Long.MAX_VALUE - 2 until Long.MAX_VALUE) s += 1
    return s
}

fun control(): String {
    val sb = StringBuilder()
    for (i in 0 until 10) {
        if (i == 2) continue
        if (i == 7) break
        for (j in 0 until i) {
            if (j == 3) break
            sb.append(j)
        }
        sb.append('|')
    }
    return sb.toString()
}

fun constArrays(k: Int): Int {
    val a = IntArray(N)
    val b = IntArray(N)
    for (i in 0 until N) a[i] = i * i
    for (i in 0 until k) b[i] = a[i] + a[N - 1 - i]    // k may be larger than N: the same bad index, after the same stores
    var s = 0
    for (i in 0 until N) s += b[i]
    return s
}

fun mixed(m: IntArray, k: Int): Int {
    val c = IntArray(4)
    var s = 0
    for (i in 0 until k) {
        c[i and 3] += m[i]          // m has an unknown size, c a constant one
        s += c[i and 3]
    }
    return s
}

fun reverse(k: Int): String {
    val p = IntArray(8)
    for (i in 0 until 8) p[i] = i
    for (i in 0 until k) { val t = p[i]; p[i] = p[k - i]; p[k - i] = t }
    return p.joinToString(",")
}

fun main() {
    println(sumUntil(0, 10)); println(sumUntil(5, 5)); println(sumUntil(7, 3)); println(sumUntil(-4, 4))
    println(nearMax()); println(longs()); println(control())
    println(constArrays(3)); println(constArrays(6))
    try { println(constArrays(8)) } catch (e: ArrayIndexOutOfBoundsException) { println("caught") }
    val m = IntArray(10) { it + 1 }
    println(mixed(m, 10))
    try { println(mixed(m, 11)) } catch (e: ArrayIndexOutOfBoundsException) { println("caught mixed") }
    println(reverse(3)); println(reverse(0))
    try { println(reverse(9)) } catch (e: ArrayIndexOutOfBoundsException) { println("caught reverse") }
}
