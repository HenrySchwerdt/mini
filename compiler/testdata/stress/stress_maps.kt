// Stress: maps of lists, linked maps and sets, built and dropped round after round.
fun main(args: Array<String>) {
    val scale = if (args.size > 0) args[0].toInt() else 1
    var check = 0L
    for (round in 0 until 6 * scale) {
        val m = HashMap<Int, ArrayList<String>>()
        for (i in 0 until 20000) {
            val l = m.getOrPut(i % 2000) { ArrayList<String>() }
            l.add("v" + i)
        }
        for (e in m.entries) check += e.value.size + e.key
        val lm = LinkedHashMap<String, Int>()
        for (i in 0 until 20000) lm["k" + (i % 5000)] = i
        for (e in lm.entries) check += e.value
        val hs = HashSet<Long>()
        for (i in 0 until 30000) hs.add((i * 7919L) % 10007)
        check += hs.size
        // removal churn on a linked map
        for (i in 0 until 5000) lm.remove("k" + i)
        check += lm.size
        val nested = HashMap<String, HashMap<Int, ArrayList<Int>>>()
        for (i in 0 until 3000) {
            val inner = nested.getOrPut("g" + (i % 50)) { HashMap<Int, ArrayList<Int>>() }
            inner.getOrPut(i % 20) { ArrayList<Int>() }.add(i)
        }
        check += nested.size
    }
    println(check)
}
