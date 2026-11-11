/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Byte and Short floorDiv / mod (kotlin-stdlib util/FloorDivMod.kt).
 */
package kotlin

public inline fun Byte.floorDiv(other: Byte): Int = 
    this.toInt().floorDiv(other.toInt())

public inline fun Byte.mod(other: Byte): Byte = 
    this.toInt().mod(other.toInt()).toByte()

public inline fun Byte.floorDiv(other: Short): Int = 
    this.toInt().floorDiv(other.toInt())

public inline fun Byte.mod(other: Short): Short = 
    this.toInt().mod(other.toInt()).toShort()

public inline fun Byte.floorDiv(other: Int): Int = 
    this.toInt().floorDiv(other)

public inline fun Byte.mod(other: Int): Int = 
    this.toInt().mod(other)

public inline fun Byte.floorDiv(other: Long): Long = 
    this.toLong().floorDiv(other)

public inline fun Byte.mod(other: Long): Long = 
    this.toLong().mod(other)

public inline fun Short.floorDiv(other: Byte): Int = 
    this.toInt().floorDiv(other.toInt())

public inline fun Short.mod(other: Byte): Byte = 
    this.toInt().mod(other.toInt()).toByte()

public inline fun Short.floorDiv(other: Short): Int = 
    this.toInt().floorDiv(other.toInt())

public inline fun Short.mod(other: Short): Short = 
    this.toInt().mod(other.toInt()).toShort()

public inline fun Short.floorDiv(other: Int): Int = 
    this.toInt().floorDiv(other)

public inline fun Short.mod(other: Int): Int = 
    this.toInt().mod(other)

public inline fun Short.floorDiv(other: Long): Long = 
    this.toLong().floorDiv(other)

public inline fun Short.mod(other: Long): Long = 
    this.toLong().mod(other)
