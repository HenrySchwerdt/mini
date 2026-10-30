// spectral-norm: dense double loops. Kotlin/JVM and Mini Managed compile this very file.
import kotlin.math.sqrt

const val N = 5000

fun a(i: Int, j: Int): Double = 1.0 / ((i + j) * (i + j + 1) / 2 + i + 1).toDouble()

fun mulAv(v: DoubleArray, out: DoubleArray) {
    for (i in 0 until N) {
        var s = 0.0
        for (j in 0 until N) s += a(i, j) * v[j]
        out[i] = s
    }
}

fun mulAtv(v: DoubleArray, out: DoubleArray) {
    for (i in 0 until N) {
        var s = 0.0
        for (j in 0 until N) s += a(j, i) * v[j]
        out[i] = s
    }
}

fun mulAtAv(v: DoubleArray, out: DoubleArray, tmp: DoubleArray) {
    mulAv(v, tmp)
    mulAtv(tmp, out)
}

fun main() {
    val u = DoubleArray(N)
    val v = DoubleArray(N)
    val tmp = DoubleArray(N)
    for (i in 0 until N) u[i] = 1.0
    for (i in 0 until 10) {
        mulAtAv(u, v, tmp)
        mulAtAv(v, u, tmp)
    }
    var vbv = 0.0
    var vv = 0.0
    for (i in 0 until N) {
        vbv += u[i] * v[i]
        vv += v[i] * v[i]
    }
    println((sqrt(vbv / vv) * 1e9 + 0.5).toLong())
}
