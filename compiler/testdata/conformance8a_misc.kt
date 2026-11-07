// Conformance round 8 (a): a lambda as the value of `a[i] = ...`, a function-typed object passed where a fun
// interface is expected, `x?.f { x }` (x is not null inside the arguments), constants of the enclosing object in
// annotation arguments, `Nothing` parameters of special bridges.
annotation class Key(val value: String)

object Holder {
    const val TEXT = "OK"
    fun message(@Key(value = TEXT) text: String = TEXT): String = text
}

object Obj {
    var key = ""
    var value = ""
    operator fun set(k: String, v: ((String) -> Unit) -> Unit) {
        key += k
        v { value += it }
    }
}

fun interface KRunnable {
    fun invoke(): String
}

object Fn : () -> String {
    override fun invoke(): String = "fn"
}

fun run(k: KRunnable): String = k.invoke()

class P(val v: Int)

fun takeP(p: P): Int = p.v

inline fun <T> T.myLet(f: (T) -> Int): Int = f(this)

fun safe(x: P?): Int? = x?.myLet { takeP(x) }

private object EmptyList : List<Nothing> {
    override fun contains(element: Nothing): Boolean = false
    override fun containsAll(elements: Collection<Nothing>): Boolean = elements.isEmpty()
    override fun indexOf(element: Nothing): Int = -2
    override fun lastIndexOf(element: Nothing): Int = -2
    override val size: Int get() = 0
    override fun isEmpty(): Boolean = true
    override fun iterator(): Iterator<Nothing> = throw UnsupportedOperationException()
    override fun get(index: Int): Nothing = throw UnsupportedOperationException()
    override fun listIterator(): ListIterator<Nothing> = throw UnsupportedOperationException()
    override fun listIterator(index: Int): ListIterator<Nothing> = throw UnsupportedOperationException()
    override fun subList(fromIndex: Int, toIndex: Int): List<Nothing> = throw UnsupportedOperationException()
}

fun main() {
    println(Holder.message())
    Obj["O"] = label@{ it("K") }
    println(Obj.key + Obj.value)
    println(run(Fn))
    println(safe(P(7)))
    println(safe(null))
    val n = EmptyList as List<String>
    println(n.contains("") || n.indexOf("") != -1 || n.lastIndexOf("") != -1)
}
