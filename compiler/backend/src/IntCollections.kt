package mini.backend

/*
 * Small dense collections for the analyses (RcOpt, ...). The ids they work with (locals of one function, positions, scopes,
 * labels) are small and dense, so arrays beat HashMap<Int, ...> / ArrayList<Int> (which box every Int, hash and
 * allocate; in the natively compiled backend each boxed Int is a heap object).
 */

/** A growable list of Ints without boxing. */
internal class IntList {
    private var a = IntArray(8)
    var size = 0
        private set

    operator fun get(i: Int): Int = a[i]

    operator fun plusAssign(v: Int) {
        if (size == a.size) {
            val n = IntArray(size * 2)
            for (k in 0 until size) n[k] = a[k]
            a = n
        }
        a[size] = v
        size++
    }

    val indices: IntRange get() = 0 until size

    fun isNotEmpty(): Boolean = size > 0
}

/** A set of small non-negative Ints (local ids) as a bit set. */
class BitIds {
    private var w = LongArray(2)

    fun add(id: Int) {
        val k = id ushr 6
        if (k >= w.size) {
            var n = w.size
            while (n <= k) n *= 2
            val b = LongArray(n)
            for (i in w.indices) b[i] = w[i]
            w = b
        }
        w[k] = w[k] or (1L shl (id and 63))
    }

    operator fun contains(id: Int): Boolean {
        val k = id ushr 6
        return k < w.size && (w[k] ushr (id and 63)) and 1L != 0L
    }

    fun isEmpty(): Boolean {
        for (x in w) if (x != 0L) return false
        return true
    }
}

/** An array indexed by an id that starts anywhere (local ids from 0, label ids of a function in a narrow range). */
internal class IdMap<T : Any> {
    private var base = 0
    private var arr = arrayOfNulls<Any>(0)

    @Suppress("UNCHECKED_CAST")
    operator fun get(id: Int): T? {
        val k = id - base
        if (k < 0 || k >= arr.size) return null
        return arr[k] as T?
    }

    operator fun set(id: Int, v: T) {
        if (arr.size == 0) {
            base = id
            arr = arrayOfNulls<Any>(16)
        }
        if (id < base) {
            val shift = base - id
            val n = arrayOfNulls<Any>(arr.size + shift)
            for (i in arr.indices) n[i + shift] = arr[i]
            arr = n
            base = id
        }
        val k = id - base
        if (k >= arr.size) {
            var n = arr.size
            while (n <= k) n *= 2
            val b = arrayOfNulls<Any>(n)
            for (i in arr.indices) b[i] = arr[i]
            arr = b
        }
        arr[k] = v
    }
}
