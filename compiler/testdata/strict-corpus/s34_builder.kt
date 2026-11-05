// ACCEPT: a builder returning an owned object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Config(var name: String, var size: Int)

class ConfigBuilder {
    var name = "x"
    var size = 1
    fun build(): Config = Config(name, size)
}

fun config(init: ConfigBuilder.() -> Unit): Config {
    val b = ConfigBuilder()
    b.init()
    return b.build()
}

fun main() {
    val c = config { name = "mini"; size = 3 }
    println(c.name + " " + c.size)
    c.free()
}
