/*
 * java.util.concurrent: Phaser, Exchanger, ThreadLocalRandom. (StampedLock is in locks/StampedLock.kt.)
 */
package java.util.concurrent

import mini.concurrent.Condition
import mini.concurrent.Lock
import mini.internal.*

/** A reusable barrier with a changing number of parties. A negative phase number means the phaser is terminated. */
public open class Phaser(parties: Int) {
    public constructor() : this(0)

    private val lock = Lock()
    private val changed: Condition = lock.newCondition()
    private var phaseNumber = 0
    private var parties = parties
    private var unarrived = parties
    private var terminatedFlag = false

    // the caller holds the lock
    private fun currentPhase(): Int = if (terminatedFlag) phaseNumber or Int.MIN_VALUE else phaseNumber

    // all parties arrived: the phase ends (the caller holds the lock)
    private fun advance() {
        if (onAdvance(phaseNumber, parties)) terminatedFlag = true
        phaseNumber = (phaseNumber + 1) and Int.MAX_VALUE
        unarrived = parties
        changed.signalAll()
    }

    public open fun onAdvance(phase: Int, registeredParties: Int): Boolean = registeredParties == 0

    public fun register(): Int = bulkRegister(1)

    public fun bulkRegister(n: Int): Int {
        if (n < 0) throw IllegalArgumentException()
        lock.lock()
        if (terminatedFlag) {
            val p = currentPhase()
            lock.unlock()
            return p
        }
        parties += n
        unarrived += n
        val p = phaseNumber
        lock.unlock()
        return p
    }

    private fun arriveWith(deregister: Boolean): Int {
        lock.lock()
        if (terminatedFlag) {
            val p = currentPhase()
            lock.unlock()
            return p
        }
        if (unarrived <= 0) {
            lock.unlock()
            throw IllegalStateException("Attempted arrival of unregistered party for " + this)
        }
        val p = phaseNumber
        unarrived--
        if (deregister) parties--
        if (unarrived == 0) advance()
        lock.unlock()
        return p
    }

    public fun arrive(): Int = arriveWith(false)
    public fun arriveAndDeregister(): Int = arriveWith(true)

    public fun arriveAndAwaitAdvance(): Int {
        lock.lock()
        if (terminatedFlag) {
            val p = currentPhase()
            lock.unlock()
            return p
        }
        if (unarrived <= 0) {
            lock.unlock()
            throw IllegalStateException("Attempted arrival of unregistered party for " + this)
        }
        val p = phaseNumber
        unarrived--
        if (unarrived == 0) {
            advance()
        } else {
            while (phaseNumber == p && !terminatedFlag) changed.await()
        }
        val r = currentPhase()
        lock.unlock()
        return r
    }

    public fun awaitAdvance(phase: Int): Int {
        if (phase < 0) return phase
        lock.lock()
        while (phaseNumber == phase && !terminatedFlag) changed.await()
        val r = currentPhase()
        lock.unlock()
        return r
    }

    public fun awaitAdvanceInterruptibly(phase: Int): Int = awaitAdvance(phase)

    public fun awaitAdvanceInterruptibly(phase: Int, timeout: Long, unit: TimeUnit): Int {
        if (phase < 0) return phase
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (phaseNumber == phase && !terminatedFlag) {
            if (awaitDeadline(changed, end)) {
                lock.unlock()
                throw TimeoutException()
            }
        }
        val r = currentPhase()
        lock.unlock()
        return r
    }

    public fun forceTermination() {
        lock.lock()
        terminatedFlag = true
        changed.signalAll()
        lock.unlock()
    }

    public val phase: Int
        get() {
            lock.lock()
            val r = currentPhase()
            lock.unlock()
            return r
        }

    public val registeredParties: Int
        get() {
            lock.lock()
            val r = parties
            lock.unlock()
            return r
        }

    public val arrivedParties: Int
        get() {
            lock.lock()
            val r = parties - unarrived
            lock.unlock()
            return r
        }

    public val unarrivedParties: Int
        get() {
            lock.lock()
            val r = unarrived
            lock.unlock()
            return r
        }

    public val isTerminated: Boolean
        get() {
            lock.lock()
            val r = terminatedFlag
            lock.unlock()
            return r
        }

    public override fun toString(): String {
        lock.lock()
        val s = "java.util.concurrent.Phaser[phase = " + currentPhase() + " parties = " + parties + " arrived = " + (parties - unarrived) + "]"
        lock.unlock()
        return s
    }
}

private class ExchangeNode(val item: Any?) {
    var reply: Any? = null
    var done = false
}

/** A rendezvous point where two threads swap objects. */
public class Exchanger<V> {
    private val lock = Lock()
    private val changed: Condition = lock.newCondition()
    private var slot: ExchangeNode? = null

    @Suppress("UNCHECKED_CAST")
    private fun exchangeUntil(x: V?, end: Long): V? {
        lock.lock()
        val other = slot
        if (other != null) {
            slot = null
            other.reply = x
            other.done = true
            changed.signalAll()
            lock.unlock()
            return other.item as V?
        }
        val node = ExchangeNode(x)
        slot = node
        try {
            while (!node.done) {
                if (end >= 0L) {
                    if (awaitDeadline(changed, end)) break
                } else {
                    changed.await()
                }
            }
        } catch (e: InterruptedException) {
            lock.lock()
            if (slot === node) slot = null
            lock.unlock()
            throw e
        }
        if (!node.done) {
            if (slot === node) slot = null
            lock.unlock()
            throw TimeoutException()
        }
        lock.unlock()
        return node.reply as V?
    }

    public fun exchange(x: V?): V? = exchangeUntil(x, -1L)

    public fun exchange(x: V?, timeout: Long, unit: TimeUnit): V? = exchangeUntil(x, sysMonotonicNanos() + unit.toNanos(timeout))
}

/** A random number generator of the calling thread (`ThreadLocalRandom.current()`). */
public class ThreadLocalRandom private constructor() : java.util.Random() {
    // one shared instance (as on the JVM) whose state is kept per thread
    protected override fun next(bits: Int): Int = state().nextInt() ushr (32 - bits)

    private fun state(): java.util.Random {
        var r = states.get()
        if (r == null) {
            r = java.util.Random()
            states.set(r)
        }
        return r
    }

    public fun nextLong(bound: Long): Long {
        if (bound <= 0L) throw IllegalArgumentException("bound must be positive")
        return nextLong(0L, bound)
    }

    public fun nextLong(origin: Long, bound: Long): Long {
        if (origin >= bound) throw IllegalArgumentException("bound must be greater than origin")
        val n = bound - origin
        if (n > 0L) {
            // rejection sampling over the non-negative longs
            val limit = Long.MAX_VALUE - (Long.MAX_VALUE % n + 1L) % n
            while (true) {
                val r = nextLong() ushr 1
                if (r <= limit) return origin + r % n
            }
        }
        while (true) {
            val r = nextLong()
            if (r >= origin && r < bound) return r
        }
    }

    public fun nextDouble(bound: Double): Double {
        if (!(bound > 0.0)) throw IllegalArgumentException("bound must be positive")
        return nextDouble() * bound
    }

    public fun nextDouble(origin: Double, bound: Double): Double {
        if (!(origin < bound)) throw IllegalArgumentException("bound must be greater than origin")
        return origin + nextDouble() * (bound - origin)
    }

    public companion object {
        private val states = java.lang.ThreadLocal<java.util.Random>()
        private val instance = ThreadLocalRandom()

        public fun current(): ThreadLocalRandom = instance
    }
}
