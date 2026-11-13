// ArrayList.add is expanded at every call site (@AlwaysInline): growth in the middle of a loop, aliases of elements and of the list that
// live across the add (the storage array is replaced while a borrowed element is still in use), the element that is the list's own
// element, a failing iteration that adds, and the reference counts of what was added. ArrayList.get requests the cache line of the
// element a few slots ahead (a hint that must not change anything): lists of every length around that distance, null elements,
// boxed numbers, strings, reads at the last slot and failing reads.
class Item(val id: Int) {
    var hits = 0
}

class Holder(val items: ArrayList<Item>)

fun grow(): Int {
    val list = ArrayList<Item>()
    var sum = 0
    for (i in 0 until 1000) {
        val first: Item = if (list.size > 0) list[0] else Item(-1)
        list.add(Item(i))
        // `first` was read before the add: it must still be alive and the same object after the storage was replaced
        first.hits++
        sum += first.id
    }
    return sum + list[0].hits + list.size
}

fun aliases(): String {
    val list = ArrayList<Item>()
    val a = Item(1)
    list.add(a)
    list.add(a)
    list.add(list[0])
    list.add(list[list.size - 1])
    a.hits = 7
    var s = ""
    for (x in list) s += "" + x.id + ":" + x.hits + " "
    return s + (list[0] === list[3])
}

fun holder(): Int {
    val h = Holder(ArrayList())
    val one = Item(5)
    var n = 0
    for (i in 0 until 100) {
        h.items.add(one)
        n += h.items.size
    }
    return n + h.items[99].id
}

fun concurrent(): String {
    val list = ArrayList<Int>()
    list.add(1)
    list.add(2)
    return try {
        for (x in list) {
            if (x == 1) list.add(3)
        }
        "no exception"
    } catch (e: ConcurrentModificationException) {
        "CME " + list.size
    }
}

fun queue(): Long {
    // a breadth-first walk over an implicit binary tree: the queue grows while its head is read
    val queue = ArrayList<Int>()
    queue.add(1)
    var head = 0
    var total = 0L
    while (head < queue.size) {
        val n = queue[head]
        head++
        total += n
        if (n < 20000) {
            queue.add(2 * n)
            queue.add(2 * n + 1)
        }
    }
    return total + queue.size
}

fun reads(): String {
    val sb = StringBuilder()
    for (n in intArrayOf(0, 1, 7, 8, 9, 16, 17, 40)) {
        val strings = ArrayList<String?>()
        val numbers = ArrayList<Int>()
        val items = ArrayList<Item?>()
        for (i in 0 until n) {
            strings.add(if (i % 3 == 0) null else "s" + i)
            numbers.add(i * i)
            items.add(if (i % 4 == 1) null else Item(i))
        }
        var a = 0
        var b = 0
        var c = 0
        for (i in 0 until n) {
            val s = strings[i]
            if (s != null) a += s.length
            b += numbers[i]
            val it = items[i]
            if (it != null) c += it.id
        }
        for (i in n - 1 downTo 0) b += numbers[i]
        sb.append(n).append(':').append(a).append('/').append(b).append('/').append(c).append(' ')
    }
    val one = ArrayList<Int>()
    one.add(5)
    try {
        println(one[1])
    } catch (e: IndexOutOfBoundsException) {
        sb.append("oob")
    }
    return sb.toString()
}

fun main() {
    println(grow())
    println(aliases())
    println(holder())
    println(concurrent())
    println(queue())
    println(reads())
}
