/*
 * Mini stdlib: kotlin.collections.AbstractIterator (an iterator that computes its elements one at a time).
 */
package kotlin.collections

public abstract class AbstractIterator<T> : Iterator<T> {
    // 0: ready (nextValue holds the next element), 1: not ready, 2: done, 3: failed (computeNext threw)
    private var state = 1
    private var nextValue: T? = null

    override fun hasNext(): Boolean {
        if (state == 3) throw IllegalArgumentException("Failed requirement.")
        if (state == 2) return false
        if (state == 0) return true
        return tryToComputeNext()
    }

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        state = 1
        @Suppress("UNCHECKED_CAST")
        return nextValue as T
    }

    private fun tryToComputeNext(): Boolean {
        state = 3
        computeNext()
        return state == 0
    }

    /** Computes the next item in the iterator: calls [setNext] with it or [done] when there is none. */
    protected abstract fun computeNext(): Unit

    protected fun setNext(value: T) {
        nextValue = value
        state = 0
    }

    protected fun done() {
        state = 2
    }
}
