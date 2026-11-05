fun fib(): Sequence<Int> = sequence {
    var a = 0
    var b = 1
    while (true) {
        yield(a)
        val t = a + b
        a = b
        b = t
    }
}

fun main() {
    println(fib().take(10).toList())
    val s = sequence<Int> {
        yield(1)
        yieldAll(listOf(2, 3))
        for (i in 4..6) {
            if (i % 2 == 0) continue
            yield(i)
        }
        yieldAll(sequenceOf(7, 8))
    }
    println(s.toList())
    println(s.map { it * 10 }.filter { it > 20 }.toList())
    val it = iterator<String> {
        try {
            yield("a")
            yield("b")
        } finally {
            println("cleanup")
        }
    }
    while (it.hasNext()) println(it.next())
    println(generateSequence(1) { if (it < 100) it * 2 else null }.toList())
    println(fib().takeWhile { it < 50 }.joinToString(","))
}
