// Float and FloatArray: 32-bit storage and arithmetic, conversions, printing, boxing, arrays.

fun sumF(xs: FloatArray): Float {
    var s = 0.0f
    for (x in xs) s += x
    return s
}

fun <T> first(xs: List<T>): T = xs[0]

fun main() {
    // arithmetic is float32: 16777216f + 1f stays 16777216f, 0.1f + 0.2f is not the Double sum
    println(16777216f + 1f)
    println(0.1f + 0.2f)
    println((0.1f + 0.2f).toDouble())
    println(1.0f / 3.0f)
    println(1.0f + 1.0)
    println(2.0f * 3)
    println(7 / 2f)
    println(5L - 1.5f)
    println(7.5f % 2f)
    println(-7.5f % 2f)
    var f = 1.5f
    f++
    f += 0.25f
    f *= 2f
    println(f)
    println(-f)

    // conversions
    println(3.99f.toInt())
    println((-3.99f).toInt())
    println(1e20f.toInt())
    println(1e20f.toLong())
    println((0.0f / 0.0f).toInt())
    println(42.toFloat())
    println(123456789.toFloat())
    println(123456789L.toFloat())
    println(0.1.toFloat())
    println(0.1.toFloat().toDouble())
    println(65.toChar().code.toFloat())
    println(2.5f.toLong())

    // comparison
    val nan = Float.NaN
    println(nan == nan)
    println(nan < 1f)
    println(1f < 2f)
    println(1.5f >= 1.5f)
    println(0.0f == -0.0f)
    println(1.5f == 1.5f)
    println(1.5f.compareTo(2.5f))

    // printing edge cases
    println(Float.NaN)
    println(Float.POSITIVE_INFINITY)
    println(Float.NEGATIVE_INFINITY)
    println(-0.0f)
    println(0.0f)
    println(Float.MAX_VALUE)
    println(Float.MIN_VALUE)
    println(1.5E10f)
    println(1e7f)
    println(9999999f)
    println(0.001f)
    println(0.0001f)
    println(1e-10f)
    println(3.4e38f * 10f)
    println(100f)
    println(1.17549435E-38f)
    println("v=${1.25f} w=" + 2.5f)
    println(Float.NaN.isNaN())
    println(Float.POSITIVE_INFINITY.isInfinite())
    println(1.5f.isFinite())

    // arrays
    val a = FloatArray(4) { i -> i * 0.5f }
    a[3] = 1.1f
    a[0] += 0.1f
    println(a.size)
    println(a.contentToString())
    println(sumF(a))
    println(a[3].toDouble())
    var t = 0f
    for (i in a.indices) t += a[i]
    println(t)
    val b = floatArrayOf(1.5f, 2.5f, -0.0f, Float.NaN)
    println(b.contentToString())
    println(b.lastIndex)
    val z = FloatArray(3)
    println(z.contentToString())
    try {
        println(b[4])
    } catch (e: ArrayIndexOutOfBoundsException) {
        println("oob")
    }

    // boxing: generics, collections, Any, nullable
    val l = ArrayList<Float>()
    l.add(1.5f)
    l.add(0.1f)
    l.add(Float.NaN)
    println(l)
    println(first(l))
    var s = 0f
    for (x in l) s += x
    println(s)
    val ml = mutableListOf(2.5f, 1.5f, 3.5f)
    ml.sort()
    println(ml)
    println(ml.contains(1.5f))
    println(ml.indexOf(3.5f))
    val m = HashMap<String, Float>()
    m["a"] = 0.1f
    println(m["a"])
    println(m["b"])
    val n: Float? = 2.5f
    val n2: Float? = null
    println(n)
    println(n2)
    println(n!! + 1f)
    println((n2 ?: 7f) * 2)
    val any: Any = 0.1f
    println(any)
    println(any is Float)
    println(any is Double)
    println(any == 0.1f)
    println(any == 0.1)
    println(any.hashCode() == 0.1f.hashCode())
    println(0.5f.hashCode())
    println(listOf(0.0f).contains(-0.0f))
    println(listOf(Float.NaN).contains(Float.NaN))
    println(l.map { it * 2 })
}
