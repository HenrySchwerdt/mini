// KCallable.name of a callable reference is a constant; a bound receiver is still evaluated.
class A { fun foo() = 1; val bar = 2 }
fun top() {}
fun main() {
    var state = 0
    val n = (state++)::toString.name
    println(n + " " + state)
    println(::top.name)
    println(A::foo.name)
    println(A()::foo.name)
    println(A::bar.name)
}
