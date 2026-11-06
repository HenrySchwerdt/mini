/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib util/Numbers.kt (floating point classification).
 */
package kotlin

public fun Double.isNaN(): Boolean = this != this

public fun Double.isInfinite(): Boolean = this == Double.POSITIVE_INFINITY || this == Double.NEGATIVE_INFINITY

public fun Double.isFinite(): Boolean = !isInfinite() && !isNaN()

public fun Float.isNaN(): Boolean = this != this

public fun Float.isInfinite(): Boolean = this == Float.POSITIVE_INFINITY || this == Float.NEGATIVE_INFINITY

public fun Float.isFinite(): Boolean = !isInfinite() && !isNaN()

public fun Char.toByte(): Byte = this.code.toByte()

public fun Char.toShort(): Short = this.code.toShort()

public fun Float.mod(other: Float): Float {
    val r = this % other
    return if (r != 0.0f && (r < 0.0f) != (other < 0.0f)) r + other else r
}

public fun Float.mod(other: Double): Double = this.toDouble().mod(other)

public fun Double.mod(other: Float): Double = this.mod(other.toDouble())

public infix fun Byte.and(other: Byte): Byte = (this.toInt() and other.toInt()).toByte()
public infix fun Byte.or(other: Byte): Byte = (this.toInt() or other.toInt()).toByte()
public infix fun Byte.xor(other: Byte): Byte = (this.toInt() xor other.toInt()).toByte()
public fun Byte.inv(): Byte = this.toInt().inv().toByte()

public infix fun Short.and(other: Short): Short = (this.toInt() and other.toInt()).toShort()
public infix fun Short.or(other: Short): Short = (this.toInt() or other.toInt()).toShort()
public infix fun Short.xor(other: Short): Short = (this.toInt() xor other.toInt()).toShort()
public fun Short.inv(): Short = this.toInt().inv().toShort()

public fun Char.toFloat(): Float = this.code.toFloat()
