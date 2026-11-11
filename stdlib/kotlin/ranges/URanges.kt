/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * UIntRange / ULongRange and their progressions (package kotlin.ranges, as in kotlin-stdlib).
 */
package kotlin.ranges

public open class UIntProgression internal constructor(start: UInt, endInclusive: UInt, public val step: Int) : Iterable<UInt> {
    public val first: UInt = start
    public val last: UInt = lastElement(start, endInclusive, step)
    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last
    override fun iterator(): Iterator<UInt> = UIntProgressionIterator(first, last, step)
    override fun equals(other: Any?): Boolean =
        other is UIntProgression && (isEmpty() && other.isEmpty() || first == other.first && last == other.last && step == other.step)
    override fun hashCode(): Int = if (isEmpty()) -1 else 31 * (31 * first.data + last.data) + step
    override fun toString(): String = if (step > 0) "$first..$last step $step" else "$first downTo $last step ${-step}"

    public companion object {
        public fun fromClosedRange(rangeStart: UInt, rangeEnd: UInt, step: Int): UIntProgression = UIntProgression(rangeStart, rangeEnd, step)
        internal fun lastElement(start: UInt, end: UInt, step: Int): UInt {
            if (step > 0) {
                if (start >= end) return end
                val diff = end.toLong() - start.toLong()
                return UInt((start.toLong() + diff - diff % step.toLong()).toInt())
            }
            if (start <= end) return end
            val diff = start.toLong() - end.toLong()
            val s = -step.toLong()
            return UInt((start.toLong() - (diff - diff % s)).toInt())
        }
    }
}

public class UIntRange(start: UInt, endInclusive: UInt) : UIntProgression(start, endInclusive, 1), ClosedRange<UInt>, OpenEndRange<UInt> {
    override val endExclusive: UInt
        get() {
            if (last == UInt.MAX_VALUE) error("Cannot return the exclusive upper bound of a range that includes MAX_VALUE.")
            return last + 1u
        }
    override val start: UInt get() = first
    override val endInclusive: UInt get() = last
    override fun contains(value: UInt): Boolean = value >= first && value <= last
    override fun isEmpty(): Boolean = first > last
    override fun equals(other: Any?): Boolean =
        other is UIntRange && (isEmpty() && other.isEmpty() || first == other.first && last == other.last)
    override fun hashCode(): Int = if (isEmpty()) -1 else (31 * first.data + last.data)
    override fun toString(): String = "$first..$last"
    public companion object {
        public val EMPTY: UIntRange = UIntRange(UInt(-1), UInt(0))
    }
}

private class UIntProgressionIterator(first: UInt, last: UInt, private val step: Int) : Iterator<UInt> {
    private val finalElement: UInt = last
    private var hasNext: Boolean = if (step > 0) first <= last else first >= last
    private var next: UInt = if (hasNext) first else last
    override fun hasNext(): Boolean = hasNext
    override fun next(): UInt {
        val value = next
        if (value == finalElement) {
            if (!hasNext) throw NoSuchElementException()
            hasNext = false
        } else {
            next = UInt(next.data + step)
        }
        return value
    }
}

public infix fun UInt.until(to: UInt): UIntRange = if (to <= UInt.MIN_VALUE) UIntRange.EMPTY else UIntRange(this, UInt(to.data - 1))
public infix fun UInt.downTo(to: UInt): UIntProgression = UIntProgression(this, to, -1)
public infix fun UIntProgression.step(step: Int): UIntProgression {
    if (step <= 0) throw IllegalArgumentException("Step must be positive, was: $step.")
    return UIntProgression(first, last, if (this.step > 0) step else -step)
}
public fun UIntProgression.reversed(): UIntProgression = UIntProgression(last, first, -step)

public open class ULongProgression internal constructor(start: ULong, endInclusive: ULong, public val step: Long) : Iterable<ULong> {
    init {
        if (step == 0L) throw IllegalArgumentException("Step must be non-zero.")
        if (step == Long.MIN_VALUE) throw IllegalArgumentException("Step must be greater than Long.MIN_VALUE to avoid overflow on negation.")
    }
    public val first: ULong = start
    public val last: ULong = lastElement(start, endInclusive, step)
    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last
    override fun iterator(): Iterator<ULong> = ULongProgressionIterator(first, last, step)
    override fun equals(other: Any?): Boolean =
        other is ULongProgression && (isEmpty() && other.isEmpty() || first == other.first && last == other.last && step == other.step)
    override fun hashCode(): Int = if (isEmpty()) -1 else (31 * (31 * first.data.hashCode() + last.data.hashCode()) + step.hashCode())
    override fun toString(): String = if (step > 0) "$first..$last step $step" else "$first downTo $last step ${-step}"

    public companion object {
        public fun fromClosedRange(rangeStart: ULong, rangeEnd: ULong, step: Long): ULongProgression = ULongProgression(rangeStart, rangeEnd, step)
        internal fun lastElement(start: ULong, end: ULong, step: Long): ULong {
            if (step > 0) {
                if (start >= end) return end
                val diff = end - start
                return start + (diff - diff % ULong(step))
            }
            if (start <= end) return end
            val diff = start - end
            return start - (diff - diff % ULong(-step))
        }
    }
}

public class ULongRange(start: ULong, endInclusive: ULong) : ULongProgression(start, endInclusive, 1L), ClosedRange<ULong>, OpenEndRange<ULong> {
    override val endExclusive: ULong
        get() {
            if (last == ULong.MAX_VALUE) error("Cannot return the exclusive upper bound of a range that includes MAX_VALUE.")
            return last + 1uL
        }
    override val start: ULong get() = first
    override val endInclusive: ULong get() = last
    override fun contains(value: ULong): Boolean = value >= first && value <= last
    override fun isEmpty(): Boolean = first > last
    override fun equals(other: Any?): Boolean =
        other is ULongRange && (isEmpty() && other.isEmpty() || first == other.first && last == other.last)
    override fun hashCode(): Int =
        if (isEmpty()) -1 else (31 * (first.data xor (first.data ushr 32)).toInt() + (last.data xor (last.data ushr 32)).toInt())
    override fun toString(): String = "$first..$last"
    public companion object {
        public val EMPTY: ULongRange = ULongRange(ULong(-1L), ULong(0L))
    }
}

private class ULongProgressionIterator(first: ULong, last: ULong, private val step: Long) : Iterator<ULong> {
    private val finalElement: ULong = last
    private var hasNext: Boolean = if (step > 0) first <= last else first >= last
    private var next: ULong = if (hasNext) first else last
    override fun hasNext(): Boolean = hasNext
    override fun next(): ULong {
        val value = next
        if (value == finalElement) {
            if (!hasNext) throw NoSuchElementException()
            hasNext = false
        } else {
            next = ULong(next.data + step)
        }
        return value
    }
}

public infix fun ULong.until(to: ULong): ULongRange = if (to <= ULong.MIN_VALUE) ULongRange(ULong(-1L), ULong(0L)) else ULongRange(this, ULong(to.data - 1L))
public infix fun ULong.downTo(to: ULong): ULongProgression = ULongProgression(this, to, -1L)
public infix fun ULongProgression.step(step: Long): ULongProgression {
    if (step <= 0L) throw IllegalArgumentException("Step must be positive, was: $step.")
    return ULongProgression(first, last, if (this.step > 0) step else -step)
}
public fun ULongProgression.reversed(): ULongProgression = ULongProgression(last, first, -step)
public operator fun UInt.rangeUntil(to: UInt): UIntRange = this until to
public operator fun ULong.rangeUntil(to: ULong): ULongRange = this until to

// UByte and UShort have no range classes of their own: as in kotlin-stdlib, their ranges are UIntRange / UIntProgression.
public operator fun UByte.rangeTo(other: UByte): UIntRange = UIntRange(this.toUInt(), other.toUInt())
public operator fun UShort.rangeTo(other: UShort): UIntRange = UIntRange(this.toUInt(), other.toUInt())
public infix fun UByte.downTo(to: UByte): UIntProgression = this.toUInt() downTo to.toUInt()
public infix fun UShort.downTo(to: UShort): UIntProgression = this.toUInt() downTo to.toUInt()
public infix fun UByte.until(to: UByte): UIntRange = this.toUInt() until to.toUInt()
public infix fun UShort.until(to: UShort): UIntRange = this.toUInt() until to.toUInt()
public operator fun UByte.rangeUntil(to: UByte): UIntRange = this.toUInt() until to.toUInt()
public operator fun UShort.rangeUntil(to: UShort): UIntRange = this.toUInt() until to.toUInt()
