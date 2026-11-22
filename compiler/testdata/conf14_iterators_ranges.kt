class Countdown(val from: Int) : Iterable<Int> {
    override fun iterator() = object : Iterator<Int> {
        var cur = from
        override fun hasNext() = cur > 0
        override fun next() = cur--
    }
}

class Range2(val lo: Int, val hi: Int) : ClosedRange<Int> {
    override val start get() = lo
    override val endInclusive get() = hi
}

fun main() {
    for (x in Countdown(3)) print("$x ")
    println()
    println(Countdown(4).toList())
    println(Countdown(5).map { it * it }.filter { it % 2 == 1 })
    for (i in 1..10 step 3) print("$i ")
    println()
    for (i in 10 downTo 1 step 4) print("$i ")
    println()
    for (i in 0 until 3) print("$i")
    println()
    println((1..5).reversed().toList())
    println(3 in 1..5 && 6 !in 1..5)
    println((1..0).toList().isEmpty())
    println(('a'..'d').joinToString(""))
    println((1..10).sum() to (1 until 10).count())
    println(5.coerceIn(1..3))
    println((1..3).contains(2))
    println(Range2(2, 4).contains(3))
    println((1.0..2.0).contains(1.5))
    println((1L..3L).map { it * 2 })
    println(IntRange(1, 3).last + (1..9 step 2).last)
    println((5 downTo 1).filter { it % 2 == 0 })
    println(1..3 == 1..3)
    println((1..3).first to (1..3).step)
    val it = listOf(1, 2, 3).iterator()
    var s = 0
    while (it.hasNext()) s += it.next()
    println(s)
    val li = mutableListOf(1, 2, 3, 4).listIterator()
    while (li.hasNext()) { val v = li.next(); if (v % 2 == 0) li.remove() else li.set(v * 10) }
    println(li.hasPrevious())
    for (c in "abc") print(c.code)
    println()
    for (i in listOf(1, 2, 3).indices) print(i)
    println()
    println((0..20 step 5).toList())
}
