fun bucket(x: Int): String = when (x) {
    in 1..2 -> "low"
    in 3 until 5 -> "mid"
    !in 0..100 -> "out of range"
    else -> "high"
}

fun next(): Int {
    println("next")
    return 3
}

fun main() {
    val x = 3
    println(x in 1..5)
    println(x !in 1..2)
    println(bucket(1))
    println(bucket(4))
    println(bucket(50))
    println(bucket(-7))
    println(next() in 1..next())
    val c = 'm'
    println(c in 'a'..'z')
    println(5L in 1L..4L)
    val s = "hello"
    println(s.length)
    println("".length)
    println(("a" + "bc").length * 2)
    val y: Int
    y = 4
    println(y)
    val z: Int
    if (x > 2) z = 10 else z = 20
    println(z)
    println(1 + 2)
}
