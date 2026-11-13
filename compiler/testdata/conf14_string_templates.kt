data class Item(val n: String, val q: Int)

fun main() {
    val name = "World"
    val n = 3
    println("Hello, $name!")
    println("Sum: ${n + 4}")
    println("Items: ${listOf(1, 2).map { it * n }}")
    println("Nested: ${"in${n}ner"}")
    println("Obj: ${Item("p", 2)}")
    println("Prop: ${Item("p", 2).n.uppercase()}")
    println("Esc: \$name \"q\" \\")
    println("Cond: ${if (n > 2) "big" else "small"}")
    println("Len: ${name.length}")
    val arr = intArrayOf(1, 2)
    println("Arr: ${arr[1]} ${arr.size}")
    println("Dollar: $")
    println("Unicode: ☃ é")
    println("$name$n")
    println("${name}s")
    println('a'.toString() + "bc")
    println("a" + 1 + 2)
    println((1 + 2).toString() + "a")
    println("x" + null)
    println("t".plus(1).plus('c'))
    val multi = """
        |line one
        |  line two $name
    """.trimMargin()
    println(multi)
    println("""a"b""")
    println("${'$'}literal")
    println(buildString { append("n=").append(n) })
    println("%s-%s".format("a", "b") + "!")
    println(n.toString().length + 3.14.toString().length)
    println("tab\there")
    println("Bool: ${n > 1 && n < 5}")
    println("Chars: ${name.toList().take(2)}")
}
