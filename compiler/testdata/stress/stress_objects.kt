// Stress: millions of small objects. Singly linked lists of points are built and dropped; a pool of long-lived objects
// survives all rounds. The scale argument multiplies the rounds, never the live data: the peak memory must not grow with it.
class P(val x: Int, val y: Int)
class L(val v: P, val next: L?)
class Holder(val items: ArrayList<P>)

fun main(args: Array<String>) {
    val scale = if (args.size > 0) args[0].toInt() else 1
    val live = Holder(ArrayList<P>())
    for (i in 0 until 1000) live.items.add(P(i, -i))
    var sum = 0L
    for (round in 0 until 10 * scale) {
        var head: L? = null
        for (i in 0 until 100000) head = L(P(i, i + 1), head)
        var n = head
        while (n != null) {
            sum += n.v.x + n.v.y
            n = n.next
        }
        // short-lived objects that die immediately
        for (i in 0 until 100000) {
            val p = P(i, i)
            sum += p.x - p.y
        }
    }
    for (p in live.items) sum += p.x + p.y
    println(sum)
}
