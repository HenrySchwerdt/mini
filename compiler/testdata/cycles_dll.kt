// Doubly linked lists: every node refers to its neighbours in both directions. Dropped lists are reference cycles.
class DNode(val value: Int) {
    var prev: DNode? = null
    var next: DNode? = null
}

class DList {
    var head: DNode? = null
    var tail: DNode? = null
    var size = 0

    fun addLast(v: Int) {
        val n = DNode(v)
        val t = tail
        if (t == null) head = n else { t.next = n; n.prev = t }
        tail = n
        size++
    }

    fun addFirst(v: Int) {
        val n = DNode(v)
        val h = head
        if (h == null) tail = n else { h.prev = n; n.next = h }
        head = n
        size++
    }

    fun removeFirst(): Int {
        val h = head!!
        head = h.next
        if (head == null) tail = null else head!!.prev = null
        h.next = null
        size--
        return h.value
    }

    fun sumForward(): Long {
        var s = 0L
        var n = head
        while (n != null) { s += n.value; n = n.next }
        return s
    }

    fun sumBackward(): Long {
        var s = 0L
        var n = tail
        while (n != null) { s += n.value; n = n.prev }
        return s
    }
}

fun main() {
    var check = 0L
    // many short-lived lists: each one is a garbage cycle when it goes out of scope
    for (round in 0 until 3000) {
        val l = DList()
        for (i in 0 until 20) l.addLast(i)
        for (i in 0 until 5) l.addFirst(100 + i)
        check += l.sumForward() + l.sumBackward() + l.size
    }
    println(check)
    // one long list stays alive across the collections that the rounds above triggered
    val live = DList()
    for (i in 0 until 50000) live.addLast(i)
    println(live.sumForward())
    println(live.sumBackward())
    var removed = 0L
    while (live.size > 49990) removed += live.removeFirst()
    println(removed)
    // a very long list that is dropped as a whole (the collector must not recurse on the structure)
    run {
        val big = DList()
        for (i in 0 until 300000) big.addLast(i)
        println(big.size)
    }
    println("done")
}
