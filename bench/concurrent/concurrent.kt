// concurrent: several threads work on shared concurrent structures: a ConcurrentHashMap (merge counting over 4096 keys), a bounded
// blocking queue (producers and consumers) and atomic counters. Deterministic output: the totals.
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

const val WORKERS = 8
const val OPS = 250000
const val KEYS = 4096
const val ITEMS = 200000

fun main() {
    val map = ConcurrentHashMap<Int, Int>()
    val ops = AtomicLong()
    val ts = ArrayList<Thread>()
    for (w in 0 until WORKERS) ts.add(thread {
        var x = w * 7919 + 1
        for (i in 0 until OPS) {
            x = (x * 1103515245 + 12345) and 0x7fffffff
            map.merge((x shr 8) % KEYS, 1) { a, b -> a + b }
            ops.incrementAndGet()
        }
    })
    for (t in ts) t.join()
    var total = 0L
    for (v in map.values) total += v
    println(map.size)
    println(total)
    println(ops.get())

    val q = ArrayBlockingQueue<Int>(256)
    val consumed = AtomicLong()
    val sum = AtomicLong()
    val producers = ArrayList<Thread>()
    for (p in 0 until 4) producers.add(thread { for (i in 0 until ITEMS) q.put(i + 1) })
    val consumers = ArrayList<Thread>()
    for (c in 0 until 4) consumers.add(thread {
        while (true) {
            val v = q.take()
            if (v < 0) break
            consumed.incrementAndGet()
            sum.addAndGet(v.toLong())
        }
    })
    for (p in producers) p.join()
    for (c in 0 until 4) q.put(-1)
    for (c in consumers) c.join()
    println(consumed.get())
    println(sum.get())
}
