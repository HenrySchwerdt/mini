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

public fun Float.coerceAtLeast(minimumValue: Float): Float = if (this < minimumValue) minimumValue else this

public fun Float.coerceAtMost(maximumValue: Float): Float = if (this > maximumValue) maximumValue else this

public fun Float.coerceIn(minimumValue: Float, maximumValue: Float): Float {
    if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}

public fun Byte.coerceAtLeast(minimumValue: Byte): Byte = if (this < minimumValue) minimumValue else this

public fun Byte.coerceAtMost(maximumValue: Byte): Byte = if (this > maximumValue) maximumValue else this

public fun Byte.coerceIn(minimumValue: Byte, maximumValue: Byte): Byte {
    if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}

public fun Short.coerceAtLeast(minimumValue: Short): Short = if (this < minimumValue) minimumValue else this

public fun Short.coerceAtMost(maximumValue: Short): Short = if (this > maximumValue) maximumValue else this

public fun Short.coerceIn(minimumValue: Short, maximumValue: Short): Short {
    if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}

public fun UInt.coerceAtLeast(minimumValue: UInt): UInt = if (this < minimumValue) minimumValue else this

public fun UInt.coerceAtMost(maximumValue: UInt): UInt = if (this > maximumValue) maximumValue else this

public fun UInt.coerceIn(minimumValue: UInt, maximumValue: UInt): UInt {
    if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}

public fun ULong.coerceAtLeast(minimumValue: ULong): ULong = if (this < minimumValue) minimumValue else this

public fun ULong.coerceAtMost(maximumValue: ULong): ULong = if (this > maximumValue) maximumValue else this

public fun ULong.coerceIn(minimumValue: ULong, maximumValue: ULong): ULong {
    if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}

public fun UByte.coerceAtLeast(minimumValue: UByte): UByte = if (this < minimumValue) minimumValue else this

public fun UByte.coerceAtMost(maximumValue: UByte): UByte = if (this > maximumValue) maximumValue else this

public fun UByte.coerceIn(minimumValue: UByte, maximumValue: UByte): UByte {
    if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}

public fun UShort.coerceAtLeast(minimumValue: UShort): UShort = if (this < minimumValue) minimumValue else this

public fun UShort.coerceAtMost(maximumValue: UShort): UShort = if (this > maximumValue) maximumValue else this

public fun UShort.coerceIn(minimumValue: UShort, maximumValue: UShort): UShort {
    if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}

public fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T = if (this < minimumValue) minimumValue else this

public fun <T : Comparable<T>> T.coerceAtMost(maximumValue: T): T = if (this > maximumValue) maximumValue else this

public fun <T : Comparable<T>> T.coerceIn(minimumValue: T?, maximumValue: T?): T {
    if (minimumValue != null && maximumValue != null) {
        if (minimumValue > maximumValue) mini.internal.throwIllegalArgument("Cannot coerce value to an empty range: maximum $maximumValue is less than minimum $minimumValue.")
    }
    if (minimumValue != null && this < minimumValue) return minimumValue
    if (maximumValue != null && this > maximumValue) return maximumValue
    return this
}
