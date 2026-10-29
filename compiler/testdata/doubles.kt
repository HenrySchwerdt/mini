fun half(x: Double): Double = x / 2

fun main() {
    println(1.5)
    println(0.5)
    println(1.0)
    println(100.0)
    println(0.1 + 0.2)
    println(1.0 / 3)
    println(2.0 * 3.5)
    println(half(7.0))
    println(10.0 - 0.25)
    println(7.5 % 2.0)
    println(-2.5)
    println(1.0E10)
    println(2.5E-5)
    println(123456.789)
    println(1234567.0)
    println(12345678.0)
    println(0.001)
    println(0.0001)
    val zero = 0.0
    println(1.0 / zero)
    println(-1.0 / zero)
    println(zero / zero)
    println(7.toDouble() / 2)
    println(3.99.toInt())
    println((-3.99).toInt())
    println(1.0E20.toInt())
    println(1.0E20.toLong())
    println(10L.toDouble())
    println(1.5 < 2.5)
    println(2.5 == 2.5)
    println(zero / zero == zero / zero)
    var acc = 0.0
    for (i in 1..10) acc += 0.1
    println(acc)
    println(acc > 0.99)
}
