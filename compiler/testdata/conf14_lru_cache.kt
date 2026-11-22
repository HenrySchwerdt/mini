class Lru<K, V>(private val cap: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > cap
}

fun main() {
    val c = Lru<String, Int>(3)
    c["a"] = 1
    c["b"] = 2
    c["c"] = 3
    println(c)
    println(c["a"])
    c["d"] = 4
    println(c)
    println(c.keys)
    c["c"] = 30
    println(c.entries.map { "${it.key}${it.value}" })
    println(c.get("zzz"))
    c.getOrPut("e") { 5 }
    println(c)
    println(c.containsKey("a"))
    c.remove("d")
    println(c.size)
    val plain = LinkedHashMap<String, Int>()
    plain["x"] = 1; plain["y"] = 2
    plain["x"]
    println(plain)
    val ao = LinkedHashMap<Int, Int>(8, 0.75f, true)
    for (i in 1..4) ao[i] = i
    ao[2]
    ao[1] = 10
    println(ao)
    println(ao.keys.first())
}
