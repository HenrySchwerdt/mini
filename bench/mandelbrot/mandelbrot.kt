// mandelbrot: N x N set, 8 pixels packed per byte, 50 iterations; output = checksum of the bitmap (not the PBM).
// Kotlin/JVM and Mini Managed compile this very file.
const val N = 4000
const val MOD = 1000000007L

fun main() {
    var chk = 0L
    for (y in 0 until N) {
        val ci = 2.0 * y / N - 1.0
        for (xb in 0 until N / 8) {
            var bits = 0L
            for (bit in 0 until 8) {
                val x = xb * 8 + bit
                val cr = 2.0 * x / N - 1.5
                var zr = 0.0
                var zi = 0.0
                var inside = 1L
                var it = 0
                while (it < 50) {
                    val tr = zr * zr - zi * zi + cr
                    zi = 2.0 * zr * zi + ci
                    zr = tr
                    if (zr * zr + zi * zi > 4.0) {
                        inside = 0L
                        break
                    }
                    it++
                }
                bits = (bits shl 1) or inside
            }
            chk = (chk * 131 + bits) % MOD
        }
    }
    println(chk)
}
