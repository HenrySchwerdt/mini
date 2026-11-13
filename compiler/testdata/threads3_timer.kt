import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.*

fun main() {
    val timer = Timer("t1", false)
    val once = CountDownLatch(1)
    timer.schedule(object : TimerTask() {
        override fun run() {
            println("once on " + Thread.currentThread().name)
            once.countDown()
        }
    }, 50)
    once.await()

    val n = AtomicInteger()
    val rep = CountDownLatch(3)
    val task = timer.schedule(10, 20) { n.incrementAndGet(); rep.countDown() }
    println(rep.await(5, TimeUnit.SECONDS))
    println(task.cancel())
    val seen = n.get()
    Thread.sleep(100)
    println(n.get() <= seen + 1)
    println(task.cancel())

    val rate = CountDownLatch(3)
    timer.scheduleAtFixedRate(0, 20) { rate.countDown() }
    println(rate.await(5, TimeUnit.SECONDS))
    timer.cancel()
    try {
        timer.schedule(object : TimerTask() { override fun run() {} }, 1)
    } catch (e: IllegalStateException) {
        println(e.message)
    }

    val c = CountDownLatch(2)
    val t2 = timer(name = "ticker", daemon = true, initialDelay = 0, period = 20) { c.countDown() }
    println(c.await(5, TimeUnit.SECONDS))
    t2.cancel()
    val c3 = CountDownLatch(2)
    val t3 = fixedRateTimer("fixed", true, 0, 20) { c3.countDown() }
    println(c3.await(5, TimeUnit.SECONDS))
    t3.cancel()
}
