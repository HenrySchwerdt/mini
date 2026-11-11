/*
 * java.util.concurrent.locks on the mutex and condition variable of the runtime (pthreads / SRW locks, no libuv).
 */
package java.util.concurrent.locks

import java.util.concurrent.TimeUnit
import mini.internal.*

public interface Lock {
    public fun lock()
    public fun unlock()
    public fun tryLock(): Boolean
    public fun tryLock(time: Long, unit: TimeUnit): Boolean
    public fun lockInterruptibly()
    public fun newCondition(): Condition
}

public interface Condition {
    public fun await()
    public fun await(time: Long, unit: TimeUnit): Boolean
    public fun awaitNanos(nanosTimeout: Long): Long
    public fun signal()
    public fun signalAll()
}

public interface ReadWriteLock {
    public fun readLock(): Lock
    public fun writeLock(): Lock
}

/** A reentrant mutual-exclusion lock. */
public open class ReentrantLock(fair: Boolean) : Lock {
    public constructor() : this(false)

    internal val mutex = LongArray(16)
    private val ownerCell = LongArray(1) // the owner is read by other threads without the mutex: an atomic cell
    private var holds = 0

    init {
        sysLockInit(mutex)
    }

    public override fun lock() {
        val me = sysThreadId()
        if (atomicLongGet(ownerCell) == me) {
            holds++
            return
        }
        sysLock(mutex)
        atomicLongSet(ownerCell, me)
        holds = 1
    }

    public override fun lockInterruptibly() {
        lock()
    }

    public override fun tryLock(): Boolean {
        val me = sysThreadId()
        if (atomicLongGet(ownerCell) == me) {
            holds++
            return true
        }
        if (!sysTryLock(mutex)) return false
        atomicLongSet(ownerCell, me)
        holds = 1
        return true
    }

    public override fun tryLock(time: Long, unit: TimeUnit): Boolean {
        if (tryLock()) return true
        val end = sysMonotonicNanos() + unit.toNanos(time)
        while (true) {
            if (tryLock()) return true
            if (sysMonotonicNanos() >= end) return false
            sysSleepNanos(50000L)
        }
    }

    public override fun unlock() {
        if (atomicLongGet(ownerCell) != sysThreadId()) throw IllegalMonitorStateException()
        holds--
        if (holds == 0) {
            atomicLongSet(ownerCell, 0L)
            sysUnlock(mutex)
        }
    }

    public val isLocked: Boolean get() = atomicLongGet(ownerCell) != 0L
    public val isHeldByCurrentThread: Boolean get() = atomicLongGet(ownerCell) == sysThreadId()
    public val holdCount: Int get() = if (atomicLongGet(ownerCell) == sysThreadId()) holds else 0

    public override fun newCondition(): Condition = ReentrantCondition(this)

    internal fun release(): Int {
        val saved = holds
        atomicLongSet(ownerCell, 0L)
        holds = 0
        return saved
    }

    internal fun reacquired(saved: Int) {
        atomicLongSet(ownerCell, sysThreadId())
        holds = saved
    }
}

private class ReentrantCondition(private val lock: ReentrantLock) : Condition {
    private val cond = LongArray(16)

    init {
        sysCondInit(cond)
    }

    public override fun await() {
        if (!lock.isHeldByCurrentThread) throw IllegalMonitorStateException()
        val saved = lock.release()
        sysCondWait(cond, lock.mutex)
        lock.reacquired(saved)
    }

    public override fun awaitNanos(nanosTimeout: Long): Long {
        if (!lock.isHeldByCurrentThread) throw IllegalMonitorStateException()
        val start = sysMonotonicNanos()
        val saved = lock.release()
        if (nanosTimeout > 0L) sysCondWaitNanos(cond, lock.mutex, nanosTimeout)
        lock.reacquired(saved)
        return nanosTimeout - (sysMonotonicNanos() - start)
    }

    public override fun await(time: Long, unit: TimeUnit): Boolean = awaitNanos(unit.toNanos(time)) > 0L

    public override fun signal() {
        if (!lock.isHeldByCurrentThread) throw IllegalMonitorStateException()
        sysCondSignal(cond)
    }

    public override fun signalAll() {
        if (!lock.isHeldByCurrentThread) throw IllegalMonitorStateException()
        sysCondBroadcast(cond)
    }
}

/** Many readers or one writer (no fairness: a steady stream of readers can delay a writer). */
public class ReentrantReadWriteLock(fair: Boolean) : ReadWriteLock {
    public constructor() : this(false)

    private val mutex = LongArray(16)
    private val cond = LongArray(16)
    private var readers = 0
    private var writer = 0L
    private var writeHolds = 0
    private val reader = ReadLock(this)
    private val writerLock = WriteLock(this)

    init {
        sysLockInit(mutex)
        sysCondInit(cond)
    }

    public override fun readLock(): Lock = reader
    public override fun writeLock(): Lock = writerLock

    public val readLockCount: Int get() = readers
    public val isWriteLocked: Boolean get() = writer != 0L

    internal fun acquireRead() {
        val me = sysThreadId()
        sysLock(mutex)
        while (writer != 0L && writer != me) sysCondWait(cond, mutex)
        readers++
        sysUnlock(mutex)
    }

    internal fun releaseRead() {
        sysLock(mutex)
        if (readers == 0) {
            sysUnlock(mutex)
            throw IllegalMonitorStateException()
        }
        readers--
        if (readers == 0) sysCondBroadcast(cond)
        sysUnlock(mutex)
    }

    internal fun acquireWrite() {
        val me = sysThreadId()
        sysLock(mutex)
        if (writer == me) {
            writeHolds++
            sysUnlock(mutex)
            return
        }
        while (readers > 0 || writer != 0L) sysCondWait(cond, mutex)
        writer = me
        writeHolds = 1
        sysUnlock(mutex)
    }

    internal fun releaseWrite() {
        sysLock(mutex)
        if (writer != sysThreadId()) {
            sysUnlock(mutex)
            throw IllegalMonitorStateException()
        }
        writeHolds--
        if (writeHolds == 0) {
            writer = 0L
            sysCondBroadcast(cond)
        }
        sysUnlock(mutex)
    }

    internal fun tryRead(): Boolean {
        val me = sysThreadId()
        sysLock(mutex)
        val ok = writer == 0L || writer == me
        if (ok) readers++
        sysUnlock(mutex)
        return ok
    }

    internal fun tryWrite(): Boolean {
        val me = sysThreadId()
        sysLock(mutex)
        var ok = false
        if (writer == me) {
            writeHolds++
            ok = true
        } else if (readers == 0 && writer == 0L) {
            writer = me
            writeHolds = 1
            ok = true
        }
        sysUnlock(mutex)
        return ok
    }

    public class ReadLock internal constructor(private val rw: ReentrantReadWriteLock) : Lock {
        public override fun lock() { rw.acquireRead() }
        public override fun lockInterruptibly() { rw.acquireRead() }
        public override fun unlock() { rw.releaseRead() }
        public override fun tryLock(): Boolean = rw.tryRead()
        public override fun tryLock(time: Long, unit: TimeUnit): Boolean {
            val end = sysMonotonicNanos() + unit.toNanos(time)
            while (true) {
                if (rw.tryRead()) return true
                if (sysMonotonicNanos() >= end) return false
                sysSleepNanos(50000L)
            }
        }
        public override fun newCondition(): Condition = throw UnsupportedOperationException("read locks have no conditions")
    }

    public class WriteLock internal constructor(private val rw: ReentrantReadWriteLock) : Lock {
        public override fun lock() { rw.acquireWrite() }
        public override fun lockInterruptibly() { rw.acquireWrite() }
        public override fun unlock() { rw.releaseWrite() }
        public override fun tryLock(): Boolean = rw.tryWrite()
        public override fun tryLock(time: Long, unit: TimeUnit): Boolean {
            val end = sysMonotonicNanos() + unit.toNanos(time)
            while (true) {
                if (rw.tryWrite()) return true
                if (sysMonotonicNanos() >= end) return false
                sysSleepNanos(50000L)
            }
        }
        public override fun newCondition(): Condition = throw UnsupportedOperationException("conditions of a write lock are not supported")
    }
}
