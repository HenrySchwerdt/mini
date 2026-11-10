/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin.experimental: bitwise operations on Byte and Short (`import kotlin.experimental.and`).
 */
package kotlin.experimental

public infix fun Byte.and(other: Byte): Byte = (this.toInt() and other.toInt()).toByte()

public infix fun Byte.or(other: Byte): Byte = (this.toInt() or other.toInt()).toByte()

public infix fun Byte.xor(other: Byte): Byte = (this.toInt() xor other.toInt()).toByte()

public fun Byte.inv(): Byte = (this.toInt().inv()).toByte()

public infix fun Short.and(other: Short): Short = (this.toInt() and other.toInt()).toShort()

public infix fun Short.or(other: Short): Short = (this.toInt() or other.toInt()).toShort()

public infix fun Short.xor(other: Short): Short = (this.toInt() xor other.toInt()).toShort()

public fun Short.inv(): Short = (this.toInt().inv()).toShort()
