// Work distribution on threads: a fixed pool of workers pulling jobs from a blocking queue, futures, a latch, atomics, a lock-guarded
// account transfer test, a concurrent map and a parallel map-reduce. All results are deterministic.

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

fun isPrime(n: Int): Boolean {
    if (n < 2) return false
    var d = 2
    while (d * d <= n) {
        if (n % d == 0) return false
        d++
    }
    return true
}

class Job(val run: (() -> Unit)?)

class WorkerPool(private val size: Int) {
    private val jobs = ArrayBlockingQueue<Job>(16, false)
    private val workers = ArrayList<Thread>()
    val completed = AtomicInteger()

    init {
        repeat(size) { n ->
            workers.add(thread(name = "worker-$n") {
                while (true) {
                    val job = jobs.take().run ?: break
                    job()
                    completed.incrementAndGet()
                }
            })
        }
    }

    fun submit(job: () -> Unit) = jobs.put(Job(job))

    fun shutdown() {
        repeat(size) { jobs.put(Job(null)) }
        workers.forEach { it.join() }
    }
}

class Account(val id: Int, var balance: Long) {
    val lock = ReentrantLock()
}

fun transfer(from: Account, to: Account, amount: Long) {
    // acquire both locks in a fixed order so opposite transfers cannot deadlock
    val (first, second) = if (from.id < to.id) from to to else to to from
    first.lock.withLock {
        second.lock.withLock {
            from.balance -= amount
            to.balance += amount
        }
    }
}

fun main() {
    // 1. a pool of workers counting primes in slices
    val pool = WorkerPool(4)
    val total = AtomicInteger()
    val perSlice = ConcurrentHashMap<Int, Int>()
    val slices = 20
    for (s in 0 until slices) {
        pool.submit {
            val lo = s * 5000
            val count = (lo until lo + 5000).count { isPrime(it) }
            perSlice[s] = count
            total.addAndGet(count)
        }
    }
    pool.shutdown()
    println("jobs completed: ${pool.completed.get()}, primes below 100000: ${total.get()}")
    println("slices: " + perSlice.entries.sortedBy { it.key }.take(5).joinToString { "${it.key}:${it.value}" } + " ...")

    // 2. executor with futures
    val exec = Executors.newFixedThreadPool(3)
    val futures = (1..8).map { n -> exec.submit<Long> { (1..n * 100000L).fold(0L) { a, b -> (a + b * b) % 1_000_003 } } }
    println("future results: " + futures.map { it.get() })
    exec.shutdown()
    println("terminated: " + exec.awaitTermination(10, TimeUnit.SECONDS))

    // 3. latch and a shared counter with a lock
    val latch = CountDownLatch(5)
    val lock = ReentrantLock()
    var counter = 0
    val atomic = AtomicLong()
    val threads = (1..5).map { id ->
        thread {
            repeat(2000) {
                lock.withLock { counter++ }
                atomic.addAndGet(id.toLong())
            }
            latch.countDown()
        }
    }
    latch.await()
    threads.forEach { it.join() }
    println("counter=$counter atomic=${atomic.get()}")

    // 4. bank transfers in both directions
    val a = Account(1, 10_000)
    val b = Account(2, 10_000)
    val movers = listOf(
        thread { repeat(1000) { transfer(a, b, 3) } },
        thread { repeat(1000) { transfer(b, a, 2) } },
        thread { repeat(500) { transfer(a, b, 7) } },
    )
    movers.forEach { it.join() }
    println("balances: a=${a.balance} b=${b.balance} total=${a.balance + b.balance}")

    // 5. parallel word count (map-reduce)
    val text = "the quick brown fox jumps over the lazy dog the end of the story".split(" ")
    val chunks = text.chunked(4)
    val partial = Array(chunks.size) { emptyMap<String, Int>() }
    chunks.mapIndexed { i, chunk -> thread { partial[i] = chunk.groupingBy { it }.eachCount() } }.forEach { it.join() }
    val merged = HashMap<String, Int>()
    for (m in partial) for ((w, c) in m) merged[w] = (merged[w] ?: 0) + c
    println("word counts: " + merged.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(4))

    // 6. producer / consumer through a bounded queue
    val queue = ArrayBlockingQueue<Int>(3)
    val consumed = ArrayList<Int>()
    val consumer = thread {
        while (true) {
            val x = queue.take()
            if (x < 0) break
            consumed.add(x * x)
        }
    }
    for (i in 1..10) queue.put(i)
    queue.put(-1)
    consumer.join()
    println("consumed: $consumed")
}
