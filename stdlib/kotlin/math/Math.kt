/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Subset of kotlin.math. The floating point functions are the C functions of libm (`@CName` externals), so they
 * round and handle NaN / infinities exactly like the JVM versions (java.lang.Math is specified against the same
 * fdlibm results within 1 ulp; `sqrt`, `abs`, `floor`, `ceil` are exact).
 */
@file:CInclude("math.h")

package kotlin.math

import kotlin.native.CName
import kotlinx.cinterop.CInclude

public const val PI: Double = 3.141592653589793
public const val E: Double = 2.718281828459045

public fun abs(n: Int): Int = if (n < 0) -n else n

public fun abs(n: Long): Long = if (n < 0) -n else n

public fun abs(x: Double): Double = if (x < 0.0) -x else if (x == 0.0) 0.0 else x

public fun max(a: Int, b: Int): Int = if (a >= b) a else b

public fun max(a: Long, b: Long): Long = if (a >= b) a else b

public fun min(a: Int, b: Int): Int = if (a <= b) a else b

public fun min(a: Long, b: Long): Long = if (a <= b) a else b

public fun max(a: Double, b: Double): Double = if (a != a) a else if (b != b) b else if (a > b) a else if (b > a) b else if (a == 0.0 && 1.0 / a < 0.0) b else a

public fun min(a: Double, b: Double): Double = if (a != a) a else if (b != b) b else if (a < b) a else if (b < a) b else if (a == 0.0 && 1.0 / a < 0.0) a else b

public val Int.sign: Int
    get() = if (this < 0) -1 else if (this > 0) 1 else 0

public val Long.sign: Int
    get() = if (this < 0L) -1 else if (this > 0L) 1 else 0

public val Double.sign: Double
    get() = if (this != this) this else if (this > 0.0) 1.0 else if (this < 0.0) -1.0 else this

@CName("sqrt") public external fun sqrt(x: Double): Double
@CName("cbrt") public external fun cbrt(x: Double): Double
@CName("exp") public external fun exp(x: Double): Double
@CName("expm1") public external fun expm1(x: Double): Double
@CName("log") public external fun ln(x: Double): Double
@CName("log1p") public external fun ln1p(x: Double): Double
@CName("log10") public external fun log10(x: Double): Double
@CName("log2") public external fun log2(x: Double): Double
@CName("sin") public external fun sin(x: Double): Double
@CName("cos") public external fun cos(x: Double): Double
@CName("tan") public external fun tan(x: Double): Double
@CName("asin") public external fun asin(x: Double): Double
@CName("acos") public external fun acos(x: Double): Double
@CName("atan") public external fun atan(x: Double): Double
@CName("atan2") public external fun atan2(y: Double, x: Double): Double
@CName("sinh") public external fun sinh(x: Double): Double
@CName("cosh") public external fun cosh(x: Double): Double
@CName("tanh") public external fun tanh(x: Double): Double
@CName("asinh") public external fun asinh(x: Double): Double
@CName("acosh") public external fun acosh(x: Double): Double
@CName("atanh") public external fun atanh(x: Double): Double
@CName("hypot") public external fun hypot(x: Double, y: Double): Double
@CName("floor") public external fun floor(x: Double): Double
@CName("ceil") public external fun ceil(x: Double): Double
@CName("trunc") public external fun truncate(x: Double): Double
/** Rounds to the nearest integer, ties to even (like Kotlin's `round`). */
@CName("rint") public external fun round(x: Double): Double

@CName("pow") public external fun Double.pow(x: Double): Double

public fun Double.pow(n: Int): Double = this.pow(n.toDouble())

public fun log(x: Double, base: Double): Double =
    if (base <= 0.0 || base == 1.0) Double.NaN else ln(x) / ln(base)

public fun Double.roundToInt(): Int =
    if (this != this) 0 else if (this >= 2147483647.0) Int.MAX_VALUE else if (this <= -2147483648.0) Int.MIN_VALUE else round(this).toInt()

public fun Double.roundToLong(): Long =
    if (this != this) 0L else if (this >= 9.223372036854775807E18) Long.MAX_VALUE else if (this <= -9.223372036854775808E18) Long.MIN_VALUE else round(this).toLong()
