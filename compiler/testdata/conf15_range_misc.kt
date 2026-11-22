// ranges in conditions and with variables, forEachIndexed, indices, withIndex, zip, chunked, windowed
fun main() {
    val l = listOf("a", "b", "c", "d", "e")
    println(l.indices); println(l.indices.reversed()); println(l.lastIndex); println(l.withIndex().map { (i, s) -> i.toString() + s })
    for ((i, s) in l.withIndex()) if (i % 2 == 0) print(s); println()
    println(l.slice(1..3)); println(l.slice(listOf(4, 0))); println(l.subList(1, 3)); println(l.drop(2).take(2))
    println(l.zip(1..5)); println(l.chunked(2)); println(l.windowed(3, 2)); println(l.windowed(3, 2, true))
    println("hello".substring(1..3)); println("hello".slice(0..4 step 2))
    val a = intArrayOf(1, 2, 3, 4, 5); println(a.copyOfRange(1, 4).toList()); println(a.sliceArray(1..2).toList()); println(a.indices); println(a.reversed())
    var lo = 3; var hi = 1; println((lo..hi).isEmpty()); lo = 0; println((lo..hi).toList())
    println((1..4).zip(4 downTo 1)); println((1..4).windowed(2)); println((1..3).flatMap { x -> (x..3).map { x * it } })
    println((1..20).filter { it % 3 == 0 }.map { it / 3 }); println((1..4).reduce { a, b -> a * b }); println((1..4).runningFold(0) { a, b -> a + b }); println((1..4).scan(1) { a, b -> a * b })
    println(('a'..'e').joinToString("")); println(('a'..'e').map { it.code - 'a'.code }); println(('a'..'z').count()); println(('a'..'z').indexOf('k'))
    println((1..3).asSequence().map { it * 2 }.toList()); println(generateSequence(1) { it * 2 }.take(6).toList()); println((1..3).associateWith { it * it })
    println((1..10).partition { it > 5 }); println((1..6).groupBy { it % 3 }); println((1..5).any { it > 4 }); println((1..5).all { it > 1 }); println((1..5).none { it > 5 })
    println((10 downTo 1).filter { it % 4 == 0 }); println((1..3).maxOrNull()); println((3 downTo 1).minOrNull()); println((1..3).average())
}
