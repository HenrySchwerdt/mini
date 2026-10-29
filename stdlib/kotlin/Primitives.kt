/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 *
 * Kotlin/Mini builtin primitive types. The members have no bodies: the compiler maps calls to
 * them onto native operations (see docs/stdlib.md, "Builtins"). Derived from the Kotlin
 * standard library's Primitives.kt (jvmMain), reduced to what the Mini backend supports.
 */
@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT", "INAPPLICABLE_OPERATOR_MODIFIER")
@file:kotlin.internal.Builtin

package kotlin

import kotlin.ranges.*

public class Byte private constructor() : Number(), Comparable<Byte> {
    public companion object {
        public const val MIN_VALUE: Byte = -128
        public const val MAX_VALUE: Byte = 127
    }
    public override operator fun compareTo(other: Byte): Int
    public operator fun compareTo(other: Short): Int
    public operator fun compareTo(other: Int): Int
    public operator fun compareTo(other: Long): Int
    public operator fun compareTo(other: Float): Int
    public operator fun compareTo(other: Double): Int
    public operator fun plus(other: Byte): Int
    public operator fun plus(other: Short): Int
    public operator fun plus(other: Int): Int
    public operator fun plus(other: Long): Long
    public operator fun plus(other: Float): Float
    public operator fun plus(other: Double): Double
    public operator fun minus(other: Byte): Int
    public operator fun minus(other: Short): Int
    public operator fun minus(other: Int): Int
    public operator fun minus(other: Long): Long
    public operator fun minus(other: Float): Float
    public operator fun minus(other: Double): Double
    public operator fun times(other: Byte): Int
    public operator fun times(other: Short): Int
    public operator fun times(other: Int): Int
    public operator fun times(other: Long): Long
    public operator fun times(other: Float): Float
    public operator fun times(other: Double): Double
    public operator fun div(other: Byte): Int
    public operator fun div(other: Short): Int
    public operator fun div(other: Int): Int
    public operator fun div(other: Long): Long
    public operator fun div(other: Float): Float
    public operator fun div(other: Double): Double
    public operator fun rem(other: Byte): Int
    public operator fun rem(other: Short): Int
    public operator fun rem(other: Int): Int
    public operator fun rem(other: Long): Long
    public operator fun rem(other: Float): Float
    public operator fun rem(other: Double): Double
    public operator fun inc(): Byte
    public operator fun dec(): Byte
    public operator fun unaryPlus(): Int
    public operator fun unaryMinus(): Int
    public operator fun rangeTo(other: Byte): IntRange
    public operator fun rangeTo(other: Short): IntRange
    public operator fun rangeTo(other: Int): IntRange
    public operator fun rangeTo(other: Long): LongRange
    public override fun toByte(): Byte
    public override fun toShort(): Short
    public override fun toInt(): Int
    public override fun toLong(): Long
    public override fun toFloat(): Float
    public override fun toDouble(): Double
    public override fun toChar(): Char
    public override fun toString(): String
    public override fun equals(other: Any?): Boolean
    public override fun hashCode(): Int
}

public class Short private constructor() : Number(), Comparable<Short> {
    public companion object {
        public const val MIN_VALUE: Short = -32768
        public const val MAX_VALUE: Short = 32767
    }
    public operator fun compareTo(other: Byte): Int
    public override operator fun compareTo(other: Short): Int
    public operator fun compareTo(other: Int): Int
    public operator fun compareTo(other: Long): Int
    public operator fun compareTo(other: Float): Int
    public operator fun compareTo(other: Double): Int
    public operator fun plus(other: Byte): Int
    public operator fun plus(other: Short): Int
    public operator fun plus(other: Int): Int
    public operator fun plus(other: Long): Long
    public operator fun plus(other: Float): Float
    public operator fun plus(other: Double): Double
    public operator fun minus(other: Byte): Int
    public operator fun minus(other: Short): Int
    public operator fun minus(other: Int): Int
    public operator fun minus(other: Long): Long
    public operator fun minus(other: Float): Float
    public operator fun minus(other: Double): Double
    public operator fun times(other: Byte): Int
    public operator fun times(other: Short): Int
    public operator fun times(other: Int): Int
    public operator fun times(other: Long): Long
    public operator fun times(other: Float): Float
    public operator fun times(other: Double): Double
    public operator fun div(other: Byte): Int
    public operator fun div(other: Short): Int
    public operator fun div(other: Int): Int
    public operator fun div(other: Long): Long
    public operator fun div(other: Float): Float
    public operator fun div(other: Double): Double
    public operator fun rem(other: Byte): Int
    public operator fun rem(other: Short): Int
    public operator fun rem(other: Int): Int
    public operator fun rem(other: Long): Long
    public operator fun rem(other: Float): Float
    public operator fun rem(other: Double): Double
    public operator fun inc(): Short
    public operator fun dec(): Short
    public operator fun unaryPlus(): Int
    public operator fun unaryMinus(): Int
    public operator fun rangeTo(other: Byte): IntRange
    public operator fun rangeTo(other: Short): IntRange
    public operator fun rangeTo(other: Int): IntRange
    public operator fun rangeTo(other: Long): LongRange
    public override fun toByte(): Byte
    public override fun toShort(): Short
    public override fun toInt(): Int
    public override fun toLong(): Long
    public override fun toFloat(): Float
    public override fun toDouble(): Double
    public override fun toChar(): Char
    public override fun toString(): String
    public override fun equals(other: Any?): Boolean
    public override fun hashCode(): Int
}

public class Int private constructor() : Number(), Comparable<Int> {
    public companion object {
        public const val MIN_VALUE: Int = -2147483647 - 1
        public const val MAX_VALUE: Int = 2147483647
    }
    public operator fun compareTo(other: Byte): Int
    public operator fun compareTo(other: Short): Int
    public override operator fun compareTo(other: Int): Int
    public operator fun compareTo(other: Long): Int
    public operator fun compareTo(other: Float): Int
    public operator fun compareTo(other: Double): Int
    public operator fun plus(other: Byte): Int
    public operator fun plus(other: Short): Int
    public operator fun plus(other: Int): Int
    public operator fun plus(other: Long): Long
    public operator fun plus(other: Float): Float
    public operator fun plus(other: Double): Double
    public operator fun minus(other: Byte): Int
    public operator fun minus(other: Short): Int
    public operator fun minus(other: Int): Int
    public operator fun minus(other: Long): Long
    public operator fun minus(other: Float): Float
    public operator fun minus(other: Double): Double
    public operator fun times(other: Byte): Int
    public operator fun times(other: Short): Int
    public operator fun times(other: Int): Int
    public operator fun times(other: Long): Long
    public operator fun times(other: Float): Float
    public operator fun times(other: Double): Double
    public operator fun div(other: Byte): Int
    public operator fun div(other: Short): Int
    public operator fun div(other: Int): Int
    public operator fun div(other: Long): Long
    public operator fun div(other: Float): Float
    public operator fun div(other: Double): Double
    public operator fun rem(other: Byte): Int
    public operator fun rem(other: Short): Int
    public operator fun rem(other: Int): Int
    public operator fun rem(other: Long): Long
    public operator fun rem(other: Float): Float
    public operator fun rem(other: Double): Double
    public operator fun inc(): Int
    public operator fun dec(): Int
    public operator fun unaryPlus(): Int
    public operator fun unaryMinus(): Int
    public operator fun rangeTo(other: Byte): IntRange
    public operator fun rangeTo(other: Short): IntRange
    public operator fun rangeTo(other: Int): IntRange
    public operator fun rangeTo(other: Long): LongRange
    public infix fun shl(bitCount: Int): Int
    public infix fun shr(bitCount: Int): Int
    public infix fun ushr(bitCount: Int): Int
    public infix fun and(other: Int): Int
    public infix fun or(other: Int): Int
    public infix fun xor(other: Int): Int
    public fun inv(): Int
    public override fun toByte(): Byte
    public override fun toShort(): Short
    public override fun toInt(): Int
    public override fun toLong(): Long
    public override fun toFloat(): Float
    public override fun toDouble(): Double
    public override fun toChar(): Char
    public override fun toString(): String
    public override fun equals(other: Any?): Boolean
    public override fun hashCode(): Int
}

public class Long private constructor() : Number(), Comparable<Long> {
    public companion object {
        public const val MIN_VALUE: Long = -9223372036854775807L - 1L
        public const val MAX_VALUE: Long = 9223372036854775807L
    }
    public operator fun compareTo(other: Byte): Int
    public operator fun compareTo(other: Short): Int
    public operator fun compareTo(other: Int): Int
    public override operator fun compareTo(other: Long): Int
    public operator fun compareTo(other: Float): Int
    public operator fun compareTo(other: Double): Int
    public operator fun plus(other: Byte): Long
    public operator fun plus(other: Short): Long
    public operator fun plus(other: Int): Long
    public operator fun plus(other: Long): Long
    public operator fun plus(other: Float): Float
    public operator fun plus(other: Double): Double
    public operator fun minus(other: Byte): Long
    public operator fun minus(other: Short): Long
    public operator fun minus(other: Int): Long
    public operator fun minus(other: Long): Long
    public operator fun minus(other: Float): Float
    public operator fun minus(other: Double): Double
    public operator fun times(other: Byte): Long
    public operator fun times(other: Short): Long
    public operator fun times(other: Int): Long
    public operator fun times(other: Long): Long
    public operator fun times(other: Float): Float
    public operator fun times(other: Double): Double
    public operator fun div(other: Byte): Long
    public operator fun div(other: Short): Long
    public operator fun div(other: Int): Long
    public operator fun div(other: Long): Long
    public operator fun div(other: Float): Float
    public operator fun div(other: Double): Double
    public operator fun rem(other: Byte): Long
    public operator fun rem(other: Short): Long
    public operator fun rem(other: Int): Long
    public operator fun rem(other: Long): Long
    public operator fun rem(other: Float): Float
    public operator fun rem(other: Double): Double
    public operator fun inc(): Long
    public operator fun dec(): Long
    public operator fun unaryPlus(): Long
    public operator fun unaryMinus(): Long
    public operator fun rangeTo(other: Byte): LongRange
    public operator fun rangeTo(other: Short): LongRange
    public operator fun rangeTo(other: Int): LongRange
    public operator fun rangeTo(other: Long): LongRange
    public infix fun shl(bitCount: Int): Long
    public infix fun shr(bitCount: Int): Long
    public infix fun ushr(bitCount: Int): Long
    public infix fun and(other: Long): Long
    public infix fun or(other: Long): Long
    public infix fun xor(other: Long): Long
    public fun inv(): Long
    public override fun toByte(): Byte
    public override fun toShort(): Short
    public override fun toInt(): Int
    public override fun toLong(): Long
    public override fun toFloat(): Float
    public override fun toDouble(): Double
    public override fun toChar(): Char
    public override fun toString(): String
    public override fun equals(other: Any?): Boolean
    public override fun hashCode(): Int
}

public class Float private constructor() : Number(), Comparable<Float> {
    public companion object {
        public const val MIN_VALUE: Float = 1.4E-45F
        public const val MAX_VALUE: Float = 3.4028235E38F
        public const val SIZE_BYTES: Int = 4
        public const val SIZE_BITS: Int = 32
        public const val NaN: Float = 0.0f / 0.0f
        public const val POSITIVE_INFINITY: Float = 1.0f / 0.0f
        public const val NEGATIVE_INFINITY: Float = -1.0f / 0.0f
    }
    public operator fun compareTo(other: Byte): Int
    public operator fun compareTo(other: Short): Int
    public operator fun compareTo(other: Int): Int
    public operator fun compareTo(other: Long): Int
    public override operator fun compareTo(other: Float): Int
    public operator fun compareTo(other: Double): Int
    public operator fun plus(other: Byte): Float
    public operator fun plus(other: Short): Float
    public operator fun plus(other: Int): Float
    public operator fun plus(other: Long): Float
    public operator fun plus(other: Float): Float
    public operator fun plus(other: Double): Double
    public operator fun minus(other: Byte): Float
    public operator fun minus(other: Short): Float
    public operator fun minus(other: Int): Float
    public operator fun minus(other: Long): Float
    public operator fun minus(other: Float): Float
    public operator fun minus(other: Double): Double
    public operator fun times(other: Byte): Float
    public operator fun times(other: Short): Float
    public operator fun times(other: Int): Float
    public operator fun times(other: Long): Float
    public operator fun times(other: Float): Float
    public operator fun times(other: Double): Double
    public operator fun div(other: Byte): Float
    public operator fun div(other: Short): Float
    public operator fun div(other: Int): Float
    public operator fun div(other: Long): Float
    public operator fun div(other: Float): Float
    public operator fun div(other: Double): Double
    public operator fun rem(other: Byte): Float
    public operator fun rem(other: Short): Float
    public operator fun rem(other: Int): Float
    public operator fun rem(other: Long): Float
    public operator fun rem(other: Float): Float
    public operator fun rem(other: Double): Double
    public operator fun inc(): Float
    public operator fun dec(): Float
    public operator fun unaryPlus(): Float
    public operator fun unaryMinus(): Float
    public override fun toByte(): Byte
    public override fun toShort(): Short
    public override fun toInt(): Int
    public override fun toLong(): Long
    public override fun toFloat(): Float
    public override fun toDouble(): Double
    public override fun toChar(): Char
    public override fun toString(): String
    public override fun equals(other: Any?): Boolean
    public override fun hashCode(): Int
}

public class Double private constructor() : Number(), Comparable<Double> {
    public companion object {
        public const val MIN_VALUE: Double = 4.9E-324
        public const val MAX_VALUE: Double = 1.7976931348623157E308
        public const val SIZE_BYTES: Int = 8
        public const val SIZE_BITS: Int = 64
        public const val NaN: Double = 0.0 / 0.0
        public const val POSITIVE_INFINITY: Double = 1.0 / 0.0
        public const val NEGATIVE_INFINITY: Double = -1.0 / 0.0
    }
    public operator fun compareTo(other: Byte): Int
    public operator fun compareTo(other: Short): Int
    public operator fun compareTo(other: Int): Int
    public operator fun compareTo(other: Long): Int
    public operator fun compareTo(other: Float): Int
    public override operator fun compareTo(other: Double): Int
    public operator fun plus(other: Byte): Double
    public operator fun plus(other: Short): Double
    public operator fun plus(other: Int): Double
    public operator fun plus(other: Long): Double
    public operator fun plus(other: Float): Double
    public operator fun plus(other: Double): Double
    public operator fun minus(other: Byte): Double
    public operator fun minus(other: Short): Double
    public operator fun minus(other: Int): Double
    public operator fun minus(other: Long): Double
    public operator fun minus(other: Float): Double
    public operator fun minus(other: Double): Double
    public operator fun times(other: Byte): Double
    public operator fun times(other: Short): Double
    public operator fun times(other: Int): Double
    public operator fun times(other: Long): Double
    public operator fun times(other: Float): Double
    public operator fun times(other: Double): Double
    public operator fun div(other: Byte): Double
    public operator fun div(other: Short): Double
    public operator fun div(other: Int): Double
    public operator fun div(other: Long): Double
    public operator fun div(other: Float): Double
    public operator fun div(other: Double): Double
    public operator fun rem(other: Byte): Double
    public operator fun rem(other: Short): Double
    public operator fun rem(other: Int): Double
    public operator fun rem(other: Long): Double
    public operator fun rem(other: Float): Double
    public operator fun rem(other: Double): Double
    public operator fun inc(): Double
    public operator fun dec(): Double
    public operator fun unaryPlus(): Double
    public operator fun unaryMinus(): Double
    public override fun toByte(): Byte
    public override fun toShort(): Short
    public override fun toInt(): Int
    public override fun toLong(): Long
    public override fun toFloat(): Float
    public override fun toDouble(): Double
    public override fun toChar(): Char
    public override fun toString(): String
    public override fun equals(other: Any?): Boolean
    public override fun hashCode(): Int
}
