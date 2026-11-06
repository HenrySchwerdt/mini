/*
 * kotlin.random: the same generators and algorithms as kotlin-stdlib (XorWow), so a seeded Random
 * produces the same numbers as on the JVM.
 */
package kotlin.random

import kotlin.math.nextDown
import mini.internal.epochMillis

public abstract class Random {
    public abstract fun nextBits(bitCount: Int): Int

    public open fun nextInt(): Int = nextBits(32)

    public open fun nextInt(until: Int): Int = nextInt(0, until)

    public open fun nextInt(from: Int, until: Int): Int {
        checkRangeBounds(from, until)
        val n = until - from
        if (n > 0 || n == Int.MIN_VALUE) {
            val rnd: Int
            if ((n and -n) == n) {
                val bitCount = fastLog2(n)
                rnd = nextBits(bitCount)
            } else {
                var v: Int
                while (true) {
                    val bits = nextInt() ushr 1
                    v = bits % n
                    if (bits - v + (n - 1) >= 0) break
                }
                rnd = v
            }
            return from + rnd
        } else {
            while (true) {
                val rnd = nextInt()
                if (rnd >= from && rnd < until) return rnd
            }
        }
    }

    public open fun nextLong(): Long = (nextInt().toLong() shl 32) + nextInt()

    public open fun nextLong(until: Long): Long = nextLong(0L, until)

    public open fun nextLong(from: Long, until: Long): Long {
        checkRangeBounds(from, until)
        val n = until - from
        if (n > 0) {
            val rnd: Long
            if ((n and -n) == n) {
                val nLow = n.toInt()
                val nHigh = (n ushr 32).toInt()
                if (nLow != 0) {
                    val bitCount = fastLog2(nLow)
                    rnd = nextBits(bitCount).toLong() and 0xFFFFFFFFL
                } else if (nHigh == 1) {
                    rnd = nextInt().toLong() and 0xFFFFFFFFL
                } else {
                    val bitCount = fastLog2(nHigh)
                    rnd = nextBits(bitCount).toLong() shl 32 or (nextInt().toLong() and 0xFFFFFFFFL)
                }
            } else {
                var v: Long
                while (true) {
                    val bits = nextLong() ushr 1
                    v = bits % n
                    if (bits - v + (n - 1) >= 0) break
                }
                rnd = v
            }
            return from + rnd
        } else {
            while (true) {
                val rnd = nextLong()
                if (rnd >= from && rnd < until) return rnd
            }
        }
    }

    public open fun nextBoolean(): Boolean = nextBits(1) != 0

    public open fun nextDouble(): Double {
        val hi = nextBits(26).toLong()
        val lo = nextBits(27).toLong()
        return ((hi shl 27) + lo) / (1L shl 53).toDouble()
    }

    public open fun nextDouble(until: Double): Double = nextDouble(0.0, until)

    public open fun nextDouble(from: Double, until: Double): Double {
        checkRangeBounds(from, until)
        val size = until - from
        val r = nextDouble() * size + from
        return if (r >= until) until.nextDown() else r
    }

    public open fun nextFloat(): Float = nextBits(24) / (1 shl 24).toFloat()

    public open fun nextBytes(array: ByteArray, fromIndex: Int = 0, toIndex: Int = array.size): ByteArray {
        if (fromIndex < 0 || fromIndex > array.size || toIndex < 0 || toIndex > array.size) {
            throw IllegalArgumentException("fromIndex (" + fromIndex + ") or toIndex (" + toIndex + ") are out of range: 0.." + array.size + ".")
        }
        if (fromIndex > toIndex) {
            throw IllegalArgumentException("fromIndex (" + fromIndex + ") must be not greater than toIndex (" + toIndex + ").")
        }
        val steps = (toIndex - fromIndex) / 4
        var position = fromIndex
        var step = 0
        while (step < steps) {
            val v = nextInt()
            array[position] = v.toByte()
            array[position + 1] = (v ushr 8).toByte()
            array[position + 2] = (v ushr 16).toByte()
            array[position + 3] = (v ushr 24).toByte()
            position += 4
            step++
        }
        val remainder = toIndex - position
        val vr = nextBits(remainder * 8)
        var i = 0
        while (i < remainder) {
            array[position + i] = (vr ushr (i * 8)).toByte()
            i++
        }
        return array
    }

    public open fun nextBytes(size: Int): ByteArray = nextBytes(ByteArray(size))

    public companion object Default : Random() {
        private val defaultRandom: Random = Random(epochMillis())
        override fun nextBits(bitCount: Int): Int = defaultRandom.nextBits(bitCount)
        override fun nextInt(): Int = defaultRandom.nextInt()
        override fun nextInt(until: Int): Int = defaultRandom.nextInt(until)
        override fun nextInt(from: Int, until: Int): Int = defaultRandom.nextInt(from, until)
        override fun nextLong(): Long = defaultRandom.nextLong()
        override fun nextLong(until: Long): Long = defaultRandom.nextLong(until)
        override fun nextLong(from: Long, until: Long): Long = defaultRandom.nextLong(from, until)
        override fun nextBoolean(): Boolean = defaultRandom.nextBoolean()
        override fun nextDouble(): Double = defaultRandom.nextDouble()
        override fun nextDouble(until: Double): Double = defaultRandom.nextDouble(until)
        override fun nextDouble(from: Double, until: Double): Double = defaultRandom.nextDouble(from, until)
        override fun nextFloat(): Float = defaultRandom.nextFloat()
    }
}

internal class XorWowRandom(
    private var x: Int,
    private var y: Int,
    private var z: Int,
    private var w: Int,
    private var v: Int,
    private var addend: Int,
) : Random() {
    init {
        require((x or y or z or w or v) != 0) { "Initial state must have at least one non-zero element." }
        var i = 0
        while (i < 64) {
            nextInt()
            i++
        }
    }

    override fun nextInt(): Int {
        var t = x
        t = t xor (t ushr 2)
        x = y
        y = z
        z = w
        val v0 = v
        w = v0
        t = (t xor (t shl 1)) xor v0 xor (v0 shl 4)
        v = t
        addend += 362437
        return t + addend
    }

    override fun nextBits(bitCount: Int): Int = takeUpperBits(nextInt(), bitCount)
}

private fun takeUpperBits(value: Int, bitCount: Int): Int = (value ushr (32 - bitCount)) and ((-bitCount) shr 31)

private fun fastLog2(value: Int): Int {
    var n = 0
    var v = value
    while (v != 1 && n < 31) {
        v = v ushr 1
        n++
    }
    return n
}



private fun checkRangeBounds(from: Int, until: Int) {
    require(until > from) { "Random range is empty: [" + from + ", " + until + ")." }
}

private fun checkRangeBounds(from: Long, until: Long) {
    require(until > from) { "Random range is empty: [" + from + ", " + until + ")." }
}

private fun checkRangeBounds(from: Double, until: Double) {
    require(until > from) { "Random range is empty: [" + from + ", " + until + ")." }
}

public fun Random(seed: Int): Random = XorWowRandom(seed, seed shr 31, 0, 0, seed.inv(), (seed shl 10) xor ((seed shr 31) ushr 4))

public fun Random(seed: Long): Random {
    val s1 = seed.toInt()
    val s2 = (seed shr 32).toInt()
    return XorWowRandom(s1, s2, 0, 0, s1.inv(), (s1 shl 10) xor (s2 ushr 4))
}

