// mutex: 4 threads take one lock 1000000 times each and increment a shared counter (ReentrantLock; Rust: std::sync::Mutex).
import java.util.concurrent.locks.ReentrantLock

const val THREADS = 4
const val ROUNDS = 1000000

class Counter {
    val lock = ReentrantLock()
    var value = 0L
}

fun main() {
    val c = Counter()
    val ts = ArrayList<Thread>()
    for (t in 0 until THREADS) {
        val th = Thread {
            for (i in 0 until ROUNDS) {
                c.lock.lock()
                c.value += 1
                c.lock.unlock()
            }
        }
        ts.add(th)
        th.start()
    }
    for (t in ts) t.join()
    println(c.value)
}
