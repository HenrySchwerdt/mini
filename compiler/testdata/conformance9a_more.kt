// Conformance round 9 (a), more: an ArrayList iterator fails after a structural modification (ConcurrentModificationException),
// AbstractMutableList.remove goes through the iterator, `Enum<*>` values and `E::name` / `E::ordinal`, `Obj::Nested` constructor
// references, a local `val` over a parameter of function type (`s()` calls the parameter), declared lambda parameter types bounding the
// type variables (`foldl(0) { e: Int, acc: Long -> ... }`), overloads that differ in type parameter bounds, `break` / `continue` in
// anonymous functions passed to inline functions, a member of two bounds with different result types, a function interface with a
// receiver.
import kotlin.coroutines.*

enum class E { I, J }

object Holder {
    class Item(val s: String)
}

inline fun <R> make(f: (String) -> R): R = f("made")

abstract class Counted : AbstractMutableList<Int>()

class Fake : Counted() {
    override fun iterator(): MutableIterator<Int> = mutableListOf(0, 5).iterator()
    override val size = 0
    override fun add(index: Int, element: Int) {}
    override fun get(index: Int) = index
    override fun removeAt(index: Int) = index
    override fun set(index: Int, element: Int) = index
}

fun callIt(s: () -> String): String {
    val s = 42
    return s.toString()
}

fun callParam(p: () -> String): String {
    val q = p
    val p = Any()
    return p().length.toString() + q()
}

tailrec fun <T, A> Iterator<T>.foldl(acc: A, f: (e: T, acc: A) -> A): A = if (!hasNext()) acc else foldl(f(next(), acc), f)

open class A
open class B
interface X {
    fun <T1, T2> foo(a: T1, b: T2): String
    fun <T1, T2 : B> foo(a: T1, b: T2): String
}

class Y : X {
    override fun <T1, T2> foo(a: T1, b: T2): String = "plain"
    override fun <T1, T2 : B> foo(a: T1, b: T2): String = "bounded"
}

inline fun twice(f: () -> Unit) {
    f()
    f()
}

interface P {
    fun foo(): Any?
}

interface Q {
    fun foo(): String
}

fun <T> both(x: T): Int where T : P, T : Q = x.foo().length

class PQ : P, Q {
    override fun foo() = "four"
}

class Susp : suspend () -> Unit {
    override suspend fun invoke() {}
}

fun interface WithReceiver {
    fun String.twice(): String
}

fun main() {
    val xs = mutableListOf("a", "b", "c")
    var seen = 0
    try {
        for (e in xs) {
            seen++
            xs.add(e)
        }
    } catch (e: ConcurrentModificationException) {
        println("CME after $seen")
    }
    val ys = mutableListOf(1, 2, 3, 4)
    val it = ys.iterator()
    while (it.hasNext()) if (it.next() % 2 == 0) it.remove()
    println(ys)
    println(Fake().remove(5))

    val en: Enum<*> = E.J
    println(en.name + en.ordinal)
    println(E::name.get(E.I) + E::ordinal.get(E.J))

    println(make(Holder::Item).s)
    println(callIt { "x" })
    println(callParam { "q" })

    val sum = (1..10).iterator().foldl(0) { e: Int, acc: Long -> acc + e }
    println(sum == 55L)

    val y = Y()
    println(y.foo(1, "s") + " " + y.foo(1, B()))

    var n = 0
    outer@ for (i in 1..3) {
        twice(fun() {
            n++
            if (i == 2) continue@outer
        })
    }
    println(n)

    println(both(PQ()))
    val nullClosure: () -> Unit? = { null }
    println(nullClosure() == null)
    println((Susp() is SuspendFunction0<*>).toString() + " " + (Susp() is SuspendFunction1<*, *>))
    val w = WithReceiver { this + this }
    with(w) { println("ab".twice()) }
}
