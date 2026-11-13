// REJECT M0101: `b = t` makes `b` an alias of `t`; `t` is released at the end of the loop body, so `b` dangles in the next round (write `b = t.move()`)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Big(val v: Int) {
    operator fun plus(o: Big): Big = Big(v + o.v)
}

fun fib(n: Int): Int {
    var a = Big(0)
    var b = Big(1)
    repeat(n) {
        val t = a + b
        a = b
        b = t
    }
    return a.v
}

fun main() {
    println(fib(10))
}
