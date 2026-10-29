class Node(val value: Int, var next: Node?)

class Person(val name: String, val nick: String?, val boss: Person?)

fun length(s: String?): Int {
    if (s == null) return -1
    return s.length
}

fun firstOr(s: String?, d: String): String = s ?: d

fun bossName(p: Person): String? = p.boss?.name

fun nickLen(p: Person): Int = p.nick?.length ?: 0

fun sum(n: Node?): Int {
    var total = 0
    var cur = n
    while (cur != null) {
        total += cur.value
        cur = cur.next
    }
    return total
}

fun half(x: Int?): Int? = if (x == null) null else x / 2

fun describe(x: Int?): String = if (x != null) "has " + (x + 1) else "none"

fun main() {
    println(length("hello"))
    println(length(null))
    println(firstOr(null, "default"))
    println(firstOr("given", "default"))
    val boss = Person("Zed", null, null)
    val emp = Person("Amy", "am", boss)
    println(bossName(emp))
    println(bossName(boss))
    println(nickLen(emp))
    println(nickLen(boss))
    println(emp.boss?.boss?.name)
    println(emp.boss?.nick ?: "nobody")
    val list = Node(1, Node(2, Node(3, null)))
    println(sum(list))
    println(sum(null))
    val n: Node? = list.next
    println(n!!.value)
    println(n.next!!.next)
    val a: Int? = 10
    val b: Int? = null
    println(a)
    println(b)
    println(half(9))
    println(half(null))
    println(describe(a))
    println(describe(b))
    println(a == 10)
    println(a == b)
    println(b == null)
    println((a ?: 0) + (b ?: 5))
    val s: String? = "text"
    println(s?.length)
    val t: String? = null
    println(t?.length)
    println("[" + t + "]")
    var cur: Node? = list
    var steps = 0
    while (cur != null) {
        steps++
        cur = cur.next
    }
    println(steps)
    val nn: Any? = null
    println(nn == null)
    println(nn ?: "was null")
}
