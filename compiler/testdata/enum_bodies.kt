// Enum classes: entries with bodies, constructors with properties, interfaces, entries, valueOf, ordinal, compareTo, when.
interface Labeled { fun label(): String }

enum class Op(val sym: String, val prec: Int = 1) : Labeled {
    ADD("+") {
        override fun apply(a: Int, b: Int) = a + b
    },
    MUL("*", 2) {
        val calls = 0
        override fun apply(a: Int, b: Int) = a * b + calls
        override fun label() = "times"
    },
    NEG("-", 3) {
        override fun apply(a: Int, b: Int) = -a
    };

    abstract fun apply(a: Int, b: Int): Int
    override fun label() = "op " + sym
}

enum class Color { RED, GREEN, BLUE;
    fun next() = entries[(ordinal + 1) % entries.size]
}

enum class Planet(val mass: Double, val radius: Double) {
    MERCURY(3.303e+23, 2.4397e6),
    EARTH(5.976e+24, 6.37814e6);

    val gravity: Double get() = 6.67300E-11 * mass / (radius * radius)
}

enum class Level { LOW, MID, HIGH }

fun describe(l: Level): String = when (l) {
    Level.LOW -> "low"
    Level.MID -> "mid"
    Level.HIGH -> "high"
}

fun main() {
    for (o in Op.values()) println("${o.name} ${o.ordinal} ${o.sym} ${o.prec} ${o.apply(3, 4)} ${o.label()}")
    println(Op.entries.map { it.sym })
    println(Op.MUL)
    println(Color.entries.size)
    println(Color.entries.joinToString("-") { it.name })
    println(Color.valueOf("GREEN"))
    println(Color.RED.compareTo(Color.BLUE))
    println(Color.BLUE.compareTo(Color.RED))
    println(Color.RED.next())
    println(Color.BLUE.next())
    println(Color.RED == Color.RED)
    println(Level.entries.map(::describe))
    println(Planet.EARTH.gravity > Planet.MERCURY.gravity)
    val byName = Color.entries.associateBy { it.name }
    println(byName["GREEN"]?.ordinal)
    for (c in Color.entries) print("${c.ordinal}:${c.name} ")
    println()
    try {
        Color.valueOf("PINK")
    } catch (e: IllegalArgumentException) {
        println("no PINK")
    }
    val l: Labeled = Op.ADD
    println(l.label())
}
