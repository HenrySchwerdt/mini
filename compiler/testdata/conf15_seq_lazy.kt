// sequences: laziness order of side effects, early termination, generateSequence, sequence builders, windowed/chunked/zip
fun main() {
    val s = sequenceOf(1, 2, 3, 4, 5).map { println("map $it"); it * 2 }.filter { println("filter $it"); it > 4 }
    println("built"); println(s.first()); println("---"); println(s.take(1).toList())
    val l = listOf(1, 2, 3).asSequence().onEach { print("a$it ") }.map { it + 1 }.onEach { print("b$it ") }.toList(); println(); println(l)
    println(generateSequence(1) { if (it < 50) it * 3 else null }.toList()); println(generateSequence { null }.toList()); println(generateSequence(2) { it * it }.takeWhile { it < 1000 }.toList())
    val fib = sequence { var a = 0; var b = 1; while (true) { yield(a); val t = a + b; a = b; b = t } }; println(fib.take(10).toList()); println(fib.drop(5).first()); println(fib.takeWhile { it < 30 }.count()); println(fib.filter { it % 2 == 0 }.take(4).toList())
    val nested = sequence { yield(1); yieldAll(listOf(2, 3)); yieldAll(sequenceOf(4, 5)); yield(6) }; println(nested.toList()); println(nested.windowed(2).toList()); println(nested.chunked(4).toList()); println(nested.zip(nested.drop(1)).toList().size)
    println((1..10).asSequence().filter { it % 2 == 0 }.map { it * it }.sum()); println(sequenceOf("b", "a", "c").sorted().toList()); println(sequenceOf(1, 1, 2, 2, 3).distinct().toList()); println(sequenceOf(1, 2, 3).flatMap { listOf(it, -it).asSequence() }.toList())
    var calls = 0; val seq = sequence { calls++; yield(1) }; println(calls); seq.toList(); seq.toList(); println(calls); val once = listOf(1, 2).asSequence(); println(once.toList()); println(once.toList())
    println(sequenceOf(1, 2, 3).runningReduce { a, b -> a + b }.toList()); println(sequenceOf(3, 1, 2).minOrNull()); println(sequenceOf("a", "bb").associateBy { it.length }); println(sequenceOf(1, 2, 3, 4).partition { it > 2 }); println(sequenceOf(1, 2).plus(3).toList()); println(emptySequence<Int>().firstOrNull())
    println(sequenceOf(5, 6).withIndex().map { (i, v) -> i * v }.toList()); println(sequenceOf(1, 2, 3).indexOfFirst { it == 2 }); println(sequenceOf(1, 2, 3).joinToString("-")); println(sequenceOf(1, 2, 3).reduce { a, b -> a * b }); println(sequenceOf(1, 2, 3).any { it > 2 })
    println(sequenceOf(1, 2, 3, 4, 5).takeWhile { it < 3 }.toList()); println(sequenceOf(1, 2, 3, 4, 5).dropWhile { it < 3 }.toList()); println(sequenceOf(1, 2, 3).last()); println(sequenceOf(1, 2, 3).elementAt(1)); println(sequenceOf(1, 2, 3).constrainOnce().toList())
}
