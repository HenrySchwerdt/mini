package collx

/** A map from keys to lists of values with a stable insertion order. */
class Multimap<K, V> {
    private val data = LinkedHashMap<K, MutableList<V>>()

    val size: Int get() = data.values.sumOf { it.size }
    val keys: Set<K> get() = data.keys

    operator fun set(key: K, value: V) {
        data.getOrPut(key) { ArrayList() }.add(value)
    }

    operator fun get(key: K): List<V> = data[key] ?: emptyList()
    operator fun contains(key: K): Boolean = key in data

    fun remove(key: K, value: V): Boolean {
        val l = data[key] ?: return false
        val removed = l.remove(value)
        if (l.isEmpty()) data.remove(key)
        return removed
    }

    inline fun forEachEntry(action: (K, V) -> Unit) {
        for (k in keys) for (v in this[k]) action(k, v)
    }

    fun toMap(): Map<K, List<V>> = data.mapValues { it.value.toList() }
    override fun toString(): String = data.entries.joinToString(", ", "{", "}") { "${it.key}=${it.value}" }
}

fun <K, V> multimapOf(vararg pairs: Pair<K, V>): Multimap<K, V> {
    val m = Multimap<K, V>()
    for ((k, v) in pairs) m[k] = v
    return m
}

inline fun <T, K> Iterable<T>.toMultimap(key: (T) -> K): Multimap<K, T> {
    val m = Multimap<K, T>()
    for (x in this) m[key(x)] = x
    return m
}
