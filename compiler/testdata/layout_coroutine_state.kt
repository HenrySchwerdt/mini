// Coroutine state classes (the compiler-generated continuation / state machine classes, which carry the live locals of every
// suspension point: Boolean, Int, Long, Double, references) get the reordered layout too. The locals must survive suspension.
import kotlin.coroutines.*

var saved: Continuation<Int>? = null

suspend fun wait(): Int = suspendCoroutine { c -> saved = c }

class Acc(var total: Long, var flag: Boolean, var n: Int)

suspend fun mix(seed: Int): String {
    val flag = seed % 2 == 0
    var big = seed.toLong() shl 33
    var count = 0
    val name = "m$seed"
    var ratio = seed / 2.0
    val acc = Acc(0L, flag, 0)
    for (i in 0 until 3) {
        val got = wait()
        count += got
        big += got.toLong() * 1_000_000_007L
        ratio += got
        acc.total += big
        acc.n++
        acc.flag = !acc.flag
        println("step $i got=$got flag=$flag count=$count big=$big ratio=$ratio name=$name acc=${acc.total}/${acc.n}/${acc.flag}")
    }
    return "$name $flag $count $big $ratio ${acc.total} ${acc.n} ${acc.flag}"
}

fun run(tag: String, block: suspend () -> String) {
    block.startCoroutine(object : Continuation<String> {
        override val context: CoroutineContext get() = EmptyCoroutineContext
        override fun resumeWith(result: Result<String>) {
            println("$tag done ${result.getOrNull()} ${result.exceptionOrNull()}")
        }
    })
}

fun main() {
    run("a") { mix(4) }
    saved!!.resume(1)
    saved!!.resume(20)
    saved!!.resume(300)
    run("b") { mix(5) + "|" + mix(6) }
    for (v in listOf(7, 8, 9, 10, 11, 12)) saved!!.resume(v)
    val seq = sequence {
        val flag = true
        var big = 1L shl 40
        for (i in 1..3) {
            big += i
            yield("$flag $big $i")
        }
    }
    println(seq.toList())
}
