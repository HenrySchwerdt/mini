import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

fun main() {
    println("== TimeUnit")
    println(TimeUnit.SECONDS.toMillis(3))
    println(TimeUnit.MILLISECONDS.toSeconds(4500))
    println(TimeUnit.HOURS.toMinutes(2))
    println(TimeUnit.MINUTES.convert(180, TimeUnit.SECONDS))
    println(TimeUnit.DAYS.toHours(1))
    println(TimeUnit.SECONDS.toNanos(Long.MAX_VALUE))

    println("== CountDownLatch / Semaphore / CyclicBarrier")
    val latch = CountDownLatch(4)
    val done = AtomicInteger()
    for (i in 0 until 4) thread {
        Thread.sleep(10L * i)
        done.incrementAndGet()
        latch.countDown()
    }
    println(latch.await(5, TimeUnit.SECONDS))
    println(done.get())
    println(latch.count)
    println(CountDownLatch(1).await(20, TimeUnit.MILLISECONDS))
    val sem = Semaphore(2)
    val active = AtomicInteger()
    val maxActive = AtomicInteger()
    val ts = ArrayList<Thread>()
    for (i in 0 until 6) ts.add(thread {
        sem.acquire()
        val now = active.incrementAndGet()
        while (true) {
            val m = maxActive.get()
            if (now <= m || maxActive.compareAndSet(m, now)) break
        }
        Thread.sleep(15)
        active.decrementAndGet()
        sem.release()
    })
    for (t in ts) t.join()
    println(maxActive.get() <= 2)
    println(sem.availablePermits())
    println(sem.tryAcquire(3))
    val barrierHits = AtomicInteger()
    val barrier = CyclicBarrier(3) { barrierHits.incrementAndGet() }
    val ts2 = ArrayList<Thread>()
    for (i in 0 until 3) ts2.add(thread {
        barrier.await()
        barrier.await()
    })
    for (t in ts2) t.join()
    println(barrierHits.get())

    println("== Executors")
    val pool = Executors.newFixedThreadPool(4)
    val futures = ArrayList<java.util.concurrent.Future<Int>>()
    for (i in 1..10) futures.add(pool.submit(Callable { i * i }))
    var sum = 0
    for (f in futures) sum += f.get()
    println(sum)
    val fr = pool.submit { 5 }
    println(fr.get())
    val bad = pool.submit(Callable<Int> { throw IllegalStateException("boom") })
    try {
        bad.get()
    } catch (e: ExecutionException) {
        println("failed: " + e.cause?.message)
    }
    val slow = pool.submit(Callable { Thread.sleep(300); 1 })
    try {
        slow.get(20, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        println("timeout")
    }
    println(slow.get())
    println(slow.isDone)
    val all = pool.invokeAll(listOf(Callable { "a" }, Callable { "b" }, Callable { "c" }))
    println(all.map { it.get() })
    val counter = AtomicInteger()
    val lat = CountDownLatch(100)
    for (i in 0 until 100) pool.execute { counter.incrementAndGet(); lat.countDown() }
    lat.await()
    println(counter.get())
    pool.shutdown()
    println(pool.awaitTermination(5, TimeUnit.SECONDS))
    println(pool.isShutdown)
    println(pool.isTerminated)
    try {
        pool.execute { }
    } catch (e: java.util.concurrent.RejectedExecutionException) {
        println("rejected")
    }
    val single = Executors.newSingleThreadExecutor()
    val order = ArrayList<Int>()
    val fs = ArrayList<java.util.concurrent.Future<*>>()
    for (i in 0 until 5) fs.add(single.submit { order.add(i) })
    for (f in fs) f.get()
    println(order)
    single.shutdown()
    val cached = Executors.newCachedThreadPool()
    println(cached.submit(Callable { "cached" }).get())
    cached.shutdown()

    println("== CompletableFuture")
    val cf = CompletableFuture.supplyAsync { 20 }.thenApply { it + 1 }.thenApply { it * 2 }
    println(cf.get())
    println(CompletableFuture.supplyAsync { "x" }.thenCombine(CompletableFuture.supplyAsync { "y" }) { a, b -> a + b }.join())
    println(CompletableFuture.supplyAsync { 3 }.thenCompose { v -> CompletableFuture.supplyAsync { v * 10 } }.join())
    val failing = CompletableFuture.supplyAsync<Int> { throw IllegalArgumentException("bad") }
    println(failing.exceptionally { -1 }.join())
    try {
        failing.get()
    } catch (e: ExecutionException) {
        println("exec: " + e.cause?.message)
    }
    val manual = CompletableFuture<String>()
    thread { Thread.sleep(20); manual.complete("late") }
    println(manual.get())
    println(manual.isDone)
    println(CompletableFuture.completedFuture(7).join())
    val parts = (1..5).map { n -> CompletableFuture.supplyAsync { n * n } }
    CompletableFuture.allOf(*parts.toTypedArray()).join()
    println(parts.map { it.join() })

    println("== ConcurrentHashMap")
    val map = ConcurrentHashMap<Int, Int>()
    val ts3 = ArrayList<Thread>()
    for (t in 0 until 8) ts3.add(thread {
        for (k in 0 until 2000) map.merge(k % 50, 1) { a, b -> a + b }
    })
    for (t in ts3) t.join()
    println(map.size)
    println(map.values.sum())
    println(map[7])
    println(map.putIfAbsent(7, 99))
    println(map.putIfAbsent(100, 5))
    println(map.computeIfAbsent(101) { it * 2 })
    println(map.getOrDefault(999, -1))
    println(map.remove(100))
    println(map.containsKey(100))
    val words = ConcurrentHashMap<String, Int>()
    for (w in listOf("a", "b", "a", "c", "a", "b")) words.compute(w) { _, v -> (v ?: 0) + 1 }
    println(words.toSortedMap())

    println("== queues")
    val q = LinkedBlockingQueue<Int>()
    val consumed = AtomicInteger()
    val producer = thread { for (i in 1..1000) q.put(i) }
    val consumers = ArrayList<Thread>()
    for (c in 0 until 3) consumers.add(thread {
        while (true) {
            val v = q.take()
            if (v < 0) break
            consumed.addAndGet(v)
        }
    })
    producer.join()
    for (c in 0 until 3) q.put(-1)
    for (c in consumers) c.join()
    println(consumed.get())
    val bq = ArrayBlockingQueue<Int>(2)
    println(bq.offer(1))
    println(bq.offer(2))
    println(bq.offer(3))
    println(bq.offer(3, 10, TimeUnit.MILLISECONDS))
    val p0: Int? = bq.poll()
    println(p0)
    println(bq.remainingCapacity())
    val pk: Int? = bq.peek()
    println(pk)
    val p1: Int? = bq.poll(10, TimeUnit.MILLISECONDS)
    println(p1)
    val p2: Int? = bq.poll(10, TimeUnit.MILLISECONDS)
    println(p2)
    val clq = ConcurrentLinkedQueue<String>()
    clq.add("x")
    clq.offer("y")
    println(clq.poll())
    println(clq.size)
    println(clq.toList())
    val cow = CopyOnWriteArrayList<Int>()
    cow.add(1)
    cow.add(2)
    for (x in cow) if (x == 1) cow.add(3)
    println(cow)
    println(cow.addIfAbsent(3))
    println(cow.addIfAbsent(4))
    println(cow.size)
}
