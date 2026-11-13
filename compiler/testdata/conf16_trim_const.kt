fun constantIndent(): String {
    return """
        Hello,
          World
    """.trimIndent()
}

fun constantMargin(): String {
    return """
        |Hello,
        |  World
    """.trimMargin()
}

fun templated(x: Int): String = """
    a $x
    b
""".trimIndent()

fun main() {
    println(constantIndent() === constantIndent())
    println(constantMargin() === constantMargin())
    println(templated(1) === templated(1))
    println(constantIndent())
    println(constantMargin())
    println(templated(2))
    val e = """
        |x
    """.trimIndent()
    println(e.length)
}
