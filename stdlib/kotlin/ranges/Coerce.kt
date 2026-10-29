/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib ranges/_Ranges.kt (coerce functions) and kotlin/math/MathH.kt (abs, sign).
 */
package kotlin.ranges

public fun Int.coerceAtLeast(minimumValue: Int): Int = if (this < minimumValue) minimumValue else this

public fun Int.coerceAtMost(maximumValue: Int): Int = if (this > maximumValue) maximumValue else this

public fun Int.coerceIn(minimumValue: Int, maximumValue: Int): Int {
    if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}

public fun Long.coerceAtLeast(minimumValue: Long): Long = if (this < minimumValue) minimumValue else this

public fun Long.coerceAtMost(maximumValue: Long): Long = if (this > maximumValue) maximumValue else this

public fun Long.coerceIn(minimumValue: Long, maximumValue: Long): Long {
    if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}

public fun Double.coerceAtLeast(minimumValue: Double): Double = if (this < minimumValue) minimumValue else this

public fun Double.coerceAtMost(maximumValue: Double): Double = if (this > maximumValue) maximumValue else this

public fun Double.coerceIn(minimumValue: Double, maximumValue: Double): Double {
    if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}
