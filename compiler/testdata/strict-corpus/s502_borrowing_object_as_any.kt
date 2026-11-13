// ACCEPT: a method returns an anonymous object as `Any`: it borrows the receiver, printing it inside the lifetime is fine
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun get(): Int }

class Counter(var n: Int) {
    fun reader(): Reader = object : Reader { override fun get(): Int = n }
}

class Tag(val id: Int) {
    fun label(): Any = object { override fun toString(): String = "tag " + id }
}

fun main() {
    val t = Tag(7)
    val l = t.label()
    println(l)
    println(t.label())
}
