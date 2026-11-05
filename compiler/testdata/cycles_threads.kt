import kotlin.concurrent.thread
import mini.concurrent.*

// Cycles and threads: the collector only runs while no thread runs. Workers drop cyclic structures while the main thread does the same;
// the cycles of the threaded phase are freed by the first collection after the last thread ended.
class Node(val id: Int) {
    var prev: Node? = null
    var next: Node? = null
}

class Parent {
    val kids = ArrayList<Child>()
}

class Child(val parent: Parent)

fun work(rounds: Int): Long {
    var sum = 0L
    for (r in 0 until rounds) {
        var head: Node? = null
        var tail: Node? = null
        for (i in 0 until 200) {
            val n = Node(i)
            if (tail == null) head = n else { tail.next = n; n.prev = tail }
            tail = n
        }
        var c = head
        while (c != null) { sum += c.id; c = c.next }
        val p = Parent()
        for (i in 0 until 20) p.kids.add(Child(p))
        sum += p.kids.size
    }
    return sum
}

fun main() {
    val total = AtomicLong(0)
    val threads = ArrayList<java.lang.Thread>()
    for (t in 1..4) {
        threads.add(thread { total.addAndGet(work(300)) })
    }
    val mine = work(300)
    for (t in threads) t.join()
    println(total.get())
    println(mine)
    // after the join the collector runs again: cycles keep getting freed
    var after = 0L
    for (k in 0 until 20) after += work(100)
    println(after)
    println("done")
}
