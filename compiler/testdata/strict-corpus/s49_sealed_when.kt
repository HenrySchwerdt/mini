// ACCEPT: a sealed hierarchy built in an arena and matched with when on a borrowed value
@file:MemoryMode(Mode.Strict)

import mini.memory.*

sealed class Expr
class Num(val v: Int) : Expr()
class Add(val l: Expr, val r: Expr) : Expr()

fun eval(e: Expr): Int = when (e) {
    is Num -> e.v
    is Add -> eval(e.l) + eval(e.r)
}

fun main() {
    Arena().use { a ->
        context(a) { println(eval(Add(Num(1), Add(Num(2), Num(3))))) }
    }
}
