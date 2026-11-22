// step, downTo, reversed, until, empty ranges, progression properties
fun main() {
    println((1..10 step 3).toList()); println((10 downTo 1 step 4).toList()); println((1..0).toList()); println((5 downTo 6).toList())
    println((1..10).reversed().toList()); println((1 until 10 step 4).toList()); println(((1..10 step 2).reversed()).toList())
    val p = 1..10 step 3; println(p.first); println(p.last); println(p.step); println(p.count()); println(p.sum())
    println((10 downTo 1 step 3).last); println((0..100 step 25).toList())
    println(('a'..'e').toList()); println(('a'..'k' step 5).toList()); println(('e' downTo 'a').toList()); println(('a'..'c').reversed().toList())
    println((1L..5L step 2L).toList()); println((5L downTo 1L step 2L).toList()); println((0 until 0).toList()); println((3 until 1).count())
    println((1..5).sum()); println((1..5).fold(1) { a, b -> a * b }); println((1..5).map { it * it }); println((1..5).filter { it % 2 == 1 })
    println(IntRange.EMPTY.toList()); println((1..3) == (1..3)); println((1..0) == (5..2)); println((1..3).hashCode() == (1..3).hashCode())
    println((1..10).random() in 1..10); println(1..3); println(3 downTo 1); println(1..10 step 2)
    for (i in 10 downTo 7) print(i); println()
    for (i in 0 until 3) for (j in i..2) print("" + i + j + " "); println()
    println((1..3).first()); println((1..3).last()); println((3..1).firstOrNull()); println((1..6 step 2).map { it })
    try { 1..5 step 0 } catch (e: IllegalArgumentException) { println(e.message) }
    try { 1..5 step -1 } catch (e: IllegalArgumentException) { println(e.message) }
}
