// HashMap put / get / containsKey / remove with Int and String keys.
fun main() {
    var total = 0L
    for (round in 0 until 5) {
        val m = HashMap<Int, Int>()
        for (i in 0 until 50000) m[i * 7] = i
        for (i in 0 until 50000) {
            val v = m[i * 7]
            if (v != null) total += v
        }
        for (i in 0 until 50000 step 2) m.remove(i * 7)
        total += m.size
    }
    val sm = HashMap<String, Int>()
    for (i in 0 until 20000) sm["key" + i] = i
    var hits = 0
    for (i in 0 until 40000) if (sm.containsKey("key" + i)) hits++
    println(total)
    println(hits)
}
