// Array length caching and bounds-check hoisting (loop versioning): every index form, empty and inclusive ranges, and out-of-range
// loops that must fail at the same iteration, after the same side effects, as an unoptimized loop.
fun sum(a: IntArray, n: Int): Int {
    var s = 0
    for (i in 0 until n) s += a[i]
    return s
}

fun shiftLeft(a: IntArray, n: Int) {
    for (i in 0 until n) a[i] = a[i + 1]
}

fun reverse(a: IntArray, n: Int) {
    for (i in 0 until n / 2) {
        val t = a[i]
        a[i] = a[n - 1 - i]
        a[n - 1 - i] = t
    }
}

fun incl(a: LongArray, lo: Int, hi: Int): Long {
    var s = 0L
    for (i in lo..hi) s += a[i]
    return s
}

fun dist(a: DoubleArray, b: DoubleArray, n: Int): Double {
    var s = 0.0
    for (i in 0 until n) s += a[i] * b[n - 1 - i]
    return s
}

fun fillFrom(a: ByteArray, lo: Int, hi: Int, k: Int) {
    for (i in lo until hi) a[i + k] = (i + k).toByte()
}

fun touch(a: BooleanArray, n: Int, log: StringBuilder) {
    for (i in 0 until n) {
        log.append(i)
        a[i] = !a[i]
    }
}

fun main() {
    val a = IntArray(8) { it + 1 }
    println(sum(a, 8))
    println(sum(a, 0))
    println(sum(a, -3))
    try { println(sum(a, 9)) } catch (e: ArrayIndexOutOfBoundsException) { println("sum oob") }

    shiftLeft(a, 7)
    println(a.joinToString(","))
    try { shiftLeft(a, 8) } catch (e: ArrayIndexOutOfBoundsException) { println("shift oob " + a.joinToString(",")) }

    val r = IntArray(7) { it }
    reverse(r, 7)
    println(r.joinToString(","))
    try { reverse(r, 20) } catch (e: ArrayIndexOutOfBoundsException) { println("reverse oob " + r.joinToString(",")) }
    try { reverse(r, -5) } catch (e: ArrayIndexOutOfBoundsException) { println("reverse neg oob") }
    println("reverse neg ok")

    val l = LongArray(5) { (it * it).toLong() }
    println(incl(l, 0, 4))
    println(incl(l, 3, 2))
    try { println(incl(l, 0, 5)) } catch (e: ArrayIndexOutOfBoundsException) { println("incl oob") }
    try { println(incl(l, -1, 2)) } catch (e: ArrayIndexOutOfBoundsException) { println("incl neg oob") }
    println(incl(l, 2, Int.MAX_VALUE - 1000000000 - 1147483645))

    val d1 = DoubleArray(4) { 1.5 * it }
    val d2 = DoubleArray(4) { 2.0 + it }
    println(dist(d1, d2, 4))
    try { println(dist(d1, d2, 5)) } catch (e: ArrayIndexOutOfBoundsException) { println("dist oob") }

    val bytes = ByteArray(10)
    fillFrom(bytes, 0, 4, 3)
    println(bytes.joinToString(","))
    try { fillFrom(bytes, 0, 4, 8) } catch (e: ArrayIndexOutOfBoundsException) { println("fill oob " + bytes.joinToString(",")) }
    try { fillFrom(bytes, 0, 4, -1) } catch (e: ArrayIndexOutOfBoundsException) { println("fill neg oob " + bytes.joinToString(",")) }

    val flags = BooleanArray(3)
    val log = StringBuilder()
    try { touch(flags, 5, log) } catch (e: ArrayIndexOutOfBoundsException) { println("touch oob log=" + log + " flags=" + flags.joinToString(",")) }
}
