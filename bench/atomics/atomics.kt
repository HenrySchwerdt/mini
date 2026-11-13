// atomics: 4 threads add to one shared counter 5000000 times each (AtomicLong.incrementAndGet) and run a compare-and-set loop
// 1000000 times each (Rust: AtomicU64 fetch_add / compare_exchange).
import java.util.concurrent.atomic.AtomicLong

const val THREADS = 4
const val ADDS = 5000000
const val CAS = 1000000

fun main() {
    val a = AtomicLong()
    val b = AtomicLong()
    val ts = ArrayList<Thread>()
    for (t in 0 until THREADS) {
        val th = Thread {
            for (i in 0 until ADDS) a.incrementAndGet()
            for (i in 0 until CAS) {
                while (true) {
                    val cur = b.get()
                    if (b.compareAndSet(cur, cur + 3)) break
                }
            }
        }
        ts.add(th)
        th.start()
    }
    for (t in ts) t.join()
    println(a.get())
    println(b.get())
}
