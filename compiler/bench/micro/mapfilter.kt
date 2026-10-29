// map / filter / fold chains (inline lambdas, intermediate lists).
fun main() {
    val base = ArrayList<Int>()
    for (i in 0 until 50000) base.add(i)
    var total = 0L
    for (round in 0 until 20) {
        val r = base.map { it * 2 }.filter { it % 3 == 0 }.map { it + round }
        total += r.size
        total += r.fold(0L) { acc, x -> acc + x }
    }
    val words = ArrayList<String>()
    for (i in 0 until 20000) words.add("w" + (i % 100))
    var n = 0
    for (round in 0 until 10) {
        n += words.filter { it.length > 2 }.map { it.length }.sum()
    }
    println(total)
    println(n)
}
