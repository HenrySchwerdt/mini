/*
 * Mini stdlib: the unsigned types UByte, UShort, UInt and ULong, as value classes over the signed storage type
 * (like kotlin-stdlib). Generated pattern; arithmetic wraps, division and comparison are unsigned.
 */
@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY")

package kotlin

import kotlin.ranges.*

public value class UByte internal constructor(internal val data: Byte) : Comparable<UByte> {
    public companion object {
        public val MIN_VALUE: UByte = UByte(0)
        public val MAX_VALUE: UByte = UByte(-1)
        public const val SIZE_BYTES: Int = 1
        public const val SIZE_BITS: Int = 8
    }

    public override operator fun compareTo(other: UByte): Int = (data.toInt() and 0xFF) - (other.data.toInt() and 0xFF)
    public operator fun compareTo(other: UShort): Int = toUInt().compareTo(other.toUInt())
    public operator fun compareTo(other: UInt): Int = toUInt().compareTo(other.toUInt())
    public operator fun compareTo(other: ULong): Int = toULong().compareTo(other.toULong())
    public operator fun plus(other: UByte): UInt = toUInt().plus(other.toUInt())
    public operator fun plus(other: UShort): UInt = toUInt().plus(other.toUInt())
    public operator fun plus(other: UInt): UInt = toUInt().plus(other.toUInt())
    public operator fun plus(other: ULong): ULong = toULong().plus(other.toULong())
    public operator fun minus(other: UByte): UInt = toUInt().minus(other.toUInt())
    public operator fun minus(other: UShort): UInt = toUInt().minus(other.toUInt())
    public operator fun minus(other: UInt): UInt = toUInt().minus(other.toUInt())
    public operator fun minus(other: ULong): ULong = toULong().minus(other.toULong())
    public operator fun times(other: UByte): UInt = toUInt().times(other.toUInt())
    public operator fun times(other: UShort): UInt = toUInt().times(other.toUInt())
    public operator fun times(other: UInt): UInt = toUInt().times(other.toUInt())
    public operator fun times(other: ULong): ULong = toULong().times(other.toULong())
    public operator fun div(other: UByte): UInt = toUInt().div(other.toUInt())
    public operator fun div(other: UShort): UInt = toUInt().div(other.toUInt())
    public operator fun div(other: UInt): UInt = toUInt().div(other.toUInt())
    public operator fun div(other: ULong): ULong = toULong().div(other.toULong())
    public operator fun rem(other: UByte): UInt = toUInt().rem(other.toUInt())
    public operator fun rem(other: UShort): UInt = toUInt().rem(other.toUInt())
    public operator fun rem(other: UInt): UInt = toUInt().rem(other.toUInt())
    public operator fun rem(other: ULong): ULong = toULong().rem(other.toULong())
    public fun floorDiv(other: UByte): UInt = div(other)
    public fun mod(other: UByte): UByte = rem(other).toUByte()
    public fun floorDiv(other: UShort): UInt = div(other)
    public fun mod(other: UShort): UShort = rem(other).toUShort()
    public fun floorDiv(other: UInt): UInt = div(other)
    public fun mod(other: UInt): UInt = rem(other).toUInt()
    public fun floorDiv(other: ULong): ULong = div(other)
    public fun mod(other: ULong): ULong = rem(other).toULong()
    public operator fun inc(): UByte = UByte(data.inc())
    public operator fun dec(): UByte = UByte(data.dec())
    public infix fun and(other: UByte): UByte = UByte((data.toInt() and other.data.toInt()).toByte())
    public infix fun or(other: UByte): UByte = UByte((data.toInt() or other.data.toInt()).toByte())
    public infix fun xor(other: UByte): UByte = UByte((data.toInt() xor other.data.toInt()).toByte())
    public fun inv(): UByte = UByte(data.toInt().inv().toByte())
    public fun toUByte(): UByte = this
    public fun toUShort(): UShort = UShort((data.toInt() and 0xFF).toShort())
    public fun toUInt(): UInt = UInt((data.toInt() and 0xFF).toInt())
    public fun toULong(): ULong = ULong((data.toInt() and 0xFF).toLong())
    public fun toByte(): Byte = data.toByte()
    public fun toShort(): Short = (data.toInt() and 0xFF).toShort()
    public fun toInt(): Int = data.toInt() and 0xFF
    public fun toLong(): Long = (data.toInt() and 0xFF).toLong()
    public fun toFloat(): Float = (data.toInt() and 0xFF).toFloat()
    public fun toDouble(): Double = (data.toInt() and 0xFF).toDouble()
    public override fun toString(): String = (data.toInt() and 0xFF).toString()
}

public value class UShort internal constructor(internal val data: Short) : Comparable<UShort> {
    public companion object {
        public val MIN_VALUE: UShort = UShort(0)
        public val MAX_VALUE: UShort = UShort(-1)
        public const val SIZE_BYTES: Int = 2
        public const val SIZE_BITS: Int = 16
    }

    public operator fun compareTo(other: UByte): Int = toUInt().compareTo(other.toUInt())
    public override operator fun compareTo(other: UShort): Int = (data.toInt() and 0xFFFF) - (other.data.toInt() and 0xFFFF)
    public operator fun compareTo(other: UInt): Int = toUInt().compareTo(other.toUInt())
    public operator fun compareTo(other: ULong): Int = toULong().compareTo(other.toULong())
    public operator fun plus(other: UByte): UInt = toUInt().plus(other.toUInt())
    public operator fun plus(other: UShort): UInt = toUInt().plus(other.toUInt())
    public operator fun plus(other: UInt): UInt = toUInt().plus(other.toUInt())
    public operator fun plus(other: ULong): ULong = toULong().plus(other.toULong())
    public operator fun minus(other: UByte): UInt = toUInt().minus(other.toUInt())
    public operator fun minus(other: UShort): UInt = toUInt().minus(other.toUInt())
    public operator fun minus(other: UInt): UInt = toUInt().minus(other.toUInt())
    public operator fun minus(other: ULong): ULong = toULong().minus(other.toULong())
    public operator fun times(other: UByte): UInt = toUInt().times(other.toUInt())
    public operator fun times(other: UShort): UInt = toUInt().times(other.toUInt())
    public operator fun times(other: UInt): UInt = toUInt().times(other.toUInt())
    public operator fun times(other: ULong): ULong = toULong().times(other.toULong())
    public operator fun div(other: UByte): UInt = toUInt().div(other.toUInt())
    public operator fun div(other: UShort): UInt = toUInt().div(other.toUInt())
    public operator fun div(other: UInt): UInt = toUInt().div(other.toUInt())
    public operator fun div(other: ULong): ULong = toULong().div(other.toULong())
    public operator fun rem(other: UByte): UInt = toUInt().rem(other.toUInt())
    public operator fun rem(other: UShort): UInt = toUInt().rem(other.toUInt())
    public operator fun rem(other: UInt): UInt = toUInt().rem(other.toUInt())
    public operator fun rem(other: ULong): ULong = toULong().rem(other.toULong())
    public fun floorDiv(other: UByte): UInt = div(other)
    public fun mod(other: UByte): UByte = rem(other).toUByte()
    public fun floorDiv(other: UShort): UInt = div(other)
    public fun mod(other: UShort): UShort = rem(other).toUShort()
    public fun floorDiv(other: UInt): UInt = div(other)
    public fun mod(other: UInt): UInt = rem(other).toUInt()
    public fun floorDiv(other: ULong): ULong = div(other)
    public fun mod(other: ULong): ULong = rem(other).toULong()
    public operator fun inc(): UShort = UShort(data.inc())
    public operator fun dec(): UShort = UShort(data.dec())
    public infix fun and(other: UShort): UShort = UShort((data.toInt() and other.data.toInt()).toShort())
    public infix fun or(other: UShort): UShort = UShort((data.toInt() or other.data.toInt()).toShort())
    public infix fun xor(other: UShort): UShort = UShort((data.toInt() xor other.data.toInt()).toShort())
    public fun inv(): UShort = UShort(data.toInt().inv().toShort())
    public fun toUByte(): UByte = UByte((data.toInt() and 0xFFFF).toByte())
    public fun toUShort(): UShort = this
    public fun toUInt(): UInt = UInt((data.toInt() and 0xFFFF).toInt())
    public fun toULong(): ULong = ULong((data.toInt() and 0xFFFF).toLong())
    public fun toByte(): Byte = data.toByte()
    public fun toShort(): Short = (data.toInt() and 0xFFFF).toShort()
    public fun toInt(): Int = data.toInt() and 0xFFFF
    public fun toLong(): Long = (data.toInt() and 0xFFFF).toLong()
    public fun toFloat(): Float = (data.toInt() and 0xFFFF).toFloat()
    public fun toDouble(): Double = (data.toInt() and 0xFFFF).toDouble()
    public override fun toString(): String = (data.toInt() and 0xFFFF).toString()
}

public value class UInt internal constructor(internal val data: Int) : Comparable<UInt> {
    public companion object {
        public val MIN_VALUE: UInt = UInt(0)
        public val MAX_VALUE: UInt = UInt(-1)
        public const val SIZE_BYTES: Int = 4
        public const val SIZE_BITS: Int = 32
    }

    public operator fun compareTo(other: UByte): Int = toUInt().compareTo(other.toUInt())
    public operator fun compareTo(other: UShort): Int = toUInt().compareTo(other.toUInt())
    public override operator fun compareTo(other: UInt): Int = (data xor Int.MIN_VALUE).compareTo(other.data xor Int.MIN_VALUE)
    public operator fun compareTo(other: ULong): Int = toULong().compareTo(other.toULong())
    public operator fun plus(other: UByte): UInt = toUInt().plus(other.toUInt())
    public operator fun plus(other: UShort): UInt = toUInt().plus(other.toUInt())
    public operator fun plus(other: UInt): UInt = UInt(data + other.data)
    public operator fun plus(other: ULong): ULong = toULong().plus(other.toULong())
    public operator fun minus(other: UByte): UInt = toUInt().minus(other.toUInt())
    public operator fun minus(other: UShort): UInt = toUInt().minus(other.toUInt())
    public operator fun minus(other: UInt): UInt = UInt(data - other.data)
    public operator fun minus(other: ULong): ULong = toULong().minus(other.toULong())
    public operator fun times(other: UByte): UInt = toUInt().times(other.toUInt())
    public operator fun times(other: UShort): UInt = toUInt().times(other.toUInt())
    public operator fun times(other: UInt): UInt = UInt(data * other.data)
    public operator fun times(other: ULong): ULong = toULong().times(other.toULong())
    public operator fun div(other: UByte): UInt = toUInt().div(other.toUInt())
    public operator fun div(other: UShort): UInt = toUInt().div(other.toUInt())
    public operator fun div(other: UInt): UInt = UInt(((data.toLong() and 0xFFFFFFFFL) / (other.data.toLong() and 0xFFFFFFFFL)).toInt())
    public operator fun div(other: ULong): ULong = toULong().div(other.toULong())
    public operator fun rem(other: UByte): UInt = toUInt().rem(other.toUInt())
    public operator fun rem(other: UShort): UInt = toUInt().rem(other.toUInt())
    public operator fun rem(other: UInt): UInt = UInt(((data.toLong() and 0xFFFFFFFFL) % (other.data.toLong() and 0xFFFFFFFFL)).toInt())
    public operator fun rem(other: ULong): ULong = toULong().rem(other.toULong())
    public fun floorDiv(other: UByte): UInt = div(other)
    public fun mod(other: UByte): UByte = rem(other).toUByte()
    public fun floorDiv(other: UShort): UInt = div(other)
    public fun mod(other: UShort): UShort = rem(other).toUShort()
    public fun floorDiv(other: UInt): UInt = div(other)
    public fun mod(other: UInt): UInt = rem(other).toUInt()
    public fun floorDiv(other: ULong): ULong = div(other)
    public fun mod(other: ULong): ULong = rem(other).toULong()
    public operator fun inc(): UInt = UInt(data.inc())
    public operator fun dec(): UInt = UInt(data.dec())
    public operator fun rangeTo(other: UInt): UIntRange = UIntRange(this, other)
    public infix fun and(other: UInt): UInt = UInt(data and other.data)
    public infix fun or(other: UInt): UInt = UInt(data or other.data)
    public infix fun xor(other: UInt): UInt = UInt(data xor other.data)
    public fun inv(): UInt = UInt(data.inv())
    public infix fun shl(bitCount: Int): UInt = UInt(data shl bitCount)
    public infix fun shr(bitCount: Int): UInt = UInt(data ushr bitCount)
    public fun toUByte(): UByte = UByte(data.toByte())
    public fun toUShort(): UShort = UShort(data.toShort())
    public fun toUInt(): UInt = this
    public fun toULong(): ULong = ULong(data.toLong() and 0xFFFFFFFFL)
    public fun toByte(): Byte = data.toByte()
    public fun toShort(): Short = data.toShort()
    public fun toInt(): Int = data
    public fun toLong(): Long = data.toLong() and 0xFFFFFFFFL
    public fun toFloat(): Float = toDouble().toFloat()
    public fun toDouble(): Double = (data.toLong() and 0xFFFFFFFFL).toDouble()
    public override fun toString(): String = (data.toLong() and 0xFFFFFFFFL).toString()
}

public value class ULong internal constructor(internal val data: Long) : Comparable<ULong> {
    public companion object {
        public val MIN_VALUE: ULong = ULong(0L)
        public val MAX_VALUE: ULong = ULong(-1L)
        public const val SIZE_BYTES: Int = 8
        public const val SIZE_BITS: Int = 64
    }

    public operator fun compareTo(other: UByte): Int = toULong().compareTo(other.toULong())
    public operator fun compareTo(other: UShort): Int = toULong().compareTo(other.toULong())
    public operator fun compareTo(other: UInt): Int = toULong().compareTo(other.toULong())
    public override operator fun compareTo(other: ULong): Int = (data xor Long.MIN_VALUE).compareTo(other.data xor Long.MIN_VALUE)
    public operator fun plus(other: UByte): ULong = toULong().plus(other.toULong())
    public operator fun plus(other: UShort): ULong = toULong().plus(other.toULong())
    public operator fun plus(other: UInt): ULong = toULong().plus(other.toULong())
    public operator fun plus(other: ULong): ULong = ULong(data + other.data)
    public operator fun minus(other: UByte): ULong = toULong().minus(other.toULong())
    public operator fun minus(other: UShort): ULong = toULong().minus(other.toULong())
    public operator fun minus(other: UInt): ULong = toULong().minus(other.toULong())
    public operator fun minus(other: ULong): ULong = ULong(data - other.data)
    public operator fun times(other: UByte): ULong = toULong().times(other.toULong())
    public operator fun times(other: UShort): ULong = toULong().times(other.toULong())
    public operator fun times(other: UInt): ULong = toULong().times(other.toULong())
    public operator fun times(other: ULong): ULong = ULong(data * other.data)
    public operator fun div(other: UByte): ULong = toULong().div(other.toULong())
    public operator fun div(other: UShort): ULong = toULong().div(other.toULong())
    public operator fun div(other: UInt): ULong = toULong().div(other.toULong())
    public operator fun div(other: ULong): ULong = ULong(ulongDivide(data, other.data))
    public operator fun rem(other: UByte): ULong = toULong().rem(other.toULong())
    public operator fun rem(other: UShort): ULong = toULong().rem(other.toULong())
    public operator fun rem(other: UInt): ULong = toULong().rem(other.toULong())
    public operator fun rem(other: ULong): ULong = ULong(ulongRemainder(data, other.data))
    public fun floorDiv(other: UByte): ULong = div(other)
    public fun mod(other: UByte): UByte = rem(other).toUByte()
    public fun floorDiv(other: UShort): ULong = div(other)
    public fun mod(other: UShort): UShort = rem(other).toUShort()
    public fun floorDiv(other: UInt): ULong = div(other)
    public fun mod(other: UInt): UInt = rem(other).toUInt()
    public fun floorDiv(other: ULong): ULong = div(other)
    public fun mod(other: ULong): ULong = rem(other).toULong()
    public operator fun inc(): ULong = ULong(data.inc())
    public operator fun dec(): ULong = ULong(data.dec())
    public operator fun rangeTo(other: ULong): ULongRange = ULongRange(this, other)
    public infix fun and(other: ULong): ULong = ULong(data and other.data)
    public infix fun or(other: ULong): ULong = ULong(data or other.data)
    public infix fun xor(other: ULong): ULong = ULong(data xor other.data)
    public fun inv(): ULong = ULong(data.inv())
    public infix fun shl(bitCount: Int): ULong = ULong(data shl bitCount)
    public infix fun shr(bitCount: Int): ULong = ULong(data ushr bitCount)
    public fun toUByte(): UByte = UByte(data.toByte())
    public fun toUShort(): UShort = UShort(data.toShort())
    public fun toUInt(): UInt = UInt(data.toInt())
    public fun toULong(): ULong = this
    public fun toByte(): Byte = data.toByte()
    public fun toShort(): Short = data.toShort()
    public fun toInt(): Int = data.toInt()
    public fun toLong(): Long = data
    public fun toFloat(): Float = toDouble().toFloat()
    public fun toDouble(): Double = ulongToDouble(data)
    public override fun toString(): String = ulongToString(data)
}

public fun Byte.toUByte(): UByte = UByte(this.toByte())
public fun Byte.toUShort(): UShort = UShort(this.toShort())
public fun Byte.toUInt(): UInt = UInt(this.toInt())
public fun Byte.toULong(): ULong = ULong(this.toLong())
public fun Short.toUByte(): UByte = UByte(this.toByte())
public fun Short.toUShort(): UShort = UShort(this.toShort())
public fun Short.toUInt(): UInt = UInt(this.toInt())
public fun Short.toULong(): ULong = ULong(this.toLong())
public fun Int.toUByte(): UByte = UByte(this.toByte())
public fun Int.toUShort(): UShort = UShort(this.toShort())
public fun Int.toUInt(): UInt = UInt(this)
public fun Int.toULong(): ULong = ULong(this.toLong())
public fun Long.toUByte(): UByte = UByte(this.toByte())
public fun Long.toUShort(): UShort = UShort(this.toShort())
public fun Long.toUInt(): UInt = UInt(this.toInt())
public fun Long.toULong(): ULong = ULong(this)

public fun Double.toUInt(): UInt = if (this != this || this <= 0.0) UInt(0) else if (this >= 4294967295.0) UInt(-1) else UInt(this.toLong().toInt())
public fun Double.toULong(): ULong = doubleToULong(this)
public fun Float.toUInt(): UInt = toDouble().toUInt()
public fun Float.toULong(): ULong = toDouble().toULong()
public fun Double.toUByte(): UByte = toInt().toUByte()
public fun Double.toUShort(): UShort = toInt().toUShort()

private fun doubleToULong(v: Double): ULong {
    if (v != v || v <= 0.0) return ULong(0L)
    if (v >= 18446744073709551615.0) return ULong(-1L)
    if (v < 9223372036854775807.0) return ULong(v.toLong())
    return ULong((v - 9223372036854775808.0).toLong() + Long.MIN_VALUE)
}

internal fun ulongDivide(dividend: Long, divisor: Long): Long {
    if (divisor < 0) return if ((dividend xor Long.MIN_VALUE) < (divisor xor Long.MIN_VALUE)) 0L else 1L
    if (dividend >= 0) return dividend / divisor
    val quotient = ((dividend ushr 1) / divisor) shl 1
    val rem = dividend - quotient * divisor
    return quotient + (if ((rem xor Long.MIN_VALUE) >= (divisor xor Long.MIN_VALUE)) 1L else 0L)
}

internal fun ulongRemainder(dividend: Long, divisor: Long): Long {
    if (divisor < 0) return if ((dividend xor Long.MIN_VALUE) < (divisor xor Long.MIN_VALUE)) dividend else dividend - divisor
    if (dividend >= 0) return dividend % divisor
    val quotient = ((dividend ushr 1) / divisor) shl 1
    val rem = dividend - quotient * divisor
    return rem - (if ((rem xor Long.MIN_VALUE) >= (divisor xor Long.MIN_VALUE)) divisor else 0L)
}

internal fun ulongToString(v: Long): String {
    if (v >= 0) return v.toString()
    val q = (v ushr 1) / 5L
    val r = v - q * 10L
    return q.toString() + r.toString()
}

internal fun ulongToDouble(v: Long): Double {
    if (v >= 0) return v.toDouble()
    return ((v ushr 1) or (v and 1L)).toDouble() * 2.0
}

public fun String.toUIntOrNull(): UInt? {
    val l = toULongOrNull() ?: return null
    return if (l > UInt.MAX_VALUE.toULong()) null else l.toUInt()
}

public fun String.toULongOrNull(): ULong? {
    if (isEmpty()) return null
    var r = 0L
    for (c in this) {
        if (c < '0' || c > '9') return null
        val d = (c.code - '0'.code).toLong()
        // r * 10 + d must not exceed 2^64 - 1
        if ((r xor Long.MIN_VALUE) > (ulongDivide(-1L, 10L) xor Long.MIN_VALUE)) return null
        val m = r * 10L
        val s = m + d
        if ((s xor Long.MIN_VALUE) < (m xor Long.MIN_VALUE)) return null
        r = s
    }
    return ULong(r)
}

public fun String.toUInt(): UInt = toUIntOrNull() ?: mini.internal.throwNumberFormat("For input string: \"$this\"")
public fun String.toULong(): ULong = toULongOrNull() ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun minOf(a: UInt, b: UInt): UInt = if (a <= b) a else b
public fun maxOf(a: UInt, b: UInt): UInt = if (a >= b) a else b
public fun minOf(a: ULong, b: ULong): ULong = if (a <= b) a else b
public fun maxOf(a: ULong, b: ULong): ULong = if (a >= b) a else b

// ---- arrays -------------------------------------------------------------------------------------------------

public value class UByteArray(internal val storage: ByteArray) {
    public constructor(size: Int) : this(ByteArray(size))
    public constructor(size: Int, init: (Int) -> UByte) : this(ByteArray(size)) {
        for (i in 0 until size) storage[i] = init(i).data
    }
    public val size: Int get() = storage.size
    public operator fun get(index: Int): UByte = UByte(storage[index])
    public operator fun set(index: Int, value: UByte) { storage[index] = value.data }
    public operator fun iterator(): Iterator<UByte> = UByteArrayIterator(storage)
    public fun contentToString(): String {
        val sb = StringBuilder()
        sb.append('[')
        for (i in 0 until size) {
            if (i > 0) sb.append(", ")
            sb.append(this[i].toString())
        }
        sb.append(']')
        return sb.toString()
    }
}

private class UByteArrayIterator(private val array: ByteArray) : Iterator<UByte> {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun next(): UByte = UByte(array[index++])
}

public val UByteArray.indices: IntRange get() = 0 until size
public val UByteArray.lastIndex: Int get() = size - 1

public fun ubyteArrayOf(vararg elements: UByte): UByteArray {
    val a = UByteArray(elements.size)
    for (i in 0 until elements.size) a[i] = elements[i]
    return a
}

public value class UShortArray(internal val storage: ShortArray) {
    public constructor(size: Int) : this(ShortArray(size))
    public constructor(size: Int, init: (Int) -> UShort) : this(ShortArray(size)) {
        for (i in 0 until size) storage[i] = init(i).data
    }
    public val size: Int get() = storage.size
    public operator fun get(index: Int): UShort = UShort(storage[index])
    public operator fun set(index: Int, value: UShort) { storage[index] = value.data }
    public operator fun iterator(): Iterator<UShort> = UShortArrayIterator(storage)
    public fun contentToString(): String {
        val sb = StringBuilder()
        sb.append('[')
        for (i in 0 until size) {
            if (i > 0) sb.append(", ")
            sb.append(this[i].toString())
        }
        sb.append(']')
        return sb.toString()
    }
}

private class UShortArrayIterator(private val array: ShortArray) : Iterator<UShort> {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun next(): UShort = UShort(array[index++])
}

public val UShortArray.indices: IntRange get() = 0 until size
public val UShortArray.lastIndex: Int get() = size - 1

public fun ushortArrayOf(vararg elements: UShort): UShortArray {
    val a = UShortArray(elements.size)
    for (i in 0 until elements.size) a[i] = elements[i]
    return a
}

public value class UIntArray(internal val storage: IntArray) {
    public constructor(size: Int) : this(IntArray(size))
    public constructor(size: Int, init: (Int) -> UInt) : this(IntArray(size)) {
        for (i in 0 until size) storage[i] = init(i).data
    }
    public val size: Int get() = storage.size
    public operator fun get(index: Int): UInt = UInt(storage[index])
    public operator fun set(index: Int, value: UInt) { storage[index] = value.data }
    public operator fun iterator(): Iterator<UInt> = UIntArrayIterator(storage)
    public fun contentToString(): String {
        val sb = StringBuilder()
        sb.append('[')
        for (i in 0 until size) {
            if (i > 0) sb.append(", ")
            sb.append(this[i].toString())
        }
        sb.append(']')
        return sb.toString()
    }
}

private class UIntArrayIterator(private val array: IntArray) : Iterator<UInt> {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun next(): UInt = UInt(array[index++])
}

public val UIntArray.indices: IntRange get() = 0 until size
public val UIntArray.lastIndex: Int get() = size - 1

public fun uintArrayOf(vararg elements: UInt): UIntArray {
    val a = UIntArray(elements.size)
    for (i in 0 until elements.size) a[i] = elements[i]
    return a
}

public value class ULongArray(internal val storage: LongArray) {
    public constructor(size: Int) : this(LongArray(size))
    public constructor(size: Int, init: (Int) -> ULong) : this(LongArray(size)) {
        for (i in 0 until size) storage[i] = init(i).data
    }
    public val size: Int get() = storage.size
    public operator fun get(index: Int): ULong = ULong(storage[index])
    public operator fun set(index: Int, value: ULong) { storage[index] = value.data }
    public operator fun iterator(): Iterator<ULong> = ULongArrayIterator(storage)
    public fun contentToString(): String {
        val sb = StringBuilder()
        sb.append('[')
        for (i in 0 until size) {
            if (i > 0) sb.append(", ")
            sb.append(this[i].toString())
        }
        sb.append(']')
        return sb.toString()
    }
}

private class ULongArrayIterator(private val array: LongArray) : Iterator<ULong> {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun next(): ULong = ULong(array[index++])
}

public val ULongArray.indices: IntRange get() = 0 until size
public val ULongArray.lastIndex: Int get() = size - 1

public fun ulongArrayOf(vararg elements: ULong): ULongArray {
    val a = ULongArray(elements.size)
    for (i in 0 until elements.size) a[i] = elements[i]
    return a
}

// ---- ranges -------------------------------------------------------------------------------------------------

public open class UIntProgression internal constructor(start: UInt, endInclusive: UInt, public val step: Int) : Iterable<UInt> {
    public val first: UInt = start
    public val last: UInt = lastElement(start, endInclusive, step)
    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last
    override fun iterator(): Iterator<UInt> = UIntProgressionIterator(first, last, step)
    override fun equals(other: Any?): Boolean =
        other is UIntProgression && (isEmpty() && other.isEmpty() || first == other.first && last == other.last && step == other.step)
    override fun hashCode(): Int = if (isEmpty()) -1 else 31 * (31 * first.data + last.data) + step
    override fun toString(): String = if (step > 0) "$first..$last step $step" else "$first downTo $last step ${-step}"

    internal companion object {
        fun lastElement(start: UInt, end: UInt, step: Int): UInt {
            if (step > 0) {
                if (start >= end) return end
                val diff = end.toLong() - start.toLong()
                return UInt((start.toLong() + diff - diff % step.toLong()).toInt())
            }
            if (start <= end) return end
            val diff = start.toLong() - end.toLong()
            val s = -step.toLong()
            return UInt((start.toLong() - (diff - diff % s)).toInt())
        }
    }
}

public class UIntRange(start: UInt, endInclusive: UInt) : UIntProgression(start, endInclusive, 1), ClosedRange<UInt> {
    override val start: UInt get() = first
    override val endInclusive: UInt get() = last
    override fun contains(value: UInt): Boolean = value >= first && value <= last
    override fun isEmpty(): Boolean = first > last
    companion object {
        public val EMPTY: UIntRange = UIntRange(UInt(1), UInt(0))
    }
}

private class UIntProgressionIterator(first: UInt, last: UInt, private val step: Int) : Iterator<UInt> {
    private val finalElement: UInt = last
    private var hasNext: Boolean = if (step > 0) first <= last else first >= last
    private var next: UInt = if (hasNext) first else last
    override fun hasNext(): Boolean = hasNext
    override fun next(): UInt {
        val value = next
        if (value == finalElement) {
            if (!hasNext) throw NoSuchElementException()
            hasNext = false
        } else {
            next = UInt(next.data + step)
        }
        return value
    }
}

public infix fun UInt.until(to: UInt): UIntRange = if (to <= UInt.MIN_VALUE) UIntRange.EMPTY else UIntRange(this, UInt(to.data - 1))
public infix fun UInt.downTo(to: UInt): UIntProgression = UIntProgression(this, to, -1)
public infix fun UIntProgression.step(step: Int): UIntProgression {
    if (step <= 0) throw IllegalArgumentException("Step must be positive, was: $step.")
    return UIntProgression(first, last, if (this.step > 0) step else -step)
}
public fun UIntProgression.reversed(): UIntProgression = UIntProgression(last, first, -step)

public open class ULongProgression internal constructor(start: ULong, endInclusive: ULong, public val step: Long) : Iterable<ULong> {
    public val first: ULong = start
    public val last: ULong = endInclusive
    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last
    override fun iterator(): Iterator<ULong> = ULongProgressionIterator(first, last, step)
    override fun equals(other: Any?): Boolean =
        other is ULongProgression && (isEmpty() && other.isEmpty() || first == other.first && last == other.last && step == other.step)
    override fun hashCode(): Int = if (isEmpty()) -1 else (31 * (31 * first.data.hashCode() + last.data.hashCode()) + step.hashCode())
    override fun toString(): String = if (step > 0) "$first..$last step $step" else "$first downTo $last step ${-step}"
}

public class ULongRange(start: ULong, endInclusive: ULong) : ULongProgression(start, endInclusive, 1L), ClosedRange<ULong> {
    override val start: ULong get() = first
    override val endInclusive: ULong get() = last
    override fun contains(value: ULong): Boolean = value >= first && value <= last
    override fun isEmpty(): Boolean = first > last
}

private class ULongProgressionIterator(first: ULong, last: ULong, private val step: Long) : Iterator<ULong> {
    private val finalElement: ULong = last
    private var hasNext: Boolean = if (step > 0) first <= last else first >= last
    private var next: ULong = if (hasNext) first else last
    override fun hasNext(): Boolean = hasNext
    override fun next(): ULong {
        val value = next
        if (value == finalElement) {
            if (!hasNext) throw NoSuchElementException()
            hasNext = false
        } else {
            next = ULong(next.data + step)
        }
        return value
    }
}

public infix fun ULong.until(to: ULong): ULongRange = if (to <= ULong.MIN_VALUE) ULongRange(ULong(1L), ULong(0L)) else ULongRange(this, ULong(to.data - 1L))
public infix fun ULong.downTo(to: ULong): ULongProgression = ULongProgression(this, to, -1L)
