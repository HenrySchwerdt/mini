/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 *
 * Kotlin/Mini builtins: the declarations the compiler itself knows (kotlin.Any, kotlin.Int, ...).
 * Members have no bodies; the Mini backend maps calls to them onto native operations
 * (see docs/stdlib.md, "Builtins"). Files marked @file:kotlin.internal.Builtin are never
 * converted to C themselves. Derived from the Kotlin standard library's builtin sources
 * (Any.kt, Nothing.kt, Unit.kt, Boolean.kt, Char.kt, String.kt, Enum.kt, Array.kt, Number.kt).
 */
@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT", "INAPPLICABLE_OPERATOR_MODIFIER", "NON_MEMBER_FUNCTION_NO_BODY")
@file:kotlin.internal.Builtin

package kotlin

import mini.internal.CSymbol

public open class Any {
    public open operator fun equals(other: Any?): Boolean
    public open fun hashCode(): Int
    public open fun toString(): String
}

public interface Annotation

public class Nothing private constructor()

public object Unit {
    override fun toString(): String = "kotlin.Unit"
}

public class Boolean private constructor() : Comparable<Boolean> {
    public operator fun not(): Boolean
    public infix fun and(other: Boolean): Boolean
    public infix fun or(other: Boolean): Boolean
    public infix fun xor(other: Boolean): Boolean
    public override fun compareTo(other: Boolean): Int
    public override fun toString(): String
    public override fun equals(other: Any?): Boolean
    public override fun hashCode(): Int
}

public abstract class Number {
    public abstract fun toDouble(): Double
    public abstract fun toFloat(): Float
    public abstract fun toLong(): Long
    public abstract fun toInt(): Int
    public abstract fun toChar(): Char
    public abstract fun toShort(): Short
    public abstract fun toByte(): Byte
}

public class Char private constructor() : Comparable<Char> {
    public companion object {
        public const val MIN_VALUE: Char = '\u0000'
        public const val MAX_VALUE: Char = '￿'
    }

    public override fun compareTo(other: Char): Int
    public operator fun plus(other: Int): Char
    public operator fun minus(other: Char): Int
    public operator fun minus(other: Int): Char
    public operator fun inc(): Char
    public operator fun dec(): Char
    public operator fun rangeTo(other: Char): kotlin.ranges.CharRange
    public fun toInt(): Int
    public fun toLong(): Long
    public fun toDouble(): Double
    public override fun toString(): String
    public override fun equals(other: Any?): Boolean
    public override fun hashCode(): Int
}

public interface CharSequence {
    public val length: Int
    public operator fun get(index: Int): Char
    public fun subSequence(startIndex: Int, endIndex: Int): CharSequence
}

public interface Comparable<in T> {
    public operator fun compareTo(other: T): Int
}

/**
 * The String members are implemented by the runtime (libminirt); see `mini.internal.CSymbol`.
 */
public class String : Comparable<String>, CharSequence {
    public companion object {}

    public operator fun plus(other: Any?): String

    @CSymbol("mk_string_length")
    public override val length: Int

    @CSymbol("mk_string_char_at")
    public override fun get(index: Int): Char

    public override fun subSequence(startIndex: Int, endIndex: Int): CharSequence

    @CSymbol("mk_string_compare")
    public override fun compareTo(other: String): Int

    public override fun equals(other: Any?): Boolean

    public override fun hashCode(): Int

    public override fun toString(): String
}

public abstract class Enum<E : Enum<E>> protected constructor() : Comparable<E> {
    public final val name: String
    public final val ordinal: Int
    public final override fun compareTo(other: E): Int
    public final override fun equals(other: Any?): Boolean
    public final override fun hashCode(): Int
    public override fun toString(): String
}

/** Fixed-size array. Mini has no JVM array classes; `Array<T>` is a runtime object (see docs/stdlib.md). */
public class Array<T> {
    public constructor(size: Int, init: (Int) -> T)
    public operator fun get(index: Int): T
    public operator fun set(index: Int, value: T): Unit
    public val size: Int
    public operator fun iterator(): Iterator<T>
}

public interface Function<out R>

public val Char.code: Int
    get() = this.toInt()

public fun Any?.toString(): String
