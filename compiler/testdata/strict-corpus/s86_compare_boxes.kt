// ACCEPT: maxOf-like generic helper over Comparable Strict values (borrowed)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class V(val n: Int) : Comparable<V> {
    override fun compareTo(other: V): Int = n - other.n
}

fun <T : Comparable<T>> bigger(a: T, b: T): Boolean = a > b

fun main() {
    val a = V(1)
    val b = V(2)
    println(bigger(a, b))
    println(bigger(b, a))
    a.free()
    b.free()
}
