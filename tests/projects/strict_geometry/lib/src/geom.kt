package geom

import mini.memory.*

/** A heap allocated 2-D vector: the owner frees it. */
@AllocatedBy(Heap::class)
class Vec(val x: Int, val y: Int) {
    fun dot(o: Vec): Int = x * o.x + y * o.y
    fun len2(): Int = dot(this)
    fun manhattan(): Int = abs(x) + abs(y)
}

@AllocatedBy(Heap::class)
class Rect(val w: Int, val h: Int) {
    fun area(): Int = w * h
    fun perimeter(): Int = 2 * (w + h)
    fun contains(p: Vec): Boolean = p.x in 0..w && p.y in 0..h
}

fun abs(n: Int): Int = if (n < 0) -n else n

/** Returns a new vector the caller owns. */
fun sum(a: Vec, b: Vec): Vec = Vec(a.x + b.x, a.y + b.y)

fun scale(v: Vec, k: Int): Vec = Vec(v.x * k, v.y * k)

fun describe(v: Vec): String = "(" + v.x + ", " + v.y + ")"

fun boundingBox(a: Vec, b: Vec): Rect = Rect(abs(a.x - b.x), abs(a.y - b.y))

inline fun <T> measure(label: String, block: () -> T): T {
    val r = block()
    println("[" + label + "]")
    return r
}
