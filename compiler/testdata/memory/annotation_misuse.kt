// REJECT M0111: @Owned on a type that has no ownership (Int). Spec 4.4, 7.11
@file:MemoryMode(Mode.Strict)

package memory.annotation_misuse

import mini.memory.*

fun twice(x: @Owned Int): Int = x * 2
//           ^ M0111

fun main() {
    println(twice(4))
}
