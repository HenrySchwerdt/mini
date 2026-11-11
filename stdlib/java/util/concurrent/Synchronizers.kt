/*
 * java.util.concurrent: TimeUnit, the exceptions, CountDownLatch, Semaphore, CyclicBarrier. Built on the mutex and condition
 * variable of the runtime (runtime/src/sys.c).
 */
package java.util.concurrent

import mini.concurrent.Condition
import mini.concurrent.Lock
import mini.internal.*

public enum class TimeUnit(private val nanos: Long) {
    NANOSECONDS(1L),
    MICROSECONDS(1000L),
    MILLISECONDS(1000000L),
    SECONDS(1000000000L),
    MINUTES(60000000000L),
    HOURS(3600000000000L),
    DAYS(86400000000000L);

    public fun toNanos(d: Long): Long = saturatedMul(d, nanos)
    public fun toMicros(d: Long): Long = convertTo(d, 1000L)
    public fun toMillis(d: Long): Long = convertTo(d, 1000000L)
    public fun toSeconds(d: Long): Long = convertTo(d, 1000000000L)
    public fun toMinutes(d: Long): Long = convertTo(d, 60000000000L)
    public fun toHours(d: Long): Long = convertTo(d, 3600000000000L)
    public fun toDays(d: Long): Long = convertTo(d, 86400000000000L)

    public fun convert(sourceDuration: Long, sourceUnit: TimeUnit): Long = convertTo(sourceDuration, sourceUnit.nanos, nanos)

    public fun sleep(timeout: Long) {
        if (timeout > 0L) sysSleepNanos(toNanos(timeout))
    }

    private fun convertTo(d: Long, targetNanos: Long): Long = convertTo(d, nanos, targetNanos)

    private fun convertTo(d: Long, fromNanos: Long, toNanosPer: Long): Long {
        if (fromNanos == toNanosPer) return d
        if (fromNanos > toNanosPer) return saturatedMul(d, fromNanos / toNanosPer)
        return d / (toNanosPer / fromNanos)
    }

    private fun saturatedMul(d: Long, m: Long): Long {
        if (m == 1L) return d
        val max = Long.MAX_VALUE / m
        if (d > max) return Long.MAX_VALUE
        if (d < -max) return Long.MIN_VALUE
        return d * m
    }
}

public open class ExecutionException : Exception {
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public open class TimeoutException : Exception {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class CancellationException : IllegalStateException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class RejectedExecutionException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class BrokenBarrierException : Exception {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

/** Waits (with the lock held) until [cond] is signalled or the deadline passes; true when the deadline passed. */
internal fun awaitDeadline(cond: Condition, deadlineNanos: Long): Boolean {
    val left = deadlineNanos - sysMonotonicNanos()
    if (left <= 0L) return true
    cond.awaitNanos(left)
    return sysMonotonicNanos() >= deadlineNanos
}

public class CountDownLatch(count: Int) {
    private val lock = Lock()
    private val zero: Condition = lock.newCondition()
    private var remaining = count

    init {
        if (count < 0) throw IllegalArgumentException("count < 0")
    }

    public fun countDown() {
        lock.lock()
        if (remaining > 0) {
            remaining--
            if (remaining == 0) zero.signalAll()
        }
        lock.unlock()
    }

    public val count: Long
        get() {
            lock.lock()
            val r = remaining.toLong()
            lock.unlock()
            return r
        }

    public fun await() {
        lock.lock()
        while (remaining > 0) zero.await()
        lock.unlock()
    }

    public fun await(timeout: Long, unit: TimeUnit): Boolean {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        var ok = true
        while (remaining > 0) {
            if (awaitDeadline(zero, end)) {
                ok = remaining == 0
                break
            }
        }
        lock.unlock()
        return ok
    }

    override fun toString(): String = "CountDownLatch[Count = " + count + "]"
}

public class Semaphore(permits: Int, fair: Boolean) {
    public constructor(permits: Int) : this(permits, false)

    private val lock = Lock()
    private val changed: Condition = lock.newCondition()
    private var available = permits

    public fun acquire() {
        acquire(1)
    }

    public fun acquire(permits: Int) {
        lock.lock()
        while (available < permits) changed.await()
        available -= permits
        lock.unlock()
    }

    public fun acquireUninterruptibly() {
        acquire(1)
    }

    public fun tryAcquire(): Boolean = tryAcquire(1)

    public fun tryAcquire(permits: Int): Boolean {
        lock.lock()
        val ok = available >= permits
        if (ok) available -= permits
        lock.unlock()
        return ok
    }

    public fun tryAcquire(timeout: Long, unit: TimeUnit): Boolean = tryAcquire(1, timeout, unit)

    public fun tryAcquire(permits: Int, timeout: Long, unit: TimeUnit): Boolean {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        var ok = true
        while (available < permits) {
            if (awaitDeadline(changed, end)) {
                ok = available >= permits
                break
            }
        }
        if (ok) available -= permits
        lock.unlock()
        return ok
    }

    public fun release() {
        release(1)
    }

    public fun release(permits: Int) {
        lock.lock()
        available += permits
        changed.signalAll()
        lock.unlock()
    }

    public fun availablePermits(): Int {
        lock.lock()
        val r = available
        lock.unlock()
        return r
    }

    public fun drainPermits(): Int {
        lock.lock()
        val r = available
        available = 0
        lock.unlock()
        return r
    }
}

public class CyclicBarrier(public val parties: Int, private val barrierAction: Runnable?) {
    public constructor(parties: Int) : this(parties, null)

    private val lock = Lock()
    private val tripped: Condition = lock.newCondition()
    private var waiting = 0
    private var generation = 0
    private var broken = false


    public fun await(): Int {
        lock.lock()
        val gen = generation
        if (broken) {
            lock.unlock()
            throw BrokenBarrierException()
        }
        val index = parties - 1 - waiting
        waiting++
        if (waiting == parties) {
            val action = barrierAction
            if (action != null) action.run()
            waiting = 0
            generation++
            tripped.signalAll()
            lock.unlock()
            return 0
        }
        while (gen == generation && !broken) tripped.await()
        val wasBroken = broken && gen == generation
        lock.unlock()
        if (wasBroken) throw BrokenBarrierException()
        return index
    }

    public fun reset() {
        lock.lock()
        waiting = 0
        generation++
        broken = false
        tripped.signalAll()
        lock.unlock()
    }

    public val isBroken: Boolean get() = broken
}
