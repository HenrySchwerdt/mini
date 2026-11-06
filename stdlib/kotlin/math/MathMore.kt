/*
 * More of kotlin.math: sign/absoluteValue, Float overloads, rounding with the JVM semantics (ties towards +infinity).
 */
@file:CInclude("math.h")

package kotlin.math

import kotlin.native.CName
import kotlinx.cinterop.CInclude

public fun sign(x: Double): Double = x.sign
public fun sign(x: Int): Int = x.sign
public fun sign(x: Long): Int = x.sign

public val Int.absoluteValue: Int
    get() = abs(this)

public val Long.absoluteValue: Long
    get() = abs(this)

public val Double.absoluteValue: Double
    get() = abs(this)

public val Float.absoluteValue: Float
    get() = abs(this)

public val Float.sign: Float
    get() = if (this != this) this else if (this > 0.0f) 1.0f else if (this < 0.0f) -1.0f else this

public fun sqrt(x: Float): Float = sqrt(x.toDouble()).toFloat()

public fun floor(x: Float): Float = floor(x.toDouble()).toFloat()

public fun ceil(x: Float): Float = ceil(x.toDouble()).toFloat()

public fun round(x: Float): Float = round(x.toDouble()).toFloat()

public fun truncate(x: Float): Float = truncate(x.toDouble()).toFloat()

public fun Float.pow(x: Float): Float = this.toDouble().pow(x.toDouble()).toFloat()

public fun Float.pow(n: Int): Float = this.toDouble().pow(n.toDouble()).toFloat()

public fun sin(x: Float): Float = sin(x.toDouble()).toFloat()

public fun cos(x: Float): Float = cos(x.toDouble()).toFloat()

public fun exp(x: Float): Float = exp(x.toDouble()).toFloat()

public fun ln(x: Float): Float = ln(x.toDouble()).toFloat()

@CName("fmod")
private external fun fmod(x: Double, y: Double): Double

@CName("remainder")
private external fun remainder(x: Double, y: Double): Double

@CName("nextafter")
private external fun nextafter(x: Double, y: Double): Double

public fun Double.IEEErem(divisor: Double): Double = remainder(this, divisor)

public fun Double.nextUp(): Double = nextafter(this, Double.POSITIVE_INFINITY)

public fun Double.nextDown(): Double = nextafter(this, Double.NEGATIVE_INFINITY)

public fun Double.nextTowards(to: Double): Double = nextafter(this, to)

public fun Double.withSign(sign: Double): Double {
    val negative = sign < 0.0 || (sign == 0.0 && 1.0 / sign < 0.0)
    val a = abs(this)
    return if (negative) -a else a
}

public fun Double.withSign(sign: Int): Double = withSign(sign.toDouble())

public fun Double.ulp(): Double = abs(nextafter(abs(this), Double.POSITIVE_INFINITY) - abs(this))
