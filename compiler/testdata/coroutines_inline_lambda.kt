import kotlin.coroutines.*

// Suspension points inside lambdas of inline functions (forEach, repeat, let, apply, user-defined ones): the inline function
// is expanded before the state machine is built. `tick` parks the coroutine; `resumeAll` continues the parked ones.
val parked = ArrayList<Continuation<Int>>()
var counter = 0

suspend fun tick(): Int = suspendCoroutine { c -> parked.add(c) }

fun resumeAll() {
    while (parked.isNotEmpty()) {
        val c = parked.removeAt(0)
        counter++
        c.resume(counter)
    }
    counter = 0
}

fun launch(name: String, block: suspend () -> Any?) {
    block.startCoroutine(object : Continuation<Any?> {
        override val context: CoroutineContext get() = EmptyCoroutineContext
        override fun resumeWith(result: Result<Any?>) {
            println(name + " -> " + result.getOrNull() + (if (result.isFailure) " failed " + result.exceptionOrNull()!!.message else ""))
        }
    })
    resumeAll()
}

class Lock {
    var held = 0
}

inline fun <T> Lock.withLock(action: () -> T): T {
    held++
    try {
        return action()
    } finally {
        held--
    }
}

inline fun <T> firstMatching(xs: List<T>, pred: (T) -> Boolean): T {
    for (x in xs) {
        if (pred(x)) return x
    }
    throw NoSuchElementException("none")
}

inline fun <reified T> eachOf(xs: List<Any?>, f: (T) -> Unit) {
    for (x in xs) if (x is T) f(x)
}

inline fun <A, B> pairUp(a: () -> A, b: () -> B): String = a().toString() + "&" + b().toString()

suspend fun forEachDemo(): String {
    val sb = StringBuilder()
    listOf("a", "b", "c").forEach { sb.append(it).append(tick()).append(' ') }
    return sb.toString().trim()
}

suspend fun repeatDemo(): Int {
    var sum = 0
    repeat(3) { i -> sum += tick() * 10 + i }
    return sum
}

suspend fun scopeDemo(): String {
    val a = 5.let { it + tick() }
    val b = StringBuilder().apply { append("x").append(tick()) }.toString()
    val c = run { tick() * 100 }
    return "$a $b $c"
}

suspend fun lockDemo(lock: Lock): String {
    val r = lock.withLock { tick() + lock.held * 100 }
    return "$r held=${lock.held}"
}

suspend fun findFirst(xs: List<Int>, target: Int): String {
    xs.forEach { x ->
        val t = tick()
        if (x == target) return "found $x at $t"
    }
    return "none"
}

suspend fun nestedDemo(): String {
    val sb = StringBuilder()
    listOf(1, 2).forEach { i ->
        listOf("x", "y").forEach { s -> sb.append(i).append(s).append(tick()).append(' ') }
    }
    return sb.toString().trim()
}

suspend fun tryDemo(): String {
    val log = StringBuilder()
    repeat(3) { i ->
        try {
            val v = tick()
            if (i == 1) throw IllegalStateException("boom" + v)
            log.append("ok").append(v)
        } catch (e: IllegalStateException) {
            log.append(" caught ").append(e.message)
        } finally {
            log.append(" f").append(i).append(';')
        }
    }
    return log.toString()
}

suspend fun labelDemo(): String {
    val r = listOf(1, 2, 3).map { x ->
        if (x == 2) return@map -1
        x * 10 + tick()
    }
    return r.toString()
}

suspend fun searchDemo(): String = "found " + firstMatching(listOf(3, 4, 5)) { tick() >= 2 }

suspend fun reifiedDemo(): String {
    val sb = StringBuilder()
    eachOf<String>(listOf(1, "a", 2, "b")) { sb.append(it).append(tick()) }
    return sb.toString()
}

suspend fun withDemo(): String = with(StringBuilder("w")) {
    append(tick())
    append(tick())
    toString()
}

suspend fun argsDemo(): String = pairUp({ tick() }, { tick() + 10 })

suspend fun inLoopDemo(): String {
    val sb = StringBuilder()
    var i = 0
    while (i < 2) {
        i++
        listOf(i, i * 10).forEach { sb.append(it + tick()).append(',') }
        if (i == 1) continue
        sb.append('|')
    }
    return sb.toString()
}

suspend fun nonLocalNoSuspend(x: Int): String {
    listOf(1, 2, 3).forEach { if (it == x) return "hit " + it }
    val t = tick()
    return "miss " + t
}

suspend inline fun twiceS(block: suspend () -> Unit) {
    block()
    block()
}

suspend inline fun twiceP(block: () -> Unit) {
    block()
    block()
}

suspend fun suspendInlineDemo(): String {
    val sb = StringBuilder()
    twiceS { sb.append(tick()) }
    twiceP { sb.append('-').append(tick()) }
    return sb.toString()
}

suspend fun anonDemo(): String {
    val sb = StringBuilder()
    listOf(1, 2).forEach(fun(x: Int) {
        sb.append(x + tick())
    })
    return sb.toString()
}

fun main() {
    launch("forEach") { forEachDemo() }
    launch("repeat") { repeatDemo() }
    launch("scope") { scopeDemo() }
    launch("lock") { lockDemo(Lock()) }
    launch("nonlocal") { findFirst(listOf(10, 20, 30), 20) + "/" + findFirst(listOf(1, 2), 9) }
    launch("nested") { nestedDemo() }
    launch("try") { tryDemo() }
    launch("label") { labelDemo() }
    launch("search") { searchDemo() }
    launch("reified") { reifiedDemo() }
    launch("with") { withDemo() }
    launch("args") { argsDemo() }
    launch("loop") { inLoopDemo() }
    launch("nonlocal2") { nonLocalNoSuspend(2) + "/" + nonLocalNoSuspend(9) }
    launch("suspend inline") { suspendInlineDemo() }
    launch("anon") { anonDemo() }
    launch("lambda") {
        val sb = StringBuilder()
        listOf(1, 2).forEach { sb.append(tick()) }
        sb.toString()
    }
}
