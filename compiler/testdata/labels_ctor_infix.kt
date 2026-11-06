// labels of lambda arguments: the class name for a constructor call (also `Array`), the function name for an infix call

class Foo(val f: () -> Int)

class Holder<T>(val v: T, val g: (T) -> String)

inline fun runReturning(f: () -> Nothing): Nothing = f()

infix fun Int.myMap(x: () -> Unit): Int {
    x()
    return this
}

infix fun Int.pick(f: (Int) -> Int): Int = f(this)

fun test(x: Int): Int {
    x myMap {
        return@myMap
    }
    return x * 2
}

fun main() {
    val a = Foo { return@Foo 5 }
    println(a.f())
    val h = Holder(7) { if (it > 5) return@Holder "big" else "small" }
    println(h.g(h.v))
    val arr = Array<String>(3) { if (it == 0) return@Array "zero" else "n$it" }
    println(arr.toList())
    val b = Array<String>(1) { runReturning { return@Array "OK" } }
    println(b[0])
    println(test(21))
    println(10 pick { if (it > 5) return@pick it * 2 else 0 })
}
