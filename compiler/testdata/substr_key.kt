// substr-key (opt/SubstrKey.kt): lookups whose key is a substring that is made only on insertion, escaping keys, a bad range (same exception, same place), operands that change, wide strings
fun count(text: String): HashMap<String, Int> {
    val m = HashMap<String, Int>()
    var pos = 0
    while (pos < text.length) {
        var e = text.indexOf(' ', pos)
        if (e < 0) e = text.length
        val w = text.substring(pos, e)
        val c = m[w]
        if (c == null) m[w] = 1 else m[w] = c + 1
        pos = e + 1
    }
    return m
}

fun escape(text: String): List<String> {
    val m = HashMap<String, Int>()
    val keep = ArrayList<String>()
    var i = 0
    while (i + 2 <= text.length) {
        val w = text.substring(i, i + 2)
        if (m.containsKey(w)) keep.add(w) else m[w] = i   // w escapes into the list: not rewritten
        i += 2
    }
    return keep
}

fun badRange(text: String, a: Int, b: Int): String {
    val m = HashMap<String, Int>()
    try {
        println("before")
        val w = text.substring(a, b)
        println("after substring")
        println(m[w])
        m[w] = 5
        println(m[w])
    } catch (e: IndexOutOfBoundsException) {
        println("caught " + e.message)
    }
    return "done ${m.size}"
}

fun reassign(text: String): Int {
    val m = LinkedHashMap<String, Int>()
    var a = 0
    var b = 3
    val w = text.substring(a, b)   // operands change after the declaration: the key must not
    a = 1
    b = 5
    m[w] = 10
    val w2 = text.substring(a, b)
    m[w2] = 20
    println(m)
    println(m[w] ?: -1)
    println(m[w2] ?: -1)
    return m.size
}

fun wide(): Int {
    val text = "café 中文 café 中文 中"
    val m = HashMap<String, Int>()
    var pos = 0
    while (pos < text.length) {
        var e = text.indexOf(' ', pos)
        if (e < 0) e = text.length
        val w = text.substring(pos, e)
        m[w] = (m[w] ?: 0) + 1
        pos = e + 1
    }
    println(m["café"])
    println(m["中文"])
    println(m["中"])
    println(m.size)
    return m.size
}

fun setOps(text: String): Int {
    val s = HashSet<String>()
    var dup = 0
    var i = 0
    while (i + 3 <= text.length) {
        val w = text.substring(i, i + 3)
        if (!s.contains(w)) s.add(w) else dup++
        i++
    }
    println("dups $dup size ${s.size}")
    return s.size
}

fun getOrDefaultLoop(text: String): Int {
    val m = HashMap<String, Int>()
    var i = 0
    var total = 0
    while (i < text.length) {
        val w = text.substring(i, minOf(i + 2, text.length))
        total += m.getOrDefault(w, 0)
        m[w] = total + 1
        m[w] = total + 2
        i += 1
    }
    println(total)
    return m.size
}

fun emptyKey(text: String): Int {
    val m = HashMap<String, String>()
    val w = text.substring(2, 2)
    m[w] = "empty"
    println(m[""])
    println(m.containsKey(w))
    println(m.get(text.substring(0, 0)))
    return m.size
}

fun main() {
    val t = "a b c a b a dd dd a"
    val m = count(t)
    println(m["a"]); println(m["b"]); println(m["dd"]); println(m["zz"]); println(m.size)
    println(escape("aabbaaccbbaa"))
    println(badRange("hello", 2, 9))
    println(badRange("hello", 3, 1))
    println(badRange("hello", -1, 2))
    println(badRange("hello", 1, 3))
    println(reassign("abcdefgh"))
    println(wide())
    println(setOps("abcabcabcxyzxyz"))
    println(getOrDefaultLoop("abababcab"))
    println(emptyKey("abc"))
    // a key made by substring but later also printed: kept as is
    val m2 = HashMap<String, Int>()
    val w = "xyzzy".substring(1, 4)
    m2[w] = 1
    println(w + m2[w])
}
