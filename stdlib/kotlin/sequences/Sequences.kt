/*
 * Sequences and the `sequence { }` / `iterator { }` builders (docs/coroutines.md). A reduced port of kotlin-stdlib
 * sequences; the builders run on the suspend-function state machines of the compiler.
 */
package kotlin.sequences

import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.SuspendMarker

public interface Sequence<out T> {
    public operator fun iterator(): Iterator<T>
}

public fun <T> Iterator<T>.asSequence(): Sequence<T> {
    val it = this
    return object : Sequence<T> {
        override fun iterator(): Iterator<T> = it
    }
}

public fun <T> Iterable<T>.asSequence(): Sequence<T> {
    val src = this
    return object : Sequence<T> {
        override fun iterator(): Iterator<T> = src.iterator()
    }
}

public fun <T> emptySequence(): Sequence<T> = object : Sequence<T> {
    override fun iterator(): Iterator<T> = EmptyIterator<T>()
}

private class EmptyIterator<T> : Iterator<T> {
    override fun hasNext(): Boolean = false
    override fun next(): T = throw NoSuchElementException()
}

public fun <T> sequenceOf(vararg elements: T): Sequence<T> = elements.asList().asSequence()

public fun <T : Any> generateSequence(seed: T?, nextFunction: (T) -> T?): Sequence<T> {
    return object : Sequence<T> {
        override fun iterator(): Iterator<T> = GeneratorIterator<T>({ seed }, nextFunction)
    }
}

public fun <T : Any> generateSequence(nextFunction: () -> T?): Sequence<T> {
    return object : Sequence<T> {
        override fun iterator(): Iterator<T> = GeneratorIterator<T>(nextFunction, { nextFunction() })
    }
}

private class GeneratorIterator<T : Any>(private val getFirst: () -> T?, private val getNext: (T) -> T?) : Iterator<T> {
    private var nextItem: T? = null
    private var state = -2 // -2: not started, -1: next item unknown, 0: done, 1: nextItem is ready

    private fun calc() {
        nextItem = if (state == -2) getFirst() else getNext(nextItem!!)
        state = if (nextItem == null) 0 else 1
    }

    override fun hasNext(): Boolean {
        if (state < 0) calc()
        return state == 1
    }

    override fun next(): T {
        if (state < 0) calc()
        if (state == 0) throw NoSuchElementException()
        state = -1
        return nextItem!!
    }
}

// ---- builders ---------------------------------------------------------------------------------------------------

public abstract class SequenceScope<in T> internal constructor() {
    public abstract suspend fun yield(value: T)
    public abstract suspend fun yieldAll(iterator: Iterator<T>)
    public suspend fun yieldAll(elements: Iterable<T>) {
        yieldAll(elements.iterator())
    }
    public suspend fun yieldAll(sequence: Sequence<T>) {
        yieldAll(sequence.iterator())
    }
}

public fun <T> sequence(block: suspend SequenceScope<T>.() -> Unit): Sequence<T> = Sequence { iterator(block) }

public fun <T> Sequence(iterator: () -> Iterator<T>): Sequence<T> = object : Sequence<T> {
    override fun iterator(): Iterator<T> = iterator()
}

public fun <T> iterator(block: suspend SequenceScope<T>.() -> Unit): Iterator<T> {
    val sink = SequenceSink()
    val it = SequenceBuilderIterator<T>(sink)
    it.nextStep = block.createCoroutineUnintercepted(BuilderScope<T>(), sink)
    return it
}

/**
 * What a `yield` hands up to the iterator: the value (or an iterator of values) and the continuation to resume. It is
 * returned by the suspend function `yield` and travels up the chain of state machines like COROUTINE_SUSPENDED; the
 * coroutine never refers to the iterator, so an abandoned sequence frees its state (reference counting has no cycle collector).
 */
internal class YieldRequest(val value: Any?, val values: Iterator<Any?>?, val next: Continuation<Unit>) : SuspendMarker()

/** The completion of the builder coroutine: remembers a failure for the iterator. */
private class SequenceSink : Continuation<Unit> {
    var failure: Throwable? = null
    override val context: CoroutineContext get() = EmptyCoroutineContext
    override fun resumeWith(result: Result<Unit>) {
        failure = result.exceptionOrNull()
    }
}

private class BuilderScope<T> : SequenceScope<T>() {
    override suspend fun yield(value: T) {
        suspendCoroutineUninterceptedOrReturn<Unit> { c -> YieldRequest(value, null, c) }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun yieldAll(iterator: Iterator<T>) {
        if (!iterator.hasNext()) return
        suspendCoroutineUninterceptedOrReturn<Unit> { c -> YieldRequest(null, iterator as Iterator<Any?>, c) }
    }
}

private class SequenceBuilderIterator<T>(private val sink: SequenceSink) : Iterator<T> {
    // 0: not ready, 1: has a value in nextValue, 2: has an iterator in nextIterator, 3: done
    private var state = 0
    private var nextValue: Any? = null
    private var nextIterator: Iterator<T>? = null
    var nextStep: Continuation<Unit>? = null

    @Suppress("UNCHECKED_CAST")
    override fun hasNext(): Boolean {
        while (true) {
            when (state) {
                0 -> {}
                1 -> return true
                2 -> {
                    val itr = nextIterator!!
                    if (itr.hasNext()) return true
                    nextIterator = null
                    state = 0
                }
                else -> return false
            }
            // run the builder to its next yield
            val step = nextStep!!
            nextStep = null
            val out = (step as BaseContinuation).resumeRaw(Result.success(Unit))
            if (out is YieldRequest) {
                nextStep = out.next
                val vs = out.values
                if (vs != null) {
                    nextIterator = vs as Iterator<T>
                    state = 2
                } else {
                    nextValue = out.value
                    state = 1
                }
            } else {
                state = 3
                val f = sink.failure
                if (f != null) throw f
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun next(): T {
        if (state == 0 || state == 2) {
            if (!hasNext()) throw NoSuchElementException()
        }
        if (state == 1) {
            state = 0
            val v = nextValue as T
            nextValue = null
            return v
        }
        if (state == 2) return nextIterator!!.next()
        throw NoSuchElementException()
    }
}

// ---- operations -------------------------------------------------------------------------------------------------

public fun <T> Sequence<T>.toList(): List<T> {
    val out = ArrayList<T>()
    for (x in this) out.add(x)
    return out
}

public fun <T> Sequence<T>.toMutableList(): MutableList<T> {
    val out = ArrayList<T>()
    for (x in this) out.add(x)
    return out
}

public fun <T> Sequence<T>.toSet(): Set<T> {
    val out = HashSet<T>()
    for (x in this) out.add(x)
    return out
}

public inline fun <T> Sequence<T>.forEach(action: (T) -> Unit) {
    for (x in this) action(x)
}

public fun <T, R> Sequence<T>.map(transform: (T) -> R): Sequence<R> {
    val src = this
    return object : Sequence<R> {
        override fun iterator(): Iterator<R> {
            val it = src.iterator()
            return object : Iterator<R> {
                override fun hasNext(): Boolean = it.hasNext()
                override fun next(): R = transform(it.next())
            }
        }
    }
}

public fun <T, R> Sequence<T>.mapIndexed(transform: (Int, T) -> R): Sequence<R> {
    val src = this
    return object : Sequence<R> {
        override fun iterator(): Iterator<R> {
            val it = src.iterator()
            var i = 0
            return object : Iterator<R> {
                override fun hasNext(): Boolean = it.hasNext()
                override fun next(): R = transform(i++, it.next())
            }
        }
    }
}

private class FilterIterator<T>(private val src: Iterator<T>, private val keep: Boolean, private val predicate: (T) -> Boolean) : Iterator<T> {
    private var state = -1 // -1: unknown, 0: done, 1: nextItem is ready
    private var nextItem: T? = null

    private fun calc() {
        while (src.hasNext()) {
            val item = src.next()
            if (predicate(item) == keep) {
                nextItem = item
                state = 1
                return
            }
        }
        state = 0
    }

    override fun hasNext(): Boolean {
        if (state == -1) calc()
        return state == 1
    }

    @Suppress("UNCHECKED_CAST")
    override fun next(): T {
        if (state == -1) calc()
        if (state == 0) throw NoSuchElementException()
        val r = nextItem as T
        nextItem = null
        state = -1
        return r
    }
}

public fun <T> Sequence<T>.filter(predicate: (T) -> Boolean): Sequence<T> {
    val src = this
    return object : Sequence<T> {
        override fun iterator(): Iterator<T> = FilterIterator(src.iterator(), true, predicate)
    }
}

public fun <T> Sequence<T>.filterNot(predicate: (T) -> Boolean): Sequence<T> {
    val src = this
    return object : Sequence<T> {
        override fun iterator(): Iterator<T> = FilterIterator(src.iterator(), false, predicate)
    }
}

public fun <T> Sequence<T>.take(n: Int): Sequence<T> {
    require(n >= 0) { "Requested element count $n is less than zero." }
    val src = this
    return object : Sequence<T> {
        override fun iterator(): Iterator<T> {
            val it = src.iterator()
            var left = n
            return object : Iterator<T> {
                override fun hasNext(): Boolean = left > 0 && it.hasNext()
                override fun next(): T {
                    if (left == 0) throw NoSuchElementException()
                    left--
                    return it.next()
                }
            }
        }
    }
}

public fun <T> Sequence<T>.drop(n: Int): Sequence<T> {
    require(n >= 0) { "Requested element count $n is less than zero." }
    val src = this
    return object : Sequence<T> {
        override fun iterator(): Iterator<T> {
            val it = src.iterator()
            var skipped = false
            return object : Iterator<T> {
                private fun skip() {
                    if (!skipped) {
                        var i = 0
                        while (i < n && it.hasNext()) {
                            it.next()
                            i++
                        }
                        skipped = true
                    }
                }
                override fun hasNext(): Boolean {
                    skip()
                    return it.hasNext()
                }
                override fun next(): T {
                    skip()
                    return it.next()
                }
            }
        }
    }
}

public fun <T> Sequence<T>.takeWhile(predicate: (T) -> Boolean): Sequence<T> {
    val src = this
    return sequence {
        for (x in src) {
            if (!predicate(x)) break
            yield(x)
        }
    }
}

public fun <T, R> Sequence<T>.flatMap(transform: (T) -> Sequence<R>): Sequence<R> {
    val src = this
    return sequence {
        for (x in src) yieldAll(transform(x))
    }
}

public fun <T> Sequence<T>.first(): T {
    val it = iterator()
    if (!it.hasNext()) throw NoSuchElementException("Sequence is empty.")
    return it.next()
}

public fun <T> Sequence<T>.firstOrNull(): T? {
    val it = iterator()
    return if (it.hasNext()) it.next() else null
}

public fun <T> Sequence<T>.count(): Int {
    var n = 0
    for (x in this) n++
    return n
}

public fun Sequence<Int>.sum(): Int {
    var s = 0
    for (x in this) s += x
    return s
}

public fun <T> Sequence<T>.joinToString(separator: CharSequence = ", ", prefix: CharSequence = "", postfix: CharSequence = ""): String {
    val sb = StringBuilder()
    sb.append(prefix)
    var first = true
    for (x in this) {
        if (!first) sb.append(separator)
        first = false
        sb.append(x.toString())
    }
    sb.append(postfix)
    return sb.toString()
}
