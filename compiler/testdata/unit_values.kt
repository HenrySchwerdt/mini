// Local variables of type Unit (no storage), Unit as a generic result, println(Unit).
fun foo(): Unit {
    println("foo ran")
}

fun <T> id(t: T): T = t

fun main() {
    val x = foo()
    println(x)
    val z = id(Unit)
    println(z)
    var w = Unit
    w = foo()
    println(w)
    val r: Unit = id(Unit)
    println(r)
    val list = listOf(Unit, foo())
    println(list.size)
}
