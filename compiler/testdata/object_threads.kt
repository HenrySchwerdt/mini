import kotlin.concurrent.thread
import mini.concurrent.*

// Kotlin objects and top-level properties are initialised exactly once, also when several threads touch them first at the same time.
val inits = AtomicInt(0)

class Slow(val v: Int) {
    init {
        inits.incrementAndGet()
        sleepMillis(20)
    }
}

object Registry {
    val slow = Slow(7)
    val tag: String = "registry"
    val counter = AtomicInt(0)
}

val topLevel: Slow = Slow(11)

object Outer {
    val inner = Inner.value + 1
}

object Inner {
    val value = Base.base * 2
}

object Base {
    val base = 20
}

fun main() {
    val sum = AtomicInt(0)
    val threads = ArrayList<java.lang.Thread>()
    for (i in 1..8) {
        threads.add(
            thread {
                // all of them read the fields right after the start: none may see a half-initialised object
                sum.addAndGet(Registry.slow.v + topLevel.v + Outer.inner)
                Registry.counter.incrementAndGet()
            },
        )
    }
    for (t in threads) t.join()
    println(sum.get())
    println(Registry.tag)
    println(Registry.counter.get())
    println(inits.get())
}
