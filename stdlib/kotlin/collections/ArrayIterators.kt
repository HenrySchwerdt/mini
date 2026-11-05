/*
 * Iterators over arrays (`val it = array.iterator()`; `for (x in array)` is lowered to an index loop by the backend).
 */
package kotlin.collections

private class ByteArrayIterator(private val array: ByteArray) : ByteIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < array.size

    override fun nextByte(): Byte = if (index < array.size) array[index++] else throw NoSuchElementException(index.toString())
}

public operator fun ByteArray.iterator(): ByteIterator = ByteArrayIterator(this)

private class ShortArrayIterator(private val array: ShortArray) : ShortIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < array.size

    override fun nextShort(): Short = if (index < array.size) array[index++] else throw NoSuchElementException(index.toString())
}

public operator fun ShortArray.iterator(): ShortIterator = ShortArrayIterator(this)

private class IntArrayIterator(private val array: IntArray) : IntIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < array.size

    override fun nextInt(): Int = if (index < array.size) array[index++] else throw NoSuchElementException(index.toString())
}

public operator fun IntArray.iterator(): IntIterator = IntArrayIterator(this)

private class LongArrayIterator(private val array: LongArray) : LongIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < array.size

    override fun nextLong(): Long = if (index < array.size) array[index++] else throw NoSuchElementException(index.toString())
}

public operator fun LongArray.iterator(): LongIterator = LongArrayIterator(this)

private class FloatArrayIterator(private val array: FloatArray) : FloatIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < array.size

    override fun nextFloat(): Float = if (index < array.size) array[index++] else throw NoSuchElementException(index.toString())
}

public operator fun FloatArray.iterator(): FloatIterator = FloatArrayIterator(this)

private class DoubleArrayIterator(private val array: DoubleArray) : DoubleIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < array.size

    override fun nextDouble(): Double = if (index < array.size) array[index++] else throw NoSuchElementException(index.toString())
}

public operator fun DoubleArray.iterator(): DoubleIterator = DoubleArrayIterator(this)

private class CharArrayIterator(private val array: CharArray) : CharIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < array.size

    override fun nextChar(): Char = if (index < array.size) array[index++] else throw NoSuchElementException(index.toString())
}

public operator fun CharArray.iterator(): CharIterator = CharArrayIterator(this)

private class BooleanArrayIterator(private val array: BooleanArray) : BooleanIterator() {
    private var index = 0

    override fun hasNext(): Boolean = index < array.size

    override fun nextBoolean(): Boolean = if (index < array.size) array[index++] else throw NoSuchElementException(index.toString())
}

public operator fun BooleanArray.iterator(): BooleanIterator = BooleanArrayIterator(this)

private class ObjectArrayIterator<T>(private val array: Array<out T>) : Iterator<T> {
    private var index = 0

    override fun hasNext(): Boolean = index < array.size

    override fun next(): T = if (index < array.size) array[index++] else throw NoSuchElementException(index.toString())
}

public operator fun <T> Array<out T>.iterator(): Iterator<T> = ObjectArrayIterator(this)
