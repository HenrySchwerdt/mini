/*
 * java.util.Collections, Arrays and Objects: the static helpers that programs written for the JVM call by their Java names. Every
 * function forwards to the Kotlin standard library (results are those of the JVM, including the order Collections.shuffle(list, Random)
 * produces for a seeded java.util.Random).
 */
package java.util

private class UnmodifiableList<T>(private val l: List<T>) : List<T> by l {
    override fun toString(): String = l.toString()
    override fun equals(other: Any?): Boolean = l == other
    override fun hashCode(): Int = l.hashCode()
}

private class UnmodifiableSet<T>(private val s: Set<T>) : Set<T> by s {
    override fun toString(): String = s.toString()
    override fun equals(other: Any?): Boolean = s == other
    override fun hashCode(): Int = s.hashCode()
}

private class UnmodifiableMap<K, V>(private val m: Map<K, V>) : Map<K, V> by m {
    override fun toString(): String = m.toString()
    override fun equals(other: Any?): Boolean = m == other
    override fun hashCode(): Int = m.hashCode()
}

public object Collections {
    public fun <T : Comparable<T>> sort(list: MutableList<T>) {
        list.sort()
    }

    public fun <T> sort(list: MutableList<T>, c: Comparator<in T>?) {
        if (c == null) {
            @Suppress("UNCHECKED_CAST")
            (list as MutableList<Comparable<Any?>>).sort()
        } else {
            list.sortWith(c)
        }
    }

    public fun reverse(list: MutableList<*>) {
        @Suppress("UNCHECKED_CAST")
        (list as MutableList<Any?>).reverse()
    }

    public fun swap(list: MutableList<*>, i: Int, j: Int) {
        @Suppress("UNCHECKED_CAST")
        val l = list as MutableList<Any?>
        val t = l[i]
        l[i] = l[j]
        l[j] = t
    }

    public fun shuffle(list: MutableList<*>) {
        shuffle(list, Random())
    }

    public fun shuffle(list: MutableList<*>, rnd: Random) {
        var i = list.size
        while (i > 1) {
            swap(list, i - 1, rnd.nextInt(i))
            i--
        }
    }

    public fun <T : Comparable<T>> max(c: Collection<T>): T = c.max()
    public fun <T : Comparable<T>> min(c: Collection<T>): T = c.min()
    public fun <T> max(c: Collection<T>, cmp: Comparator<in T>): T = c.maxWith(cmp)
    public fun <T> min(c: Collection<T>, cmp: Comparator<in T>): T = c.minWith(cmp)

    public fun <T> emptyList(): List<T> = kotlin.collections.emptyList()
    public fun <T> emptySet(): Set<T> = kotlin.collections.emptySet()
    public fun <K, V> emptyMap(): Map<K, V> = kotlin.collections.emptyMap()
    public fun <T> singletonList(o: T): List<T> = listOf(o)
    public fun <T> singleton(o: T): Set<T> = setOf(o)
    public fun <K, V> singletonMap(k: K, v: V): Map<K, V> = mapOf(k to v)
    public fun <T> nCopies(n: Int, o: T): List<T> {
        if (n < 0) throw IllegalArgumentException("List length = $n")
        return UnmodifiableList(List(n) { o })
    }

    public fun frequency(c: Collection<*>, o: Any?): Int {
        var n = 0
        for (x in c) if (x == o) n++
        return n
    }

    public fun <T> unmodifiableList(l: List<T>): List<T> = UnmodifiableList(l)
    public fun <T> unmodifiableCollection(c: Collection<T>): Collection<T> = c
    public fun <T> unmodifiableSet(s: Set<T>): Set<T> = UnmodifiableSet(s)
    public fun <K, V> unmodifiableMap(m: Map<K, V>): Map<K, V> = UnmodifiableMap(m)
    public fun <T> synchronizedList(l: MutableList<T>): MutableList<T> = l
    public fun <K, V> synchronizedMap(m: MutableMap<K, V>): MutableMap<K, V> = m

    public fun <T> addAll(c: MutableCollection<in T>, vararg elements: T): Boolean {
        var changed = false
        for (e in elements) if (c.add(e)) changed = true
        return changed
    }

    public fun <T> reverseOrder(): Comparator<T> {
        @Suppress("UNCHECKED_CAST")
        return Comparator { a, b -> (b as Comparable<Any?>).compareTo(a) }
    }

    public fun <T> reverseOrder(c: Comparator<T>?): Comparator<T> {
        if (c == null) return reverseOrder()
        return Comparator { a, b -> c.compare(b, a) }
    }

    public fun <T : Comparable<T>> binarySearch(list: List<T>, key: T): Int = list.binarySearch(key)
    public fun <T> binarySearch(list: List<T>, key: T, c: Comparator<in T>): Int = list.binarySearch(key, c)

    public fun <T> fill(list: MutableList<in T>, o: T) {
        var i = 0
        while (i < list.size) {
            list[i] = o
            i++
        }
    }

    public fun disjoint(a: Collection<*>, b: Collection<*>): Boolean {
        for (x in a) if (b.contains(x)) return false
        return true
    }

    public fun rotate(list: MutableList<*>, distance: Int) {
        val n = list.size
        if (n == 0) return
        @Suppress("UNCHECKED_CAST")
        val l = list as MutableList<Any?>
        val copy = ArrayList<Any?>(l)
        var d = distance % n
        if (d < 0) d += n
        var i = 0
        while (i < n) {
            l[(i + d) % n] = copy[i]
            i++
        }
    }
}

public object Arrays {
    public fun toString(a: IntArray?): String = if (a == null) "null" else a.contentToString()
    public fun toString(a: LongArray?): String = if (a == null) "null" else a.contentToString()
    public fun toString(a: DoubleArray?): String = if (a == null) "null" else a.contentToString()
    public fun toString(a: FloatArray?): String = if (a == null) "null" else a.contentToString()
    public fun toString(a: CharArray?): String = if (a == null) "null" else a.contentToString()
    public fun toString(a: ByteArray?): String = if (a == null) "null" else a.contentToString()
    public fun toString(a: ShortArray?): String = if (a == null) "null" else a.contentToString()
    public fun toString(a: BooleanArray?): String = if (a == null) "null" else a.contentToString()
    public fun toString(a: Array<*>?): String = if (a == null) "null" else a.contentToString()
    public fun deepToString(a: Array<*>?): String = if (a == null) "null" else a.contentDeepToString()

    public fun <T> asList(vararg a: T): MutableList<T> {
        val l = ArrayList<T>(a.size)
        for (x in a) l.add(x)
        return l
    }

    public fun equals(a: IntArray?, b: IntArray?): Boolean = a.contentEquals(b)
    public fun equals(a: LongArray?, b: LongArray?): Boolean = a.contentEquals(b)
    public fun equals(a: DoubleArray?, b: DoubleArray?): Boolean = a.contentEquals(b)
    public fun equals(a: CharArray?, b: CharArray?): Boolean = a.contentEquals(b)
    public fun equals(a: ByteArray?, b: ByteArray?): Boolean = a.contentEquals(b)
    public fun equals(a: BooleanArray?, b: BooleanArray?): Boolean = a.contentEquals(b)
    public fun equals(a: Array<*>?, b: Array<*>?): Boolean = a.contentEquals(b)
    public fun deepEquals(a: Array<*>?, b: Array<*>?): Boolean = (a == null && b == null) || (a != null && b != null && a.contentDeepToString() == b.contentDeepToString())

    public fun hashCode(a: IntArray?): Int = if (a == null) 0 else a.contentHashCode()
    public fun hashCode(a: LongArray?): Int = if (a == null) 0 else a.contentHashCode()
    public fun hashCode(a: CharArray?): Int = if (a == null) 0 else a.contentHashCode()
    public fun hashCode(a: Array<*>?): Int = if (a == null) 0 else a.contentHashCode()

    public fun sort(a: IntArray) { a.sort() }
    public fun sort(a: LongArray) { a.sort() }
    public fun sort(a: DoubleArray) { a.sort() }
    public fun sort(a: FloatArray) { a.sort() }
    public fun sort(a: CharArray) { a.sort() }
    public fun sort(a: ByteArray) { a.sort() }
    public fun sort(a: ShortArray) { a.sort() }
    public fun sort(a: IntArray, from: Int, to: Int) { a.sort(from, to) }
    public fun sort(a: LongArray, from: Int, to: Int) { a.sort(from, to) }
    public fun sort(a: DoubleArray, from: Int, to: Int) { a.sort(from, to) }
    public fun <T : Comparable<T>> sort(a: Array<T>) { a.sort() }
    public fun <T> sort(a: Array<T>, c: Comparator<in T>) { a.sortWith(c) }

    public fun fill(a: IntArray, v: Int) { a.fill(v) }
    public fun fill(a: LongArray, v: Long) { a.fill(v) }
    public fun fill(a: DoubleArray, v: Double) { a.fill(v) }
    public fun fill(a: CharArray, v: Char) { a.fill(v) }
    public fun fill(a: ByteArray, v: Byte) { a.fill(v) }
    public fun fill(a: BooleanArray, v: Boolean) { a.fill(v) }
    public fun <T> fill(a: Array<T>, v: T) { a.fill(v) }
    public fun fill(a: IntArray, from: Int, to: Int, v: Int) { a.fill(v, from, to) }

    public fun copyOf(a: IntArray, n: Int): IntArray = a.copyOf(n)
    public fun copyOf(a: LongArray, n: Int): LongArray = a.copyOf(n)
    public fun copyOf(a: DoubleArray, n: Int): DoubleArray = a.copyOf(n)
    public fun copyOf(a: CharArray, n: Int): CharArray = a.copyOf(n)
    public fun copyOf(a: ByteArray, n: Int): ByteArray = a.copyOf(n)
    public fun copyOf(a: BooleanArray, n: Int): BooleanArray = a.copyOf(n)
    public fun copyOfRange(a: IntArray, from: Int, to: Int): IntArray = a.copyOfRange(from, to)
    public fun copyOfRange(a: LongArray, from: Int, to: Int): LongArray = a.copyOfRange(from, to)
    public fun copyOfRange(a: DoubleArray, from: Int, to: Int): DoubleArray = a.copyOfRange(from, to)
    public fun copyOfRange(a: CharArray, from: Int, to: Int): CharArray = a.copyOfRange(from, to)
    public fun copyOfRange(a: ByteArray, from: Int, to: Int): ByteArray = a.copyOfRange(from, to)

    public fun binarySearch(a: IntArray, key: Int): Int = a.binarySearch(key)
    public fun binarySearch(a: LongArray, key: Long): Int = a.binarySearch(key)
    public fun binarySearch(a: DoubleArray, key: Double): Int = a.binarySearch(key)
    public fun binarySearch(a: CharArray, key: Char): Int = a.binarySearch(key)
}

public object Objects {
    public fun equals(a: Any?, b: Any?): Boolean = a == b
    public fun deepEquals(a: Any?, b: Any?): Boolean = a == b
    public fun hashCode(o: Any?): Int = o?.hashCode() ?: 0
    public fun hash(vararg values: Any?): Int {
        var r = 1
        for (v in values) r = 31 * r + (v?.hashCode() ?: 0)
        return r
    }

    public fun toString(o: Any?): String = o.toString()
    public fun toString(o: Any?, nullDefault: String): String = if (o == null) nullDefault else o.toString()
    public fun isNull(o: Any?): Boolean = o == null
    public fun nonNull(o: Any?): Boolean = o != null
    public fun <T> requireNonNull(o: T?): T {
        if (o == null) throw NullPointerException()
        return o
    }

    public fun <T> requireNonNull(o: T?, message: String): T {
        if (o == null) throw NullPointerException(message)
        return o
    }

    public fun <T> requireNonNullElse(o: T?, d: T): T = o ?: d
    public fun <T> compare(a: T, b: T, c: Comparator<in T>): Int = if (a === b) 0 else c.compare(a, b)
}
