import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

// Objects (lists, strings, maps) travel between threads through queues, maps and futures; every reference count is atomic once a thread
// runs, and under the leak suite (AddressSanitizer + LeakSanitizer) nothing may leak or be freed twice.
class Job(val id: Int, val payload: List<String>, val next: Job?)

fun makeJob(id: Int, depth: Int): Job {
    val items = ArrayList<String>()
    for (i in 0 until 5) items.add("item-" + id + "-" + i)
    return Job(id, items, if (depth > 0) makeJob(id + 1000, depth - 1) else null)
}

fun weigh(j: Job): Long {
    var w = 0L
    var cur: Job? = j
    while (cur != null) {
        for (s in cur.payload) w += s.length
        cur = cur.next
    }
    return w
}

fun main() {
    // pipeline: producers -> queue -> consumers -> concurrent map
    val q = LinkedBlockingQueue<Job>(64)
    val results = ConcurrentHashMap<Int, Long>()
    val producers = ArrayList<Thread>()
    for (p in 0 until 4) producers.add(thread {
        for (i in 0 until 500) q.put(makeJob(p * 10000 + i, 2))
    })
    val consumers = ArrayList<Thread>()
    val poison = Job(-1, emptyList(), null)
    for (c in 0 until 4) consumers.add(thread {
        while (true) {
            val j = q.take()
            if (j.id < 0) break
            results.put(j.id, weigh(j))
        }
    })
    for (p in producers) p.join()
    for (c in 0 until 4) q.put(poison)
    for (c in consumers) c.join()
    println(results.size)
    var total = 0L
    for (v in results.values) total += v
    println(total)

    // executor with many small tasks that build and drop structures
    val pool = Executors.newFixedThreadPool(6)
    val futures = ArrayList<java.util.concurrent.Future<Long>>()
    for (i in 0 until 2000) futures.add(pool.submit(java.util.concurrent.Callable { weigh(makeJob(i, 3)) }))
    var sum = 0L
    for (f in futures) sum += f.get()
    println(sum)
    pool.shutdown()
    println(pool.awaitTermination(10, TimeUnit.SECONDS))

    // completable futures chained across threads
    val chain = (0 until 200).map { n ->
        CompletableFuture.supplyAsync { makeJob(n, 1) }.thenApply { weigh(it) }.thenCombine(CompletableFuture.supplyAsync { n.toLong() }) { a, b -> a + b }
    }
    var cs = 0L
    for (c in chain) cs += c.join()
    println(cs)

    // shared counters under contention and a latch
    val counter = AtomicLong()
    val hits = AtomicInteger()
    val latch = CountDownLatch(8)
    for (t in 0 until 8) thread {
        for (i in 0 until 20000) {
            counter.addAndGet(i.toLong())
            if (i % 1000 == 0) hits.incrementAndGet()
        }
        latch.countDown()
    }
    latch.await()
    println(counter.get())
    println(hits.get())
}
