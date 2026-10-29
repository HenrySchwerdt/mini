// ArrayList add / get / set / iteration over boxed Ints and over objects.
class Item(val id: Int, val weight: Int)

fun fill(n: Int): ArrayList<Int> {
    val l = ArrayList<Int>()
    for (i in 0 until n) l.add(i * 3 + 1)
    return l
}

fun sumGet(l: ArrayList<Int>): Long {
    var s = 0L
    for (i in 0 until l.size) s += l[i]
    return s
}

fun main() {
    var total = 0L
    for (round in 0 until 20) {
        val l = fill(100000)
        total += sumGet(l)
        for (i in 0 until l.size) l[i] = l[i] + round
        var s2 = 0L
        for (x in l) s2 += x
        total += s2
    }
    val items = ArrayList<Item>()
    for (i in 0 until 200000) items.add(Item(i, i % 7))
    var w = 0L
    for (round in 0 until 10) for (i in 0 until items.size) w += items[i].weight
    println(total)
    println(w)
}
