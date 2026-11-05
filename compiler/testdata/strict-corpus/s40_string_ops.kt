// ACCEPT: strings built from a Strict object's fields in templates and concatenations
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class User(val name: String, val age: Int)

fun greet(u: User): String = "Hello ${u.name}, you are ${u.age}"

fun main() {
    val u = User("Ann", 30)
    val s = greet(u)
    println(s)
    println(s.length)
    u.free()
}
