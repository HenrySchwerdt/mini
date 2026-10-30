// RC elision (RcOpt): element reads of object arrays borrow when the array keeps the slot alive for the whole use.
// The "hazard" functions overwrite the slot (or drop the array) while the element read earlier is still needed: a wrong
// elision is a use-after-free that the ASan run of the leak check reports.

class Cell(var v: Int)

class Box(var cells: Array<Cell?>)

fun sumAll(a: Array<Cell?>): Int {
    var s = 0
    for (i in 0 until a.size) {
        val c = a[i]!!
        s += c.v * 2
    }
    return s
}

// no write to the array between the read and the last use: borrowed
fun readOnly(a: Array<Cell?>): Int {
    var s = 0
    for (i in 0 until a.size) {
        val c = a[i]!!
        val d = a[a.size - 1 - i]!!
        s += c.v * d.v
    }
    return s
}

// the slot is overwritten while the element is still in use
fun overwriteSlot(a: Array<Cell?>): Int {
    val c = a[0]!!
    a[0] = Cell(100)
    return c.v + a[0]!!.v
}

fun clobber(a: Array<Cell?>) {
    for (i in 0 until a.size) a[i] = Cell(i + 1000)
}

// a callee overwrites the slots
fun overwriteInCallee(a: Array<Cell?>): Int {
    val c = a[1]!!
    clobber(a)
    return c.v + a[1]!!.v
}

fun clobberBox(b: Box) {
    b.cells = arrayOfNulls<Cell>(3)
}

// the array itself is dropped (the field is overwritten) while the element is in use
fun dropArray(b: Box): Int {
    val c = b.cells[2]!!
    clobberBox(b)
    return c.v
}

// reassigned array variable
fun reassign(): Int {
    var a = arrayOfNulls<Cell>(2)
    a[0] = Cell(5)
    a[1] = Cell(6)
    val c = a[0]!!
    a = arrayOfNulls<Cell>(2)
    return c.v + (a[0]?.v ?: 0)
}

// the element is returned and used after the array is gone
fun keep(a: Array<Cell?>): Cell {
    val c = a[2]!!
    return c
}

fun outOfRange(a: Array<Cell?>): Int {
    return try {
        val c = a[a.size]!!
        c.v
    } catch (e: ArrayIndexOutOfBoundsException) {
        -1
    }
}

fun main() {
    val a = arrayOfNulls<Cell>(4)
    for (i in 0 until 4) a[i] = Cell(i + 1)
    println(sumAll(a))
    println(readOnly(a))
    println(overwriteSlot(a))
    println(overwriteInCallee(a))
    val b = Box(arrayOfNulls<Cell>(3))
    for (i in 0 until 3) b.cells[i] = Cell(i * 10)
    println(dropArray(b))
    println(reassign())
    val kept = keep(arrayOf<Cell?>(Cell(1), Cell(2), Cell(3)))
    println(kept.v)
    println(outOfRange(a))
}
