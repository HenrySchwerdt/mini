// the HashMap remembers the node of the last String key it found: removal, clear, iteration removal and equal-but-distinct keys must stay exact
fun main() {
    val m = HashMap<String, Int>()
    val k = "a" + "b"
    m[k] = 1
    println(m[k])
    m[k] = (m[k] ?: 0) + 1
    println(m[k])
    println(m.remove(k))
    println(m[k])
    m[k] = 7
    println(m["ab"])
    m.clear()
    println(m[k])
    m[k] = 9
    println(m.size)
    val k2 = StringBuilder().append('a').append('b').toString()
    println(m[k2])
    m[k2] = 10
    println(m.size)
    println(m[k])
    val it = m.keys.iterator()
    it.next()
    it.remove()
    println(m[k])
    m[k] = 11
    println(m)
    val lm = LinkedHashMap<String, Int>()
    for (w in listOf("x", "y", "x", "z", "y", "x")) {
        val c = lm[w]
        if (c == null) lm[w] = 1 else lm[w] = c + 1
    }
    println(lm)
    println(lm.remove("x"))
    lm["x"] = 5
    println(lm)
}
