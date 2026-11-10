/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin.time time sources and marks (ported from kotlin-stdlib commonMain TimeSource.kt, TimeSources.kt, longSaturatedMath.kt,
 * measureTime.kt; the monotonic reading is mini.internal.nanoTime).
 */
package kotlin.time

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.sign
import kotlin.time.Duration.Companion.milliseconds
import mini.internal.nanoTime

internal fun DurationUnit.shortName(): String = when (this) {
    DurationUnit.NANOSECONDS -> "ns"
    DurationUnit.MICROSECONDS -> "us"
    DurationUnit.MILLISECONDS -> "ms"
    DurationUnit.SECONDS -> "s"
    DurationUnit.MINUTES -> "m"
    DurationUnit.HOURS -> "h"
    DurationUnit.DAYS -> "d"
}

internal fun convertDurationUnit(value: Long, sourceUnit: DurationUnit, targetUnit: DurationUnit): Long =
    if (sourceUnit.nanos >= targetUnit.nanos) value * (sourceUnit.nanos / targetUnit.nanos) else value / (targetUnit.nanos / sourceUnit.nanos)

internal fun Duration.truncateTo(unit: DurationUnit): Duration =
    if (isInfinite()) this else Duration((nanos / unit.nanos) * unit.nanos)

public interface TimeSource {

    public fun markNow(): TimeMark
    public interface WithComparableMarks : TimeSource {
        override fun markNow(): ComparableTimeMark
    }

    public object Monotonic : TimeSource.WithComparableMarks {
        override fun markNow(): ValueTimeMark = MonotonicTimeSource.markNow()
        override fun toString(): String = MonotonicTimeSource.toString()
        public value class ValueTimeMark internal constructor(internal val reading: ValueTimeMarkReading) : ComparableTimeMark {
            override fun elapsedNow(): Duration = MonotonicTimeSource.elapsedFrom(this)
            override fun plus(duration: Duration): ValueTimeMark = MonotonicTimeSource.adjustReading(this, duration)
            override fun minus(duration: Duration): ValueTimeMark = MonotonicTimeSource.adjustReading(this, -duration)
            override fun hasPassedNow(): Boolean = !elapsedNow().isNegative()
            override fun hasNotPassedNow(): Boolean = elapsedNow().isNegative()

            override fun minus(other: ComparableTimeMark): Duration {
                if (other !is ValueTimeMark)
                    throw IllegalArgumentException("Subtracting or comparing time marks from different time sources is not possible: $this and $other")
                return this.minus(other)
            }

            public operator fun minus(other: ValueTimeMark): Duration = MonotonicTimeSource.differenceBetween(this, other)

            public operator fun compareTo(other: ValueTimeMark): Int =
                (this - other).compareTo(Duration.ZERO)
        }
    }

    public companion object {

    }
}

internal typealias ValueTimeMarkReading = Long
public interface TimeMark {

    public abstract fun elapsedNow(): Duration

    public operator fun plus(duration: Duration): TimeMark = AdjustedTimeMark(this, duration)

    public open operator fun minus(duration: Duration): TimeMark = plus(-duration)

    public fun hasPassedNow(): Boolean = !elapsedNow().isNegative()

    public fun hasNotPassedNow(): Boolean = elapsedNow().isNegative()
}
public interface ComparableTimeMark : TimeMark, Comparable<ComparableTimeMark> {
    public abstract override operator fun plus(duration: Duration): ComparableTimeMark
    public open override operator fun minus(duration: Duration): ComparableTimeMark = plus(-duration)

    public operator fun minus(other: ComparableTimeMark): Duration

    public override operator fun compareTo(other: ComparableTimeMark): Int =
        (this - other).compareTo(Duration.ZERO)

    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
}

private class AdjustedTimeMark(val mark: TimeMark, val adjustment: Duration) : TimeMark {
    override fun elapsedNow(): Duration = mark.elapsedNow() - adjustment

    override fun plus(duration: Duration): TimeMark = AdjustedTimeMark(mark, adjustment + duration)
}

internal object MonotonicTimeSource : TimeSource.WithComparableMarks {
    private val zero: Long = nanoTime()

    private fun read(): Long = nanoTime() - zero

    override fun toString(): String = "TimeSource(nanoTime())"

    override fun markNow(): TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.ValueTimeMark(read())

    fun elapsedFrom(timeMark: TimeSource.Monotonic.ValueTimeMark): Duration = saturatingDiff(read(), timeMark.reading, DurationUnit.NANOSECONDS)

    fun differenceBetween(one: TimeSource.Monotonic.ValueTimeMark, another: TimeSource.Monotonic.ValueTimeMark): Duration =
        saturatingOriginsDiff(one.reading, another.reading, DurationUnit.NANOSECONDS)

    fun adjustReading(timeMark: TimeSource.Monotonic.ValueTimeMark, duration: Duration): TimeSource.Monotonic.ValueTimeMark =
        TimeSource.Monotonic.ValueTimeMark(saturatingAdd(timeMark.reading, DurationUnit.NANOSECONDS, duration))
}
public abstract class AbstractLongTimeSource(protected val unit: DurationUnit) : TimeSource.WithComparableMarks {

    protected abstract fun read(): Long

    private val zero by lazy { read() }
    private fun adjustedRead(): Long = read() - zero

    private class LongTimeMark(private val startedAt: Long, private val timeSource: AbstractLongTimeSource, private val offset: Duration) : ComparableTimeMark {
        override fun elapsedNow(): Duration =
            saturatingOriginsDiff(timeSource.adjustedRead(), startedAt, timeSource.unit) - offset

        override fun plus(duration: Duration): ComparableTimeMark {
            val unit = timeSource.unit
            if (duration.isInfinite()) {
                val newValue = saturatingAdd(startedAt, unit, duration)
                return LongTimeMark(newValue, timeSource, Duration.ZERO)
            }
            val durationInUnit = duration.truncateTo(unit)
            val rest = (duration - durationInUnit) + offset
            var sum = saturatingAdd(startedAt, unit, durationInUnit)
            val restInUnit = rest.truncateTo(unit)
            sum = saturatingAdd(sum, unit, restInUnit)
            var restUnderUnit = rest - restInUnit
            val restUnderUnitNs = restUnderUnit.inWholeNanoseconds
            if (sum != 0L && restUnderUnitNs != 0L && (sum xor restUnderUnitNs) < 0L) {
                val correction = restUnderUnitNs.sign.toDuration(unit)
                sum = saturatingAdd(sum, unit, correction)
                restUnderUnit -= correction
            }
            val newValue = sum
            val newOffset = if (newValue.isSaturated()) Duration.ZERO else restUnderUnit
            return LongTimeMark(newValue, timeSource, newOffset)
        }

        override fun minus(other: ComparableTimeMark): Duration {
            if (other !is LongTimeMark || this.timeSource != other.timeSource)
                throw IllegalArgumentException("Subtracting or comparing time marks from different time sources is not possible: $this and $other")

            val startedAtDiff = saturatingOriginsDiff(this.startedAt, other.startedAt, timeSource.unit)
            return startedAtDiff + (offset - other.offset)
        }

        override fun equals(other: Any?): Boolean =
            other is LongTimeMark && this.timeSource == other.timeSource && (this - other) == Duration.ZERO

        override fun hashCode(): Int = offset.hashCode() * 37 + startedAt.hashCode()

        override fun toString(): String = "LongTimeMark($startedAt${timeSource.unit.shortName()} + $offset, $timeSource)"
    }

    override fun markNow(): ComparableTimeMark = LongTimeMark(adjustedRead(), this, Duration.ZERO)
}
public abstract class AbstractDoubleTimeSource(protected val unit: DurationUnit) : TimeSource.WithComparableMarks {

    protected abstract fun read(): Double

    @Suppress("DEPRECATION")
    private class DoubleTimeMark(private val startedAt: Double, private val timeSource: AbstractDoubleTimeSource, private val offset: Duration) : ComparableTimeMark {
        override fun elapsedNow(): Duration = (timeSource.read() - startedAt).toDuration(timeSource.unit) - offset
        override fun plus(duration: Duration): ComparableTimeMark = DoubleTimeMark(startedAt, timeSource, offset + duration)

        override fun minus(other: ComparableTimeMark): Duration {
            if (other !is DoubleTimeMark || this.timeSource != other.timeSource)
                throw IllegalArgumentException("Subtracting or comparing time marks from different time sources is not possible: $this and $other")

            if (this.offset == other.offset && this.offset.isInfinite()) return Duration.ZERO
            val offsetDiff = this.offset - other.offset
            val startedAtDiff = (this.startedAt - other.startedAt).toDuration(timeSource.unit)
            return if (startedAtDiff == -offsetDiff) Duration.ZERO else startedAtDiff + offsetDiff
        }

        override fun equals(other: Any?): Boolean {
            return other is DoubleTimeMark && this.timeSource == other.timeSource && (this - other) == Duration.ZERO
        }

        override fun hashCode(): Int {
            return (startedAt.toDuration(timeSource.unit) + offset).hashCode()
        }

        override fun toString(): String = "DoubleTimeMark($startedAt${timeSource.unit.shortName()} + $offset, $timeSource)"
    }

    override fun markNow(): ComparableTimeMark = DoubleTimeMark(read(), this, Duration.ZERO)
}
public class TestTimeSource : AbstractLongTimeSource(unit = DurationUnit.NANOSECONDS) {
    private var reading: Long = 0L

    init {
        markNow() // fix zero reading in the super time source
    }

    override fun read(): Long = reading

    public operator fun plusAssign(duration: Duration) {
        val longDelta = duration.toLong(unit)
        if (!longDelta.isSaturated()) {
            val newReading = reading + longDelta
            if (reading xor longDelta >= 0 && reading xor newReading < 0) overflow(duration)
            reading = newReading
        } else {
            val half = duration / 2
            if (!half.toLong(unit).isSaturated()) {
                val readingBefore = reading
                try {
                    plusAssign(half)
                    plusAssign(duration - half)
                } catch (e: IllegalStateException) {
                    reading = readingBefore
                    throw e
                }
            } else {
                overflow(duration)
            }
        }
    }

    private fun overflow(duration: Duration) {
        throw IllegalStateException("TestTimeSource will overflow if its reading ${reading}${unit.shortName()} is advanced by $duration.")
    }
}

internal fun saturatingAdd(value: Long, unit: DurationUnit, duration: Duration): Long {
    val durationInUnit = duration.toLong(unit)
    if (value.isSaturated()) { // the reading is infinitely saturated
        return checkInfiniteSumDefined(value, duration, durationInUnit)
    }
    if (durationInUnit.isSaturated()) { // duration doesn't fit in Long units
        return saturatingAddInHalves(value, unit, duration)
    }

    val result = value + durationInUnit
    if (((value xor result) and (durationInUnit xor result)) < 0) {
        return if (value < 0) Long.MIN_VALUE else Long.MAX_VALUE
    }
    return result
}

private fun checkInfiniteSumDefined(value: Long, duration: Duration, durationInUnit: Long): Long {
    if (duration.isInfinite() && (value xor durationInUnit < 0)) throw IllegalArgumentException("Summing infinities of different signs")
    return value
}

private fun saturatingAddInHalves(value: Long, unit: DurationUnit, duration: Duration): Long {
    val half = duration / 2
    val halfInUnit = half.toLong(unit)
    if (halfInUnit.isSaturated()) {
        return halfInUnit // value + inf == inf, return saturated value
    } else {
        return saturatingAdd(saturatingAdd(value, unit, half), unit, duration - half)
    }
}

private fun infinityOfSign(value: Long): Duration = if (value < 0) Duration.NEG_INFINITE else Duration.INFINITE

internal fun saturatingDiff(valueNs: Long, origin: Long, unit: DurationUnit): Duration {
    if (origin.isSaturated()) { // MIN_VALUE or MAX_VALUE
        return -infinityOfSign(origin)
    }
    return saturatingFiniteDiff(valueNs, origin, unit)
}

internal fun saturatingOriginsDiff(origin1: Long, origin2: Long, unit: DurationUnit): Duration {
    if (origin2.isSaturated()) {
        if (origin1 == origin2) return Duration.ZERO // saturated values of the same sign are considered equal
        return -infinityOfSign(origin2)
    }
    if (origin1.isSaturated()) {
        return infinityOfSign(origin1)
    }
    return saturatingFiniteDiff(origin1, origin2, unit)
}

private fun saturatingFiniteDiff(value1: Long, value2: Long, unit: DurationUnit): Duration {
    val result = value1 - value2
    if ((result xor value1) and (result xor value2).inv() < 0) { // Long overflow
        if (unit < DurationUnit.MILLISECONDS) {
            val unitsInMilli = convertDurationUnit(1, DurationUnit.MILLISECONDS, unit)
            val resultMs = value1 / unitsInMilli - value2 / unitsInMilli
            val resultUnit = value1 % unitsInMilli - value2 % unitsInMilli
            return resultMs.milliseconds + resultUnit.toDuration(unit)
        } else {
            return -infinityOfSign(result)
        }
    }
    return result.toDuration(unit)
}
internal inline fun Long.isSaturated(): Boolean =
    (this - 1) or 1 == Long.MAX_VALUE // == either MAX_VALUE or MIN_VALUE

public inline fun measureTime(block: () -> Unit): Duration {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return TimeSource.Monotonic.measureTime(block)
}
public inline fun TimeSource.measureTime(block: () -> Unit): Duration {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val mark = markNow()
    block()
    return mark.elapsedNow()
}
public inline fun TimeSource.Monotonic.measureTime(block: () -> Unit): Duration {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val mark = markNow()
    block()
    return mark.elapsedNow()
}
public data class TimedValue<T>(val value: T, val duration: Duration)
public inline fun <T> measureTimedValue(block: () -> T): TimedValue<T> {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    return TimeSource.Monotonic.measureTimedValue(block)
}
public inline fun <T> TimeSource.measureTimedValue(block: () -> T): TimedValue<T> {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val mark = markNow()
    val result = block()
    return TimedValue(result, mark.elapsedNow())
}
public inline fun <T> TimeSource.Monotonic.measureTimedValue(block: () -> T): TimedValue<T> {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val mark = markNow()
    val result = block()
    return TimedValue(result, mark.elapsedNow())
}
