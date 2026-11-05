fun main() {
    val a = arrayOf("x", "y", "z")
    val ia = intArrayOf(5, 6, 7)
    for (i in a.indices) print(i)
    println()
    val r = a.indices
    println(r.first.toString() + " " + r.last)
    println(a.indices.map { it * 2 })
    println(ia.indices.filter { ia[it] > 5 })
    println(3 in a.indices)
    for (i in a.indices.reversed()) print(i)
    println()
    println(a.indices.sum())
    println(a.lastIndex)
    val e: Iterator<Int> = ia.iterator()
    println(e.next())
}
