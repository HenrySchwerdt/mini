/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Mixed-type floorDiv / mod of Int and Long (ported from kotlin-stdlib kotlin/util/FloorDivMod.kt).
 */
package kotlin

public inline fun Int.floorDiv(other: Byte): Int = 
    this.floorDiv(other.toInt())

public inline fun Int.floorDiv(other: Short): Int = 
    this.floorDiv(other.toInt())

public inline fun Int.mod(other: Byte): Byte = 
    this.mod(other.toInt()).toByte()

public inline fun Int.mod(other: Short): Short = 
    this.mod(other.toInt()).toShort()

public inline fun Int.mod(other: Long): Long = 
    this.toLong().mod(other)

public inline fun Long.floorDiv(other: Byte): Long = 
    this.floorDiv(other.toLong())

public inline fun Long.floorDiv(other: Short): Long = 
    this.floorDiv(other.toLong())

public inline fun Long.mod(other: Byte): Byte = 
    this.mod(other.toLong()).toByte()

public inline fun Long.mod(other: Short): Short = 
    this.mod(other.toLong()).toShort()
