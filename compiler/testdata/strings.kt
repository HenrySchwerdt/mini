fun greet(name: String): String = "Hello, $name!"

fun main() {
    val i = 42
    val l = 1234567890123L
    val d = 2.5
    val b = true
    val c = 'x'
    val s = "str"
    println("int=$i long=$l double=$d bool=$b char=$c string=$s")
    println("sum: ${i + 1} and ${l * 2L}")
    println("a" + "b")
    println(s + i)
    println(s + c + b)
    println("x" + 1.5)
    println("")
    println("tab\there")
    println("quote \" backslash \\ dollar \$ done")
    println("unicode: é 中 €")
    println("question? ??? done")
    println(greet("World"))
    println(greet("a" + "b"))
    println("same" == "sa" + "me")
    println(s == "other")
    println(s != "other")
    print("no newline")
    print(' ')
    print(7)
    print(2.0)
    print(true)
    print(9L)
    println()
    println('z')
    println(true)
    println(123456789012L)
    println("${i}${i}")
    var t = ""
    for (k in 1..3) t = t + k + ","
    println(t)
    println(i.toString() + "!")
    println("é")
}
