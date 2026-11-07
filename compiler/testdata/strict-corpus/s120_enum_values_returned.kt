// ACCEPT: enum constants are immortal singletons: values(), lookups returning an entry and lists of entries need no owner
@file:MemoryMode(Mode.Strict)

import mini.memory.*

enum class Color(val rgb: Int) { RED(1), GREEN(2), BLUE(3);
    companion object {
        fun parse(s: String): Color? {
            for (c in values()) if (c.name == s) return c
            return null
        }
    }
}

object Registry { val names = "a,b" }

fun pick(i: Int): Color = Color.values()[i]

fun main() {
    println(Color.parse("GREEN"))
    println(pick(2).rgb)
    println(listOf(Color.BLUE, Color.RED).sorted())
    println(Registry.names)
}
