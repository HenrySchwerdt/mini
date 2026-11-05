/*
 * Mini stdlib: bit counting and rotation for Int and Long (kotlin-stdlib NumbersJVM / Numbers.kt).
 */
package kotlin

public fun Int.floorDiv(other: Int): Int {
    var q = this / other
    if ((this xor other) < 0 && (q * other != this)) q--
    return q
}

public fun Int.mod(other: Int): Int {
    val r = this % other
    return r + (other and (((r xor other) and (r or -r)) shr 31))
}

public fun Long.floorDiv(other: Long): Long {
    var q = this / other
    if ((this xor other) < 0L && (q * other != this)) q--
    return q
}

public fun Long.mod(other: Long): Long {
    val r = this % other
    return r + (other and (((r xor other) and (r or -r)) shr 63))
}

public fun Long.mod(other: Int): Int = this.mod(other.toLong()).toInt()

public fun Int.floorDiv(other: Long): Long = this.toLong().floorDiv(other)

public fun Long.floorDiv(other: Int): Long = this.floorDiv(other.toLong())

public fun Double.mod(other: Double): Double {
    val r = this % other
    return if (r != 0.0 && (r < 0.0) != (other < 0.0)) r + other else r
}

public fun Int.countOneBits(): Int {
    var x = this
    var n = 0
    while (x != 0) {
        x = x and (x - 1)
        n++
    }
    return n
}

public fun Int.countLeadingZeroBits(): Int {
    if (this == 0) return 32
    var n = 0
    var x = this
    while (x > 0) {
        x = x shl 1
        n++
    }
    return n
}

public fun Int.countTrailingZeroBits(): Int {
    if (this == 0) return 32
    var n = 0
    var x = this
    while ((x and 1) == 0) {
        x = x ushr 1
        n++
    }
    return n
}

public fun Int.takeHighestOneBit(): Int = if (this == 0) 0 else (1 shl 31) ushr countLeadingZeroBits()
public fun Int.takeLowestOneBit(): Int = this and -this

public fun Int.rotateLeft(bitCount: Int): Int = (this shl bitCount) or (this ushr (32 - bitCount))
public fun Int.rotateRight(bitCount: Int): Int = (this ushr bitCount) or (this shl (32 - bitCount))

public fun Long.countOneBits(): Int {
    var x = this
    var n = 0
    while (x != 0L) {
        x = x and (x - 1L)
        n++
    }
    return n
}

public fun Long.countLeadingZeroBits(): Int {
    if (this == 0L) return 64
    var n = 0
    var x = this
    while (x > 0L) {
        x = x shl 1
        n++
    }
    return n
}

public fun Long.countTrailingZeroBits(): Int {
    if (this == 0L) return 64
    var n = 0
    var x = this
    while ((x and 1L) == 0L) {
        x = x ushr 1
        n++
    }
    return n
}

public fun Long.takeHighestOneBit(): Long = if (this == 0L) 0L else (1L shl 63) ushr countLeadingZeroBits()
public fun Long.takeLowestOneBit(): Long = this and -this

public fun Long.rotateLeft(bitCount: Int): Long = (this shl bitCount) or (this ushr (64 - bitCount))
public fun Long.rotateRight(bitCount: Int): Long = (this ushr bitCount) or (this shl (64 - bitCount))
