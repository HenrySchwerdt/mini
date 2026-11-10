// A top-level extension `operator fun invoke` must not make `::name` of a property ambiguous
// (the invoke candidates of the property are not what the reference denotes).
class Adder(val n: Int)

operator fun <T> Adder.invoke(value: T): String = "$value+$n"

val top = 5
var counter = 1
val adder = Adder(3)
val lam: (Int) -> Int = { it * 2 }

fun main() {
    val r = ::top
    println(r.get())
    val c = ::counter
    c.set(c.get() + 10)
    println(counter)
    val a = ::adder
    println(a.get()(7))
    println(adder("x"))
    val l = ::lam
    println(l.get()(21))
}
