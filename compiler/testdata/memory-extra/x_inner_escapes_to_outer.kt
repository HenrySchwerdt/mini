// REJECT M0105: value of the inner arena stored into the outer arena object
@file:MemoryMode(Mode.Strict)

package memory.extra.x_inner_escapes_to_outer

import mini.memory.*

class Box(val v: Int)
class Slot(var item: Box?)

fun main() {
    Arena().use { outer ->
        context(outer) {
            val s = Slot(null)
            Arena().use { inner ->
                context(inner) {
                    s.item = Box(2)
                }
            }
        }
    }
}
