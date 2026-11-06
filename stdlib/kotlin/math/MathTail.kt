/*
 * Mini stdlib: the rest of the Float overloads of kotlin.math and the ulp / nextUp / withSign family.
 */
package kotlin.math

import mini.internal.bitsToDouble
import mini.internal.bitsToFloat
import mini.internal.doubleToBits
import mini.internal.floatToBits

public fun tan(x: Float): Float = tan(x.toDouble()).toFloat()
public fun asin(x: Float): Float = asin(x.toDouble()).toFloat()
public fun acos(x: Float): Float = acos(x.toDouble()).toFloat()
public fun atan(x: Float): Float = atan(x.toDouble()).toFloat()
public fun atan2(y: Float, x: Float): Float = atan2(y.toDouble(), x.toDouble()).toFloat()
public fun sinh(x: Float): Float = sinh(x.toDouble()).toFloat()
public fun cosh(x: Float): Float = cosh(x.toDouble()).toFloat()
public fun tanh(x: Float): Float = tanh(x.toDouble()).toFloat()
public fun asinh(x: Float): Float = asinh(x.toDouble()).toFloat()
public fun acosh(x: Float): Float = acosh(x.toDouble()).toFloat()
public fun atanh(x: Float): Float = atanh(x.toDouble()).toFloat()
public fun hypot(x: Float, y: Float): Float = hypot(x.toDouble(), y.toDouble()).toFloat()
public fun expm1(x: Float): Float = expm1(x.toDouble()).toFloat()
public fun ln1p(x: Float): Float = ln1p(x.toDouble()).toFloat()
public fun log10(x: Float): Float = log10(x.toDouble()).toFloat()
public fun log2(x: Float): Float = log2(x.toDouble()).toFloat()
public fun log(x: Float, base: Float): Float = log(x.toDouble(), base.toDouble()).toFloat()
public fun cbrt(x: Float): Float = cbrt(x.toDouble()).toFloat()

public fun Float.IEEErem(divisor: Float): Float = this.toDouble().IEEErem(divisor.toDouble()).toFloat()

public val Double.ulp: Double
    get() {
        if (this != this) return this
        if (this == Double.POSITIVE_INFINITY || this == Double.NEGATIVE_INFINITY) return Double.POSITIVE_INFINITY
        val a = abs(this)
        if (a == Double.MAX_VALUE) return 1.9958403095347198E292
        return a.nextUp() - a
    }

public val Float.ulp: Float
    get() {
        if (this != this) return this
        if (this == Float.POSITIVE_INFINITY || this == Float.NEGATIVE_INFINITY) return Float.POSITIVE_INFINITY
        val a = abs(this)
        if (a == Float.MAX_VALUE) return 2.028241E31f
        return a.nextUp() - a
    }

public fun Float.nextUp(): Float =
    if (this != this || this == Float.POSITIVE_INFINITY) this
    else if (this == 0.0f) Float.MIN_VALUE
    else bitsToFloat(floatToBits(this) + (if (this > 0.0f) 1 else -1))

public fun Float.nextDown(): Float =
    if (this != this || this == Float.NEGATIVE_INFINITY) this
    else if (this == 0.0f) -Float.MIN_VALUE
    else bitsToFloat(floatToBits(this) + (if (this > 0.0f) -1 else 1))

public fun Float.nextTowards(to: Float): Float =
    if (this != this || to != to) Float.NaN
    else if (to > this) nextUp()
    else if (to < this) nextDown()
    else to

public fun Float.withSign(sign: Float): Float {
    val s = floatToBits(sign)
    return bitsToFloat((floatToBits(this) and 0x7fffffff) or (s and Int.MIN_VALUE))
}

public fun Float.withSign(sign: Int): Float = withSign(if (sign < 0) -1.0f else 1.0f)
