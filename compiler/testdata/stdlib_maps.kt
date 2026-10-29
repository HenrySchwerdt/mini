// HashMap / LinkedHashMap / sets / groupBy from the Mini stdlib. The stdlib's hash nodes keep
// before/after links (a reference cycle), so this program is excluded from the leak check.

fun main() {
    val names = listOf("bob", "alice", "carol", "dave")
    println(names.associateWith { it.length })
    println(names.groupBy { it.length })
    val map = HashMap<String, Int>()
    map["one"] = 1
    map["two"] = 2
    map["three"] = 3
    println(map["two"])
    println(map.containsKey("four"))
    println(map.size)
    var total = 0
    for ((k, v) in map) total += v * k.length
    println(total)
    val lm = linkedMapOf("z" to 1, "a" to 2)
    println(lm)
    println(mapOf(1 to "x", 2 to "y"))

    val set = setOf(3, 1, 3, 2, 1)
    println(set.size)
    println(set.contains(2))
    println(mutableSetOf("a", "b").also { it.add("a"); it.add("c") })

    println(set.toList().size)
}
