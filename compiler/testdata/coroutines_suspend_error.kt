import kotlin.coroutines.*

suspend fun work(): Int = 1

fun main() {
    println(work())
    val ok: suspend () -> Int = { work() }
    val bad: () -> Int = { work() }
    println(ok.hashCode() + bad())
}
