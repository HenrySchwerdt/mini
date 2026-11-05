import mini.coroutines.*

fun main() {
    runBlocking {
        val ch = Channel<Int>()
        launch {
            for (i in 1..3) {
                println("send " + i)
                ch.send(i)
            }
            ch.close()
        }
        for (x in ch) println("got " + x)
        val f = flow<Int> {
            for (i in 1..5) {
                emit(i)
                delay(1)
            }
        }
        println(f.map { it * 2 }.filter { it > 2 }.take(3).toList())
        val m = Mutex()
        var counter = 0
        val jobs = ArrayList<Job>()
        for (k in 0 until 5) jobs.add(launch { for (j in 0 until 10) { m.withLock { counter++ }; yield() } })
        for (j in jobs) j.join()
        println(counter)
        val r = withTimeoutOrNull(20) { delay(1000); 1 }
        println(r)
        val job = launch { try { delay(1000) } finally { println("cancelled cleanup") } }
        delay(5)
        job.cancel()
        job.join()
        println(job.isCancelled)
        try {
            coroutineScope { launch { throw IllegalStateException("boom") }; delay(100); println("not reached") }
        } catch (e: IllegalStateException) { println("caught " + e.message) }
    }
}
