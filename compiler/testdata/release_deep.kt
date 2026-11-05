import kotlin.concurrent.thread
import mini.concurrent.*

// Dropping the head of a long chain recurses through the disposes; the runtime queues dead objects once the stack is half used
// and works the queue off from a shallower frame. Here: a long chain dropped at the top, dropped under deep recursion of the
// program itself, dropped in a worker thread, and a chain of chains (a list of lists).
class Cell(val v: Int, val next: Cell?)

class Tree(val l: Tree?, val r: Tree?)

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
        val s = sum(c)
        return s // c is released here, below `depth` frames
    }
    return deep(depth - 1, n) + 1
}

// a left-leaning tree as deep as a list
fun leftTree(n: Int): Tree? {
    var t: Tree? = null
    for (i in 0 until n) t = Tree(t, null)
    return t
}

fun main() {
    println(sum(chain(500000)))
    println(deep(20000, 300000))
    println(deep(5, 300000))
    val results = AtomicLong(0)
    val ts = ArrayList<java.lang.Thread>()
    for (k in 1..3) ts.add(thread { results.addAndGet(deep(3000, 200000)) })
    for (t in ts) t.join()
    println(results.get())
    val lists = ArrayList<Cell?>()
    for (i in 0 until 50) lists.add(chain(20000))
    var total = 0L
    for (l in lists) total += sum(l)
    println(total)
    lists.clear()
    var t = leftTree(300000)
    var depth = 0
    while (t != null) { depth++; t = t.l }
    println(depth)
    for (i in 0 until 20) println(sum(chain(100000)))
    println("done")
}
