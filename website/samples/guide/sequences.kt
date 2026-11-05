// Lazy sequences built with `sequence { }`: the block is a coroutine that suspends at every `yield`.
fun fibonacci(): Sequence<Int> = sequence {
    var a = 0
    var b = 1
    while (true) {
        yield(a)
        val next = a + b
        a = b
        b = next
    }
}

fun main() {
    println(fibonacci().take(10).toList())
    println(fibonacci().filter { it % 2 == 0 }.take(5).joinToString(" "))

    val words = sequence<String> {
        yield("sequence")
        yieldAll(listOf("builders", "are"))
        for (w in arrayOf("lazy", "and", "run", "on", "state", "machines")) {
            if (w.length > 2) yield(w)
        }
    }
    println(words.toList())
}
