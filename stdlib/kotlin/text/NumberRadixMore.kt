/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * `toString(radix)` of the remaining integer types.
 */
package kotlin.text

public fun Byte.toString(radix: Int): String = this.toInt().toString(radix)

public fun Short.toString(radix: Int): String = this.toInt().toString(radix)

public fun UByte.toString(radix: Int): String = this.toInt().toString(radix)

public fun UShort.toString(radix: Int): String = this.toInt().toString(radix)

public fun UInt.toString(radix: Int): String = this.toLong().toString(radix)

public fun ULong.toString(radix: Int): String {
    val signed = this.toLong()
    if (signed >= 0L) return signed.toString(radix)
    val r = radix.toULong()
    val q = this / r
    val rem = (this - q * r).toInt()
    val digit = if (rem < 10) ('0'.code + rem).toChar() else ('a'.code + rem - 10).toChar()
    return q.toString(radix) + digit
}
