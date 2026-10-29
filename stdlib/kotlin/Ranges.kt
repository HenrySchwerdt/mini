/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 *
 * Builtin range/progression types. `for (i in a..b / until / downTo / step)` over Int/Long/Char is
 * pattern-matched by the Mini backend and never calls these members (docs/stdlib.md).
 */
@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT", "NON_MEMBER_FUNCTION_NO_BODY")
@file:kotlin.internal.Builtin

package kotlin.ranges

public interface ClosedRange<T : Comparable<T>> {
    public val start: T
    public val endInclusive: T
    public operator fun contains(value: T): Boolean
    public open fun isEmpty(): Boolean
}

public open class IntProgression {
    public val first: Int
    public val last: Int
    public val step: Int
    public operator fun iterator(): kotlin.collections.IntIterator
    public open fun isEmpty(): Boolean
}

public class IntRange : IntProgression(), ClosedRange<Int> {
    override val start: Int
    override val endInclusive: Int
    override fun contains(value: Int): Boolean
    override fun isEmpty(): Boolean
}

public open class LongProgression {
    public val first: Long
    public val last: Long
    public val step: Long
    public operator fun iterator(): kotlin.collections.LongIterator
    public open fun isEmpty(): Boolean
}

public class LongRange : LongProgression(), ClosedRange<Long> {
    override val start: Long
    override val endInclusive: Long
    override fun contains(value: Long): Boolean
    override fun isEmpty(): Boolean
}

public open class CharProgression {
    public val first: Char
    public val last: Char
    public val step: Int
    public operator fun iterator(): kotlin.collections.CharIterator
    public open fun isEmpty(): Boolean
}

public class CharRange : CharProgression(), ClosedRange<Char> {
    override val start: Char
    override val endInclusive: Char
    override fun contains(value: Char): Boolean
    override fun isEmpty(): Boolean
}

public infix fun Int.downTo(to: Int): IntProgression
public infix fun Long.downTo(to: Long): LongProgression
public infix fun Char.downTo(to: Char): CharProgression
public infix fun Int.until(to: Int): IntRange
public infix fun Long.until(to: Long): LongRange
public infix fun Char.until(to: Char): CharRange
public infix fun IntProgression.step(step: Int): IntProgression
public infix fun LongProgression.step(step: Long): LongProgression
public infix fun CharProgression.step(step: Int): CharProgression
