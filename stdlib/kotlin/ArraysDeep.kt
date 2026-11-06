/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Reimplemented after kotlin-stdlib collections/Arrays.kt: the `contentDeep*` functions of nested arrays.
 */
package kotlin

private fun deepElementToString(e: Any?): String = when (e) {
    is Array<*> -> e.contentDeepToString()
    is IntArray -> e.contentToString()
    is LongArray -> e.contentToString()
    is DoubleArray -> e.contentToString()
    is FloatArray -> e.contentToString()
    is ByteArray -> e.contentToString()
    is ShortArray -> e.contentToString()
    is CharArray -> e.contentToString()
    is BooleanArray -> e.contentToString()
    else -> e.toString()
}

public fun <T> Array<out T>.contentDeepToString(): String {
    val sb = StringBuilder()
    sb.append('[')
    for (i in 0 until size) {
        if (i > 0) sb.append(", ")
        sb.append(deepElementToString(this[i]))
    }
    sb.append(']')
    return sb.toString()
}

private fun deepEq(a: Any?, b: Any?): Boolean {
    if (a is Array<*> && b is Array<*>) return a.contentDeepEquals(b)
    if (a is IntArray && b is IntArray) return a.contentEquals(b)
    if (a is LongArray && b is LongArray) return a.contentEquals(b)
    if (a is DoubleArray && b is DoubleArray) return a.contentEquals(b)
    if (a is FloatArray && b is FloatArray) return a.contentEquals(b)
    if (a is ByteArray && b is ByteArray) return a.contentEquals(b)
    if (a is ShortArray && b is ShortArray) return a.contentEquals(b)
    if (a is CharArray && b is CharArray) return a.contentEquals(b)
    if (a is BooleanArray && b is BooleanArray) return a.contentEquals(b)
    return a == b
}

public infix fun <T> Array<out T>.contentDeepEquals(other: Array<out T>): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in 0 until size) if (!deepEq(this[i], other[i])) return false
    return true
}

private fun deepHash(e: Any?): Int = when (e) {
    is Array<*> -> e.contentDeepHashCode()
    is IntArray -> e.contentHashCode()
    is LongArray -> e.contentHashCode()
    is DoubleArray -> e.contentHashCode()
    is FloatArray -> e.contentHashCode()
    is ByteArray -> e.contentHashCode()
    is ShortArray -> e.contentHashCode()
    is CharArray -> e.contentHashCode()
    is BooleanArray -> e.contentHashCode()
    else -> e?.hashCode() ?: 0
}

public fun <T> Array<out T>.contentDeepHashCode(): Int {
    var result = 1
    for (i in 0 until size) result = 31 * result + deepHash(this[i])
    return result
}

// ---- contentEquals / contentHashCode ----------------------------------------------------------------

public infix fun <T> Array<out T>.contentEquals(other: Array<out T>): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in 0 until size) if (this[i] != other[i]) return false
    return true
}

public fun <T> Array<out T>.contentHashCode(): Int {
    var result = 1
    for (i in 0 until size) result = 31 * result + (this[i]?.hashCode() ?: 0)
    return result
}

public infix fun IntArray.contentEquals(other: IntArray): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in 0 until size) if (this[i] != other[i]) return false
    return true
}

public fun IntArray.contentHashCode(): Int {
    var result = 1
    for (e in this) result = 31 * result + e
    return result
}

public infix fun LongArray.contentEquals(other: LongArray): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in 0 until size) if (this[i] != other[i]) return false
    return true
}

public fun LongArray.contentHashCode(): Int {
    var result = 1
    for (e in this) result = 31 * result + (e xor (e ushr 32)).toInt()
    return result
}

public infix fun DoubleArray.contentEquals(other: DoubleArray): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in 0 until size) if (this[i] != other[i]) return false
    return true
}

public fun DoubleArray.contentHashCode(): Int {
    var result = 1
    for (e in this) result = 31 * result + e.hashCode()
    return result
}

public infix fun FloatArray.contentEquals(other: FloatArray): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in 0 until size) if (this[i] != other[i]) return false
    return true
}

public fun FloatArray.contentHashCode(): Int {
    var result = 1
    for (e in this) result = 31 * result + e.hashCode()
    return result
}

public infix fun ByteArray.contentEquals(other: ByteArray): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in 0 until size) if (this[i] != other[i]) return false
    return true
}

public fun ByteArray.contentHashCode(): Int {
    var result = 1
    for (e in this) result = 31 * result + e.toInt()
    return result
}

public infix fun ShortArray.contentEquals(other: ShortArray): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in 0 until size) if (this[i] != other[i]) return false
    return true
}

public fun ShortArray.contentHashCode(): Int {
    var result = 1
    for (e in this) result = 31 * result + e.toInt()
    return result
}

public infix fun CharArray.contentEquals(other: CharArray): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in 0 until size) if (this[i] != other[i]) return false
    return true
}

public fun CharArray.contentHashCode(): Int {
    var result = 1
    for (e in this) result = 31 * result + e.code
    return result
}

public infix fun BooleanArray.contentEquals(other: BooleanArray): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in 0 until size) if (this[i] != other[i]) return false
    return true
}

public fun BooleanArray.contentHashCode(): Int {
    var result = 1
    for (e in this) result = 31 * result + (if (e) 1231 else 1237)
    return result
}
