// integer literal arithmetic with an expected Long is computed in Int and converted
const val hour: Long = 60 * 60 * 1000

fun take(x: Long) = x

fun main() {
    val a: Long = 2147483647 + 1
    println(a)
    val b: Long = -(1 shl 31)
    println(b)
    println(hour)
    println(take(100 * 100))
    val c: Long = 3000000000
    println(c + 1)
    val l1 = Long.MAX_VALUE
    println(l1 + 1)
}
