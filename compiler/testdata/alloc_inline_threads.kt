// inline allocation (CEmitter mkh_alloc_inline) on another thread: its own cache, objects freed by the thread that ends
class Node(var next: Node?, var v: Int)
class P3(var a: Long, var b: Long, var c: Long)

fun churn(rounds: Int): Long {
    var sum = 0L
    for (r in 0 until rounds) {
        val q = P3(r.toLong(), 2L, 3L)
        sum += q.a + q.b + q.c
    }
    return sum
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
    val results = ArrayList<Long>()
    val th = Thread { results.add(churn(10000) + keep(1000)) }
    th.start(); th.join()
    println(results[0])
    println(churn(1000))
}
