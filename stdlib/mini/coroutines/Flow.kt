/*
 * mini.coroutines: cold flows (flow { }, collect, map, filter, take, onEach, toList, first, ...). A flow is a suspend function that
 * calls a collector; operators wrap the collector.
 */
package mini.coroutines

import kotlin.coroutines.*

public fun interface FlowCollector<in T> {
    public suspend fun emit(value: T)
}

public interface Flow<out T> {
    public suspend fun collect(collector: FlowCollector<T>)
}

private class SafeFlow<T>(private val block: suspend FlowCollector<T>.() -> Unit) : Flow<T> {
    override suspend fun collect(collector: FlowCollector<T>) {
        val b = block
        collector.b()
    }
}

public fun <T> flow(block: suspend FlowCollector<T>.() -> Unit): Flow<T> = SafeFlow<T>(block)

public fun <T> flowOf(vararg elements: T): Flow<T> = flow<T> {
    for (i in 0 until elements.size) emit(elements[i])
}

public fun <T> Iterable<T>.asFlow(): Flow<T> = flow<T> {
    for (e in this@asFlow) emit(e)
}

public fun <T, R> Flow<T>.map(transform: suspend (T) -> R): Flow<R> {
    val up = this
    return flow<R> {
        val down = this
        up.collect { v -> down.emit(transform(v)) }
    }
}

public fun <T> Flow<T>.filter(predicate: suspend (T) -> Boolean): Flow<T> {
    val up = this
    return flow<T> {
        val down = this
        up.collect { v -> if (predicate(v)) down.emit(v) }
    }
}

public fun <T> Flow<T>.onEach(action: suspend (T) -> Unit): Flow<T> {
    val up = this
    return flow<T> {
        val down = this
        up.collect { v ->
            action(v)
            down.emit(v)
        }
    }
}

/** thrown to stop the upstream of `take` */
private class AbortFlowException : CancellationException("Flow was aborted")

public fun <T> Flow<T>.take(count: Int): Flow<T> {
    val up = this
    return flow<T> {
        val down = this
        if (count > 0) {
            var left = count
            val abort = AbortFlowException()
            try {
                up.collect { v ->
                    left--
                    down.emit(v)
                    if (left == 0) throw abort
                }
            } catch (e: AbortFlowException) {
                if (e !== abort) throw e
            }
        }
    }
}

public suspend fun <T> Flow<T>.toList(): List<T> {
    val out = ArrayList<T>()
    collect { v -> out.add(v) }
    return out
}

public suspend fun <T> Flow<T>.first(): T {
    var result: Any? = null
    var found = false
    val abort = AbortFlowException()
    try {
        collect { v ->
            result = v
            found = true
            throw abort
        }
    } catch (e: AbortFlowException) {
        if (e !== abort) throw e
    }
    if (!found) throw NoSuchElementException("Expected at least one element")
    @Suppress("UNCHECKED_CAST")
    return result as T
}

public suspend fun Flow<Int>.sum(): Int {
    var s = 0
    collect { v -> s += v }
    return s
}

public fun <T> Flow<T>.launchIn(scope: CoroutineScope): Job = scope.launch {
    collect { _ -> }
}
