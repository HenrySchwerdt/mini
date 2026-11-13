/*
 * java.util.concurrent.atomic: AtomicIntegerArray, AtomicLongArray, AtomicReferenceArray, LongAdder, DoubleAdder, LongAccumulator,
 * DoubleAccumulator, AtomicStampedReference, AtomicMarkableReference. The integer arrays and the adders are lock free (element atomics of the
 * runtime); the reference arrays and the stamped / marked references take a mutex.
 */
package java.util.concurrent.atomic

import java.util.function.BinaryOperator
import java.util.function.IntBinaryOperator
import java.util.function.IntUnaryOperator
import java.util.function.LongBinaryOperator
import java.util.function.LongUnaryOperator
import java.util.function.UnaryOperator
import mini.concurrent.Lock
import mini.internal.*

private fun checkIndex(i: Int, n: Int) {
    if (i < 0 || i >= n) throw IndexOutOfBoundsException("index $i")
}

public class AtomicIntegerArray {
    private val a: IntArray

    public constructor(length: Int) {
        a = IntArray(length)
    }

    public constructor(array: IntArray) {
        a = array.copyOf()
    }

    public fun length(): Int = a.size
    public fun get(i: Int): Int { checkIndex(i, a.size); return atomicIntGetAt(a, i) }
    public fun set(i: Int, v: Int) { checkIndex(i, a.size); atomicIntSetAt(a, i, v) }
    public fun lazySet(i: Int, v: Int) { set(i, v) }
    public fun getAndSet(i: Int, v: Int): Int { checkIndex(i, a.size); return atomicIntExchangeAt(a, i, v) }
    public fun compareAndSet(i: Int, expect: Int, update: Int): Boolean { checkIndex(i, a.size); return atomicIntCasAt(a, i, expect, update) }
    public fun weakCompareAndSet(i: Int, expect: Int, update: Int): Boolean = compareAndSet(i, expect, update)
    public fun getAndAdd(i: Int, d: Int): Int { checkIndex(i, a.size); return atomicIntAddAt(a, i, d) - d }
    public fun addAndGet(i: Int, d: Int): Int { checkIndex(i, a.size); return atomicIntAddAt(a, i, d) }
    public fun incrementAndGet(i: Int): Int = addAndGet(i, 1)
    public fun decrementAndGet(i: Int): Int = addAndGet(i, -1)
    public fun getAndIncrement(i: Int): Int = getAndAdd(i, 1)
    public fun getAndDecrement(i: Int): Int = getAndAdd(i, -1)

    public fun updateAndGet(i: Int, f: IntUnaryOperator): Int {
        while (true) {
            val prev = get(i)
            val next = f.applyAsInt(prev)
            if (compareAndSet(i, prev, next)) return next
        }
    }

    public fun getAndUpdate(i: Int, f: IntUnaryOperator): Int {
        while (true) {
            val prev = get(i)
            if (compareAndSet(i, prev, f.applyAsInt(prev))) return prev
        }
    }

    public fun accumulateAndGet(i: Int, x: Int, f: IntBinaryOperator): Int {
        while (true) {
            val prev = get(i)
            val next = f.applyAsInt(prev, x)
            if (compareAndSet(i, prev, next)) return next
        }
    }

    public fun getAndAccumulate(i: Int, x: Int, f: IntBinaryOperator): Int {
        while (true) {
            val prev = get(i)
            if (compareAndSet(i, prev, f.applyAsInt(prev, x))) return prev
        }
    }

    public override fun toString(): String {
        val sb = StringBuilder("[")
        for (i in 0 until a.size) {
            if (i > 0) sb.append(", ")
            sb.append(get(i))
        }
        return sb.append("]").toString()
    }
}

public class AtomicLongArray {
    private val a: LongArray

    public constructor(length: Int) {
        a = LongArray(length)
    }

    public constructor(array: LongArray) {
        a = array.copyOf()
    }

    public fun length(): Int = a.size
    public fun get(i: Int): Long { checkIndex(i, a.size); return atomicLongGetAt(a, i) }
    public fun set(i: Int, v: Long) { checkIndex(i, a.size); atomicLongSetAt(a, i, v) }
    public fun lazySet(i: Int, v: Long) { set(i, v) }
    public fun getAndSet(i: Int, v: Long): Long { checkIndex(i, a.size); return atomicLongExchangeAt(a, i, v) }
    public fun compareAndSet(i: Int, expect: Long, update: Long): Boolean { checkIndex(i, a.size); return atomicLongCasAt(a, i, expect, update) }
    public fun weakCompareAndSet(i: Int, expect: Long, update: Long): Boolean = compareAndSet(i, expect, update)
    public fun getAndAdd(i: Int, d: Long): Long { checkIndex(i, a.size); return atomicLongAddAt(a, i, d) - d }
    public fun addAndGet(i: Int, d: Long): Long { checkIndex(i, a.size); return atomicLongAddAt(a, i, d) }
    public fun incrementAndGet(i: Int): Long = addAndGet(i, 1L)
    public fun decrementAndGet(i: Int): Long = addAndGet(i, -1L)
    public fun getAndIncrement(i: Int): Long = getAndAdd(i, 1L)
    public fun getAndDecrement(i: Int): Long = getAndAdd(i, -1L)

    public fun updateAndGet(i: Int, f: LongUnaryOperator): Long {
        while (true) {
            val prev = get(i)
            val next = f.applyAsLong(prev)
            if (compareAndSet(i, prev, next)) return next
        }
    }

    public fun getAndUpdate(i: Int, f: LongUnaryOperator): Long {
        while (true) {
            val prev = get(i)
            if (compareAndSet(i, prev, f.applyAsLong(prev))) return prev
        }
    }

    public fun accumulateAndGet(i: Int, x: Long, f: LongBinaryOperator): Long {
        while (true) {
            val prev = get(i)
            val next = f.applyAsLong(prev, x)
            if (compareAndSet(i, prev, next)) return next
        }
    }

    public fun getAndAccumulate(i: Int, x: Long, f: LongBinaryOperator): Long {
        while (true) {
            val prev = get(i)
            if (compareAndSet(i, prev, f.applyAsLong(prev, x))) return prev
        }
    }

    public override fun toString(): String {
        val sb = StringBuilder("[")
        for (i in 0 until a.size) {
            if (i > 0) sb.append(", ")
            sb.append(get(i))
        }
        return sb.append("]").toString()
    }
}

public class AtomicReferenceArray<E> {
    private val a: Array<Any?>
    private val lock = Lock()

    public constructor(length: Int) {
        a = arrayOfNulls<Any?>(length)
    }

    public constructor(array: Array<E>) {
        a = arrayOfNulls<Any?>(array.size)
        for (i in 0 until array.size) a[i] = array[i]
    }

    public fun length(): Int = a.size

    @Suppress("UNCHECKED_CAST")
    public fun get(i: Int): E? {
        checkIndex(i, a.size)
        lock.lock()
        val r = a[i]
        lock.unlock()
        return r as E?
    }

    public fun set(i: Int, v: E?) {
        checkIndex(i, a.size)
        lock.lock()
        a[i] = v
        lock.unlock()
    }

    public fun lazySet(i: Int, v: E?) { set(i, v) }

    @Suppress("UNCHECKED_CAST")
    public fun getAndSet(i: Int, v: E?): E? {
        checkIndex(i, a.size)
        lock.lock()
        val r = a[i]
        a[i] = v
        lock.unlock()
        return r as E?
    }

    public fun compareAndSet(i: Int, expect: E?, update: E?): Boolean {
        checkIndex(i, a.size)
        lock.lock()
        val ok = a[i] === expect
        if (ok) a[i] = update
        lock.unlock()
        return ok
    }

    public fun weakCompareAndSet(i: Int, expect: E?, update: E?): Boolean = compareAndSet(i, expect, update)

    public fun updateAndGet(i: Int, f: UnaryOperator<E?>): E? {
        while (true) {
            val prev = get(i)
            val next = f.apply(prev)
            if (compareAndSet(i, prev, next)) return next
        }
    }

    public fun getAndUpdate(i: Int, f: UnaryOperator<E?>): E? {
        while (true) {
            val prev = get(i)
            if (compareAndSet(i, prev, f.apply(prev))) return prev
        }
    }

    public fun accumulateAndGet(i: Int, x: E?, f: BinaryOperator<E?>): E? {
        while (true) {
            val prev = get(i)
            val next = f.apply(prev, x)
            if (compareAndSet(i, prev, next)) return next
        }
    }

    public override fun toString(): String {
        val sb = StringBuilder("[")
        for (i in 0 until a.size) {
            if (i > 0) sb.append(", ")
            sb.append(get(i))
        }
        return sb.append("]").toString()
    }
}

private const val ADDER_CELLS = 16

/** One cache line (8 longs) per cell, the cell chosen by the thread id: threads that add at the same time touch different lines. */
private fun cellOf(): Int = ((sysThreadId() and (ADDER_CELLS - 1).toLong()).toInt()) * 8

public class LongAdder : Number() {
    private val cells = LongArray(ADDER_CELLS * 8)

    public fun add(x: Long) { atomicLongAddAt(cells, cellOf(), x) }
    public fun increment() { add(1L) }
    public fun decrement() { add(-1L) }

    public fun sum(): Long {
        var s = 0L
        for (i in 0 until ADDER_CELLS) s += atomicLongGetAt(cells, i * 8)
        return s
    }

    public fun reset() {
        for (i in 0 until ADDER_CELLS) atomicLongSetAt(cells, i * 8, 0L)
    }

    public fun sumThenReset(): Long {
        var s = 0L
        for (i in 0 until ADDER_CELLS) s += atomicLongExchangeAt(cells, i * 8, 0L)
        return s
    }

    public override fun toInt(): Int = sum().toInt()
    public override fun toLong(): Long = sum()
    public override fun toFloat(): Float = sum().toFloat()
    public override fun toDouble(): Double = sum().toDouble()
    public override fun toShort(): Short = sum().toShort()
    public override fun toByte(): Byte = sum().toByte()
    public override fun toString(): String = sum().toString()
}

public class DoubleAdder : Number() {
    private val cells = LongArray(ADDER_CELLS * 8)

    public fun add(x: Double) {
        val i = cellOf()
        while (true) {
            val prev = atomicLongGetAt(cells, i)
            val next = (Double.fromBits(prev) + x).toBits()
            if (atomicLongCasAt(cells, i, prev, next)) return
        }
    }

    public fun sum(): Double {
        var s = 0.0
        for (i in 0 until ADDER_CELLS) s += Double.fromBits(atomicLongGetAt(cells, i * 8))
        return s
    }

    public fun reset() {
        for (i in 0 until ADDER_CELLS) atomicLongSetAt(cells, i * 8, 0L)
    }

    public fun sumThenReset(): Double {
        var s = 0.0
        for (i in 0 until ADDER_CELLS) s += Double.fromBits(atomicLongExchangeAt(cells, i * 8, 0L))
        return s
    }

    public override fun toInt(): Int = sum().toInt()
    public override fun toLong(): Long = sum().toLong()
    public override fun toFloat(): Float = sum().toFloat()
    public override fun toDouble(): Double = sum()
    public override fun toShort(): Short = sum().toInt().toShort()
    public override fun toByte(): Byte = sum().toInt().toByte()
    public override fun toString(): String = sum().toString()
}

public class LongAccumulator(private val function: LongBinaryOperator, private val identity: Long) : Number() {
    private val cell = LongArray(1)

    init {
        atomicLongSet(cell, identity)
    }

    public fun accumulate(x: Long) {
        while (true) {
            val prev = atomicLongGet(cell)
            if (atomicLongCas(cell, prev, function.applyAsLong(prev, x))) return
        }
    }

    public fun get(): Long = atomicLongGet(cell)
    public fun reset() { atomicLongSet(cell, identity) }
    public fun getThenReset(): Long = atomicLongExchange(cell, identity)

    public override fun toInt(): Int = get().toInt()
    public override fun toLong(): Long = get()
    public override fun toFloat(): Float = get().toFloat()
    public override fun toDouble(): Double = get().toDouble()
    public override fun toShort(): Short = get().toShort()
    public override fun toByte(): Byte = get().toByte()
    public override fun toString(): String = get().toString()
}

public class DoubleAccumulator(private val function: java.util.function.DoubleBinaryOperator, private val identity: Double) : Number() {
    private val cell = LongArray(1)

    init {
        atomicLongSet(cell, identity.toBits())
    }

    public fun accumulate(x: Double) {
        while (true) {
            val prev = atomicLongGet(cell)
            if (atomicLongCas(cell, prev, function.applyAsDouble(Double.fromBits(prev), x).toBits())) return
        }
    }

    public fun get(): Double = Double.fromBits(atomicLongGet(cell))
    public fun reset() { atomicLongSet(cell, identity.toBits()) }
    public fun getThenReset(): Double = Double.fromBits(atomicLongExchange(cell, identity.toBits()))

    public override fun toInt(): Int = get().toInt()
    public override fun toLong(): Long = get().toLong()
    public override fun toFloat(): Float = get().toFloat()
    public override fun toDouble(): Double = get()
    public override fun toShort(): Short = get().toInt().toShort()
    public override fun toByte(): Byte = get().toInt().toByte()
    public override fun toString(): String = get().toString()
}

public class AtomicStampedReference<V>(initialRef: V?, initialStamp: Int) {
    private val lock = Lock()
    private var ref: V? = initialRef
    private var stampValue: Int = initialStamp

    public val reference: V?
        get() {
            lock.lock()
            val r = ref
            lock.unlock()
            return r
        }

    public val stamp: Int
        get() {
            lock.lock()
            val r = stampValue
            lock.unlock()
            return r
        }

    public fun get(stampHolder: IntArray): V? {
        lock.lock()
        stampHolder[0] = stampValue
        val r = ref
        lock.unlock()
        return r
    }

    public fun compareAndSet(expectedReference: V?, newReference: V?, expectedStamp: Int, newStamp: Int): Boolean {
        lock.lock()
        val ok = ref === expectedReference && stampValue == expectedStamp
        if (ok) {
            ref = newReference
            stampValue = newStamp
        }
        lock.unlock()
        return ok
    }

    public fun weakCompareAndSet(expectedReference: V?, newReference: V?, expectedStamp: Int, newStamp: Int): Boolean =
        compareAndSet(expectedReference, newReference, expectedStamp, newStamp)

    public fun set(newReference: V?, newStamp: Int) {
        lock.lock()
        ref = newReference
        stampValue = newStamp
        lock.unlock()
    }

    public fun attemptStamp(expectedReference: V?, newStamp: Int): Boolean {
        lock.lock()
        val ok = ref === expectedReference
        if (ok) stampValue = newStamp
        lock.unlock()
        return ok
    }
}

public class AtomicMarkableReference<V>(initialRef: V?, initialMark: Boolean) {
    private val lock = Lock()
    private var ref: V? = initialRef
    private var markValue: Boolean = initialMark

    public val reference: V?
        get() {
            lock.lock()
            val r = ref
            lock.unlock()
            return r
        }

    public val isMarked: Boolean
        get() {
            lock.lock()
            val r = markValue
            lock.unlock()
            return r
        }

    public fun get(markHolder: BooleanArray): V? {
        lock.lock()
        markHolder[0] = markValue
        val r = ref
        lock.unlock()
        return r
    }

    public fun compareAndSet(expectedReference: V?, newReference: V?, expectedMark: Boolean, newMark: Boolean): Boolean {
        lock.lock()
        val ok = ref === expectedReference && markValue == expectedMark
        if (ok) {
            ref = newReference
            markValue = newMark
        }
        lock.unlock()
        return ok
    }

    public fun set(newReference: V?, newMark: Boolean) {
        lock.lock()
        ref = newReference
        markValue = newMark
        lock.unlock()
    }

    public fun attemptMark(expectedReference: V?, newMark: Boolean): Boolean {
        lock.lock()
        val ok = ref === expectedReference
        if (ok) markValue = newMark
        lock.unlock()
        return ok
    }
}
