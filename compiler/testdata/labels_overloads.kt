// Lambda labels, nested classes of objects, overloads chosen by the lambda result type.
object nn {
    class Linear(val n: Int) { fun twice() = n * 2 }
    object Inner { fun hi() = "inner" }
}

fun main() {
    listOf(1, 2, 3).forEach lit@{ if (it == 2) return@lit; println(it) }
    listOf(1, 2, 3).forEach { if (it == 2) return@forEach; println(it) }
    val r = listOf(1, 2, 3).map lbl@{ if (it == 2) return@lbl 20; it }
    println(r)
    outer@ for (i in 1..3) {
        for (j in 1..3) {
            if (j == 2) continue@outer
            if (i == 3) break@outer
            println("$i$j")
        }
    }
    val l = nn.Linear(2)
    println(l.n)
    println(nn.Linear(5).twice())
    println(nn.Inner.hi())
    println(listOf(1.5, 2.5).sumOf { it })
    println(listOf(1, 2).sumOf { it * 2 })
    println(listOf(1, 2).sumOf { it.toLong() * 3000000000 })
}
