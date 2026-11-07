/*
 * Kotlin common atomics (kotlin.concurrent.atomics, Kotlin 2.1) over the cells of mini.concurrent. The API follows kotlin-stdlib:
 * load / store / exchange / compareAndSet / compareAndExchange / fetchAndAdd / addAndFetch, the *At forms of the array classes and
 * the update helpers. compareAndSet of a reference compares by identity (as on the other platforms).
 */
package kotlin.concurrent.atomics

import mini.concurrent.AtomicLong as MiniAtomicLong
import mini.concurrent.AtomicInt as MiniAtomicInt
import mini.concurrent.AtomicReference as MiniAtomicReference

/** Opt-in marker of the atomics API; mini does not require the opt-in. */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class ExperimentalAtomicApi

public class AtomicInt public constructor(value: Int) {
    private val cell = MiniAtomicInt(value)

    public fun load(): Int = cell.get()
    public fun store(newValue: Int) {
        cell.set(newValue)
    }
    public fun exchange(newValue: Int): Int = cell.getAndSet(newValue)
    public fun compareAndSet(expectedValue: Int, newValue: Int): Boolean = cell.compareAndSet(expectedValue, newValue)
    public fun compareAndExchange(expectedValue: Int, newValue: Int): Int {
        while (true) {
            val cur = cell.get()
            if (cur != expectedValue) return cur
            if (cell.compareAndSet(expectedValue, newValue)) return expectedValue
        }
    }
    public fun fetchAndAdd(delta: Int): Int = cell.getAndAdd(delta)
    public fun addAndFetch(delta: Int): Int = cell.addAndGet(delta)
    public override fun toString(): String = cell.get().toString()
}

public operator fun AtomicInt.plusAssign(delta: Int): Unit { this.addAndFetch(delta) }
public operator fun AtomicInt.minusAssign(delta: Int): Unit { this.addAndFetch(-delta) }
public fun AtomicInt.fetchAndIncrement(): Int = this.fetchAndAdd(1)
public fun AtomicInt.incrementAndFetch(): Int = this.addAndFetch(1)
public fun AtomicInt.decrementAndFetch(): Int = this.addAndFetch(-1)
public fun AtomicInt.fetchAndDecrement(): Int = this.fetchAndAdd(-1)

public inline fun AtomicInt.update(transform: (Int) -> Int) {
    while (true) {
        val old = load()
        if (compareAndSet(old, transform(old))) return
    }
}

public inline fun AtomicInt.updateAndFetch(transform: (Int) -> Int): Int {
    while (true) {
        val old = load()
        val new = transform(old)
        if (compareAndSet(old, new)) return new
    }
}

public inline fun AtomicInt.fetchAndUpdate(transform: (Int) -> Int): Int {
    while (true) {
        val old = load()
        if (compareAndSet(old, transform(old))) return old
    }
}

public class AtomicLong public constructor(value: Long) {
    private val cell = MiniAtomicLong(value)

    public fun load(): Long = cell.get()
    public fun store(newValue: Long) {
        cell.set(newValue)
    }
    public fun exchange(newValue: Long): Long = cell.getAndSet(newValue)
    public fun compareAndSet(expectedValue: Long, newValue: Long): Boolean = cell.compareAndSet(expectedValue, newValue)
    public fun compareAndExchange(expectedValue: Long, newValue: Long): Long {
        while (true) {
            val cur = cell.get()
            if (cur != expectedValue) return cur
            if (cell.compareAndSet(expectedValue, newValue)) return expectedValue
        }
    }
    public fun fetchAndAdd(delta: Long): Long = cell.getAndAdd(delta)
    public fun addAndFetch(delta: Long): Long = cell.addAndGet(delta)
    public override fun toString(): String = cell.get().toString()
}

public operator fun AtomicLong.plusAssign(delta: Long): Unit { this.addAndFetch(delta) }
public operator fun AtomicLong.minusAssign(delta: Long): Unit { this.addAndFetch(-delta) }
public fun AtomicLong.fetchAndIncrement(): Long = this.fetchAndAdd(1L)
public fun AtomicLong.incrementAndFetch(): Long = this.addAndFetch(1L)
public fun AtomicLong.decrementAndFetch(): Long = this.addAndFetch(-1L)
public fun AtomicLong.fetchAndDecrement(): Long = this.fetchAndAdd(-1L)

public inline fun AtomicLong.update(transform: (Long) -> Long) {
    while (true) {
        val old = load()
        if (compareAndSet(old, transform(old))) return
    }
}

public inline fun AtomicLong.updateAndFetch(transform: (Long) -> Long): Long {
    while (true) {
        val old = load()
        val new = transform(old)
        if (compareAndSet(old, new)) return new
    }
}

public inline fun AtomicLong.fetchAndUpdate(transform: (Long) -> Long): Long {
    while (true) {
        val old = load()
        if (compareAndSet(old, transform(old))) return old
    }
}

public class AtomicBoolean public constructor(value: Boolean) {
    private val cell = MiniAtomicInt(if (value) 1 else 0)

    public fun load(): Boolean = cell.get() != 0
    public fun store(newValue: Boolean) {
        cell.set(if (newValue) 1 else 0)
    }
    public fun exchange(newValue: Boolean): Boolean = cell.getAndSet(if (newValue) 1 else 0) != 0
    public fun compareAndSet(expectedValue: Boolean, newValue: Boolean): Boolean =
        cell.compareAndSet(if (expectedValue) 1 else 0, if (newValue) 1 else 0)
    public fun compareAndExchange(expectedValue: Boolean, newValue: Boolean): Boolean {
        while (true) {
            val cur = load()
            if (cur != expectedValue) return cur
            if (compareAndSet(expectedValue, newValue)) return expectedValue
        }
    }
    public override fun toString(): String = load().toString()
}

public class AtomicReference<T> public constructor(value: T) {
    private val cell = MiniAtomicReference<T>(value)

    public fun load(): T = cell.get()
    public fun store(newValue: T) {
        cell.set(newValue)
    }
    public fun exchange(newValue: T): T = cell.getAndSet(newValue)
    public fun compareAndSet(expectedValue: T, newValue: T): Boolean = cell.compareAndSet(expectedValue, newValue)
    public fun compareAndExchange(expectedValue: T, newValue: T): T {
        while (true) {
            val cur = cell.get()
            if (cur !== expectedValue) return cur
            if (cell.compareAndSet(expectedValue, newValue)) return expectedValue
        }
    }
    public override fun toString(): String = cell.get().toString()
}

public inline fun <T> AtomicReference<T>.update(transform: (T) -> T) {
    while (true) {
        val old = load()
        if (compareAndSet(old, transform(old))) return
    }
}

public inline fun <T> AtomicReference<T>.updateAndFetch(transform: (T) -> T): T {
    while (true) {
        val old = load()
        val new = transform(old)
        if (compareAndSet(old, new)) return new
    }
}

public inline fun <T> AtomicReference<T>.fetchAndUpdate(transform: (T) -> T): T {
    while (true) {
        val old = load()
        if (compareAndSet(old, transform(old))) return old
    }
}

public class AtomicIntArray {
    private val cells: Array<MiniAtomicInt>

    public constructor(size: Int) {
        cells = Array(size) { MiniAtomicInt(0) }
    }

    public constructor(array: IntArray) {
        cells = Array(array.size) { MiniAtomicInt(array[it]) }
    }

    public val size: Int get() = cells.size

    private fun cellAt(index: Int): MiniAtomicInt {
        if (index < 0 || index >= cells.size) throw IndexOutOfBoundsException("index $index, size ${cells.size}")
        return cells[index]
    }

    public fun loadAt(index: Int): Int = cellAt(index).get()
    public fun storeAt(index: Int, newValue: Int) {
        cellAt(index).set(newValue)
    }
    public fun exchangeAt(index: Int, newValue: Int): Int = cellAt(index).getAndSet(newValue)
    public fun compareAndSetAt(index: Int, expectedValue: Int, newValue: Int): Boolean =
        cellAt(index).compareAndSet(expectedValue, newValue)
    public fun compareAndExchangeAt(index: Int, expectedValue: Int, newValue: Int): Int {
        val c = cellAt(index)
        while (true) {
            val cur = c.get()
            if (cur != expectedValue) return cur
            if (c.compareAndSet(expectedValue, newValue)) return expectedValue
        }
    }
    public fun fetchAndAddAt(index: Int, delta: Int): Int = cellAt(index).getAndAdd(delta)
    public fun addAndFetchAt(index: Int, delta: Int): Int = cellAt(index).addAndGet(delta)
    public override fun toString(): String {
        val sb = StringBuilder("[")
        for (i in 0 until cells.size) {
            if (i > 0) sb.append(", ")
            sb.append(cells[i].get())
        }
        return sb.append("]").toString()
    }
}

public inline fun AtomicIntArray(size: Int, init: (Int) -> Int): AtomicIntArray = AtomicIntArray(IntArray(size) { init(it) })
public fun AtomicIntArray.fetchAndIncrementAt(index: Int): Int = this.fetchAndAddAt(index, 1)
public fun AtomicIntArray.incrementAndFetchAt(index: Int): Int = this.addAndFetchAt(index, 1)
public fun AtomicIntArray.decrementAndFetchAt(index: Int): Int = this.addAndFetchAt(index, -1)
public fun AtomicIntArray.fetchAndDecrementAt(index: Int): Int = this.fetchAndAddAt(index, -1)

public inline fun AtomicIntArray.updateAt(index: Int, transform: (Int) -> Int) {
    while (true) {
        val old = loadAt(index)
        if (compareAndSetAt(index, old, transform(old))) return
    }
}

public inline fun AtomicIntArray.updateAndFetchAt(index: Int, transform: (Int) -> Int): Int {
    while (true) {
        val old = loadAt(index)
        val new = transform(old)
        if (compareAndSetAt(index, old, new)) return new
    }
}

public inline fun AtomicIntArray.fetchAndUpdateAt(index: Int, transform: (Int) -> Int): Int {
    while (true) {
        val old = loadAt(index)
        if (compareAndSetAt(index, old, transform(old))) return old
    }
}

public class AtomicLongArray {
    private val cells: Array<MiniAtomicLong>

    public constructor(size: Int) {
        cells = Array(size) { MiniAtomicLong(0L) }
    }

    public constructor(array: LongArray) {
        cells = Array(array.size) { MiniAtomicLong(array[it]) }
    }

    public val size: Int get() = cells.size

    private fun cellAt(index: Int): MiniAtomicLong {
        if (index < 0 || index >= cells.size) throw IndexOutOfBoundsException("index $index, size ${cells.size}")
        return cells[index]
    }

    public fun loadAt(index: Int): Long = cellAt(index).get()
    public fun storeAt(index: Int, newValue: Long) {
        cellAt(index).set(newValue)
    }
    public fun exchangeAt(index: Int, newValue: Long): Long = cellAt(index).getAndSet(newValue)
    public fun compareAndSetAt(index: Int, expectedValue: Long, newValue: Long): Boolean =
        cellAt(index).compareAndSet(expectedValue, newValue)
    public fun compareAndExchangeAt(index: Int, expectedValue: Long, newValue: Long): Long {
        val c = cellAt(index)
        while (true) {
            val cur = c.get()
            if (cur != expectedValue) return cur
            if (c.compareAndSet(expectedValue, newValue)) return expectedValue
        }
    }
    public fun fetchAndAddAt(index: Int, delta: Long): Long = cellAt(index).getAndAdd(delta)
    public fun addAndFetchAt(index: Int, delta: Long): Long = cellAt(index).addAndGet(delta)
    public override fun toString(): String {
        val sb = StringBuilder("[")
        for (i in 0 until cells.size) {
            if (i > 0) sb.append(", ")
            sb.append(cells[i].get())
        }
        return sb.append("]").toString()
    }
}

public inline fun AtomicLongArray(size: Int, init: (Int) -> Long): AtomicLongArray = AtomicLongArray(LongArray(size) { init(it) })
public fun AtomicLongArray.fetchAndIncrementAt(index: Int): Long = this.fetchAndAddAt(index, 1L)
public fun AtomicLongArray.incrementAndFetchAt(index: Int): Long = this.addAndFetchAt(index, 1L)
public fun AtomicLongArray.decrementAndFetchAt(index: Int): Long = this.addAndFetchAt(index, -1L)
public fun AtomicLongArray.fetchAndDecrementAt(index: Int): Long = this.fetchAndAddAt(index, -1L)

public inline fun AtomicLongArray.updateAt(index: Int, transform: (Long) -> Long) {
    while (true) {
        val old = loadAt(index)
        if (compareAndSetAt(index, old, transform(old))) return
    }
}

public inline fun AtomicLongArray.updateAndFetchAt(index: Int, transform: (Long) -> Long): Long {
    while (true) {
        val old = loadAt(index)
        val new = transform(old)
        if (compareAndSetAt(index, old, new)) return new
    }
}

public inline fun AtomicLongArray.fetchAndUpdateAt(index: Int, transform: (Long) -> Long): Long {
    while (true) {
        val old = loadAt(index)
        if (compareAndSetAt(index, old, transform(old))) return old
    }
}

public class AtomicArray<T> public constructor(array: Array<T>) {
    private val cells: Array<MiniAtomicReference<T>> = Array(array.size) { MiniAtomicReference<T>(array[it]) }

    public val size: Int get() = cells.size

    private fun cellAt(index: Int): MiniAtomicReference<T> {
        if (index < 0 || index >= cells.size) throw IndexOutOfBoundsException("index $index, size ${cells.size}")
        return cells[index]
    }

    public fun loadAt(index: Int): T = cellAt(index).get()
    public fun storeAt(index: Int, newValue: T) {
        cellAt(index).set(newValue)
    }
    public fun exchangeAt(index: Int, newValue: T): T = cellAt(index).getAndSet(newValue)
    public fun compareAndSetAt(index: Int, expectedValue: T, newValue: T): Boolean =
        cellAt(index).compareAndSet(expectedValue, newValue)
    public fun compareAndExchangeAt(index: Int, expectedValue: T, newValue: T): T {
        val c = cellAt(index)
        while (true) {
            val cur = c.get()
            if (cur !== expectedValue) return cur
            if (c.compareAndSet(expectedValue, newValue)) return expectedValue
        }
    }
    public override fun toString(): String {
        val sb = StringBuilder("[")
        for (i in 0 until cells.size) {
            if (i > 0) sb.append(", ")
            sb.append(cells[i].get())
        }
        return sb.append("]").toString()
    }
}

public inline fun <reified T> AtomicArray(size: Int, init: (Int) -> T): AtomicArray<T> = AtomicArray(Array(size) { init(it) })

public inline fun <T> AtomicArray<T>.updateAt(index: Int, transform: (T) -> T) {
    while (true) {
        val old = loadAt(index)
        if (compareAndSetAt(index, old, transform(old))) return
    }
}

public inline fun <T> AtomicArray<T>.updateAndFetchAt(index: Int, transform: (T) -> T): T {
    while (true) {
        val old = loadAt(index)
        val new = transform(old)
        if (compareAndSetAt(index, old, new)) return new
    }
}

public inline fun <T> AtomicArray<T>.fetchAndUpdateAt(index: Int, transform: (T) -> T): T {
    while (true) {
        val old = loadAt(index)
        if (compareAndSetAt(index, old, transform(old))) return old
    }
}
