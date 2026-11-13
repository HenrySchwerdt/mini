// queue: 4 producers push 250000 numbers each through a bounded blocking queue (capacity 1024) to one consumer that sums them
// (ArrayBlockingQueue; Rust: std::sync::mpsc::sync_channel).
import java.util.concurrent.ArrayBlockingQueue

const val PRODUCERS = 4
const val ITEMS = 250000

fun main() {
    val q = ArrayBlockingQueue<Long>(1024)
    val ts = ArrayList<Thread>()
    for (p in 0 until PRODUCERS) {
        val th = Thread {
            for (i in 1..ITEMS) q.put(i.toLong())
        }
        ts.add(th)
        th.start()
    }
    var sum = 0L
    for (i in 0 until PRODUCERS * ITEMS) sum += q.take()
    for (t in ts) t.join()
    println(sum)
}
