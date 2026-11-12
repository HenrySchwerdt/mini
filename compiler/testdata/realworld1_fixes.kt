// realworld-1: a nullable lambda result against an unbounded type parameter (`mapNotNull(f)` with `f: (T) -> R?`), smart casts of a
// variable that is declared and assigned inside a lambda (a loop in `run { }` / `sequence { }`).

fun <T, R> List<T>.mapNotEmpty(f: (T) -> R?): List<R> = mapNotNull(f)

fun take(s: String) = s.length

fun total(a: Iterator<String>): Int = run {
    var y = if (a.hasNext()) a.next() else null
    var t = 0
    while (y != null) {
        t += take(y)
        y = if (a.hasNext()) a.next() else null
    }
    t
}

fun <T : Comparable<T>> merge(a: Iterator<T>, b: Iterator<T>): Sequence<T> = sequence {
    var x = if (a.hasNext()) a.next() else null
    var y = if (b.hasNext()) b.next() else null
    while (x != null || y != null) {
        if (y == null || (x != null && x <= y)) {
            yield(x!!)
            x = if (a.hasNext()) a.next() else null
        } else {
            yield(y)
            y = if (b.hasNext()) b.next() else null
        }
    }
}

fun main() {
    println(listOf("1", "x", "3").mapNotEmpty { it.toIntOrNull() })
    println(total(listOf("ab", "c", "def").iterator()))
    println(merge(listOf(1, 4, 6).iterator(), listOf(2, 3, 9).iterator()).toList())
    var counter = 0
    val inc = { counter++ }
    inc()
    inc()
    println(counter)
}
