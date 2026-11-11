// Round 13: bound references to generic extensions take the receiver type, adapted references with a vararg followed by a
// defaulted parameter, a Unit-bounded lambda result.

inline fun <reified T> T.funWithExtensionNoArgs() = this

fun normalF(a: Int, vararg b: String, c: Int = 0) = a + b.size + c

fun varargConversion(ref: (Int, String, Int) -> Int) = ref
fun noConversion(ref: (Int, Array<String>, Int) -> Int) = ref

inline fun <T> remember(crossinline calculation: () -> T): T = calculation()

fun main() {
    val f2: (String) -> String = { it + "!" }
    val c3 = f2::funWithExtensionNoArgs
    println(c3()("x"))
    println(varargConversion(::normalF)(1, "a", 2))
    println(noConversion(::normalF)(1, arrayOf("a", "b"), 2))
    println(varargConversion(::normalF) == varargConversion(::normalF))
    println((varargConversion(::normalF) as Any) == (noConversion(::normalF) as Any))
    val u: Unit = remember { null }
    println(u)
}
