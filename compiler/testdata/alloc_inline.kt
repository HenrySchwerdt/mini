// inline allocation of small class instances (CEmitter mkh_alloc_inline): free-list reuse, bump chunks, sizes from 16 to more than 64 bytes,
// cyclic (armed) and plain classes, zeroed fields in reused blocks, a collector run (another thread: alloc_inline_threads.kt)
class P1(var a: Int)                                   // 24 bytes
class P3(var a: Long, var b: Long, var c: Long)        // 40 bytes
class P5(var a: Long, var b: Long, var c: Long, var d: Long, var e: Long, var f: Long, var g: Long) // 72 bytes: not inline
class Node(var next: Node?, var v: Int)                // armed (cyclic)
class Big(var a: Long, var b: Long, var c: Long, var d: Long, var e: Long, var f: Long, var g: Long, var h: Long, var i: Long, var j: Long)
class Zero { var x = 0; var y = 0L; var s: String? = null; var o: Node? = null }

fun churn(rounds: Int): Long {
    var sum = 0L
    for (r in 0 until rounds) {
        val p = P1(r)
        val q = P3(r.toLong(), 2L, 3L)
        val w = P5(1, 2, 3, 4, 5, 6, r.toLong())
        sum += p.a + q.a + q.b + q.c + w.g
    }
    return sum
}

fun zeroed(): Int {
    var bad = 0
    for (r in 0 until 1000) {
        val z = Zero()
        if (z.x != 0 || z.y != 0L || z.s != null || z.o != null) bad++
        z.x = r; z.y = r.toLong(); z.s = "s$r"; z.o = Node(null, r)
    }
    return bad
}

fun cycles(n: Int): Int {
    var live = 0
    for (r in 0 until n) {
        val a = Node(null, r)
        val b = Node(a, r + 1)
        a.next = b              // a cycle: only the collector frees it
        live += a.v + b.v
    }
    return live
}

fun keep(n: Int): Long {
    val list = ArrayList<Node>()
    for (i in 0 until n) list.add(Node(null, i))
    for (i in 1 until n) list[i - 1].next = list[i]
    var s = 0L
    var cur: Node? = list[0]
    while (cur != null) { s += cur.v; cur = cur.next }
    return s
}

fun main() {
    println(churn(40000))
    println(zeroed())
    println(cycles(20000))
    println(keep(10000))
    val big = ArrayList<Big>()
    for (i in 0 until 1000) big.add(Big(i.toLong(), 1, 2, 3, 4, 5, 6, 7, 8, 9))
    var t = 0L
    for (b in big) t += b.a + b.j
    println(t)
    println(churn(1000))
}
