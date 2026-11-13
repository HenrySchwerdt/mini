interface Stack<T> {
    fun push(x: T)
    fun pop(): T?
    val size: Int
}

class ArrayStack<T> : Stack<T> {
    private val items = ArrayList<T>()
    override fun push(x: T) { items.add(x) }
    override fun pop(): T? = if (items.isEmpty()) null else items.removeAt(items.size - 1)
    override val size get() = items.size
}

class Pair2<out A, out B>(val a: A, val b: B) {
    fun <C> mapA(f: (A) -> C) = Pair2(f(a), b)
    override fun toString() = "($a, $b)"
}

class Sink<in T> { val got = ArrayList<Any?>(); fun accept(x: T) { got.add(x) } }

fun <T> swap(p: Pair<T, T>) = p.second to p.first
fun <T> List<T>.second(): T = this[1]
fun <A, B, C> compose(f: (A) -> B, g: (B) -> C): (A) -> C = { g(f(it)) }
inline fun <reified T> isType(x: Any?) = x is T
inline fun <reified T> filterType(xs: List<Any?>): List<T> = xs.filterIsInstance<T>()
fun <T> copyTo(dst: MutableList<in T>, src: List<T>) { dst.addAll(src) }
fun sumNums(xs: List<out Number>) = xs.sumOf { it.toInt() }

fun main() {
    val st: Stack<String> = ArrayStack()
    st.push("a"); st.push("b")
    println(st.pop() to st.size)
    println(Pair2(1, "x").mapA { it + 1 })
    val sink = Sink<Number>()
    val sk2: Sink<Int> = sink
    sk2.accept(3)
    println(sink.got)
    println(swap(1 to 2))
    println(listOf("a", "b", "c").second())
    println(compose<Int, Int, String>({ it + 1 }, { "v$it" })(4))
    println(isType<String>("s") to isType<Int>("s"))
    println(filterType<Int>(listOf(1, "a", 2, null, 3.0)))
    val dst = mutableListOf<Any>()
    copyTo(dst, listOf(1, 2))
    println(dst)
    println(sumNums(listOf(1, 2.9, 3L)))
    val e: List<Nothing> = emptyList()
    println(e.size)
    println(listOf(1, 2) as List<Any>)
    fun <T : Comparable<T>> top(l: List<T>) = l.sorted().last()
    println(top(listOf("b", "c", "a")))
}
