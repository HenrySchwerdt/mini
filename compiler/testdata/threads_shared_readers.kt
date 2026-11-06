import kotlin.concurrent.thread
import mini.concurrent.*

// Read-only use of shared data from several threads must not write shared state: the HashMap lookup cache used to be updated by every
// get() (a double release of the cached node, "reference count underflow"), and a release recorded its cycle candidate after dropping
// its reference, when another thread could already have freed the object.
class Item(val id: Int, val name: String) { var next: Item? = null }
class Shared(val items: List<Item>, val table: Map<String, Item>, val tag: String)

fun main() {
    val items = ArrayList<Item>()
    for (i in 0 until 200) items.add(Item(i, "item" + i))
    val table = HashMap<String, Item>()
    for (it in items) table[it.name] = it
    val shared = Shared(items, table, "tag" + 1)
    val total = AtomicLong(0)
    val ref = AtomicReference<Item?>(null)
    val threads = ArrayList<java.lang.Thread>()
    for (t in 0 until 8) {
        threads.add(
            thread {
                var sum = 0L
                for (r in 0 until 3000) {
                    val local = ArrayList<Item>()
                    for (k in 0 until 20) local.add(shared.items[(r + k + t) % 200])
                    val m = shared.table["item" + ((r * 7 + t) % 200)]!!
                    val s = shared.tag + m.name + local.size
                    sum += s.length + local[0].id
                    val f = { m.id + local.size }
                    sum += f()
                    ref.set(m)
                    val got = ref.get()
                    if (got != null) sum += got.id * 0
                    if (shared.table.containsKey("item" + (r % 200))) sum += 1
                }
                total.addAndGet(sum)
            },
        )
    }
    for (t in threads) t.join()
    println(total.get() > 0)
    println(ref.get() != null)
    println(shared.table.size)
}
