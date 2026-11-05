import mini.coroutines.*

fun main() {
    runBlocking {
        val j = launch { println("in launch"); delay(5); println("launch end") }
        println("launched")
        j.join()
        println("joined")
        val a = async { 21 * 2 }
        println(a.await())
    }
    println("done")
}
