@file:CInclude("clock.h")

import kotlinx.cinterop.CInclude
import kotlin.native.CName
import mini.tensor.*

@CName("bench_now") external fun now(): Double

/** The textbook i-j-k loop (cache unfriendly for large n). */
fun naive(a: DoubleArray, b: DoubleArray, c: DoubleArray, n: Int) {
    for (i in 0 until n) {
        for (j in 0 until n) {
            var s = 0.0
            for (p in 0 until n) s += a[i * n + p] * b[p * n + j]
            c[i * n + j] = s
        }
    }
}

fun gflops(n: Int, seconds: Double, reps: Int): Double = 2.0 * n * n * n * reps / seconds / 1e9

fun main() {
    println("backend: ${Blas.name} (available=${Blas.available})")
    val sizes = intArrayOf(256, 512, 1024)
    for (n in sizes) {
        val g = Generator(1)
        val a = randn(n, n, generator = g, dtype = DType.Float64)
        val b = randn(n, n, generator = g, dtype = DType.Float64)
        val reps = if (n <= 256) 10 else if (n <= 512) 3 else 1
        // naive Kotlin
        val c = DoubleArray(n * n)
        var t0 = now()
        naive(a.data, b.data, c, n)
        val naiveS = now() - t0
        println("n=$n naive i-j-k      ${gflops(n, naiveS, 1)} GFLOP/s")
        // blocked i-k-j Kotlin kernel through Tensor.matmul
        Blas.enabled = false
        a.matmul(b)
        t0 = now()
        var r = a.matmul(b)
        for (i in 1 until reps) r = a.matmul(b)
        val ktS = now() - t0
        println("n=$n kotlin i-k-j     ${gflops(n, ktS, reps)} GFLOP/s")
        var maxErr = 0.0
        for (i in 0 until n * n) {
            val d = kotlin.math.abs(r.data[i] - c[i])
            if (d > maxErr) maxErr = d
        }
        if (Blas.available) {
            Blas.enabled = true
            a.matmul(b)
            t0 = now()
            var q = a.matmul(b)
            val reps2 = reps * 5
            for (i in 1 until reps2) q = a.matmul(b)
            val bS = now() - t0
            println("n=$n blas (${Blas.name})   ${gflops(n, bS, reps2)} GFLOP/s")
        }
        println("n=$n max abs diff kotlin vs naive: $maxErr")
    }
}
