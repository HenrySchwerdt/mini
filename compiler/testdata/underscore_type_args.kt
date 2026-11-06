// `_` in an explicit type argument list: the argument is inferred

fun <K, T> mapIt(x: (K) -> T): Pair<K, T> = (1 as K) to (2.5f as T)

fun <A, B, C> triple(a: A, b: B, c: C): String = "$a/$b/$c"

class Box<T>(val v: T)

fun <T : Comparable<T>> maxOf3(a: T, b: T, c: T): T = maxOf(a, maxOf(b, c))

fun <R> pick(f: () -> R): R = f()

fun main() {
    val p = mapIt<Int, _> { it.toFloat() }
    println(p)
    println(triple<_, String, _>(1, "two", 3.0))
    println(triple<Int, _, _>(1, 'x', true))
    val b = Box<_>("text")
    println(b.v.length)
    println(maxOf3<_>(3, 9, 4))
    println(listOf<_>(1, 2, 3).sum())
    println(pick<_> { "r" }.length)
    val m: Map<String, Int> = mapOf<_, _>("a" to 1)
    println(m)
}
