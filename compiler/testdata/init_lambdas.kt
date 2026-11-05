// Lambdas in `init` blocks and property initializers capture locals, constructor parameters and `this`;
// labeled anonymous functions; member extension operators; FunctionN type names; enumValues<E>().
enum class Color { RED, GREEN, BLUE }

class Counter(n: Int) {
    val inc = { n + 1 }
    val boxed = run {
        var t = n
        { t++ }
    }
    var total: Int = 0
    val base: Int = 100

    init {
        var i = 10
        val bump = { i += 5; i }
        bump()
        bump()
        val g = { i + n + base }
        total = g()
    }
}

class Pairs {
    operator fun Int.component1() = this + 1
    operator fun Int.component2() = this * 2

    fun render(l: List<Int>): String {
        var s = ""
        for ((a, b) in l) s += "$a:$b;"
        return s
    }
}

fun labeled() = l@ fun(x: Int): Int {
    if (x > 2) return@l 100
    return x
}

fun apply2(f: Function2<Int, Int, Int>, g: Any): String = "" + f(3, 4) + (g is Function0<*>)

fun main() {
    val c = Counter(2)
    println(c.inc())
    println(c.boxed())
    println(c.boxed())
    println(c.total)
    println(Pairs().render(listOf(1, 2)))
    println(labeled()(1))
    println(labeled()(7))
    println(apply2({ a, b -> a * b }, { 1 }))
    println(enumValues<Color>().size)
    println(enumValueOf<Color>("GREEN"))
    println(enumEntries<Color>().map { it.name })
}
