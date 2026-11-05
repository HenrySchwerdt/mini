// Adapted callable references: default arguments, vararg and coercion to Unit.
fun greet(name: String, greeting: String = "Hello"): String = greeting + ", " + name

fun sum(vararg xs: Int): Int {
    var s = 0
    for (x in xs) s += x
    return s
}

fun mixed(a: Int, vararg rest: String, suffix: String = "!"): String {
    var r = a.toString()
    for (x in rest) r += x
    return r + suffix
}

fun noisy(x: Int): Int {
    println("noisy " + x)
    return x * 2
}

class Box(val tag: String) {
    fun wrap(s: String, times: Int = 1): String {
        var r = tag
        for (i in 0 until times) r += s
        return r
    }
}

fun <T> apply1(f: (String) -> T): T = f("world")
fun call0(f: () -> Int): Int = f()
fun call2(f: (Int, Int) -> Int): Int = f(3, 4)
fun call1(f: (Int) -> String): String = f(5)
fun call3(f: (Int, String, String) -> String): String = f(1, "x", "y")
fun callUnit(f: (Int) -> Unit) {
    f(5)
}
fun callBox(f: (Box, String) -> String): String = f(Box("u"), "z")

fun main() {
    println(apply1(::greet))
    println(call0(::sum))
    println(call2(::sum))
    println(call1(::mixed))
    println(call3(::mixed))
    callUnit(::noisy)
    val b = Box("t")
    println(apply1(b::wrap))
    println(callBox(Box::wrap))
}
