// an array passed by name to a vararg parameter is the value of the parameter; Unit values as vararg elements

fun join(sep: String, vararg parts: String): String = parts.joinToString(sep)

fun sum(vararg x: Int, scale: Int = 1): Int {
    var s = 0
    for (v in x) s += v * scale
    return s
}

fun interface A {
    fun f(x: String): String
}

fun foo(a: A, vararg s: String): String = a.f(s[0])

fun bar(vararg s: String): String = foo({ it }, s = s)

fun units(vararg x: Unit, y: Any): Int = x.size

fun main() {
    println(join("-", "a", "b", "c"))
    println(join(sep = "+", parts = arrayOf("x", "y")))
    println(join(parts = arrayOf("p", "q"), sep = ","))
    println(sum(1, 2, 3))
    println(sum(x = intArrayOf(4, 5), scale = 2))
    println(bar("OK"))
    var acc = ""
    println(units({ acc += "1" }(), y = { acc += "2" }()))
    println(units(x = arrayOf({ acc += "3" }()), y = { acc += "4" }()))
    println(units(x = *arrayOf({ acc += "5" }()), y = { acc += "6" }()))
    println(acc)
}
