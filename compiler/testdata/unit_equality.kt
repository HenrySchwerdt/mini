// a Unit-typed call compared with the Unit object: the call runs, the value is Unit

var log = ""

fun foo() { log += "foo;" }

fun any(): Any = Unit

fun nu(b: Boolean): Unit? = if (b) Unit else null

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
    println(nu(true) == Unit)
    println(nu(false) == Unit)
    println(nu(false) == null)
    println(nu(true) == null)
}
