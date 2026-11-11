import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.getOrSet
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.concurrent.write

class Worker(val out: AtomicInteger) : Thread("worker-x") {
    override fun run() {
        out.addAndGet(5)
    }
}

class Counter {
    private val lock = Any()
    var n = 0
    fun inc() = synchronized(lock) { n++ }
}

fun main() {
    println("== Thread")
    val t = Thread { }
    println(t.name.startsWith("Thread-"))
    println(t.isAlive)
    t.start()
    t.join()
    println(t.isAlive)
    val named = Thread({ }, "my-thread")
    println(named.name)
    named.name = "renamed"
    println(named.name)
    val box = AtomicInteger(0)
    val w = Worker(box)
    println(w.name)
    w.start()
    w.join()
    println(box.get())
    try {
        w.start()
    } catch (e: IllegalThreadStateException) {
        println("already started")
    }
    println(Thread.currentThread().name)
    val seen = AtomicReference<String?>(null)
    val tt = thread(name = "kt-thread") { seen.set(Thread.currentThread().name) }
    tt.join()
    println(seen.get())
    val a = thread { }
    val b = thread { }
    println(a.id != b.id)
    println(Thread.currentThread() === Thread.currentThread())
    val slow = thread { Thread.sleep(150) }
    slow.join(10)
    println(slow.isAlive)
    slow.join()
    println(slow.isAlive)

    println("== ThreadLocal")
    val tl = ThreadLocal<Int?>()
    println(tl.get())
    tl.set(7)
    val other = AtomicReference<Int?>(-1)
    thread { other.set(tl.get()) }.join()
    println(tl.get())
    println(other.get())
    val tl2 = ThreadLocal.withInitial { 41 }
    println(tl2.get())
    val tl3 = ThreadLocal<StringBuilder>()
    val sb = tl3.getOrSet { StringBuilder("x") }
    println(sb === tl3.getOrSet { StringBuilder("y") })
    tl3.remove()
    println(tl3.get())

    println("== atomics")
    val ai = AtomicInteger()
    val al = AtomicLong(10)
    val ab = AtomicBoolean()
    val ar = AtomicReference("a")
    val ths = ArrayList<Thread>()
    for (i in 0 until 8) {
        ths.add(thread {
            for (k in 0 until 10000) {
                ai.incrementAndGet()
                al.getAndAdd(2L)
            }
        })
    }
    for (x in ths) x.join()
    println(ai.get())
    println(al.get())
    println(ab.compareAndSet(false, true))
    println(ab.compareAndSet(false, true))
    println(ab.get())
    println(ar.compareAndSet("a", "b"))
    println(ar.get())
    println(ai.updateAndGet { it * 2 })
    println(ai.getAndIncrement())
    println(ai.accumulateAndGet(5) { x, y -> x + y })
    println(ar.getAndSet("z"))

    println("== locks")
    val lock = ReentrantLock()
    var shared = 0
    val ths2 = ArrayList<Thread>()
    for (i in 0 until 8) {
        ths2.add(thread {
            for (k in 0 until 5000) {
                lock.withLock {
                    lock.lock()
                    shared++
                    lock.unlock()
                }
            }
        })
    }
    for (x in ths2) x.join()
    println(shared)
    println(lock.isLocked)
    println(lock.isHeldByCurrentThread)
    lock.lock()
    println(lock.holdCount)
    lock.lock()
    println(lock.holdCount)
    lock.unlock()
    lock.unlock()
    println(lock.tryLock())
    val got = AtomicBoolean(true)
    thread { got.set(lock.tryLock()) }.join()
    println(got.get())
    lock.unlock()
    val rw = ReentrantReadWriteLock()
    var data = 0
    val readersSaw = AtomicInteger(0)
    val ths3 = ArrayList<Thread>()
    for (i in 0 until 4) {
        ths3.add(thread {
            for (k in 0 until 2000) {
                rw.write { data++ }
                rw.read { if (data >= 0) readersSaw.incrementAndGet() }
            }
        })
    }
    for (x in ths3) x.join()
    println(data)
    println(readersSaw.get())
    val c = Counter()
    val ths4 = ArrayList<Thread>()
    for (i in 0 until 8) ths4.add(thread { for (k in 0 until 5000) c.inc() })
    for (x in ths4) x.join()
    println(c.n)
    val cond = lock.newCondition()
    var ready = false
    val waiter = thread {
        lock.withLock {
            while (!ready) cond.await()
            println("waiter released")
        }
    }
    Thread.sleep(30)
    lock.withLock {
        ready = true
        cond.signalAll()
    }
    waiter.join()
    lock.lock()
    println(cond.await(20, java.util.concurrent.TimeUnit.MILLISECONDS))
    lock.unlock()
}
