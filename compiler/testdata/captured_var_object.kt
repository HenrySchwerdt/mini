// A `var` of a class type that a lambda captures lives in a heap cell; its initializer is a fresh object.
// A Unit parameter of an override of a generic function (`T` = Unit) holds the Unit value.
class A(var value: String)

fun runIt(c: () -> Unit) {
    c()
}

interface Handler<T> {
    fun handle(v: T)
}

class UnitHandler : Handler<Unit> {
    override fun handle(v: Unit) {
        println("handled " + v)
    }
}

fun main() {
    var a = A("")
    runIt { a = A("x") }
    println(a.value)
    var b = A("start")
    runIt { b.value = b.value + "!" }
    println(b.value)
    val h: Handler<Unit> = UnitHandler()
    h.handle(Unit)
}
