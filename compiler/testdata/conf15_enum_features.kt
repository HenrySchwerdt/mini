// enums: abstract members, valueOf, entries, ordinal, compareTo, properties, when, interfaces, companion
enum class Op(val sym: String) : Comparable<Op> {
    ADD("+") { override fun apply(a: Int, b: Int) = a + b }, SUB("-") { override fun apply(a: Int, b: Int) = a - b }, MUL("*") { override fun apply(a: Int, b: Int) = a * b };
    abstract fun apply(a: Int, b: Int): Int
    companion object { fun bySym(s: String) = values().first { it.sym == s } }
}
enum class Level(val v: Int) { LOW(1), MID(5), HIGH(10); fun next() = values()[(ordinal + 1) % values().size]; val isTop get() = this == HIGH }
interface Describable { fun desc(): String }
enum class Planet : Describable { EARTH, MARS; override fun desc() = name.lowercase() + "#" + ordinal }
enum class Empty
fun main() {
    println(Op.ADD.apply(2, 3)); println(Op.bySym("*").apply(4, 5)); println(Op.values().map { it.sym }); println(Op.valueOf("SUB").sym); println(Op.SUB)
    try { Op.valueOf("DIV") } catch (e: IllegalArgumentException) { println(e.message) }
    println(Level.LOW.next()); println(Level.HIGH.next()); println(Level.MID.isTop); println(Level.HIGH.isTop); println(Level.LOW < Level.HIGH); println(Level.MID.compareTo(Level.LOW))
    println(Level.entries); println(Level.entries.size); println(Level.values().sumOf { it.v }); println(Level.valueOf("MID").ordinal); println(Level.HIGH.name)
    println(Planet.MARS.desc()); println(Planet.values().map { it.desc() }); println(Empty.values().size)
    println(Level.MID == Level.valueOf("MID")); println(Level.MID === Level.MID); println(Level.LOW.hashCode() == Level.LOW.hashCode());
    val m = EnumMap2(); println(m.size()); val byLevel = Level.values().associateWith { it.v * 2 }; println(byLevel); println(Level.values().groupBy { it.v > 3 })
    println(when (Level.MID) { Level.LOW -> "l"; Level.MID -> "m"; Level.HIGH -> "h" }); println(Level.values().filter { it in Level.MID..Level.HIGH })
    println(Op.ADD::class.simpleName.orEmpty().length >= 0); println(Level.HIGH.toString() + Level.HIGH)
}
class EnumMap2 { private val m = HashMap<Level, Int>(); init { m[Level.LOW] = 1; m[Level.LOW] = 2 }; fun size() = m.size }
