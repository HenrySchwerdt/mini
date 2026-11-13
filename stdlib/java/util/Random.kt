/*
 * java.util.Random: the 48-bit linear congruential generator of the JDK (same seed scrambling, same next(bits), same nextInt(bound) and
 * nextDouble / nextGaussian algorithms), so a seeded generator produces the values the JVM does.
 */
package java.util

public open class Random {
    private var seed: Long
    private var nextNextGaussian: Double = 0.0
    private var haveNextNextGaussian: Boolean = false

    public constructor() {
        seed = scramble(mini.internal.nanoTime() xor 0x5DEECE66DL)
    }

    public constructor(seed: Long) {
        this.seed = scramble(seed)
    }

    private fun scramble(s: Long): Long = (s xor MULTIPLIER) and MASK

    public fun setSeed(s: Long) {
        seed = scramble(s)
        haveNextNextGaussian = false
    }

    protected open fun next(bits: Int): Int {
        seed = (seed * MULTIPLIER + 0xBL) and MASK
        return (seed ushr (48 - bits)).toInt()
    }

    public open fun nextInt(): Int = next(32)

    public open fun nextInt(bound: Int): Int {
        if (bound <= 0) throw IllegalArgumentException("bound must be positive")
        var r = next(31)
        val m = bound - 1
        if ((bound and m) == 0) {
            r = ((bound.toLong() * r.toLong()) shr 31).toInt()
        } else {
            var u = r
            r = u % bound
            while (u - r + m < 0) {
                u = next(31)
                r = u % bound
            }
        }
        return r
    }

    public fun nextInt(origin: Int, bound: Int): Int {
        if (origin >= bound) throw IllegalArgumentException("bound must be greater than origin")
        val n = bound - origin
        if (n > 0) return nextInt(n) + origin
        var r: Int
        do {
            r = nextInt()
        } while (r < origin || r >= bound)
        return r
    }

    public open fun nextLong(): Long = (next(32).toLong() shl 32) + next(32).toLong()
    public open fun nextBoolean(): Boolean = next(1) != 0
    public open fun nextFloat(): Float = next(24) / (1 shl 24).toFloat()
    public open fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27).toLong()) * (1.0 / (1L shl 53).toDouble())

    public open fun nextBytes(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            var rnd = nextInt()
            var n = if (bytes.size - i < 4) bytes.size - i else 4
            while (n-- > 0) {
                bytes[i++] = rnd.toByte()
                rnd = rnd shr 8
            }
        }
    }

    public open fun nextGaussian(): Double {
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false
            return nextNextGaussian
        }
        var v1: Double
        var v2: Double
        var s: Double
        do {
            v1 = 2 * nextDouble() - 1
            v2 = 2 * nextDouble() - 1
            s = v1 * v1 + v2 * v2
        } while (s >= 1 || s == 0.0)
        val multiplier = kotlin.math.sqrt(-2 * kotlin.math.ln(s) / s)
        nextNextGaussian = v2 * multiplier
        haveNextNextGaussian = true
        return v1 * multiplier
    }

    private companion object {
        const val MULTIPLIER: Long = 0x5DEECE66DL
        const val MASK: Long = 0xFFFFFFFFFFFFL
    }
}
