class Service {
    lateinit var name: String
    var ready = false
    fun init(n: String) { name = n; ready = true }
    fun describe(): String {
        check(ready) { "not ready" }
        return "service $name"
    }
    fun isNameSet() = this::name.isInitialized
}

fun div(a: Int, b: Int): Int {
    require(b != 0) { "b must not be zero" }
    return a / b
}

fun mustBePositive(n: Int): Int {
    if (n < 0) error("negative: $n")
    return n
}

fun main() {
    val s = Service()
    println(s.isNameSet())
    try { s.describe() } catch (e: IllegalStateException) { println("ISE: ${e.message}") }
    try { println(s.name) } catch (e: UninitializedPropertyAccessException) { println("UPAE") }
    s.init("db")
    println(s.describe())
    println(s.isNameSet())
    try { div(1, 0) } catch (e: IllegalArgumentException) { println("IAE: ${e.message}") }
    println(div(9, 3))
    try { mustBePositive(-4) } catch (e: IllegalStateException) { println(e.message) }
    println(runCatching { div(1, 0) }.isFailure)
    println(runCatching { div(8, 2) }.getOrNull())
    println(runCatching { mustBePositive(-1) }.exceptionOrNull()?.message)
    val x: String? = null
    try { x!!.length } catch (e: NullPointerException) { println("NPE") }
    try { requireNotNull(x) { "x was null" } } catch (e: IllegalArgumentException) { println(e.message) }
    try { checkNotNull(x) } catch (e: IllegalStateException) { println("check") }
    try { listOf(1)[3] } catch (e: IndexOutOfBoundsException) { println("IOOBE") }
    try { "abc".toInt() } catch (e: NumberFormatException) { println("NFE") }
    try { Any() as String } catch (e: ClassCastException) { println("CCE") }
    try { TODO("later") } catch (e: NotImplementedError) { println(e.message) }
    println(1 / 1)
    try { println(1 / (s.name.length - 2)) } catch (e: ArithmeticException) { println("AE ${e.message}") }
}
