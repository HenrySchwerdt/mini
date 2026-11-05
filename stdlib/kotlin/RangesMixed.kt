/*
 * Ported from kotlin-stdlib generated/_Ranges.kt: downTo / until / rangeUntil between the integral types other than the
 * Int/Long/Char pairs of Ranges.kt (Byte, Short and mixed widths).
 */
package kotlin.ranges

public infix fun Byte.downTo(to: Byte): IntProgression = IntProgression.fromClosedRange(this.toInt(), to.toInt(), -1)

public infix fun Byte.until(to: Byte): IntRange = this.toInt() until to.toInt()

public operator fun Byte.rangeUntil(to: Byte): IntRange = this.toInt() until to.toInt()

public infix fun Byte.downTo(to: Short): IntProgression = IntProgression.fromClosedRange(this.toInt(), to.toInt(), -1)

public infix fun Byte.until(to: Short): IntRange = this.toInt() until to.toInt()

public operator fun Byte.rangeUntil(to: Short): IntRange = this.toInt() until to.toInt()

public infix fun Byte.downTo(to: Int): IntProgression = IntProgression.fromClosedRange(this.toInt(), to, -1)

public infix fun Byte.until(to: Int): IntRange = this.toInt() until to

public operator fun Byte.rangeUntil(to: Int): IntRange = this.toInt() until to

public infix fun Byte.downTo(to: Long): LongProgression = LongProgression.fromClosedRange(this.toLong(), to, -1L)

public infix fun Byte.until(to: Long): LongRange = this.toLong() until to

public operator fun Byte.rangeUntil(to: Long): LongRange = this.toLong() until to

public infix fun Short.downTo(to: Byte): IntProgression = IntProgression.fromClosedRange(this.toInt(), to.toInt(), -1)

public infix fun Short.until(to: Byte): IntRange = this.toInt() until to.toInt()

public operator fun Short.rangeUntil(to: Byte): IntRange = this.toInt() until to.toInt()

public infix fun Short.downTo(to: Short): IntProgression = IntProgression.fromClosedRange(this.toInt(), to.toInt(), -1)

public infix fun Short.until(to: Short): IntRange = this.toInt() until to.toInt()

public operator fun Short.rangeUntil(to: Short): IntRange = this.toInt() until to.toInt()

public infix fun Short.downTo(to: Int): IntProgression = IntProgression.fromClosedRange(this.toInt(), to, -1)

public infix fun Short.until(to: Int): IntRange = this.toInt() until to

public operator fun Short.rangeUntil(to: Int): IntRange = this.toInt() until to

public infix fun Short.downTo(to: Long): LongProgression = LongProgression.fromClosedRange(this.toLong(), to, -1L)

public infix fun Short.until(to: Long): LongRange = this.toLong() until to

public operator fun Short.rangeUntil(to: Long): LongRange = this.toLong() until to

public infix fun Int.downTo(to: Byte): IntProgression = IntProgression.fromClosedRange(this, to.toInt(), -1)

public infix fun Int.until(to: Byte): IntRange = this until to.toInt()

public operator fun Int.rangeUntil(to: Byte): IntRange = this until to.toInt()

public infix fun Int.downTo(to: Short): IntProgression = IntProgression.fromClosedRange(this, to.toInt(), -1)

public infix fun Int.until(to: Short): IntRange = this until to.toInt()

public operator fun Int.rangeUntil(to: Short): IntRange = this until to.toInt()

public infix fun Int.downTo(to: Long): LongProgression = LongProgression.fromClosedRange(this.toLong(), to, -1L)

public infix fun Int.until(to: Long): LongRange = this.toLong() until to

public operator fun Int.rangeUntil(to: Long): LongRange = this.toLong() until to

public infix fun Long.downTo(to: Byte): LongProgression = LongProgression.fromClosedRange(this, to.toLong(), -1L)

public infix fun Long.until(to: Byte): LongRange = this until to.toLong()

public operator fun Long.rangeUntil(to: Byte): LongRange = this until to.toLong()

public infix fun Long.downTo(to: Short): LongProgression = LongProgression.fromClosedRange(this, to.toLong(), -1L)

public infix fun Long.until(to: Short): LongRange = this until to.toLong()

public operator fun Long.rangeUntil(to: Short): LongRange = this until to.toLong()

public infix fun Long.downTo(to: Int): LongProgression = LongProgression.fromClosedRange(this, to.toLong(), -1L)

public infix fun Long.until(to: Int): LongRange = this until to.toLong()

public operator fun Long.rangeUntil(to: Int): LongRange = this until to.toLong()
