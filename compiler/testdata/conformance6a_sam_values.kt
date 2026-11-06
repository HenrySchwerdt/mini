// function values (also nullable ones) converted to fun interfaces; nested / inner classes of the superclass in scope
fun interface KRunnable {
    fun invoke()
}

fun run1(r: KRunnable) = r.invoke()

fun isNull(r: KRunnable?): Boolean {
    if (r == null) return true
    r.invoke()
    return false
}

fun nullableFun(fromNull: Boolean): (() -> Unit)? =
    if (fromNull) null else { { println("called") } }

open class A(val value: String) {
    inner class B(val s: String) {
        val result = value + "_" + s
    }
}

class C : A("fromC") {
    fun classReceiver() = B("OK")
    fun superReceiver() = super.B("OK")
}

fun main() {
    val f: () -> Unit = { println("f") }
    run1(f)
    println(isNull(nullableFun(true)))
    println(isNull(nullableFun(false)))
    println(isNull {})
    println(C().classReceiver().result)
    println(C().superReceiver().result)
}
