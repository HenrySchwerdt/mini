/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 * Reimplemented after kotlin-stdlib text/StringNumberConversions.kt (same accepted syntax and exceptions).
 */
package kotlin.text

import mini.internal.stringIsDouble
import mini.internal.stringParseDouble

public fun String.toIntOrNull(): Int? = toIntOrNull(10)

public fun String.toIntOrNull(radix: Int): Int? {
    val l = toLongOrNull(radix) ?: return null
    if (l < Int.MIN_VALUE.toLong() || l > Int.MAX_VALUE.toLong()) return null
    return l.toInt()
}

public fun String.toLongOrNull(): Long? = toLongOrNull(10)

public fun String.toLongOrNull(radix: Int): Long? {
    val length = this.length
    if (length == 0) return null
    var i = 0
    var negative = false
    val first = this[0]
    if (first == '-') {
        negative = true
        i = 1
    } else if (first == '+') {
        i = 1
    }
    if (i == length) return null
    // accumulate negatively so that Long.MIN_VALUE is representable
    var result = 0L
    val limit = if (negative) Long.MIN_VALUE else -Long.MAX_VALUE
    val multMin = limit / radix
    while (i < length) {
        val c = this[i]
        val digit = if (c in '0'..'9') c.code - '0'.code
        else if (c in 'a'..'z') c.code - 'a'.code + 10
        else if (c in 'A'..'Z') c.code - 'A'.code + 10
        else -1
        if (digit < 0 || digit >= radix) return null
        if (result < multMin) return null
        result *= radix
        if (result < limit + digit) return null
        result -= digit
        i++
    }
    return if (negative) result else -result
}

public fun String.toInt(): Int = toIntOrNull() ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toInt(radix: Int): Int = toIntOrNull(radix) ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toLong(): Long = toLongOrNull() ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toDoubleOrNull(): Double? = if (stringIsDouble(this)) stringParseDouble(this) else null

public fun String.toDouble(): Double = toDoubleOrNull() ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toBoolean(): Boolean = this.lowercase() == "true"

public fun String.toBooleanStrictOrNull(): Boolean? = if (this == "true") true else if (this == "false") false else null

public fun Int.toString(radix: Int): String = toLong().toString(radix)

public fun Long.toString(radix: Int): String {
    if (radix == 10) return this.toString()
    if (this == 0L) return "0"
    val negative = this < 0
    val sb = StringBuilder()
    var v = this
    // work with negative values so that Long.MIN_VALUE works
    if (v > 0) v = -v
    while (v != 0L) {
        val d = -(v % radix).toInt()
        sb.append(if (d < 10) ('0'.code + d).toChar() else ('a'.code + d - 10).toChar())
        v /= radix
    }
    if (negative) sb.append('-')
    return sb.reverse().toString()
}
