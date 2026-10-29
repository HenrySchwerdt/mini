// Used on "How it works: Closures and inline" and "Generics" to show closures, bridges and inline expansion.

fun adder(k: Int): (Int) -> Int = { x -> x + k }

inline fun twice(action: () -> Unit) {
    action()
    action()
}

class Box<T>(val value: T) {
    fun get(): T = value
}

fun main() {
    val add5 = adder(5)
    println(add5(10))
    var n = 0
    twice { n += 1 }
    println(n)
    println(Box(41).get() + 1)
}
