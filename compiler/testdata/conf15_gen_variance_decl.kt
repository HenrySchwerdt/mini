// declaration-site variance and the subtyping it allows
interface Producer<out T> { fun get(): T }
interface Consumer<in T> { fun put(t: T): String }
class StrProducer(val s: String) : Producer<String> { override fun get() = s }
class AnyConsumer : Consumer<Any> { override fun put(t: Any) = "got " + t }
fun use(p: Producer<CharSequence>): Int = p.get().length
fun feed(c: Consumer<String>): String = c.put("hello")
class ImmList<out T>(private val items: List<T>) { operator fun get(i: Int): T = items[i]; val size get() = items.size
    fun <R> map(f: (T) -> R): ImmList<R> = ImmList(items.map(f)) }
fun total(l: ImmList<Number>): Double { var s = 0.0; for (i in 0 until l.size) s += l[i].toDouble(); return s }
fun main() {
    println(use(StrProducer("four")))
    println(feed(AnyConsumer()))
    val ints = ImmList(listOf(1, 2, 3))
    println(total(ints))
    println(total(ints.map { it * 1.5 }))
    val f: (Any) -> String = { it.toString() }
    val g: (String) -> Any = f
    println(g("x"))
    val l: List<Any> = listOf("a", 1)
    println(l)
}
