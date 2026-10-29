class Foo
open class Base
class Derived : Base()
fun main() {
    println(Foo::class.simpleName)
    println(Foo::class.qualifiedName)
    val b: Base = Derived()
    println(b::class.simpleName)
    println(b::class == Derived::class)
    println(Foo::class == Derived::class)
    println("x"::class.qualifiedName)
    println(5::class.simpleName)
}
