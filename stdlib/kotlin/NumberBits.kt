/*
 * IEEE 754 bit representations.
 */
package kotlin

import mini.internal.doubleToBits
import mini.internal.floatToBits

public fun Double.toBits(): Long = if (this != this) 0x7ff8000000000000L else doubleToBits(this)

public fun Double.toRawBits(): Long = doubleToBits(this)

public fun Float.toBits(): Int = if (this != this) 0x7fc00000 else floatToBits(this)

public fun Float.toRawBits(): Int = floatToBits(this)
