// a Unit-typed call compared with the Unit object: the call runs, the value is Unit

var log = ""

fun foo() { log += "foo;" }

fun any(): Any = Unit

fun main() {
    println(foo() == Unit)
    println(Unit == foo())
    println(foo() != Unit)
    println(foo() == any())
    println(any() == foo())
    println(log)
    val x: Any? = foo()
    println(x == Unit)
    println(foo() === Unit)
}
