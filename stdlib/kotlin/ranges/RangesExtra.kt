/*
 * Ranges of floating point numbers and of any Comparable (`0.0..1.0`, `1.5f..2f`, `"a".."c"`), the open-ended ranges (`..<`) of those,
 * and the coerceIn functions with ranges (ported from kotlin-stdlib ranges/{Range,_Ranges}.kt).
 */
package kotlin.ranges

public interface ClosedFloatingPointRange<T : Comparable<T>> : ClosedRange<T> {
    override fun contains(value: T): Boolean = lessThanOrEquals(start, value) && lessThanOrEquals(value, endInclusive)
    override fun isEmpty(): Boolean = !lessThanOrEquals(start, endInclusive)

    /** Compares two values of the range type like the `<=` operator of the type (`NaN` is not comparable). */
    public fun lessThanOrEquals(a: T, b: T): Boolean
}

public interface OpenEndRange<T : Comparable<T>> {
    public val start: T
    public val endExclusive: T
    public operator fun contains(value: T): Boolean = value >= start && value < endExclusive
    public fun isEmpty(): Boolean = start >= endExclusive
}

private class ClosedDoubleRange(start: Double, endInclusive: Double) : ClosedFloatingPointRange<Double> {
    private val _start = start
    private val _endInclusive = endInclusive
    override val start: Double get() = _start
    override val endInclusive: Double get() = _endInclusive

    override fun lessThanOrEquals(a: Double, b: Double): Boolean = a <= b
    override fun contains(value: Double): Boolean = value >= _start && value <= _endInclusive
    override fun isEmpty(): Boolean = !(_start <= _endInclusive)

    override fun equals(other: Any?): Boolean =
        other is ClosedDoubleRange && (isEmpty() && other.isEmpty() || _start == other._start && _endInclusive == other._endInclusive)

    override fun hashCode(): Int = if (isEmpty()) -1 else 31 * _start.hashCode() + _endInclusive.hashCode()
    override fun toString(): String = _start.toString() + ".." + _endInclusive
}

private class ClosedFloatRange(start: Float, endInclusive: Float) : ClosedFloatingPointRange<Float> {
    private val _start = start
    private val _endInclusive = endInclusive
    override val start: Float get() = _start
    override val endInclusive: Float get() = _endInclusive

    override fun lessThanOrEquals(a: Float, b: Float): Boolean = a <= b
    override fun contains(value: Float): Boolean = value >= _start && value <= _endInclusive
    override fun isEmpty(): Boolean = !(_start <= _endInclusive)

    override fun equals(other: Any?): Boolean =
        other is ClosedFloatRange && (isEmpty() && other.isEmpty() || _start == other._start && _endInclusive == other._endInclusive)

    override fun hashCode(): Int = if (isEmpty()) -1 else 31 * _start.hashCode() + _endInclusive.hashCode()
    override fun toString(): String = _start.toString() + ".." + _endInclusive
}

private class ComparableRange<T : Comparable<T>>(override val start: T, override val endInclusive: T) : ClosedRange<T> {
    override fun equals(other: Any?): Boolean =
        other is ComparableRange<*> && (isEmpty() && other.isEmpty() || start == other.start && endInclusive == other.endInclusive)

    override fun hashCode(): Int = if (isEmpty()) -1 else 31 * (start as Any).hashCode() + (endInclusive as Any).hashCode()
    override fun toString(): String = (start as Any).toString() + ".." + (endInclusive as Any)
}

private class ComparableOpenEndRange<T : Comparable<T>>(override val start: T, override val endExclusive: T) : OpenEndRange<T> {
    override fun equals(other: Any?): Boolean =
        other is ComparableOpenEndRange<*> && (isEmpty() && other.isEmpty() || start == other.start && endExclusive == other.endExclusive)

    override fun hashCode(): Int = if (isEmpty()) -1 else 31 * (start as Any).hashCode() + (endExclusive as Any).hashCode()
    override fun toString(): String = (start as Any).toString() + "..<" + (endExclusive as Any)
}

private class OpenDoubleRange(start: Double, endExclusive: Double) : OpenEndRange<Double> {
    private val _start = start
    private val _endExclusive = endExclusive
    override val start: Double get() = _start
    override val endExclusive: Double get() = _endExclusive

    override fun contains(value: Double): Boolean = value >= _start && value < _endExclusive
    override fun isEmpty(): Boolean = !(_start < _endExclusive)

    override fun equals(other: Any?): Boolean =
        other is OpenDoubleRange && (isEmpty() && other.isEmpty() || _start == other._start && _endExclusive == other._endExclusive)

    override fun hashCode(): Int = if (isEmpty()) -1 else 31 * _start.hashCode() + _endExclusive.hashCode()
    override fun toString(): String = _start.toString() + "..<" + _endExclusive
}

private class OpenFloatRange(start: Float, endExclusive: Float) : OpenEndRange<Float> {
    private val _start = start
    private val _endExclusive = endExclusive
    override val start: Float get() = _start
    override val endExclusive: Float get() = _endExclusive

    override fun contains(value: Float): Boolean = value >= _start && value < _endExclusive
    override fun isEmpty(): Boolean = !(_start < _endExclusive)

    override fun equals(other: Any?): Boolean =
        other is OpenFloatRange && (isEmpty() && other.isEmpty() || _start == other._start && _endExclusive == other._endExclusive)

    override fun hashCode(): Int = if (isEmpty()) -1 else 31 * _start.hashCode() + _endExclusive.hashCode()
    override fun toString(): String = _start.toString() + "..<" + _endExclusive
}

public operator fun Double.rangeTo(that: Double): ClosedFloatingPointRange<Double> = ClosedDoubleRange(this, that)

public operator fun Float.rangeTo(that: Float): ClosedFloatingPointRange<Float> = ClosedFloatRange(this, that)

public operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T> = ComparableRange(this, that)

public operator fun Double.rangeUntil(that: Double): OpenEndRange<Double> = OpenDoubleRange(this, that)

public operator fun Float.rangeUntil(that: Float): OpenEndRange<Float> = OpenFloatRange(this, that)

public operator fun <T : Comparable<T>> T.rangeUntil(that: T): OpenEndRange<T> = ComparableOpenEndRange(this, that)

public operator fun ClosedRange<Double>.contains(value: Float): Boolean = contains(value.toDouble())

public operator fun ClosedRange<Float>.contains(value: Double): Boolean = contains(value.toFloat())

public operator fun OpenEndRange<Double>.contains(value: Float): Boolean = contains(value.toDouble())

public operator fun OpenEndRange<Float>.contains(value: Double): Boolean = contains(value.toFloat())

// ---- coerceIn with ranges ------------------------------------------------------------------------------

public fun Int.coerceIn(range: ClosedRange<Int>): Int {
    if (range.isEmpty()) throw IllegalArgumentException("Cannot coerce value to an empty range: " + range + ".")
    return if (this < range.start) range.start else if (this > range.endInclusive) range.endInclusive else this
}

public fun Long.coerceIn(range: ClosedRange<Long>): Long {
    if (range.isEmpty()) throw IllegalArgumentException("Cannot coerce value to an empty range: " + range + ".")
    return if (this < range.start) range.start else if (this > range.endInclusive) range.endInclusive else this
}

public fun Double.coerceIn(range: ClosedRange<Double>): Double {
    if (range.isEmpty()) throw IllegalArgumentException("Cannot coerce value to an empty range: " + range + ".")
    return if (this < range.start) range.start else if (this > range.endInclusive) range.endInclusive else this
}

public fun Float.coerceIn(range: ClosedRange<Float>): Float {
    if (range.isEmpty()) throw IllegalArgumentException("Cannot coerce value to an empty range: " + range + ".")
    return if (this < range.start) range.start else if (this > range.endInclusive) range.endInclusive else this
}

public fun <T : Comparable<T>> T.coerceIn(range: ClosedFloatingPointRange<T>): T {
    if (range.isEmpty()) throw IllegalArgumentException("Cannot coerce value to an empty range: " + range + ".")
    return if (range.lessThanOrEquals(this, range.start) && !range.lessThanOrEquals(range.start, this)) range.start
    else if (range.lessThanOrEquals(range.endInclusive, this) && !range.lessThanOrEquals(this, range.endInclusive)) range.endInclusive
    else this
}

public fun <T : Comparable<T>> T.coerceIn(range: ClosedRange<T>): T {
    if (range is ClosedFloatingPointRange<*>) {
        @Suppress("UNCHECKED_CAST")
        return this.coerceIn(range as ClosedFloatingPointRange<T>)
    }
    if (range.isEmpty()) throw IllegalArgumentException("Cannot coerce value to an empty range: " + range + ".")
    return if (this < range.start) range.start else if (this > range.endInclusive) range.endInclusive else this
}
