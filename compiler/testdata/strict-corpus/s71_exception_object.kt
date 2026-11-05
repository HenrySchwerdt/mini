// ACCEPT: an exception carrying a value read from a borrowed Strict object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

class BadValue(val v: Int) : Exception("bad value " + v)

fun validate(b: Box) {
    if (b.v < 0) throw BadValue(b.v)
}

fun main() {
    val b = Box(-1)
    try {
        validate(b)
    } catch (e: BadValue) {
        println(e.message)
    } finally {
        b.free()
    }
}
