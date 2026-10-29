fun main() {
    val r = 1..5
    println(r)
    for (i in r) print(i)
    println()
    println((0 until 4).map { it * 2 })
    println((1..3).joinToString("-"))
    for (i in (1..4).reversed()) print(i)
    println()
    println(3 in r)
    println((10 downTo 1 step 3).toList())
    println(('a'..'e').toList())
    println((1L..3L).toList())
    for (i in 0 until 3) print(i)
    println()
}
