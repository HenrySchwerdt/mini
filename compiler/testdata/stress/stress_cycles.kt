// Stress: cyclic structures are built and dropped over and over (doubly linked lists, trees with parent links,
// graphs, observers, closures that capture their owner). Without the cycle collector the memory grows with the rounds.
class DNode(val value: Int) {
    var prev: DNode? = null
    var next: DNode? = null
}

class TNode(val parent: TNode?) {
    val kids = ArrayList<TNode>()
}

class Vtx(val id: Int) {
    val out = ArrayList<Vtx>()
}

class Subject {
    val observers = ArrayList<Obs>()
}

class Obs(val subject: Subject) {
    var hits = 0
    var callback: () -> Int = { 0 }
}

fun wire(o: Obs) {
    o.callback = { o.hits++ }
    o.subject.observers.add(o)
}

fun main(args: Array<String>) {
    val scale = if (args.size > 0) args[0].toInt() else 1
    var check = 0L
    for (round in 0 until 20 * scale) {
        // doubly linked list of 20000 nodes
        var head: DNode? = null
        var tail: DNode? = null
        for (i in 0 until 20000) {
            val n = DNode(i)
            if (tail == null) head = n else { tail.next = n; n.prev = tail }
            tail = n
        }
        var c = head
        while (c != null) { check += c.value; c = c.next }
        // a tree with parent links
        val root = TNode(null)
        var frontier = ArrayList<TNode>()
        frontier.add(root)
        for (d in 0 until 7) {
            val nf = ArrayList<TNode>()
            for (t in frontier) for (k in 0 until 3) { val ch = TNode(t); t.kids.add(ch); nf.add(ch) }
            frontier = nf
        }
        check += frontier.size
        // a random graph
        val vs = ArrayList<Vtx>()
        for (i in 0 until 3000) vs.add(Vtx(i))
        for (i in 0 until 3000) { vs[i].out.add(vs[(i * 31 + 7) % 3000]); vs[i].out.add(vs[(i * 17 + 3) % 3000]) }
        check += vs[5].out.size
        // observers with self-capturing callbacks
        val s = Subject()
        for (i in 0 until 2000) wire(Obs(s))
        for (o in s.observers) check += o.callback()
    }
    println(check)
}
