// ACCEPT: the same rotation with moves (`a = b.move()`, `b = t.move()`): every object has one owner at any time and is released when the last owner is overwritten or ends
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Big(val v: Int) {
    operator fun plus(o: Big): Big = Big(v + o.v)
}

fun fib(n: Int): Int {
    var a = Big(0)
    var b = Big(1)
    for (i in 0 until n) {
        val t = a + b
        a = b.move()
        b = t.move()
    }
    return a.v
}

fun main() {
    println(fib(10))
}
