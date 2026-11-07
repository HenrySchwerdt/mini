// qualified types with a one-name package, bare generic types in `is` / `as`, `recv.(fn)(args)`, receiver function types as plain function types,
// defaults inherited from an interface for a function implemented by a superclass, SAM constructors behind generic aliases
fun <T> cmp(comparator: kotlin.Comparator<in T>, a: T, b: T) = comparator.compare(a, b)

sealed class C<out T, out U>
class A<out T>(val x: T) : C<T, Nothing>()
class B<out U>(val x: U) : C<Nothing, U>()

fun bar(x: String): C<Int, String> = B(x)
fun baz(x: Any) = "any:$x"
fun baz(x: String) = "str:$x"

typealias L<T> = List<T>

fun test1(f: (Int) -> Int) = f(1)
fun test2(f: Int.() -> Int) = 2.f()

class Recv {
    companion object
}

val anyFun: Any.() -> Unit = { println("anyFun called") }

interface Foo {
    fun foo(a: Double = 1.0): Double
}

interface FooChain : Foo

open class Impl {
    fun foo(a: Double) = a
}

class FooImpl : FooChain, Impl()

fun interface KConsumer<T> {
    fun accept(value: T)
}

typealias StringConsumer = KConsumer<String>

fun main() {
    println(cmp<Int>({ a, b -> a - b }, 3, 5) < 0)
    try {
        throw kotlin.IllegalStateException("x")
    } catch (e: kotlin.IllegalStateException) {
        println("caught")
    }
    when (val x = bar("O")) {
        is A -> println("A")
        is B -> println(baz(x.x))
    }
    val test: Collection<Int> = listOf(1, 2, 3)
    if (test is L) println("is L")
    val t2 = test as L
    println(t2.size)
    val a: (Int) -> Int = { it + 10 }
    val b: Int.() -> Int = { this * 2 }
    println(test1(a))
    println(test1(b))
    println(test2(a))
    println(test2(b))
    println(1.(fun Int.() = this + 1)())
    println((fun () = 7)())
    println(1.(fun Int.(i: Int) = i + this)(5))
    Recv.(anyFun)()
    println(FooImpl().foo())
    println(FooImpl().foo(2.0))
    var s = ""
    StringConsumer { s += it }.accept("K")
    println(s)
}
