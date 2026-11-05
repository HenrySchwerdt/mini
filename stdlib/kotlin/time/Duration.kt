/*
 * kotlin.time: Duration (an ordinary class holding nanoseconds, not a value class; range about +-292 years,
 * larger values saturate to INFINITE), TimeSource.Monotonic, measureTime. toString follows kotlin-stdlib.
 */
package kotlin.time

import kotlin.math.roundToLong
import mini.internal.nanoTime

public enum class DurationUnit(internal val nanos: Long) {
    NANOSECONDS(1L),
    MICROSECONDS(1000L),
    MILLISECONDS(1000000L),
    SECONDS(1000000000L),
    MINUTES(60000000000L),
    HOURS(3600000000000L),
    DAYS(86400000000000L);
}

public class Duration internal constructor(internal val nanos: Long) : Comparable<Duration> {
    public companion object {
        public val ZERO: Duration = Duration(0L)
        public val INFINITE: Duration = Duration(Long.MAX_VALUE)
        internal val NEG_INFINITE: Duration = Duration(-Long.MAX_VALUE)

        public fun convert(value: Double, sourceUnit: DurationUnit, targetUnit: DurationUnit): Double =
            value * sourceUnit.nanos.toDouble() / targetUnit.nanos.toDouble()
    }

    public fun isNegative(): Boolean = nanos < 0L
    public fun isPositive(): Boolean = nanos > 0L
    public fun isInfinite(): Boolean = nanos == Long.MAX_VALUE || nanos == -Long.MAX_VALUE
    public fun isFinite(): Boolean = !isInfinite()

    public val absoluteValue: Duration
        get() = if (isNegative()) -this else this

    public operator fun unaryMinus(): Duration = Duration(-nanos)

    public operator fun plus(other: Duration): Duration {
        if (isInfinite()) {
            if (other.isInfinite() && (nanos < 0L) != (other.nanos < 0L)) throw IllegalArgumentException("Summing infinite durations of different signs yields an undefined result.")
            return this
        }
        if (other.isInfinite()) return other
        val r = nanos + other.nanos
        if (((nanos xor r) and (other.nanos xor r)) < 0L) return if (nanos < 0L) NEG_INFINITE else INFINITE
        return Duration(r)
    }

    public operator fun minus(other: Duration): Duration = this + (-other)

    public operator fun times(scale: Int): Duration = times(scale.toLong().toDouble())

    public operator fun times(scale: Double): Duration {
        if (scale != scale) throw IllegalArgumentException("Duration.times(scale) cannot be NaN.")
        if (isInfinite()) return if ((scale < 0.0) != (nanos < 0L)) NEG_INFINITE else INFINITE
        return fromNanosDouble(nanos.toDouble() * scale)
    }

    public operator fun div(scale: Int): Duration {
        if (scale == 0) {
            return if (isPositive()) INFINITE else if (isNegative()) NEG_INFINITE else throw IllegalArgumentException("Dividing zero duration by zero yields an undefined result.")
        }
        if (isInfinite()) return if ((scale < 0) != (nanos < 0L)) NEG_INFINITE else INFINITE
        return Duration(nanos / scale.toLong())
    }

    public operator fun div(scale: Double): Duration {
        if (scale != scale) throw IllegalArgumentException("Duration.div(scale) cannot be NaN.")
        if (isInfinite()) return if ((scale < 0.0) != (nanos < 0L)) NEG_INFINITE else INFINITE
        return fromNanosDouble(nanos.toDouble() / scale)
    }

    public operator fun div(other: Duration): Double {
        if (isInfinite() || other.isInfinite()) throw IllegalArgumentException("Operation yields an undefined result.")
        return nanos.toDouble() / other.nanos.toDouble()
    }

    public override operator fun compareTo(other: Duration): Int = nanos.compareTo(other.nanos)

    public fun toDouble(unit: DurationUnit): Double {
        if (nanos == Long.MAX_VALUE) return Double.POSITIVE_INFINITY
        if (nanos == -Long.MAX_VALUE) return Double.NEGATIVE_INFINITY
        return nanos.toDouble() / unit.nanos.toDouble()
    }

    public fun toLong(unit: DurationUnit): Long {
        if (nanos == Long.MAX_VALUE) return Long.MAX_VALUE
        if (nanos == -Long.MAX_VALUE) return Long.MIN_VALUE
        return nanos / unit.nanos
    }

    public fun toInt(unit: DurationUnit): Int = toLong(unit).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    public val inWholeDays: Long get() = toLong(DurationUnit.DAYS)
    public val inWholeHours: Long get() = toLong(DurationUnit.HOURS)
    public val inWholeMinutes: Long get() = toLong(DurationUnit.MINUTES)
    public val inWholeSeconds: Long get() = toLong(DurationUnit.SECONDS)
    public val inWholeMilliseconds: Long get() = toLong(DurationUnit.MILLISECONDS)
    public val inWholeMicroseconds: Long get() = toLong(DurationUnit.MICROSECONDS)
    public val inWholeNanoseconds: Long get() = toLong(DurationUnit.NANOSECONDS)

    public inline fun <T> toComponents(action: (days: Long, hours: Int, minutes: Int, seconds: Int, nanoseconds: Int) -> T): T =
        action(inWholeDays, hoursComponent, minutesComponent, secondsComponent, nanosecondsComponent)

    public val hoursComponent: Int get() = if (isInfinite()) 0 else ((nanos / 3600000000000L) % 24L).toInt()
    public val minutesComponent: Int get() = if (isInfinite()) 0 else ((nanos / 60000000000L) % 60L).toInt()
    public val secondsComponent: Int get() = if (isInfinite()) 0 else ((nanos / 1000000000L) % 60L).toInt()
    public val nanosecondsComponent: Int get() = if (isInfinite()) 0 else (nanos % 1000000000L).toInt()

    override fun equals(other: Any?): Boolean = other is Duration && other.nanos == nanos

    override fun hashCode(): Int = nanos.hashCode()

    override fun toString(): String {
        if (nanos == 0L) return "0s"
        if (nanos == Long.MAX_VALUE) return "Infinity"
        if (nanos == -Long.MAX_VALUE) return "-Infinity"
        val negative = isNegative()
        val abs = absoluteValue
        val sb = StringBuilder()
        if (negative) sb.append('-')
        val days = abs.inWholeDays
        val hours = abs.hoursComponent
        val minutes = abs.minutesComponent
        val seconds = abs.secondsComponent
        val nanoseconds = abs.nanosecondsComponent
        val hasDays = days != 0L
        val hasHours = hours != 0
        val hasMinutes = minutes != 0
        val hasSeconds = seconds != 0 || nanoseconds != 0
        var components = 0
        if (hasDays) {
            sb.append(days).append('d')
            components++
        }
        if (hasHours || (hasDays && (hasMinutes || hasSeconds))) {
            if (components++ > 0) sb.append(' ')
            sb.append(hours).append('h')
        }
        if (hasMinutes || (hasSeconds && (hasHours || hasDays))) {
            if (components++ > 0) sb.append(' ')
            sb.append(minutes).append('m')
        }
        if (hasSeconds) {
            if (components++ > 0) sb.append(' ')
            if (seconds != 0 || hasDays || hasHours || hasMinutes) {
                appendFractional(sb, seconds, nanoseconds, 9, "s")
            } else if (nanoseconds >= 1000000) {
                appendFractional(sb, nanoseconds / 1000000, nanoseconds % 1000000, 6, "ms")
            } else if (nanoseconds >= 1000) {
                appendFractional(sb, nanoseconds / 1000, nanoseconds % 1000, 3, "us")
            } else {
                sb.append(nanoseconds).append("ns")
            }
        }
        if (negative && components > 1) {
            sb.insert(1, "(")
            sb.append(')')
        }
        return sb.toString()
    }

    /** Like Java's `String.format("%.{decimals}f")` of the value in [unit] followed by the unit symbol. */
    public fun toString(unit: DurationUnit, decimals: Int = 0): String {
        require(decimals >= 0) { "decimals must be not negative, but was " + decimals }
        if (isInfinite()) return if (nanos > 0L) "Infinity" else "-Infinity"
        val number = toDouble(unit)
        val d = if (decimals > 12) 12 else decimals
        val text = if (kotlin.math.abs(number) < 1e14) formatFixed(number, d) else number.toString()
        return text + unitSymbol(unit)
    }

    private fun formatFixed(v: Double, decimals: Int): String {
        var p = 1.0
        var i = 0
        while (i < decimals) { p *= 10.0; i++ }
        val scaled = kotlin.math.round(kotlin.math.abs(v) * p)
        val whole = (scaled / p).toLong()
        val rest = (scaled - whole.toDouble() * p).toLong()
        val sb = StringBuilder()
        if (v < 0.0 && scaled != 0.0) sb.append('-')
        sb.append(whole)
        if (decimals > 0) {
            sb.append('.')
            sb.append(rest.toString().padStart(decimals, '0'))
        }
        return sb.toString()
    }
}

private fun unitSymbol(unit: DurationUnit): String = when (unit) {
    DurationUnit.NANOSECONDS -> "ns"
    DurationUnit.MICROSECONDS -> "us"
    DurationUnit.MILLISECONDS -> "ms"
    DurationUnit.SECONDS -> "s"
    DurationUnit.MINUTES -> "m"
    DurationUnit.HOURS -> "h"
    DurationUnit.DAYS -> "d"
}

private fun appendFractional(sb: StringBuilder, whole: Int, fractional: Int, fractionalSize: Int, unit: String) {
    sb.append(whole)
    if (fractional != 0) {
        sb.append('.')
        val fracString = fractional.toString().padStart(fractionalSize, '0')
        val nonZeroDigits = fracString.indexOfLast { it != '0' } + 1
        if (nonZeroDigits < 3) {
            sb.append(fracString.substring(0, nonZeroDigits))
        } else {
            sb.append(fracString.substring(0, ((nonZeroDigits + 2) / 3) * 3))
        }
    }
    sb.append(unit)
}

private fun fromNanosDouble(v: Double): Duration {
    if (v != v) throw IllegalArgumentException("Duration value cannot be NaN.")
    if (v >= 9.223372036854775807E18) return Duration.INFINITE
    if (v <= -9.223372036854775807E18) return Duration.NEG_INFINITE
    return Duration(v.roundToLong())
}

private fun longToDuration(value: Long, unit: DurationUnit): Duration {
    val m = unit.nanos
    val max = Long.MAX_VALUE / m
    if (value > max) return Duration.INFINITE
    if (value < -max) return Duration.NEG_INFINITE
    return Duration(value * m)
}

public fun Int.toDuration(unit: DurationUnit): Duration = longToDuration(this.toLong(), unit)

public fun Long.toDuration(unit: DurationUnit): Duration = longToDuration(this, unit)

public fun Double.toDuration(unit: DurationUnit): Duration {
    if (this != this) throw IllegalArgumentException("Duration value cannot be NaN.")
    return fromNanosDouble(this * unit.nanos.toDouble())
}

public operator fun Int.times(duration: Duration): Duration = duration * this

public operator fun Double.times(duration: Duration): Duration = duration * this

public val Int.nanoseconds: Duration get() = toDuration(DurationUnit.NANOSECONDS)
public val Long.nanoseconds: Duration get() = toDuration(DurationUnit.NANOSECONDS)
public val Double.nanoseconds: Duration get() = toDuration(DurationUnit.NANOSECONDS)
public val Int.microseconds: Duration get() = toDuration(DurationUnit.MICROSECONDS)
public val Long.microseconds: Duration get() = toDuration(DurationUnit.MICROSECONDS)
public val Double.microseconds: Duration get() = toDuration(DurationUnit.MICROSECONDS)
public val Int.milliseconds: Duration get() = toDuration(DurationUnit.MILLISECONDS)
public val Long.milliseconds: Duration get() = toDuration(DurationUnit.MILLISECONDS)
public val Double.milliseconds: Duration get() = toDuration(DurationUnit.MILLISECONDS)
public val Int.seconds: Duration get() = toDuration(DurationUnit.SECONDS)
public val Long.seconds: Duration get() = toDuration(DurationUnit.SECONDS)
public val Double.seconds: Duration get() = toDuration(DurationUnit.SECONDS)
public val Int.minutes: Duration get() = toDuration(DurationUnit.MINUTES)
public val Long.minutes: Duration get() = toDuration(DurationUnit.MINUTES)
public val Double.minutes: Duration get() = toDuration(DurationUnit.MINUTES)
public val Int.hours: Duration get() = toDuration(DurationUnit.HOURS)
public val Long.hours: Duration get() = toDuration(DurationUnit.HOURS)
public val Double.hours: Duration get() = toDuration(DurationUnit.HOURS)
public val Int.days: Duration get() = toDuration(DurationUnit.DAYS)
public val Long.days: Duration get() = toDuration(DurationUnit.DAYS)
public val Double.days: Duration get() = toDuration(DurationUnit.DAYS)

// ---- time sources --------------------------------------------------------------------------------

public class TimeMark internal constructor(private val startNanos: Long) {
    public fun elapsedNow(): Duration = Duration(nanoTime() - startNanos)
    public fun hasPassedNow(): Boolean = nanoTime() - startNanos >= 0L
}

public interface TimeSource {
    public fun markNow(): TimeMark

    public object Monotonic : TimeSource {
        override fun markNow(): TimeMark = TimeMark(nanoTime())
    }
}

public inline fun measureTime(block: () -> Unit): Duration {
    val mark = TimeSource.Monotonic.markNow()
    block()
    return mark.elapsedNow()
}

public data class TimedValue<T>(val value: T, val duration: Duration)

public inline fun <T> measureTimedValue(block: () -> T): TimedValue<T> {
    val mark = TimeSource.Monotonic.markNow()
    val result = block()
    return TimedValue(result, mark.elapsedNow())
}
