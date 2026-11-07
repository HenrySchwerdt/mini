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

class Lazy1 {
    lateinit var bar: String
    fun test(): String {
        var state = 0
        if (run { state++; this }::bar.isInitialized) return "Fail 1"
        bar = "A"
        if (!run { state++; this }::bar.isInitialized) return "Fail 3"
        return if (state == 2) "OK" else "Fail: state=$state"
    }
}

class Cell { var v = 1 }
var made = 0
fun makeCell(): Cell { made++; return Cell() }

fun main() {
    println(Lazy1().test())
    val ref = makeCell()::v
    ref.set(5)
    println("${ref.get()} $made")
    foo()::x
    println(sb)
    val r = ++getB()[logged(1), logged(2), logged(3)]
    println(r)
    println(log)
}
