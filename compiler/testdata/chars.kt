fun next(c: Char): Char = c + 1

fun main() {
    val a = 'a'
    println(a + 1)
    println(next('y'))
    println('z' - 'a')
    println('c' - 1)
    println(a.code)
    println((97 + 2).toChar())
    println(a < 'b')
    println(a == 'a')
    println('b' > 'c')
    var c = 'a'
    c++
    println(c)
    println("char: " + c)
    println("${'q'}${'r'}")
    for (x in 'a'..'c') print(x)
    println()
}
