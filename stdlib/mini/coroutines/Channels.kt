/*
 * mini.coroutines: Mutex and channels (rendezvous, buffered, unlimited). All state is under [CoroutinesLock]; a suspended
 * sender or receiver is a [CancellableContinuation], removed from the queue when its coroutine is cancelled.
 */
package mini.coroutines

import kotlin.coroutines.*

// ---- Mutex ---------------------------------------------------------------------------------------------------------------------------

/** A mutex whose `lock` suspends instead of blocking the thread (not reentrant). */
public class Mutex(locked: Boolean = false) {
    private var held = locked
    private val waiters = ArrayDeque<CancellableContinuation<Unit>>()

    public val isLocked: Boolean
        get() {
            CoroutinesLock.lock()
            val r = held
            CoroutinesLock.unlock()
            return r
        }

    public fun tryLock(): Boolean {
        CoroutinesLock.lock()
        val ok = !held
        if (ok) held = true
        CoroutinesLock.unlock()
        return ok
    }

    public suspend fun lock() {
        if (tryLock()) return
        suspendCancellableCoroutine<Unit> { cont ->
            CoroutinesLock.lock()
            if (!held) {
                held = true
                CoroutinesLock.unlock()
                cont.resume(Unit)
            } else {
                waiters.addLast(cont)
                CoroutinesLock.unlock()
                val me = this
                cont.invokeOnCancellation { _ -> me.removeWaiter(cont) }
            }
        }
    }

    private fun removeWaiter(c: CancellableContinuation<Unit>) {
        CoroutinesLock.lock()
        waiters.remove(c)
        CoroutinesLock.unlock()
    }

    public fun unlock() {
        while (true) {
            CoroutinesLock.lock()
            if (!held) {
                CoroutinesLock.unlock()
                throw IllegalStateException("This mutex is not locked")
            }
            if (waiters.isEmpty()) {
                held = false
                CoroutinesLock.unlock()
                return
            }
            val next = waiters.removeFirst()
            CoroutinesLock.unlock()
            // the lock stays held and goes to the next waiter, unless that one was cancelled in the meantime
            if (next.isActive) {
                next.resume(Unit)
                return
            }
        }
    }
}

public suspend fun <T> Mutex.withLock(action: suspend () -> T): T {
    lock()
    try {
        return action()
    } finally {
        unlock()
    }
}

// ---- Channel -------------------------------------------------------------------------------------------------------------------------

public class ClosedSendChannelException(message: String?) : IllegalStateException(message)

public class ClosedReceiveChannelException(message: String?) : NoSuchElementException(message)

public interface SendChannel<in E> {
    public suspend fun send(element: E)
    public fun trySend(element: E): Boolean
    public fun close(cause: Throwable?): Boolean
}

public interface ReceiveChannel<out E> {
    public suspend fun receive(): E
    public suspend fun receiveOrNull(): E?
    public fun tryReceive(): E?
    public fun cancel(cause: CancellationException?)
    public operator fun iterator(): ChannelIterator<E>
}

public interface ChannelIterator<out E> {
    public suspend operator fun hasNext(): Boolean
    public operator fun next(): E
}

public interface Channel<E> : SendChannel<E>, ReceiveChannel<E>

public fun SendChannel<*>.close(): Boolean = close(null)

public fun ReceiveChannel<*>.cancel() {
    cancel(null)
}

public const val RENDEZVOUS: Int = 0
public const val UNLIMITED: Int = Int.MAX_VALUE
public const val BUFFERED: Int = 64

public fun <E> Channel(capacity: Int = RENDEZVOUS): Channel<E> = ChannelImpl<E>(capacity)

private class SenderEntry<E>(val cont: CancellableContinuation<Unit>, val element: E)

private class ChannelImpl<E>(private val capacity: Int) : Channel<E> {
    private val buffer = ArrayDeque<Any?>()
    private val senders = ArrayDeque<SenderEntry<E>>()
    private val receivers = ArrayDeque<CancellableContinuation<Any?>>()
    private var closed = false
    private var closeCause: Throwable? = null
    // a receiver that found the buffer empty and the channel open is parked in [receivers]; a "closed" result is this marker
    private object Closed

    // The element of a completed receive travels in the resume value: a real element, or Closed.

    public override suspend fun send(element: E) {
        // fast paths without suspending
        CoroutinesLock.lock()
        if (closed) {
            val c = closeCause
            CoroutinesLock.unlock()
            throw c ?: ClosedSendChannelException("Channel was closed")
        }
        while (receivers.isNotEmpty()) {
            val r = receivers.removeFirst()
            if (r.isActiveLocked()) {
                CoroutinesLock.unlock()
                r.resume(element)
                return
            }
        }
        if (buffer.size < capacity) {
            buffer.addLast(element)
            CoroutinesLock.unlock()
            return
        }
        CoroutinesLock.unlock()
        suspendCancellableCoroutine<Unit> { cont ->
            CoroutinesLock.lock()
            if (closed) {
                val c = closeCause
                CoroutinesLock.unlock()
                cont.resumeWithException(c ?: ClosedSendChannelException("Channel was closed"))
            } else {
                val entry = SenderEntry<E>(cont, element)
                senders.addLast(entry)
                CoroutinesLock.unlock()
                val me = this
                cont.invokeOnCancellation { _ -> me.removeSender(entry) }
            }
        }
    }

    private fun removeSender(e: SenderEntry<E>) {
        CoroutinesLock.lock()
        senders.remove(e)
        CoroutinesLock.unlock()
    }

    private fun removeReceiver(c: CancellableContinuation<Any?>) {
        CoroutinesLock.lock()
        receivers.remove(c)
        CoroutinesLock.unlock()
    }

    public override fun trySend(element: E): Boolean {
        CoroutinesLock.lock()
        if (closed) {
            CoroutinesLock.unlock()
            return false
        }
        while (receivers.isNotEmpty()) {
            val r = receivers.removeFirst()
            if (r.isActiveLocked()) {
                CoroutinesLock.unlock()
                r.resume(element)
                return true
            }
        }
        if (buffer.size < capacity) {
            buffer.addLast(element)
            CoroutinesLock.unlock()
            return true
        }
        CoroutinesLock.unlock()
        return false
    }

    /** takes an element if there is one: sets [taken] and returns it; the caller holds the lock; wakes a waiting sender */
    private var takenFlag = false

    @Suppress("UNCHECKED_CAST")
    private fun takeLocked(): Any? {
        takenFlag = false
        if (buffer.isNotEmpty()) {
            val v = buffer.removeFirst()
            // a suspended sender refills the buffer
            while (senders.isNotEmpty()) {
                val s = senders.removeFirst()
                if (s.cont.isActiveLocked()) {
                    buffer.addLast(s.element)
                    pendingResume = s.cont
                    break
                }
            }
            takenFlag = true
            return v
        }
        while (senders.isNotEmpty()) {
            val s = senders.removeFirst()
            if (s.cont.isActiveLocked()) {
                pendingResume = s.cont
                takenFlag = true
                return s.element
            }
        }
        return null
    }

    private var pendingResume: CancellableContinuation<Unit>? = null

    @Suppress("UNCHECKED_CAST")
    public override suspend fun receive(): E {
        val v = receiveRaw()
        if (v === Closed) {
            val c = closeCause
            throw c ?: ClosedReceiveChannelException("Channel was closed")
        }
        return v as E
    }

    @Suppress("UNCHECKED_CAST")
    public override suspend fun receiveOrNull(): E? {
        val v = receiveRaw()
        if (v === Closed) return null
        return v as E?
    }

    /** an element, or [Closed] when the channel is closed and empty */
    private suspend fun receiveRaw(): Any? {
        CoroutinesLock.lock()
        val v = takeLocked()
        if (takenFlag) {
            val p = pendingResume
            pendingResume = null
            CoroutinesLock.unlock()
            if (p != null) p.resume(Unit)
            return v
        }
        if (closed) {
            CoroutinesLock.unlock()
            return Closed
        }
        CoroutinesLock.unlock()
        return suspendCancellableCoroutine<Any?> { cont ->
            CoroutinesLock.lock()
            val v2 = takeLocked()
            if (takenFlag) {
                val p = pendingResume
                pendingResume = null
                CoroutinesLock.unlock()
                if (p != null) p.resume(Unit)
                cont.resume(v2)
            } else if (closed) {
                CoroutinesLock.unlock()
                cont.resume(Closed)
            } else {
                receivers.addLast(cont)
                CoroutinesLock.unlock()
                val me = this
                cont.invokeOnCancellation { _ -> me.removeReceiver(cont) }
            }
        }
    }

    public override fun tryReceive(): E? {
        CoroutinesLock.lock()
        val v = takeLocked()
        val ok = takenFlag
        val p = pendingResume
        pendingResume = null
        CoroutinesLock.unlock()
        if (p != null) p.resume(Unit)
        @Suppress("UNCHECKED_CAST")
        return if (ok) v as E? else null
    }

    public override fun close(cause: Throwable?): Boolean {
        CoroutinesLock.lock()
        if (closed) {
            CoroutinesLock.unlock()
            return false
        }
        closed = true
        closeCause = cause
        // receivers that wait have nothing to get: they end with Closed (senders cannot be waiting while receivers are)
        val rs = ArrayList<CancellableContinuation<Any?>>(receivers)
        receivers.clear()
        CoroutinesLock.unlock()
        for (r in rs) r.resume(Closed)
        return true
    }

    public override fun cancel(cause: CancellationException?) {
        close(cause)
        CoroutinesLock.lock()
        buffer.clear()
        val ss = ArrayList<SenderEntry<E>>(senders)
        senders.clear()
        CoroutinesLock.unlock()
        for (s in ss) s.cont.resumeWithException(cause ?: CancellationException("Channel was cancelled"))
    }

    public override fun iterator(): ChannelIterator<E> = ChannelIteratorImpl<E>(this)

    internal suspend fun nextOrClosed(): Any? = receiveRaw()

    internal fun isClosedMarker(v: Any?): Boolean = v === Closed
}

private class ChannelIteratorImpl<E>(private val ch: ChannelImpl<E>) : ChannelIterator<E> {
    private var nextValue: Any? = null
    private var has = false

    override suspend fun hasNext(): Boolean {
        if (has) return true
        val v = ch.nextOrClosed()
        if (ch.isClosedMarker(v)) return false
        nextValue = v
        has = true
        return true
    }

    @Suppress("UNCHECKED_CAST")
    override fun next(): E {
        if (!has) throw NoSuchElementException()
        has = false
        val v = nextValue
        nextValue = null
        return v as E
    }
}

public suspend fun <E> ReceiveChannel<E>.consumeEach(action: suspend (E) -> Unit) {
    for (x in this) action(x)
}

public fun <E> CoroutineScope.produce(capacity: Int = RENDEZVOUS, block: suspend SendChannel<E>.() -> Unit): ReceiveChannel<E> {
    val ch = Channel<E>(capacity)
    launch {
        try {
            block(ch)
            ch.close()
        } catch (e: Throwable) {
            ch.close(e)
            throw e
        }
    }
    return ch
}
