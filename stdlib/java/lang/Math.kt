/*
 * The part of java.lang.Math and java.lang.Integer that Kotlin programs written for the JVM call by their Java names (`Math.max`,
 * `Math.floorMod`, `Integer.parseInt`, `Integer.toBinaryString`, ...). Every function forwards to kotlin.math or to the Kotlin
 * extensions, so the results are those of the JVM.
 */
package java.lang

import kotlin.math.pow

public object Math {
    public const val PI: Double = 3.141592653589793
    public const val E: Double = 2.718281828459045

    public fun abs(x: Int): Int = kotlin.math.abs(x)
    public fun abs(x: Long): Long = kotlin.math.abs(x)
    public fun abs(x: Double): Double = kotlin.math.abs(x)
    public fun abs(x: Float): Float = kotlin.math.abs(x)
    public fun max(a: Int, b: Int): Int = if (a >= b) a else b
    public fun max(a: Long, b: Long): Long = if (a >= b) a else b
    public fun max(a: Double, b: Double): Double = kotlin.math.max(a, b)
    public fun max(a: Float, b: Float): Float = kotlin.math.max(a, b)
    public fun min(a: Int, b: Int): Int = if (a <= b) a else b
    public fun min(a: Long, b: Long): Long = if (a <= b) a else b
    public fun min(a: Double, b: Double): Double = kotlin.math.min(a, b)
    public fun min(a: Float, b: Float): Float = kotlin.math.min(a, b)
    public fun signum(x: Double): Double = if (x.isNaN() || x == 0.0) x else kotlin.math.sign(x)
    public fun sqrt(x: Double): Double = kotlin.math.sqrt(x)
    public fun cbrt(x: Double): Double = kotlin.math.cbrt(x)
    public fun pow(a: Double, b: Double): Double = a.pow(b)
    public fun exp(x: Double): Double = kotlin.math.exp(x)
    public fun expm1(x: Double): Double = kotlin.math.expm1(x)
    public fun log(x: Double): Double = kotlin.math.ln(x)
    public fun log10(x: Double): Double = kotlin.math.log10(x)
    public fun log1p(x: Double): Double = kotlin.math.ln1p(x)
    public fun sin(x: Double): Double = kotlin.math.sin(x)
    public fun cos(x: Double): Double = kotlin.math.cos(x)
    public fun tan(x: Double): Double = kotlin.math.tan(x)
    public fun asin(x: Double): Double = kotlin.math.asin(x)
    public fun acos(x: Double): Double = kotlin.math.acos(x)
    public fun atan(x: Double): Double = kotlin.math.atan(x)
    public fun atan2(y: Double, x: Double): Double = kotlin.math.atan2(y, x)
    public fun sinh(x: Double): Double = kotlin.math.sinh(x)
    public fun cosh(x: Double): Double = kotlin.math.cosh(x)
    public fun tanh(x: Double): Double = kotlin.math.tanh(x)
    public fun hypot(x: Double, y: Double): Double = kotlin.math.hypot(x, y)
    public fun floor(x: Double): Double = kotlin.math.floor(x)
    public fun ceil(x: Double): Double = kotlin.math.ceil(x)
    public fun rint(x: Double): Double = kotlin.math.round(x)
    public fun round(x: Double): Long = if (x.isNaN()) 0L else kotlin.math.floor(x + 0.5).toLong()
    public fun round(x: Float): Int = if (x.isNaN()) 0 else kotlin.math.floor(x.toDouble() + 0.5).toInt()
    public fun toRadians(deg: Double): Double = deg / 180.0 * PI
    public fun toDegrees(rad: Double): Double = rad * 180.0 / PI
    public fun floorDiv(a: Int, b: Int): Int = a.floorDiv(b)
    public fun floorDiv(a: Long, b: Long): Long = a.floorDiv(b)
    public fun floorMod(a: Int, b: Int): Int = a.mod(b)
    public fun floorMod(a: Long, b: Long): Long = a.mod(b)
    public fun addExact(a: Int, b: Int): Int {
        val r = a + b
        if (((a xor r) and (b xor r)) < 0) throw ArithmeticException("integer overflow")
        return r
    }
    public fun addExact(a: Long, b: Long): Long {
        val r = a + b
        if (((a xor r) and (b xor r)) < 0L) throw ArithmeticException("long overflow")
        return r
    }
    public fun subtractExact(a: Int, b: Int): Int {
        val r = a - b
        if (((a xor b) and (a xor r)) < 0) throw ArithmeticException("integer overflow")
        return r
    }
    public fun multiplyExact(a: Int, b: Int): Int {
        val r = a.toLong() * b.toLong()
        if (r.toInt().toLong() != r) throw ArithmeticException("integer overflow")
        return r.toInt()
    }
    public fun toIntExact(v: Long): Int {
        if (v.toInt().toLong() != v) throw ArithmeticException("integer overflow")
        return v.toInt()
    }
    public fun negateExact(a: Int): Int {
        if (a == Int.MIN_VALUE) throw ArithmeticException("integer overflow")
        return -a
    }
}

public object Integer {
    public const val MAX_VALUE: Int = 2147483647
    public const val MIN_VALUE: Int = -2147483648
    public const val SIZE: Int = 32
    public const val BYTES: Int = 4

    public fun parseInt(s: String): Int = s.toInt()
    public fun parseInt(s: String, radix: Int): Int = s.toInt(radix)
    public fun valueOf(s: String): Int = s.toInt()
    public fun valueOf(i: Int): Int = i
    public fun toString(i: Int): String = i.toString()
    public fun toString(i: Int, radix: Int): String = i.toString(radix)
    public fun toBinaryString(i: Int): String = i.toUInt().toString(2)
    public fun toHexString(i: Int): String = i.toUInt().toString(16)
    public fun toOctalString(i: Int): String = i.toUInt().toString(8)
    public fun bitCount(i: Int): Int = i.countOneBits()
    public fun numberOfLeadingZeros(i: Int): Int = i.countLeadingZeroBits()
    public fun numberOfTrailingZeros(i: Int): Int = i.countTrailingZeroBits()
    public fun highestOneBit(i: Int): Int = i.takeHighestOneBit()
    public fun lowestOneBit(i: Int): Int = i.takeLowestOneBit()
    public fun rotateLeft(i: Int, d: Int): Int = i.rotateLeft(d)
    public fun rotateRight(i: Int, d: Int): Int = i.rotateRight(d)
    public fun reverse(i: Int): Int {
        var v = i
        var r = 0
        var k = 0
        while (k < 32) {
            r = (r shl 1) or (v and 1)
            v = v ushr 1
            k++
        }
        return r
    }
    public fun compare(a: Int, b: Int): Int = if (a < b) -1 else if (a == b) 0 else 1
    public fun signum(i: Int): Int = if (i > 0) 1 else if (i < 0) -1 else 0
    public fun sum(a: Int, b: Int): Int = a + b
    public fun max(a: Int, b: Int): Int = if (a >= b) a else b
    public fun min(a: Int, b: Int): Int = if (a <= b) a else b
}
