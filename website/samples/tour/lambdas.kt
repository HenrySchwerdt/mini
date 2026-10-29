// Lambdas, closures that capture mutable state, function references, inline functions
// with non-local return, and scope functions.

fun makeCounter(): () -> Int {
    var n = 0                      // captured and mutated: lives in a shared heap cell
    return { n += 1; n }
}

fun compose(f: (Int) -> Int, g: (Int) -> Int): (Int) -> Int = { x -> g(f(x)) }

fun double(x: Int) = x * 2

inline fun twice(action: () -> Unit) {
    action()
    action()
}

fun firstBig(xs: List<Int>): String {
    xs.forEach { if (it > 10) return "big: $it" }   // non-local return from an inline lambda
    return "none"
}

fun main() {
    val c1 = makeCounter()
    val c2 = makeCounter()
    println("${c1()} ${c1()} ${c1()} ${c2()}")

    val addThree = { x: Int -> x + 3 }
    println(compose(addThree, ::double)(4))

    var log = ""
    twice { log += "x" }
    println(log)

    println(firstBig(listOf(1, 20, 30)))
    println(firstBig(listOf(1, 2)))

    println(5.let { it + 1 })
    println("abc".also { println("also $it") }.length)
    println(StringBuilder().apply { append("a"); append(1) }.toString())
    println(with("hello") { uppercase() })
}
