// raw strings, trimMargin, trimIndent, prependIndent, lines
fun main() {
    val a = """
        |first
        |  second
        |third
    """.trimMargin()
    println(a); println(a.lines().size)
    val b = """
        one
          two
        three
    """.trimIndent()
    println(b); println(b.length)
    println("""|x
        |y""".trimMargin())
    println("""#a
        #b""".trimMargin("#"))
    println("  a\n    b".trimIndent()); println("a\n\n  b".trimIndent().lines()); println("\n  x\n".trimIndent().length)
    println("x\ny".prependIndent("> ")); println("a\r\nb\rc\nd".lines()); println("a\n".lines()); println("".lines()); println("\n".lines().size)
    println("""a"b""c""".length); println("""
    |
    |x""".trimMargin().length)
    println("tab\there".replace("\t", "<T>")); println("""C:\dir\file""".length); println("""$""".length)
    val w = 5; println("""w=$w ${w + 1}"""); println("""line1
line2""".length)
    println("  padded  ".trim().length); println("xxhixx".trim('x')); println("abc".padStart(6, '.') + "|" + "abc".padEnd(6) + "|")
    println("a,b;c".split(',', ';')); println("a--b--c".split("--")); println("abc".split("")); println("a b  c".split(" ")); println("a1b2".split(Regex("\\d")))
    println("hello world".split(" ", limit = 1)); println("x".split(",")); println(",a,".split(","))
}
