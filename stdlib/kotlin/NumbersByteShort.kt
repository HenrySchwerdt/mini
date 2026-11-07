/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Byte and Short bit helpers (kotlin-stdlib util/Numbers.kt).
 */
package kotlin

public inline fun Byte.takeHighestOneBit(): Byte = (toInt() and 0xFF).takeHighestOneBit().toByte()

public inline fun Byte.takeLowestOneBit(): Byte = toInt().takeLowestOneBit().toByte()

public fun Byte.rotateLeft(bitCount: Int): Byte =
    (toInt().shl(bitCount and 7) or (toInt() and 0xFF).ushr(8 - (bitCount and 7))).toByte()

public fun Byte.rotateRight(bitCount: Int): Byte =
    (toInt().shl(8 - (bitCount and 7)) or (toInt() and 0xFF).ushr(bitCount and 7)).toByte()

public inline fun Short.takeHighestOneBit(): Short = (toInt() and 0xFFFF).takeHighestOneBit().toShort()

public inline fun Short.takeLowestOneBit(): Short = toInt().takeLowestOneBit().toShort()

public fun Short.rotateLeft(bitCount: Int): Short =
    (toInt().shl(bitCount and 15) or (toInt() and 0xFFFF).ushr(16 - (bitCount and 15))).toShort()

public fun Short.rotateRight(bitCount: Int): Short =
    (toInt().shl(16 - (bitCount and 15)) or (toInt() and 0xFFFF).ushr(bitCount and 15)).toShort()
