import kotlin.concurrent.atomics.*

class Box(val n: Int) {
    override fun toString(): String = "Box($n)"
}

@OptIn(ExperimentalAtomicApi::class)
fun main() {
    val i = AtomicInt(5)
    println(i.load())
    i.store(7)
    println(i.exchange(9))
    println(i.compareAndSet(9, 10))
    println(i.compareAndSet(9, 11))
    println(i.compareAndExchange(10, 20))
    println(i.compareAndExchange(10, 30))
    println(i.fetchAndAdd(5))
    println(i.addAndFetch(5))
    println(i.incrementAndFetch())
    println(i.fetchAndIncrement())
    println(i.decrementAndFetch())
    println(i.fetchAndDecrement())
    i += 100
    i -= 1
    println(i)
    println(i.load())

    val l = AtomicLong(1L shl 40)
    println(l.incrementAndFetch())
    println(l.fetchAndAdd(10L))
    println(l.load())
    println(l.compareAndSet(l.load(), 5L))
    println(l)

    val b = AtomicBoolean(false)
    println(b.load())
    println(b.compareAndSet(false, true))
    println(b.exchange(false))
    println(b.compareAndExchange(false, true))
    println(b)

    val first = Box(1)
    val second = Box(2)
    val r = AtomicReference(first)
    println(r.load())
    println(r.compareAndSet(Box(1), second))
    println(r.compareAndSet(first, second))
    println(r.exchange(first))
    r.store(second)
    println(r)

    val ia = AtomicIntArray(4)
    ia.storeAt(1, 5)
    println(ia.size)
    println(ia.addAndFetchAt(1, 2))
    println(ia.fetchAndIncrementAt(0))
    println(ia.compareAndSetAt(2, 0, 9))
    println(ia.exchangeAt(3, 4))
    println(ia)
    println(ia.loadAt(3))
    println(r.load())
    val ia2 = AtomicIntArray(3) { it * 10 }
    println(ia2)
    val la = AtomicLongArray(longArrayOf(1L, 2L))
    println(la.incrementAndFetchAt(1))
    println(la)
    val aa = AtomicArray(arrayOf("a", "b", "c"))
    println(aa.loadAt(2))
    aa.storeAt(0, "z")
    println(aa.exchangeAt(1, "y"))
    println(aa)
    try {
        ia.loadAt(4)
    } catch (e: IndexOutOfBoundsException) {
        println("IOOBE")
    }
}
