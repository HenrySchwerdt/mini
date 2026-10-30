@file:MemoryMode(Mode.Strict)

package strict.matmul

import mini.memory.*

// Mini extension `@` in Strict mode: `a @ b` is an ordinary call `a.matmul(b)`, so the owned result is tracked like the
// result of any other operator call (here a heap object that must be freed exactly once).
// Not valid Kotlin: marked with strict_matmul.nok2 so the K2-based comparisons skip it.

class Cell(val v: Int, val tag: String)

class Mat(val a: Int, val b: Int) {
    context(h: Heap)
    operator fun matmul(o: Mat): @Owned Mat = Mat(a * o.a, b + o.b)
}

context(h: Heap)
fun pair(a: Mat, b: Mat): @Owned Mat {
    val r = a @ b
    return r
}

fun main() {
    context(Heap()) {
        val x = Mat(2, 3)
        val y = Mat(4, 5)
        val z = x @ y
        println(z.a.toString() + "/" + z.b)
        z.free()

        val p = pair(x, y)
        println(p.a.toString() + "/" + p.b)
        p.free()

        // the intermediate `x @ y` of a chain is an owned temporary of its own
        val w = (x @ y)
        val u = w @ x
        println(u.a.toString() + "/" + u.b)
        u.free()
        w.free()

        x.free()
        y.free()
    }
}
