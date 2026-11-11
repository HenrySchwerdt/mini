// exact-is (opt/ExactIs.kt): `is` on a class without subclasses compares the type word; classes with subclasses, interfaces and null keep their meaning
sealed class J
object JNull : J()
class JNum(val v: Long) : J()
class JStr(val s: String) : J()
class JArr(val items: ArrayList<J>) : J()

open class Shape
class Circle : Shape()
class Square : Shape()
class BigSquare : Shape()   // a leaf next to a leaf
open class Base
open class Mid : Base()
class Leaf : Mid()

interface Named { fun name(): String }
class Dog : Named { override fun name() = "dog" }
class Cat : Named { override fun name() = "cat" }

fun kind(j: J?): String = when (j) {
    null -> "null"
    is JNull -> "JNull"
    is JNum -> "JNum ${j.v}"
    is JStr -> "JStr ${j.s}"
    is JArr -> "JArr ${j.items.size}"
}

fun depth(j: J): Int = when (j) {
    is JArr -> {
        var d = 0
        for (i in 0 until j.items.size) { val c = depth(j.items[i]); if (c > d) d = c }
        d + 1
    }
    else -> 0
}

fun describe(b: Base?): String {
    if (b is Leaf) return "leaf"
    if (b is Mid) return "mid"
    if (b is Base) return "base"
    return "none"
}

fun main() {
    val items = ArrayList<J>()
    items.add(JNum(3)); items.add(JStr("x")); items.add(JNull)
    val inner = ArrayList<J>()
    inner.add(JArr(items))
    val all: List<J?> = listOf(JNull, JNum(7), JStr("hello"), JArr(items), JArr(inner), null)
    for (j in all) println(kind(j))
    println(depth(JArr(inner)))
    val shapes: List<Shape> = listOf(Circle(), Square(), BigSquare(), Shape())
    for (s in shapes) println("${s is Circle} ${s is Square} ${s is BigSquare} ${s is Shape}")
    println(describe(Leaf()) + " " + describe(Mid()) + " " + describe(Base()) + " " + describe(null))
    val named: List<Any> = listOf(Dog(), Cat(), "str", 5)
    for (n in named) println("${n is Dog} ${n is Cat} ${n is Named} ${n is String}")
    val anyNull: Any? = null
    println(anyNull is JNull)
}
