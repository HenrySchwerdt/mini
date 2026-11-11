// ACCEPT: a closure returned from a method that captures `this` carries a loan of the receiver: usable while the owner is alive
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Counter(var n: Int) {
    fun incr(): () -> Int = { n++ }
    fun peek(): () -> Int {
        val f = { n }
        return f
    }
    fun adder(k: Int): (Int) -> Int = { it + n + k }
}

fun main() {
    val c = Counter(0)
    val f = c.incr()
    println(f())
    println(f())
    println(c.peek()())
    println(c.adder(10)(5))
    c.free()
}
