class Node(val v: Int, val next: Node? = null)

fun len(s: String?): Int = s?.length ?: 0

fun chain(n: Node?): Int? = n?.next?.next?.v

fun firstOrNegative(l: List<Int>?): Int = l?.firstOrNull() ?: -1

fun main() {
    println(len(null) + len("ab"))
    val n = Node(1, Node(2, Node(3)))
    println(chain(n))
    println(chain(n.next))
    println(firstOrNegative(null) to firstOrNegative(listOf(4)))
    val s: String? = "text"
    if (s != null) println(s.length)
    val t: String? = null
    println(t ?: "default")
    println(t?.length)
    println(s!!.length)
    val m = mapOf("a" to 1)
    println(m["b"] ?: 0)
    println(m["a"]?.plus(1))
    val x: Int? = 5
    val y: Int? = null
    println((x ?: 0) + (y ?: 0))
    println(x?.compareTo(3))
    println(x == 5 && y == null)
    val list: List<String?> = listOf("a", null, "b")
    println(list.filterNotNull().joinToString())
    println(list.map { it?.uppercase() })
    println(list.count { it == null })
    var z: Node? = n
    var total = 0
    while (z != null) { total += z.v; z = z.next }
    println(total)
    val any: Any? = null
    println(any is String)
    println((any as? String)?.length)
    println(any?.hashCode())
    println(null.toString() + null.hashCode())
    println(listOfNotNull(1, null, 2))
    val q: Int? = if (total > 3) total else null
    println(q?.let { it * 2 })
    println(arrayOfNulls<String>(2).toList())
    println(s?.let { t?.length } ?: "nested null")
}
