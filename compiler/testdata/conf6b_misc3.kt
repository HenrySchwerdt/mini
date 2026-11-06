val sb = StringBuilder()
class TestClass { var x: Int = 42 }
fun foo(): TestClass { sb.append(42); return TestClass() }

var log = ""
fun <T> logged(value: T): T = value.also { log += "$value;" }
object B {
    var x = 0
    operator fun get(i1: Int, i2: Int, i3: Int): Int = x.also { log += "get-B($i1, $i2, $i3);" }
    operator fun set(i1: Int, i2: Int, i3: Int, value: Int) {
        log += "set-B($i1, $i2, $i3, $value);"
        x = value
    }
}
fun getB() = B.also { log += "getB();" }

fun main() {
    foo()::x
    println(sb)
    val r = ++getB()[logged(1), logged(2), logged(3)]
    println(r)
    println(log)
}
