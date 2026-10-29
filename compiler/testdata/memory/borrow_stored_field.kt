// REJECT M0109: a borrowed parameter is stored in an owned field. Spec 4.2, 7.9
@file:MemoryMode(Mode.Strict)

package memory.borrow_stored_field

import mini.memory.*

class Item(val v: Int)

class Holder {
    var item: Item? = null
}

fun keep(h: Holder, i: Item) {
    h.item = i
//           ^ M0109
}

fun main() {
    context(Heap()) {
        val h = Holder()
        val i = Item(1)
        keep(h, i)
        h.free()
    }
}
