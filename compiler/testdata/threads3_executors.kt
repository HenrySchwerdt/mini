import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    // thread factories and names
    val names = ConcurrentHashMap<String, Boolean>()
    val pool = Executors.newFixedThreadPool(3)
    val latch = CountDownLatch(3)
    val gate = CyclicBarrier(3)
    for (i in 1..3) pool.execute {
        gate.await()
        names[Thread.currentThread().name] = true
        latch.countDown()
    }
    latch.await()
    println(names.keys.sorted())
    pool.shutdown()
    println(pool.awaitTermination(5, TimeUnit.SECONDS))
    println("terminated=${pool.isTerminated}")

    val counter = AtomicInteger()
    val custom = Executors.newFixedThreadPool(2, ThreadFactory { r -> Thread(r, "worker-" + counter.incrementAndGet()) })
    val f1 = custom.submit(Callable { Thread.currentThread().name.startsWith("worker-") })
    println(f1.get())
    custom.shutdown()
    custom.awaitTermination(5, TimeUnit.SECONDS)
    println("created=${counter.get() in 1..2}")

    val single = Executors.newSingleThreadExecutor()
    val order = StringBuffer()
    val fs = (1..5).map { i -> single.submit { order.append(i) } }
    fs.forEach { it.get() }
    println(order)
    single.shutdown()

    // invokeAll / invokeAny
    val ex = Executors.newCachedThreadPool()
    val results = ex.invokeAll(listOf(Callable { 1 }, Callable { 2 }, Callable { 3 })).map { it.get() }
    println(results)
    println(ex.invokeAny(listOf(Callable { "any" })))
    ex.shutdown()

    // completion service: results in the order of completion
    val pool2 = Executors.newFixedThreadPool(3)
    val cs = ExecutorCompletionService<String>(pool2)
    cs.submit(Callable { Thread.sleep(400); "slow" })
    cs.submit(Callable { Thread.sleep(200); "medium" })
    cs.submit(Callable { "fast" })
    for (i in 1..3) println(cs.take().get())
    println(cs.poll() as Any?)
    pool2.shutdown()

    // scheduling
    val sched = Executors.newScheduledThreadPool(2)
    val t0 = System.nanoTime()
    val sf = sched.schedule(Callable { "scheduled" }, 150, TimeUnit.MILLISECONDS)
    println(sf.get())
    println("delay respected: ${(System.nanoTime() - t0) / 1_000_000 >= 140}")
    val ticks = AtomicInteger()
    val tickLatch = CountDownLatch(4)
    val rate = sched.scheduleAtFixedRate({ ticks.incrementAndGet(); tickLatch.countDown() }, 0, 50, TimeUnit.MILLISECONDS)
    println(tickLatch.await(5, TimeUnit.SECONDS))
    rate.cancel(false)
    println("cancelled=${rate.isCancelled}")
    val after = ticks.get()
    Thread.sleep(200)
    println("stopped=${ticks.get() <= after + 1}")
    val delayTicks = AtomicInteger()
    val dLatch = CountDownLatch(3)
    val fd = sched.scheduleWithFixedDelay({ delayTicks.incrementAndGet(); dLatch.countDown() }, 10, 30, TimeUnit.MILLISECONDS)
    println(dLatch.await(5, TimeUnit.SECONDS))
    fd.cancel(false)
    // a one-shot task still runs after shutdown, a periodic one does not
    val late = sched.schedule(Callable { "late" }, 100, TimeUnit.MILLISECONDS)
    sched.scheduleAtFixedRate({ }, 10, 10, TimeUnit.MILLISECONDS)
    sched.shutdown()
    println(late.get())
    println(sched.awaitTermination(5, TimeUnit.SECONDS))
    try {
        sched.schedule(Runnable { }, 1, TimeUnit.MILLISECONDS)
    } catch (e: RejectedExecutionException) {
        println("rejected")
    }

    // task failure
    val p3 = Executors.newSingleThreadExecutor()
    val bad = p3.submit(Callable<Int> { throw IllegalStateException("boom") })
    try {
        bad.get()
    } catch (e: ExecutionException) {
        println("failed: ${e.cause?.message}")
    }
    p3.shutdown()
    println(Executors.callable(Runnable { }, 5).call())
}
