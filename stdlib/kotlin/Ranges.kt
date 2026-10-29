/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 *
 * Ranges and progressions, ported from kotlin-stdlib ranges/{Range,Progressions,PrimitiveRanges,
 * ProgressionIterators}.kt and the `until`/`downTo`/`step`/`reversed` functions of generated/_Ranges.kt.
 * They are ordinary classes: `for (i in a..b / until / downTo / step)` over Int/Long/Char and `x in a..b`
 * are still pattern-matched by the Mini backend (no object is created there), everything else
 * (`(0 until n).map { }`, `val r = 1..5`, `r.reversed()`) goes through these classes.
 * `Int.rangeTo(Int)` itself is a builtin member that the backend turns into `IntRange(a, b)`.
 */
package kotlin.ranges

public interface ClosedRange<T : Comparable<T>> {
    public val start: T
    public val endInclusive: T
    public operator fun contains(value: T): Boolean = value >= start && value <= endInclusive
    public fun isEmpty(): Boolean = start > endInclusive
}

// ---- progressions -------------------------------------------------------------------------------------

public open class IntProgression internal constructor(start: Int, endInclusive: Int, step: Int) : Iterable<Int> {
    init {
        if (step == 0) throw IllegalArgumentException("Step must be non-zero.")
        if (step == Int.MIN_VALUE) throw IllegalArgumentException("Step must be greater than Int.MIN_VALUE to avoid overflow on negation.")
    }

    public val first: Int = start
    public val last: Int = getProgressionLastElement(start, endInclusive, step)
    public val step: Int = step

    override fun iterator(): IntIterator = IntProgressionIterator(first, last, step)

    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last

    override fun equals(other: Any?): Boolean =
        other is IntProgression && (isEmpty() && other.isEmpty() ||
            first == other.first && last == other.last && step == other.step)

    override fun hashCode(): Int = if (isEmpty()) -1 else (31 * (31 * first + last) + step)

    override fun toString(): String = if (step > 0) "$first..$last step $step" else "$first downTo $last step ${-step}"

    public companion object {
        public fun fromClosedRange(rangeStart: Int, rangeEnd: Int, step: Int): IntProgression = IntProgression(rangeStart, rangeEnd, step)
    }
}

public open class LongProgression internal constructor(start: Long, endInclusive: Long, step: Long) : Iterable<Long> {
    init {
        if (step == 0L) throw IllegalArgumentException("Step must be non-zero.")
        if (step == Long.MIN_VALUE) throw IllegalArgumentException("Step must be greater than Long.MIN_VALUE to avoid overflow on negation.")
    }

    public val first: Long = start
    public val last: Long = getProgressionLastElement(start, endInclusive, step)
    public val step: Long = step

    override fun iterator(): LongIterator = LongProgressionIterator(first, last, step)

    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last

    override fun equals(other: Any?): Boolean =
        other is LongProgression && (isEmpty() && other.isEmpty() ||
            first == other.first && last == other.last && step == other.step)

    override fun hashCode(): Int =
        if (isEmpty()) -1 else (31 * (31 * (first xor (first ushr 32)).toInt() + (last xor (last ushr 32)).toInt()) + (step xor (step ushr 32)).toInt())

    override fun toString(): String = if (step > 0) "$first..$last step $step" else "$first downTo $last step ${-step}"

    public companion object {
        public fun fromClosedRange(rangeStart: Long, rangeEnd: Long, step: Long): LongProgression = LongProgression(rangeStart, rangeEnd, step)
    }
}

public open class CharProgression internal constructor(start: Char, endInclusive: Char, step: Int) : Iterable<Char> {
    init {
        if (step == 0) throw IllegalArgumentException("Step must be non-zero.")
        if (step == Int.MIN_VALUE) throw IllegalArgumentException("Step must be greater than Int.MIN_VALUE to avoid overflow on negation.")
    }

    public val first: Char = start
    public val last: Char = getProgressionLastElement(start.code, endInclusive.code, step).toChar()
    public val step: Int = step

    override fun iterator(): CharIterator = CharProgressionIterator(first, last, step)

    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last

    override fun equals(other: Any?): Boolean =
        other is CharProgression && (isEmpty() && other.isEmpty() ||
            first == other.first && last == other.last && step == other.step)

    override fun hashCode(): Int = if (isEmpty()) -1 else (31 * (31 * first.code + last.code) + step)

    override fun toString(): String = if (step > 0) "$first..$last step $step" else "$first downTo $last step ${-step}"

    public companion object {
        public fun fromClosedRange(rangeStart: Char, rangeEnd: Char, step: Int): CharProgression = CharProgression(rangeStart, rangeEnd, step)
    }
}

// ---- ranges -------------------------------------------------------------------------------------------

public class IntRange(start: Int, endInclusive: Int) : IntProgression(start, endInclusive, 1), ClosedRange<Int> {
    override val start: Int get() = first
    override val endInclusive: Int get() = last

    override fun contains(value: Int): Boolean = first <= value && value <= last

    override fun isEmpty(): Boolean = first > last

    override fun equals(other: Any?): Boolean =
        other is IntRange && (isEmpty() && other.isEmpty() || first == other.first && last == other.last)

    override fun hashCode(): Int = if (isEmpty()) -1 else (31 * first + last)

    override fun toString(): String = "$first..$last"

    public companion object {
        public val EMPTY: IntRange = IntRange(1, 0)
    }
}

public class LongRange(start: Long, endInclusive: Long) : LongProgression(start, endInclusive, 1L), ClosedRange<Long> {
    override val start: Long get() = first
    override val endInclusive: Long get() = last

    override fun contains(value: Long): Boolean = first <= value && value <= last

    override fun isEmpty(): Boolean = first > last

    override fun equals(other: Any?): Boolean =
        other is LongRange && (isEmpty() && other.isEmpty() || first == other.first && last == other.last)

    override fun hashCode(): Int =
        if (isEmpty()) -1 else (31 * (first xor (first ushr 32)).toInt() + (last xor (last ushr 32)).toInt())

    override fun toString(): String = "$first..$last"

    public companion object {
        public val EMPTY: LongRange = LongRange(1L, 0L)
    }
}

public class CharRange(start: Char, endInclusive: Char) : CharProgression(start, endInclusive, 1), ClosedRange<Char> {
    override val start: Char get() = first
    override val endInclusive: Char get() = last

    override fun contains(value: Char): Boolean = first <= value && value <= last

    override fun isEmpty(): Boolean = first > last

    override fun equals(other: Any?): Boolean =
        other is CharRange && (isEmpty() && other.isEmpty() || first == other.first && last == other.last)

    override fun hashCode(): Int = if (isEmpty()) -1 else (31 * first.code + last.code)

    override fun toString(): String = "$first..$last"

    public companion object {
        public val EMPTY: CharRange = CharRange(1.toChar(), 0.toChar())
    }
}

// ---- iterators ----------------------------------------------------------------------------------------

internal class IntProgressionIterator(first: Int, last: Int, val step: Int) : IntIterator() {
    private val finalElement: Int = last
    private var hasNext: Boolean = if (step > 0) first <= last else first >= last
    private var next: Int = if (hasNext) first else finalElement

    override fun hasNext(): Boolean = hasNext

    override fun nextInt(): Int {
        val value = next
        if (value == finalElement) {
            if (!hasNext) throw NoSuchElementException()
            hasNext = false
        } else {
            next += step
        }
        return value
    }
}

internal class LongProgressionIterator(first: Long, last: Long, val step: Long) : LongIterator() {
    private val finalElement: Long = last
    private var hasNext: Boolean = if (step > 0) first <= last else first >= last
    private var next: Long = if (hasNext) first else finalElement

    override fun hasNext(): Boolean = hasNext

    override fun nextLong(): Long {
        val value = next
        if (value == finalElement) {
            if (!hasNext) throw NoSuchElementException()
            hasNext = false
        } else {
            next += step
        }
        return value
    }
}

internal class CharProgressionIterator(first: Char, last: Char, val step: Int) : CharIterator() {
    private val finalElement: Int = last.code
    private var hasNext: Boolean = if (step > 0) first <= last else first >= last
    private var next: Int = if (hasNext) first.code else finalElement

    override fun hasNext(): Boolean = hasNext

    override fun nextChar(): Char {
        val value = next
        if (value == finalElement) {
            if (!hasNext) throw NoSuchElementException()
            hasNext = false
        } else {
            next += step
        }
        return value.toChar()
    }
}

// ---- last element of a progression (kotlin.internal.progressionUtil) -------------------------------------

private fun differenceModulo(a: Int, b: Int, c: Int): Int {
    val ac = a % c
    val bc = b % c
    return if (ac >= bc) ac - bc else ac - bc + c
}

private fun differenceModulo(a: Long, b: Long, c: Long): Long {
    val ac = a % c
    val bc = b % c
    return if (ac >= bc) ac - bc else ac - bc + c
}

internal fun getProgressionLastElement(start: Int, end: Int, step: Int): Int = when {
    step > 0 -> if (start >= end) end else end - differenceModulo(end, start, step)
    step < 0 -> if (start <= end) end else end + differenceModulo(start, end, -step)
    else -> throw IllegalArgumentException("Step is zero.")
}

internal fun getProgressionLastElement(start: Long, end: Long, step: Long): Long = when {
    step > 0 -> if (start >= end) end else end - differenceModulo(end, start, step)
    step < 0 -> if (start <= end) end else end + differenceModulo(start, end, -step)
    else -> throw IllegalArgumentException("Step is zero.")
}

// ---- until / downTo / step / reversed -------------------------------------------------------------------

public infix fun Int.downTo(to: Int): IntProgression = IntProgression.fromClosedRange(this, to, -1)

public infix fun Long.downTo(to: Long): LongProgression = LongProgression.fromClosedRange(this, to, -1L)

public infix fun Char.downTo(to: Char): CharProgression = CharProgression.fromClosedRange(this, to, -1)

public infix fun Int.until(to: Int): IntRange {
    if (to <= Int.MIN_VALUE) return IntRange.EMPTY
    return IntRange(this, to - 1)
}

public infix fun Long.until(to: Long): LongRange {
    if (to <= Long.MIN_VALUE) return LongRange.EMPTY
    return LongRange(this, to - 1)
}

public infix fun Char.until(to: Char): CharRange {
    if (to <= '\u0000') return CharRange.EMPTY
    return CharRange(this, to - 1)
}

public fun IntProgression.reversed(): IntProgression = IntProgression.fromClosedRange(last, first, -step)

public fun LongProgression.reversed(): LongProgression = LongProgression.fromClosedRange(last, first, -step)

public fun CharProgression.reversed(): CharProgression = CharProgression.fromClosedRange(last, first, -step)

public infix fun IntProgression.step(step: Int): IntProgression {
    checkStepIsPositive(step > 0, step)
    return IntProgression.fromClosedRange(first, last, if (this.step > 0) step else -step)
}

public infix fun LongProgression.step(step: Long): LongProgression {
    checkStepIsPositive(step > 0, step)
    return LongProgression.fromClosedRange(first, last, if (this.step > 0) step else -step)
}

public infix fun CharProgression.step(step: Int): CharProgression {
    checkStepIsPositive(step > 0, step)
    return CharProgression.fromClosedRange(first, last, if (this.step > 0) step else -step)
}

internal fun checkStepIsPositive(isPositive: Boolean, step: Any) {
    if (!isPositive) throw IllegalArgumentException("Step must be positive, was: $step.")
}
