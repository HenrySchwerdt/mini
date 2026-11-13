import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

suspend fun twice(x: Int): Int = suspendCoroutine { c -> c.resume(x * 2) }

tailrec suspend fun sumTo(n: Int, acc: Long = 0): Long = if (n == 0) acc else sumTo(n - 1, acc + twice(n))

suspend fun inLambdas(): String {
    val sb = StringBuilder()
    listOf(1, 2, 3).forEach { sb.append(twice(it)).append(',') }
    run { sb.append(twice(10)) }
    val l = listOf(4, 5).map { twice(it) }
    sb.append(l)
    with(sb) { append(twice(7)) }
    repeat(2) { sb.append(twice(it)) }
    return sb.toString()
}

suspend fun ctx(): Boolean = coroutineContext[ContinuationInterceptor] == null

fun <T> runIt(b: suspend () -> T): T {
    var r: Result<T>? = null
    b.startCoroutine(Continuation(EmptyCoroutineContext) { r = it })
    return r!!.getOrThrow()
}

fun main() {
    println(runIt { inLambdas() })
    println(runIt { sumTo(100000) })
    println(runIt { ctx() })
}
