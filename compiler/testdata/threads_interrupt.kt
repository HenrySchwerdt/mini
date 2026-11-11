import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.system.measureNanoTime

class Counter {
    var n = 0

    @Synchronized
    fun inc() {
        n++
    }

    @Synchronized
    fun twice(): Int {
        if (n > 1000000) return -1
        n += 2
        return n
    }

    @Synchronized
    fun viaExpr() = n
}

object Registry {
    var hits = 0

    @Synchronized
    fun hit() {
        hits++
    }
}

var topCount = 0

@Synchronized
fun topHit() {
    topCount++
}

fun sleepsUntilInterrupted(): String {
    val result = StringBuilder()
    val t = thread {
        try {
            Thread.sleep(10000)
            result.append("slept")
        } catch (e: InterruptedException) {
            result.append("interrupted;flag=" + Thread.currentThread().isInterrupted)
        }
    }
    Thread.sleep(50)
    t.interrupt()
    t.join()
    return result.toString()
}

fun main() {
    println("== interrupt")
    println(sleepsUntilInterrupted())
    // a flag set before the sleep makes it throw at once
    val pre = thread {
        Thread.currentThread().interrupt()
        try {
            Thread.sleep(5000)
            println("not interrupted")
        } catch (e: InterruptedException) {
            println("pre-interrupted sleep")
        }
        println(Thread.interrupted())
        println(Thread.interrupted())
    }
    pre.join()
    val q = LinkedBlockingQueue<Int>()
    val got = AtomicInteger(-1)
    val taker = thread {
        try {
            q.take()
        } catch (e: InterruptedException) {
            got.set(1)
        }
    }
    Thread.sleep(50)
    taker.interrupt()
    taker.join()
    println(got.get())
    val full = ArrayBlockingQueue<Int>(1)
    full.put(1)
    val putter = thread {
        try {
            full.put(2)
        } catch (e: InterruptedException) {
            got.set(2)
        }
    }
    Thread.sleep(50)
    putter.interrupt()
    putter.join()
    println(got.get())
    val lock = ReentrantLock()
    val cond = lock.newCondition()
    val waiter = thread {
        lock.lock()
        try {
            cond.await()
        } catch (e: InterruptedException) {
            got.set(3)
            println("holds lock again: " + lock.isHeldByCurrentThread)
        } finally {
            lock.unlock()
        }
    }
    Thread.sleep(50)
    waiter.interrupt()
    waiter.join()
    println(got.get())
    val latch = CountDownLatch(1)
    val lw = thread {
        try {
            latch.await()
        } catch (e: InterruptedException) {
            got.set(4)
        }
    }
    Thread.sleep(50)
    lw.interrupt()
    lw.join()
    println(got.get())
    val sem = Semaphore(0)
    val sw = thread {
        try {
            sem.acquire()
        } catch (e: InterruptedException) {
            got.set(5)
        }
    }
    Thread.sleep(50)
    sw.interrupt()
    sw.join()
    println(got.get())
    val sleeper = thread { Thread.sleep(400) }
    val joiner = thread {
        try {
            sleeper.join()
        } catch (e: InterruptedException) {
            got.set(6)
        }
    }
    Thread.sleep(50)
    joiner.interrupt()
    joiner.join()
    println(got.get())
    sleeper.join()
    println(Thread.currentThread().isInterrupted)

    println("== wait / notify")
    val mon = Any()
    var ready = false
    val msgs = ArrayList<String>()
    val w = thread {
        synchronized(mon) {
            while (!ready) (mon as java.lang.Object).wait()
            msgs.add("woken")
        }
    }
    Thread.sleep(50)
    synchronized(mon) {
        ready = true
        (mon as java.lang.Object).notifyAll()
    }
    w.join()
    println(msgs)
    synchronized(mon) {
        val took = kotlin.system.measureNanoTime { (mon as java.lang.Object).wait(30) }
        println(took >= 20000000L)
    }
    try {
        (mon as java.lang.Object).notify()
    } catch (e: IllegalMonitorStateException) {
        println("not owner")
    }
    val box = Any()
    var turn = 0
    val log = ArrayList<Int>()
    val ping = thread {
        for (i in 0 until 5) synchronized(box) {
            while (turn != 0) (box as java.lang.Object).wait()
            log.add(i * 2)
            turn = 1
            (box as java.lang.Object).notifyAll()
        }
    }
    val pong = thread {
        for (i in 0 until 5) synchronized(box) {
            while (turn != 1) (box as java.lang.Object).wait()
            log.add(i * 2 + 1)
            turn = 0
            (box as java.lang.Object).notifyAll()
        }
    }
    ping.join()
    pong.join()
    println(log)

    println("== @Synchronized")
    val c = Counter()
    val ths = ArrayList<Thread>()
    for (i in 0 until 8) ths.add(thread {
        for (k in 0 until 5000) {
            c.inc()
            Registry.hit()
            topHit()
        }
    })
    for (t in ths) t.join()
    println(c.n)
    println(Registry.hits)
    println(topCount)
    println(c.twice())
    println(c.viaExpr())

    println("== ConcurrentHashMap segments")
    val map = ConcurrentHashMap<Int, Int>()
    val ths2 = ArrayList<Thread>()
    for (t in 0 until 8) ths2.add(thread {
        for (k in 0 until 20000) {
            map.merge(k % 500, 1) { a, b -> a + b }
            if (k % 1000 == 0) map.size
        }
    })
    for (t in ths2) t.join()
    println(map.size)
    println(map.values.sum())
    println(map.keys.sorted().take(3))
    println(map.containsKey(499))
    println(map.containsKey(500))
    map.clear()
    println(map.isEmpty())
}
