fun foo(vararg arr: Int = intArrayOf(1, 2)) { arr.forEach { println(it) } }
fun bar(vararg f: () -> String = arrayOf({ "d" })) = f.map { it() }
class C(vararg a: String = arrayOf("x", "y")) { val n = a.size }
fun main() { under(); foo(); foo(42); println(bar()); println(bar({ "z" })); println(C().n) }

class A { operator fun component1() = 1; operator fun component2() = 2 }
fun under() {
    val (_, b) = A()
    val (`_`, c) = A()
    println("$b $`_` $c")
}
