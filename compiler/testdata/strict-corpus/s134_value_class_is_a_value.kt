// ACCEPT: a value class around a plain value is a value: it can be returned, kept in lists and passed around without an owner
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@JvmInline
value class Meters(val v: Int) {
    operator fun plus(o: Meters) = Meters(v + o.v)
}

@JvmInline
value class Id(val raw: String)

fun total(xs: List<Meters>): Meters {
    var t = Meters(0)
    for (x in xs) t = t + x
    return t
}

fun ids(): List<Id> = listOf(Id("a"), Id("b"))

fun main() {
    val xs = listOf(Meters(1), Meters(2), Meters(3))
    println(total(xs).v)
    println(ids().map { it.raw })
    val m: Meters? = Meters(9)
    println(m?.v)
}
