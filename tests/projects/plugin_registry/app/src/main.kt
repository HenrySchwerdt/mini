import plugreg.*

class Upper : BasePlugin<String, String>("upper") {
    override fun transform(input: String) = input.uppercase()
}

class Length : BasePlugin<String, Int>("length") {
    val seen = ArrayList<String>()
    override fun before(input: String) { seen.add(input) }
    override fun transform(input: String) = input.length
}

class Anything : Plugin<Any, String> {
    override val id = "anything"
    override fun process(input: Any) = "<" + input + ">"
}

var watched: Int by tracked(1) { old, new -> println("watched " + old + " -> " + new) }

fun main() {
    println("start " + startup + " " + Global.counter)
    val reg = Registry<String, Any>()
    reg.register(Upper()).register(Length())
    reg.register(object : Plugin<String, Any> {
        override val id = "reverse"
        override fun process(input: String): Any = input.reversed()
    })
    reg.hook { it.trim() }
    reg.hook { it + "!" }
    println(reg.runAll("  hello "))
    println(reg.size)
    println("upper" in reg)
    println(reg["length"])
    println(reg["zzz"])
    try {
        reg.register(Upper())
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    val cur = reg.cursor()
    val ids = ArrayList<String>()
    while (cur.hasNext()) ids.add(cur.next().id)
    println(ids)

    val any = Anything()
    println(any.processAll(listOf(1, "two", 3.0)))
    val asStringPlugin: Plugin<String, Any> = any
    println(asStringPlugin.process("s"))
    println(Upper().processAll(listOf("a", "b")))

    watched = 5
    watched = 7
    println(watched)

    val v = Version.parse("2.10")
    val (maj, min) = v
    println("" + v + " " + maj + " " + min + " " + Version.ZERO + " " + Version.of(3))
    println(listOf(Version.of(1, 2), v, Version.of(2, 9)).sorted())
    println(maxOfAll(Version.of(1), Version.of(4, 1), v))
    println(maxOfAll(3, 9, 4))
    println(Version.SEP)
    println(Version.parse("1.2") == Version.of(1, 2))

    println(Global.banner)
    println(Global.banner)
    println(Global.log)
    println(listOf(10, 20, 30).second)
    println(listOf(1).second)
    println(makeFormatter(">> ")("x"))
    println(gcd(84, 36))
    val fmt = Formatter { it.repeat(2) }
    println(fmt("ab"))
}
