/*
 * java.util.concurrent.locks.StampedLock: a read-write lock with optimistic reads. One mutex and one condition guard the state; the version
 * is odd while a writer holds the lock, so a stamp of `tryOptimisticRead` stays valid until the next write lock is taken.
 */
package java.util.concurrent.locks

import java.util.concurrent.TimeUnit
import mini.concurrent.Condition
import mini.internal.*

public class StampedLock {
    private val mutex = mini.concurrent.Lock()
    private val changed: Condition = mutex.newCondition()
    private var version = 256L // even: no writer; odd: a writer holds the lock
    private var readers = 0

    private fun writeHeld(): Boolean = (version and 1L) != 0L
    private fun readStamp(): Long = version or READ_MARK

    // the caller holds the mutex; takes the write lock, returns the stamp
    private fun takeWrite(): Long {
        version++
        return version
    }

    public fun writeLock(): Long {
        mutex.lock()
        while (writeHeld() || readers > 0) changed.await()
        val s = takeWrite()
        mutex.unlock()
        return s
    }

    public fun tryWriteLock(): Long {
        mutex.lock()
        var s = 0L
        if (!writeHeld() && readers == 0) s = takeWrite()
        mutex.unlock()
        return s
    }

    public fun tryWriteLock(time: Long, unit: TimeUnit): Long {
        val end = sysMonotonicNanos() + unit.toNanos(time)
        mutex.lock()
        while (writeHeld() || readers > 0) {
            if (awaitUntil(end)) break
        }
        var s = 0L
        if (!writeHeld() && readers == 0) s = takeWrite()
        mutex.unlock()
        return s
    }

    private fun awaitUntil(end: Long): Boolean {
        val left = end - sysMonotonicNanos()
        if (left <= 0L) return true
        changed.awaitNanos(left)
        return sysMonotonicNanos() >= end
    }

    public fun readLock(): Long {
        mutex.lock()
        while (writeHeld()) changed.await()
        readers++
        val s = readStamp()
        mutex.unlock()
        return s
    }

    public fun tryReadLock(): Long {
        mutex.lock()
        var s = 0L
        if (!writeHeld()) {
            readers++
            s = readStamp()
        }
        mutex.unlock()
        return s
    }

    public fun tryReadLock(time: Long, unit: TimeUnit): Long {
        val end = sysMonotonicNanos() + unit.toNanos(time)
        mutex.lock()
        while (writeHeld()) {
            if (awaitUntil(end)) break
        }
        var s = 0L
        if (!writeHeld()) {
            readers++
            s = readStamp()
        }
        mutex.unlock()
        return s
    }

    public fun tryOptimisticRead(): Long {
        mutex.lock()
        val s = if (writeHeld()) 0L else version
        mutex.unlock()
        return s
    }

    public fun validate(stamp: Long): Boolean {
        mutex.lock()
        val ok = stamp != 0L && (stamp and READ_MARK) == 0L && stamp == version
        val readOk = stamp != 0L && (stamp and READ_MARK) != 0L && (stamp and READ_MARK.inv()) == version
        mutex.unlock()
        return ok || readOk
    }

    public fun unlockWrite(stamp: Long) {
        mutex.lock()
        if (!writeHeld() || stamp != version) {
            mutex.unlock()
            throw IllegalMonitorStateException()
        }
        version++
        changed.signalAll()
        mutex.unlock()
    }

    public fun unlockRead(stamp: Long) {
        mutex.lock()
        if (readers <= 0 || (stamp and READ_MARK) == 0L) {
            mutex.unlock()
            throw IllegalMonitorStateException()
        }
        readers--
        if (readers == 0) changed.signalAll()
        mutex.unlock()
    }

    public fun unlock(stamp: Long) {
        if ((stamp and READ_MARK) != 0L) unlockRead(stamp) else unlockWrite(stamp)
    }

    public fun tryConvertToWriteLock(stamp: Long): Long {
        mutex.lock()
        var r = 0L
        if ((stamp and READ_MARK) != 0L) {
            if (readers == 1 && !writeHeld()) {
                readers = 0
                r = takeWrite()
            }
        } else if (writeHeld()) {
            if (stamp == version) r = stamp
        } else if (stamp != 0L && stamp == version && readers == 0) {
            r = takeWrite()
        }
        mutex.unlock()
        return r
    }

    public fun tryConvertToReadLock(stamp: Long): Long {
        mutex.lock()
        var r = 0L
        if ((stamp and READ_MARK) != 0L) {
            r = stamp
        } else if (writeHeld()) {
            if (stamp == version) {
                version++
                readers++
                r = readStamp()
                changed.signalAll()
            }
        } else if (stamp != 0L && stamp == version) {
            readers++
            r = readStamp()
        }
        mutex.unlock()
        return r
    }

    public fun tryConvertToOptimisticRead(stamp: Long): Long {
        mutex.lock()
        var r = 0L
        if ((stamp and READ_MARK) != 0L) {
            if (readers > 0) {
                readers--
                if (readers == 0) changed.signalAll()
                r = version
            }
        } else if (writeHeld()) {
            if (stamp == version) {
                version++
                changed.signalAll()
                r = version
            }
        } else if (stamp != 0L && stamp == version) {
            r = stamp
        }
        mutex.unlock()
        return r
    }

    public val isWriteLocked: Boolean
        get() {
            mutex.lock()
            val r = writeHeld()
            mutex.unlock()
            return r
        }

    public val isReadLocked: Boolean
        get() {
            mutex.lock()
            val r = readers > 0
            mutex.unlock()
            return r
        }

    public val readLockCount: Int
        get() {
            mutex.lock()
            val r = readers
            mutex.unlock()
            return r
        }

    private companion object {
        const val READ_MARK = 1L shl 62
    }
}
