fun find(m: List<List<Int>>, t: Int): Pair<Int, Int>? {
    outer@ for ((i, row) in m.withIndex()) {
        for ((j, v) in row.withIndex()) {
            if (v == t) return i to j
            if (v > 100) continue@outer
            if (v < 0) break@outer
        }
    }
    return null
}

fun sumUntil(xs: List<Int>, stop: Int): Int {
    var s = 0
    xs.forEach lit@{
        if (it == stop) return@lit
        s += it
    }
    return s
}

fun firstBig(xs: List<Int>): Int {
    xs.forEach {
        if (it > 10) return it
    }
    return -1
}

fun main() {
    val m = listOf(listOf(1, 2, 3), listOf(200, 5), listOf(7, -1, 9), listOf(9))
    println(find(m, 3))
    println(find(m, 5))
    println(find(m, 9))
    println(sumUntil(listOf(1, 2, 3, 2), 2))
    println(firstBig(listOf(1, 20, 30)))
    val r = run {
        if (m.size > 2) return@run "big"
        "small"
    }
    var count = 0
    loop@ while (true) {
        count++
        when {
            count < 3 -> continue@loop
            count > 5 -> break@loop
        }
        println("count $count")
    }
    val list = listOf(1, 2, 3, 4).map m@{
        if (it % 2 == 0) return@m 0
        it
    }
    println(list)
    fun local(x: Int): Int {
        if (x > 2) return 99
        return x
    }
    println(local(5) + local(1))
    do {
        count--
        if (count == 3) break
    } while (count > 0)
    println(count)
}
