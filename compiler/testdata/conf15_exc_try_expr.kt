// try as an expression, multiple catch order, rethrow, custom exceptions, cause chain
class AppEx(msg: String, cause: Throwable? = null) : Exception(msg, cause)
class SubEx(msg: String) : Exception(msg)
fun parse(s: String): Int = try { s.toInt() } catch (e: NumberFormatException) { -1 }
fun tf(s: String): String = try { "ok:" + s.toInt() } catch (e: NumberFormatException) { "nfe" } finally { println("done " + s) }
fun order(x: Int): String = try { when (x) { 0 -> throw SubEx("sub"); 1 -> throw AppEx("app"); 2 -> throw IllegalStateException("ise"); else -> "fine" } }
    catch (e: SubEx) { "S" } catch (e: Exception) { "E:" + e.message } 
fun wrap(): Nothing = try { throw SubEx("low") } catch (e: SubEx) { throw AppEx("high", e) }
fun main() {
    println(parse("12") + parse("zz")); println(tf("5")); println(tf("q"))
    println(order(0) + order(1) + order(2) + order(3))
    try { wrap() } catch (e: AppEx) { println(e.message + " <- " + e.cause?.message); println(e.cause is SubEx) }
    val v = try { throw Error("err") } catch (e: Throwable) { e.message }; println(v)
    val n: Int = try { 1 / 0 } catch (e: ArithmeticException) { println(e.message); 0 }; println(n)
    try { try { throw SubEx("x") } catch (e: AppEx) { println("no") } } catch (e: SubEx) { println("outer " + e.message) }
    try { error("boom") } catch (e: IllegalStateException) { println(e.message) }
    try { TODO("later") } catch (e: NotImplementedError) { println(e.message) }
    try { val a = intArrayOf(1); a[2] } catch (e: ArrayIndexOutOfBoundsException) { println("aioobe") }
    try { listOf(1)[3] } catch (e: IndexOutOfBoundsException) { println("ioobe") }
    try { "abc".substring(5) } catch (e: StringIndexOutOfBoundsException) { println("sioobe") }
    try { mapOf(1 to 2).getValue(3) } catch (e: NoSuchElementException) { println("nsee") }
    try { emptyList<Int>().first() } catch (e: NoSuchElementException) { println(e.message) }
    try { Any() as String } catch (e: ClassCastException) { println("cce") }
    try { throw AppEx("t") } catch (e: Exception) { println(e::class.simpleName); println(e.toString()) }
    println(runCatching { 1 / 0 }.isFailure); println(runCatching { 5 }.getOrNull()); println(runCatching { error("z") }.exceptionOrNull()?.message)
    println(runCatching { "a".toInt() }.getOrElse { -9 }); println(Result.success(3).map { it + 1 }.getOrThrow())
}
