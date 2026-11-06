import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

// tailrec suspend: the self call in tail position runs in a loop (1_000_000 iterations would overflow the stack otherwise)

var parked: Continuation<Unit>? = null

suspend fun park(): Unit = suspendCoroutine { c -> parked = c }

tailrec suspend fun sum(x: Long, acc: Long): Long {
    if (x == 0L) return acc
    return sum(x - 1, acc + x)
}

// swapped arguments: all new values are computed before any parameter changes
tailrec suspend fun swapCount(a: Int, b: Int, n: Int): String {
    if (n == 0) return "$a,$b"
    return swapCount(b, a, n - 1)
}

// expression body, when, a suspension point before the call
tailrec suspend fun countDown(n: Int, log: StringBuilder): Int = when {
    n == 0 -> 0
    n % 400_000 == 0 -> { log.append(n).append(' '); park(); countDown(n - 1, log) }
    else -> countDown(n - 1, log)
}

// Unit function, the call is the last statement
tailrec suspend fun StringBuilder.fromTo(from: Int, to: Int) {
    if (from > to) return
    if (from % 2 == 0) append(from)
    fromTo(from + 1, to)
}

// the extension receiver changes
tailrec suspend infix fun Int.test(x: Int): Int {
    if (this > 1) return (this - 1) test x
    return this
}

// a member: the dispatch receiver stays
class Walker(val step: Int) {
    tailrec suspend fun walk(i: Int, acc: Int): Int = if (i <= 0) acc else walk(i - step, acc + 1)
}

// a self call that is not in tail position stays a call
tailrec suspend fun mixed(n: Int): Int {
    if (n <= 0) return 0
    if (n % 2 == 0) return 1 + mixed(n - 1)
    return mixed(n - 1)
}

// a lambda created in the body sees the value of its iteration
tailrec suspend fun gather(i: Int, out: MutableList<() -> Int>): Int {
    if (i == 3) return out.size
    out.add { i * 10 }
    return gather(i + 1, out)
}

// generic function with a lambda parameter
tailrec suspend fun <T, A> Iterator<T>.foldl(acc: A, f: (T, A) -> A): A =
    if (!hasNext()) acc else foldl(f(next(), acc), f)

// local tailrec suspend function
suspend fun local(): Int {
    tailrec suspend fun loop(i: Int, s: Int): Int = if (i == 0) s else loop(i - 1, s + 2)
    return loop(500_000, 0)
}

fun launch(block: suspend () -> Unit) {
    block.startCoroutine(object : Continuation<Unit> {
        override val context: CoroutineContext get() = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) { result.getOrThrow() }
    })
}

fun main() {
    launch { println(sum(1_000_000, 0)) }
    launch { println(swapCount(1, 2, 7)) }
    val log = StringBuilder()
    var r = -1
    launch { r = countDown(1_000_000, log) }
    while (parked != null) { val c = parked!!; parked = null; c.resume(Unit) }
    println("$r [$log]")
    launch { val sb = StringBuilder(); sb.fromTo(1, 10); println(sb) }
    launch { println(1_000_000.test(5)) }
    launch { println(Walker(2).walk(1_000_000, 0)) }
    launch { println(mixed(10)) }
    launch {
        val l = ArrayList<() -> Int>()
        gather(0, l)
        println(l.map { it() })
    }
    launch { println((1..1_000_000).iterator().foldl(0L) { e, acc -> acc + e }) }
    launch { println(local()) }
}
