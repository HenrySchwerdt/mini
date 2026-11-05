// A fun interface that inherits its single abstract method from a generic fun interface.
fun interface G<T> {
    fun foo(t: T): T
}

fun interface C : G<Char>

fun interface Mapper<A, B> {
    fun map(a: A): B
}

fun interface IntToString : Mapper<Int, String>

fun useC(c: C): Char = c.foo('x')

fun main() {
    val g = G<Int> { it * 10 }
    println(g.foo(2))
    val c = C { it + 2 }
    println(c.foo('A'))
    println(useC { it + 1 })
    val m = IntToString { "#" + it }
    println(m.map(7))
}
