// ACCEPT: abstract class hierarchy, virtual calls
@file:MemoryMode(Mode.Strict)

import mini.memory.*

abstract class Animal(val name: String) {
    abstract fun sound(): String
    fun speak(): String = name + " says " + sound()
}

@AllocatedBy(Heap::class)
class Dog(name: String) : Animal(name) { override fun sound(): String = "woof" }

fun main() {
    val d = Dog("Rex")
    println(d.speak())
    d.free()
}
