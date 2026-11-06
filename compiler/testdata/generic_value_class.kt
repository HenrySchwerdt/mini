// value classes (and functions) whose type parameter has a primitive bound: the value is an Int / Long at run time

@JvmInline
value class Augmented<T : Int>(val x: T) {
    override fun toString(): String = (x + 1).toString()
}

@JvmInline
value class AsAny<T : Any>(val a: T) {
    override fun toString(): String = "AsAny: $a"
}

data class Both(val a: Augmented<Int>, val b: AsAny<Int>)

@JvmInline
value class Wrapping<T : Int>(val value: T) {
    operator fun inc(): Wrapping<T> = plus(1)
    operator fun plus(num: Int): Wrapping<T> = Wrapping(((value + num) and 0xFFFF) as T)
}

@JvmInline
value class UI<T : Int>(private val value: T) : Comparable<UI<T>> {
    fun asInt(): Int = value
    fun toLong(): Long = value.toLong() and 0xffffffffL
    override fun compareTo(other: UI<T>): Int = flip().compareTo(other.flip())
    override fun toString(): String = toLong().toString()
    private fun flip(): Int = value xor Int.MIN_VALUE
}

@JvmInline
value class Direction<T : Int>(private val direction: T) {
    fun dx() = dx[direction]
    fun dy() = dy[direction]

    companion object {
        private val dx = intArrayOf(0, 1, 0, -1)
        private val dy = intArrayOf(-1, 0, 1, 0)
    }
}

@JvmInline
value class L<T : Long>(val x: T) {
    fun twice(): Long = x * 2
}

fun <T : Int> f(x: T): Int = x + 1
fun <T : Int> g(x: T): T = (x * 2) as T
fun <T : Double> half(x: T): Double = x / 2
class Box<T : Int>(val v: T) { fun plus1() = v + 1; fun idx(a: IntArray) = a[v] }

class A<T>(val x: T)
fun A<Int>.t() = x * 10
fun <T> call(fn: () -> T) = fn()
fun useW(w: A<Int?>): String = if (w.x == 42) "OK" else "fail"

fun main() {
    println(Augmented(0))
    println("${Augmented(1)} and ${Augmented(2)}")
    println(Both(Augmented(0), AsAny(42)))
    var w = Wrapping(65535)
    w++
    println(w.value)
    ++w
    println(w.value)
    val a = UI(-1)
    val b = UI(5)
    println("$a $b ${a > b} ${a.asInt()}")
    val dirs = arrayOf(Direction(0), Direction(1), Direction(2), Direction(3))
    for (d in dirs) print("${d.dx()},${d.dy()} ")
    println()
    println(L(21L).twice())
    println(f(3))
    println(g(5))
    println(half(5.0))
    println(Box(2).plus1())
    println(Box(2).idx(intArrayOf(7, 8, 9)))
    // callable references with the type arguments of the receiver class
    val r1 = A<Int>::t
    println(r1(A(4)))
    val r2 = A<String>::x
    println(r2.get(A("s")))
    val r3 = List<Int>::size
    println(r3(listOf(1, 2, 3)))
    // the expected type of the outer call reaches the lambda result
    println(useW(call { A(42) }))
}
