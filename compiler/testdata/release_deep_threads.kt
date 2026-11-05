import kotlin.concurrent.thread
import mini.concurrent.*

// The deep release of release_deep, in worker threads (each thread has its own stack and its own queue of deferred releases);
// not run on wasm32 (no threads).
class Cell(val v: Int, val next: Cell?)

fun chain(n: Int): Cell? {
    var h: Cell? = null
    for (i in 0 until n) h = Cell(i, h)
    return h
}

fun sum(c: Cell?): Long {
    var s = 0L
    var p = c
    while (p != null) { s += p.v; p = p.next }
    return s
}

fun deep(depth: Int, n: Int): Long {
    if (depth == 0) {
        val c = chain(n)
        return sum(c) // c is released here, below `depth` frames
    }
    return deep(depth - 1, n) + 1
}

fun main() {
    val results = AtomicLong(0)
    val ts = ArrayList<java.lang.Thread>()
    for (k in 1..3) ts.add(thread { results.addAndGet(deep(3000, 200000)) })
    for (t in ts) t.join()
    println(results.get())
    println(deep(2000, 300000))
    println("done")
}
