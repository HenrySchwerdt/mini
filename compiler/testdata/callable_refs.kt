// Property references and callable references: KProperty0/1 with get/set/name, function values, constructor and bound references.
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1

val top = 5
var counter = 1

class P(val name: String, var age: Int) {
    fun greet(other: String) = "$name greets $other"
    fun selfRef(): KProperty0<String> = this::name
    fun ageRef(): KMutableProperty0<Int> = this::age
    val label: String get() = "P:" + name
}

class Box(var value: Int) {
    fun bump() { val r = ::value; r.set(r.get() + 1) }
}

val String.shout: String get() = this.uppercase() + "!"

fun dbl(x: Int) = x * 2
fun apply2(f: (Int) -> Int, v: Int) = f(f(v))
fun <T, V> readAll(items: List<T>, p: KProperty1<T, V>): List<V> = items.map { p.get(it) }
fun show(p: KProperty0<*>) = p.name + "=" + p.get()

fun main() {
    val r = ::top
    println(r.get())
    println(r.name)
    println(r())
    val c: KMutableProperty0<Int> = ::counter
    c.set(9)
    println(counter)
    c.set(c.get() + 1)
    println(c.get())

    val p = P("ann", 30)
    val q = p::name
    println(q.get())
    println(q.name)
    val a = p::age
    a.set(31)
    println(p.age)
    println(a.get())
    println(p.selfRef().get())
    p.ageRef().set(40)
    println(p.age)

    val k: KProperty1<P, String> = P::name
    println(k.get(p))
    println(k.name)
    println(k(p))
    val m: KMutableProperty1<P, Int> = P::age
    m.set(p, 50)
    println(m.get(p))
    println(p.age)

    val people = listOf(P("bob", 20), P("al", 40), P("cy", 30))
    println(people.map(P::name))
    println(people.map(P::age).sum())
    println(people.sortedBy(P::age).map { it.name })
    println(people.groupBy(P::age).keys)
    println(readAll(people, P::name))
    println(readAll(people, P::label))
    println(show(::top))
    println(show(p::name))

    println(listOf("hi", "yo").map(String::shout))
    println(listOf("a", "bcd").map(String::length))
    println(listOf(1, 2).map(Int::toString))
    println(listOf(1, 2, 3).fold(0, Int::plus))

    println(listOf(1, 2).map(::dbl))
    println(apply2(::dbl, 3))
    val ctor = ::P
    println(ctor("zed", 1).name)
    println(listOf("u", "v").map { P(it, 0) }.map(P::name))
    val names = listOf("n1", "n2").map(String::length).map(::Box).map(Box::value)
    println(names.size)
    val bound = p::greet
    println(bound("you"))
    val unbound = P::greet
    println(unbound(p, "me"))
    val bx = Box(1)
    bx.bump()
    bx.bump()
    println(bx.value)
    val g = "abc"::plus
    println(g("d"))
    fun local(x: Int) = x + 100
    println(listOf(1, 2).map(::local))
}
