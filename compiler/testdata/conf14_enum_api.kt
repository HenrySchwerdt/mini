enum class Color(val rgb: Int) {
    RED(0xFF0000), GREEN(0x00FF00), BLUE(0x0000FF);
    fun hex() = "#" + rgb.toString(16).padStart(6, '0')
    companion object { fun parse(s: String) = valueOf(s.uppercase()) }
}

enum class Op(val sym: Char) : (Int, Int) -> Int {
    ADD('+') { override fun invoke(a: Int, b: Int) = a + b },
    MUL('*') { override fun invoke(a: Int, b: Int) = a * b };
}

enum class Level { LOW, MID, HIGH }

fun main() {
    println(Color.entries)
    println(Color.values().map { it.name })
    println(Color.valueOf("GREEN").hex())
    println(Color.parse("blue").ordinal)
    println(Color.RED < Color.BLUE)
    println(Color.RED.compareTo(Color.GREEN))
    println(Color.BLUE.toString() + Color.BLUE.name)
    try { Color.valueOf("PINK") } catch (e: IllegalArgumentException) { println("no PINK") }
    println(Op.entries.map { "${it.sym}${it(3, 4)}" })
    println(enumValues<Level>().toList())
    println(enumValueOf<Level>("MID"))
    val m = EnumMap2()
    println(m)
    println(Level.HIGH in Level.entries)
    println(Level.entries.filter { it >= Level.MID })
    println(Level.valueOf("LOW") == Level.LOW)
    val byLevel = Level.entries.associateWith { it.name.length }
    println(byLevel)
    println(when (Level.MID) { Level.LOW -> 1; Level.MID -> 2; Level.HIGH -> 3 })
    println(Color.entries.maxByOrNull { it.rgb })
}

fun EnumMap2(): Map<Level, Int> = mutableMapOf<Level, Int>().apply { Level.entries.forEach { this[it] = it.ordinal * 10 } }
