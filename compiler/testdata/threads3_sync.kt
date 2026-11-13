import java.util.concurrent.*
import java.util.concurrent.locks.*

fun main() {
    // Phaser: three workers advance through three phases together
    val log = ConcurrentLinkedQueue<String>()
    val phaser = object : Phaser(1) {
        override fun onAdvance(phase: Int, registeredParties: Int): Boolean {
            log.add("advance $phase with $registeredParties")
            return phase >= 2 || registeredParties == 0
        }
    }
    val workers = (1..3).map { w ->
        phaser.register()
        Thread {
            for (p in 0 until 3) {
                phaser.arriveAndAwaitAdvance()
            }
            phaser.arriveAndDeregister()
        }
    }
    workers.forEach { it.start() }
    for (p in 0 until 3) phaser.arriveAndAwaitAdvance()
    workers.forEach { it.join() }
    println(log.toList())
    println("${phaser.isTerminated} ${phaser.phase < 0} ${phaser.registeredParties}")

    val ph2 = Phaser(2)
    println("${ph2.phase} ${ph2.registeredParties} ${ph2.arrivedParties} ${ph2.unarrivedParties}")
    println(ph2.arrive())
    println(ph2.arrivedParties)
    val waiter = Thread { println("advanced to ${ph2.awaitAdvance(0)}") }
    waiter.start()
    Thread.sleep(50)
    ph2.arrive()
    waiter.join()
    ph2.forceTermination()
    println(ph2.isTerminated)

    // Exchanger
    val ex = Exchanger<String>()
    var got = ""
    val other = Thread { got = ex.exchange("from other")!! }
    other.start()
    println("main got " + ex.exchange("from main"))
    other.join()
    println("other got $got")
    try {
        ex.exchange("alone", 30, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        println("timeout")
    }

    // StampedLock
    val sl = StampedLock()
    var value = 1
    var stamp = sl.tryOptimisticRead()
    println("${stamp != 0L} ${sl.validate(stamp)}")
    val w = sl.writeLock()
    println("${sl.isWriteLocked} ${sl.validate(stamp)} ${sl.tryOptimisticRead()} ${sl.tryReadLock()}")
    value = 2
    sl.unlockWrite(w)
    println("${sl.isWriteLocked} ${sl.validate(stamp)}")
    stamp = sl.tryOptimisticRead()
    val seen = value
    println("${seen} ${sl.validate(stamp)}")
    val r = sl.readLock()
    val r2 = sl.readLock()
    println("${sl.readLockCount} ${sl.isReadLocked} ${sl.tryWriteLock()}")
    sl.unlockRead(r2)
    val up = sl.tryConvertToWriteLock(r)
    println("${up != 0L} ${sl.isWriteLocked}")
    sl.unlock(up)
    println("${sl.isWriteLocked} ${sl.isReadLocked}")
    val counter = IntArray(1)
    val ts = (0 until 4).map { Thread { for (i in 0 until 2000) { val s = sl.writeLock(); counter[0]++; sl.unlockWrite(s) } } }
    ts.forEach { it.start() }
    ts.forEach { it.join() }
    println(counter[0])
    try {
        sl.unlockWrite(12345L)
    } catch (e: IllegalMonitorStateException) {
        println("illegal")
    }

    // ThreadLocalRandom
    val rnd = ThreadLocalRandom.current()
    var ok = true
    for (i in 0 until 1000) {
        val a = rnd.nextInt(5, 10)
        val b = rnd.nextLong(100L)
        val c = rnd.nextDouble(2.0, 3.0)
        val d = rnd.nextInt(7)
        if (a !in 5..9 || b !in 0L..99L || c < 2.0 || c >= 3.0 || d !in 0..6) ok = false
    }
    println(ok)
    println(ThreadLocalRandom.current() === rnd)
    var other2: Any? = null
    val t = Thread { other2 = ThreadLocalRandom.current() }
    t.start(); t.join()
    println(other2 !== rnd)

    // Condition.awaitUninterruptibly and the reentrant lock
    val lock = ReentrantLock()
    val cond = lock.newCondition()
    var ready = false
    val th = Thread {
        lock.lock()
        try {
            while (!ready) cond.awaitUninterruptibly()
            println("woke up")
        } finally {
            lock.unlock()
        }
    }
    th.start()
    Thread.sleep(50)
    th.interrupt()
    Thread.sleep(50)
    lock.lock()
    ready = true
    cond.signalAll()
    lock.unlock()
    th.join()
    println(lock.isFair)
}
