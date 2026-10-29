// REJECT M0109: a borrowed parameter is returned as an owned value. Spec 4.2, 7.9
@file:MemoryMode(Mode.Strict)

package memory.borrow_returned_owned

import mini.memory.*

class Item(val v: Int)

fun pick(a: Item): Item = a
//                        ^ M0109

fun main() {
    context(Heap()) {
        val a = Item(1)
        println(pick(a).v)
        a.free()
    }
}
