import mini.coroutines.*
import mini.concurrent.*

fun main() {
    val counter = AtomicInt(0)
    val total = runBlocking {
        val jobs = ArrayList<Deferred<Int>>()
        for (i in 1..8) {
            jobs.add(async(Dispatchers.Default) {
                var s = 0
                for (k in 1..1000) {
                    s += k
                    counter.incrementAndGet()
                }
                delay(2)
                s
            })
        }
        var sum = 0
        for (j in jobs) sum += j.await()
        val w = withContext(Dispatchers.IO) { delay(3); "io" }
        println(w)
        sum
    }
    println(total)
    println(counter.get())
}
