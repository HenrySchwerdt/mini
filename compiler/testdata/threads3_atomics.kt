import java.util.concurrent.*
import java.util.concurrent.atomic.*

fun main() {
    val ia = AtomicIntegerArray(4)
    val la = AtomicLongArray(longArrayOf(1, 2, 3))
    val ra = AtomicReferenceArray<String>(2)
    val adder = LongAdder()
    val dadder = DoubleAdder()
    val acc = LongAccumulator({ a, b -> if (a > b) a else b }, Long.MIN_VALUE)
    val threads = (0 until 4).map { t ->
        Thread {
            for (i in 0 until 10000) {
                ia.incrementAndGet(i % 4)
                la.addAndGet(i % 3, 2L)
                adder.add(1)
                dadder.add(0.5)
                acc.accumulate((t * 10000 + i).toLong())
            }
        }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    println(ia)
    println(la)
    println(ia.length())
    println(adder.sum())
    println(adder)
    println(dadder.sum())
    println(acc.get())
    println(adder.sumThenReset())
    println(adder.sum())
    println(ia.compareAndSet(0, 10000, 5))
    println(ia.getAndAdd(0, 3))
    println(ia.get(0))
    println(ia.updateAndGet(1) { it / 2 })
    println(ia.accumulateAndGet(2, 10) { a, b -> a + b })
    println(la.getAndSet(0, 100))
    println(la.compareAndSet(1, 0, 1))
    try {
        ia.get(4)
    } catch (e: IndexOutOfBoundsException) {
        println("oob")
    }

    println(ra.compareAndSet(0, null, "a"))
    println(ra.getAndSet(1, "b"))
    println(ra.updateAndGet(0) { it + "!" })
    println(ra)

    val stamped = AtomicStampedReference("x", 0)
    val holder = IntArray(1)
    println(stamped.get(holder) + holder[0])
    println(stamped.compareAndSet("x", "y", 0, 1))
    println(stamped.compareAndSet("y", "z", 0, 2))
    println(stamped.reference + stamped.stamp)
    val marked = AtomicMarkableReference("m", false)
    println(marked.attemptMark("m", true))
    println(marked.isMarked)

    // the core atomics from many threads
    val ai = AtomicInteger()
    val al = AtomicLong()
    val ab = AtomicBoolean()
    val ar = AtomicReference("")
    val ws = (0 until 4).map {
        Thread {
            for (i in 0 until 5000) {
                ai.incrementAndGet()
                al.addAndGet(2)
                ab.compareAndSet(false, true)
                if (i % 1000 == 0) ar.updateAndGet { s -> if (s.length < 100) s + "." else s }
            }
        }
    }
    ws.forEach { it.start() }
    ws.forEach { it.join() }
    println("${ai.get()} ${al.get()} ${ab.get()} ${ar.get().length}")
}
