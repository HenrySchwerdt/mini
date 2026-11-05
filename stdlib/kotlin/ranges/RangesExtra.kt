/*
 * Floating point ranges (`0.0..1.0`) and coerceIn with ranges.
 */
package kotlin.ranges

public class DoubleClosedRange(override val start: Double, override val endInclusive: Double) : ClosedRange<Double> {
    override fun contains(value: Double): Boolean = value >= start && value <= endInclusive
    override fun isEmpty(): Boolean = !(start <= endInclusive)
    override fun toString(): String = start.toString() + ".." + endInclusive
    override fun equals(other: Any?): Boolean =
        other is DoubleClosedRange && ((isEmpty() && other.isEmpty()) || (start == other.start && endInclusive == other.endInclusive))
    override fun hashCode(): Int = if (isEmpty()) -1 else 31 * start.hashCode() + endInclusive.hashCode()
}

public operator fun Double.rangeTo(that: Double): ClosedRange<Double> = DoubleClosedRange(this, that)

public fun Int.coerceIn(range: ClosedRange<Int>): Int = coerceIn(range.start, range.endInclusive)

public fun Long.coerceIn(range: ClosedRange<Long>): Long = coerceIn(range.start, range.endInclusive)

public fun Double.coerceIn(range: ClosedRange<Double>): Double = coerceIn(range.start, range.endInclusive)

