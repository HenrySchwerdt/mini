fun main() {
    println(generateSequence(1) { it * 2 }.take(8).toList())
    println(generateSequence(1) { if (it < 50) it * 3 else null }.toList())
    println(generateSequence { null as Int? }.toList())
    val fib = sequence {
        var a = 0
        var b = 1
        while (true) { yield(a); val t = a + b; a = b; b = t }
    }
    println(fib.take(12).toList())
    println(fib.filter { it % 2 == 0 }.takeWhile { it < 500 }.toList())
    val s = sequence {
        yield(1)
        yieldAll(listOf(2, 3))
        yieldAll(sequenceOf(4, 5))
    }
    println(s.map { it * it }.toList())
    println((1..20).asSequence().filter { it % 3 == 0 }.map { it * 2 }.sum())
    var calls = 0
    val lazy = (1..5).asSequence().map { calls++; it }.filter { it > 2 }
    println(calls)
    println(lazy.first())
    println(calls)
    println(sequenceOf(3, 1, 2).sorted().toList())
    println(sequenceOf(1, 2, 3).zip(sequenceOf("a", "b")).toList())
    println(sequenceOf(1, 2, 3, 4).chunked(2).toList())
    println(sequenceOf(1, 2, 3, 4, 5).windowed(2).map { it.sum() }.toList())
    println(generateSequence(10) { it - 3 }.takeWhile { it > 0 }.joinToString())
    println(sequenceOf("a", "b").flatMap { x -> sequenceOf(x, x + x) }.toList())
    println(sequenceOf(1, 2, 3).onEach { print("<$it>") }.count())
    println(sequenceOf(1, 1, 2, 3, 3).distinct().toList())
    println((1..5).asSequence().drop(1).take(3).toList())
    println(sequenceOf(5, 6).withIndex().map { "${it.index}:${it.value}" }.toList())
    println(sequenceOf(1, 2, 3).fold(0) { a, b -> a + b })
    println(generateSequence("a") { it + "a" }.take(3).last())
    val it2 = sequenceOf(1, 2).iterator()
    println(it2.next() + it2.next())
    println(it2.hasNext())
}
