// Default arguments declared on an interface function (no body) and inherited by the overrides.
interface Foo {
    fun foo(a: Double = 1.0): Double
    fun bar(s: String, n: Int = 3): String
}

class FooImpl : Foo {
    override fun foo(a: Double): Double = a
    override fun bar(s: String, n: Int): String = s + n
}

abstract class Base : Foo

class Impl2 : Base() {
    override fun foo(a: Double): Double = a * 2
    override fun bar(s: String, n: Int): String = s + "#" + n
}

fun main() {
    println(FooImpl().foo())
    println(FooImpl().foo(2.0))
    println(FooImpl().bar("x"))
    println(Impl2().foo())
    println(Impl2().bar("y", 5))
    val f: Foo = Impl2()
    println(f.bar("z"))
}
