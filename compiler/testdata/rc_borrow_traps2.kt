// RcOpt: the borrowed result of a lookup function stays valid only until a store overwrites something the lookup read (RcOpt.readSets). The substring-key
// pass turns `m[s.substring(a, b)]` into such a lookup (findNodeRange); the traps below remove, clear, rehash and update the map while the found value is
// still used. A wrong elision is a use after free (the leak suites run the programs under AddressSanitizer).
class Box(val n: Int)

fun countWords(text: String, m: HashMap<String, Int>) {
    var pos = 0
    while (pos < text.length) {
        val e = text.indexOf(' ', pos)
        val c = m[text.substring(pos, e)]
        if (c == null) m[text.substring(pos, e)] = 1 else m[text.substring(pos, e)] = c + 1
        pos = e + 1
    }
}

fun removeThenUse(text: String, m: HashMap<String, Box>): String {
    val sb = StringBuilder()
    var pos = 0
    while (pos < text.length) {
        val e = text.indexOf(' ', pos)
        val b = m[text.substring(pos, e)]
        m.remove(text.substring(pos, e))          // the entry is gone: b must keep the Box alive
        sb.append(b?.n).append(',')
        pos = e + 1
    }
    return sb.toString()
}

fun clearThenUse(text: String, m: HashMap<String, Box>): String {
    val sb = StringBuilder()
    var pos = 0
    while (pos < text.length) {
        val e = text.indexOf(' ', pos)
        val b = m[text.substring(pos, e)]
        m.clear()
        m["z"] = Box(99)
        sb.append(b?.n).append(';')
        pos = e + 1
    }
    return sb.toString()
}

fun replaceThenUse(text: String, m: HashMap<String, Box>): String {
    val sb = StringBuilder()
    var pos = 0
    while (pos < text.length) {
        val e = text.indexOf(' ', pos)
        val k = text.substring(pos, e)
        val b = m[k]
        m[k] = Box(1000 + pos)                    // the old value is released: b must keep it alive
        sb.append(b?.n).append('/')
        for (i in 0 until 40) m["fill" + i + pos] = Box(i)   // rehashes the table
        sb.append(b?.n).append(' ')
        pos = e + 1
    }
    return sb.toString()
}

fun main() {
    val m = HashMap<String, Int>()
    countWords("a bb a ccc bb a ", m)
    println(m["a"].toString() + m["bb"] + m["ccc"] + m.size)
    val b1 = HashMap<String, Box>(); b1["x"] = Box(1); b1["y"] = Box(2)
    println(removeThenUse("x y x ", b1))
    val b2 = HashMap<String, Box>(); b2["x"] = Box(5); b2["y"] = Box(6)
    println(clearThenUse("x y ", b2))
    val b3 = HashMap<String, Box>(); b3["x"] = Box(7)
    println(replaceThenUse("x x ", b3))
}
