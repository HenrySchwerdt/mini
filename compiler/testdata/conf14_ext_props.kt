val String.firstWord: String get() = substringBefore(' ')
val <T> List<T>.penultimate: T get() = this[size - 2]
var StringBuilder.lastChar: Char
    get() = this[length - 1]
    set(v) { this.setCharAt(length - 1, v) }
val Int.isEven get() = this % 2 == 0
val Int.squared: Int get() = this * this
fun Int.times2() = this * 2
fun <T : Comparable<T>> T.clampTo(lo: T, hi: T): T = if (this < lo) lo else if (this > hi) hi else this
infix fun Int.plusOne(x: Int) = this + x + 1
operator fun String.times(n: Int) = repeat(n)
fun List<Int>.average2() = sum().toDouble() / size
fun <K, V> Map<K, V>.firstKey(): K = keys.first()

class Box(val v: Int) {
    val Int.boxed get() = this + v
    fun addTo(x: Int) = x.boxed
}

fun Any?.orDefault(d: String) = this?.toString() ?: d

fun main() {
    println("hello world".firstWord)
    println(listOf(1, 2, 3).penultimate)
    val sb = StringBuilder("abc")
    sb.lastChar = 'z'
    println(sb.lastChar)
    println(sb)
    println(4.isEven to 3.isEven)
    println(7.squared)
    println(3.times2().times2())
    println(15.clampTo(0, 10))
    println("b".clampTo("a", "c"))
    println(1 plusOne 2)
    println("ab" * 3)
    println(listOf(1, 2, 4).average2())
    println(mapOf("k" to 1).firstKey())
    println(Box(10).addTo(5))
    println(null.orDefault("none") + 5.orDefault("none"))
}
