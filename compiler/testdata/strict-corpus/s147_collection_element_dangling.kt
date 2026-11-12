// REJECT M0101+M0110: moving an owner into a collection is refused (M0110) and the element read back is a loan that `ys.clear()` ends (M0101, strict-5b): an element read back (`ys[0]`) is a reference no owner in Strict code keeps alive, so `ys.clear()` would leave `first` dangling (heap-use-after-free under ASan when this restriction is lifted without loan tracking of the collection)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val ys = mutableListOf<Box>()
    val b = Box(5)
    ys.add(b)
    val first = ys[0]
    ys.clear()
    println(first.v)
    b.free()
}
