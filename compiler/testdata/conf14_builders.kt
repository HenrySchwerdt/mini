fun main() {
    val l = buildList {
        add(1)
        addAll(listOf(2, 3))
        if (size > 2) add(size)
    }
    println(l)
    val m = buildMap {
        put("a", 1)
        put("b", 2)
        this["c"] = size
    }
    println(m)
    val s = buildString {
        append("x")
        append(1)
        appendLine("y")
        append('z')
        repeat(2) { append(it) }
    }
    println(s)
    val set = buildSet { add(3); add(1); add(3) }
    println(set)
    println(buildList<String> { add("q") }.size)
    val sb = StringBuilder()
    sb.append("abc").append(1).append(2.5).append(true).append('c')
    sb.insert(0, "[").append("]")
    println(sb)
    println(sb.length)
    sb.setLength(3)
    println(sb.reverse())
    println(listOf(1, 2, 3).joinToString(prefix = "<", postfix = ">", separator = "|") { "n$it" })
    println(listOf(1, 2, 3, 4, 5).joinToString(limit = 3, truncated = "..."))
}
