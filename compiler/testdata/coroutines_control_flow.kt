import kotlin.coroutines.*

// A tiny single-threaded driver: `suspendNow` parks the coroutine, `resumeAll` continues the parked ones in order.
val parked = ArrayList<Continuation<Int>>()
var counter = 0

suspend fun suspendNow(): Int = suspendCoroutine { c -> parked.add(c) }

fun resumeAll() {
    while (parked.isNotEmpty()) {
        val c = parked.removeAt(0)
        counter++
        c.resume(counter)
    }
}

fun launch(name: String, block: suspend () -> Any?) {
    block.startCoroutine(object : Continuation<Any?> {
        override val context: CoroutineContext get() = EmptyCoroutineContext
        override fun resumeWith(result: Result<Any?>) {
            println(name + " -> " + result.getOrNull() + (if (result.isFailure) " failed " + result.exceptionOrNull()!!.message else ""))
        }
    })
}

suspend fun nested(depth: Int): Int {
    if (depth == 0) return suspendNow()
    val v = nested(depth - 1)
    return v * 10 + depth
}

suspend fun <T> pick(a: T, b: T, first: Boolean): T {
    val x = suspendNow()
    return if (first && x > 0) a else b
}

suspend fun loops(): String {
    val sb = StringBuilder()
    var i = 0
    outer@ while (i < 4) {
        i++
        var j = 0
        while (true) {
            j++
            val v = suspendNow()
            if (j > 2) continue@outer
            if (i == 3) break@outer
            sb.append(i).append(':').append(j).append('=').append(v).append(' ')
        }
    }
    do {
        i--
        sb.append("d").append(i)
        suspendNow()
    } while (i > 2)
    return sb.toString()
}

suspend fun tryFinally(mode: Int): String {
    val log = StringBuilder()
    for (k in 0 until 3) {
        try {
            log.append("t").append(k)
            val v = suspendNow()
            if (mode == 1 && k == 1) throw IllegalArgumentException("bad " + v)
            if (mode == 2 && k == 1) return log.append(" ret").toString()
            if (mode == 3 && k == 1) break
        } catch (e: IllegalArgumentException) {
            log.append(" caught ").append(e.message)
        } finally {
            log.append(" f").append(k).append(';')
            if (k == 2) suspendNow()
        }
    }
    return log.toString()
}

suspend fun expression(): Int {
    val list = listOf(1, 2, 3)
    var total = 0
    for (x in list) total += x * suspendNow() + (if (x == 2) suspendNow() else 0)
    val s = "v" + suspendNow() + "w" + suspendNow()
    return total + s.length
}

interface Source {
    suspend fun next(): Int
    suspend fun twice(): Int = next() + next()
}

class Counter(private val base: Int) : Source {
    override suspend fun next(): Int = base + suspendNow()
}

suspend operator fun Counter.plus(other: Counter): Int = next() + other.next()

fun main() {
    launch("nested") { nested(3) }
    launch("pick") { pick("a", "b", true) }
    launch("loops") { loops() }
    resumeAll()
    println("-- second round")
    resumeAll()
    resumeAll()
    resumeAll()
    for (mode in 0..3) {
        launch("try" + mode) { tryFinally(mode) }
        resumeAll()
        resumeAll()
        resumeAll()
        resumeAll()
    }
    launch("expr") { expression() }
    for (i in 0 until 8) resumeAll()
    launch("iface") { Counter(100).twice() }
    resumeAll()
    resumeAll()
    launch("operator") { Counter(1) + Counter(2) }
    resumeAll()
    resumeAll()
    println("leftover " + parked.size)
}
