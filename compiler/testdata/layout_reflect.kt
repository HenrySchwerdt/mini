// Declaration-order dependent features on classes whose memory layout is reordered: property references (name, get, set),
// bound references, KClass names, enum entries with fields (ordinal, values(), constructor-parameter order), destructuring of
// Pair/Triple, object/companion with mixed fields, and an interface-typed view of a reordered class.
import kotlin.reflect.KMutableProperty1

class Sample(val flag: Boolean, var count: Int, val big: Long, var label: String, val small: Short) {
    var tail: Byte = 3
}

enum class Planet(val massKg: Double, val moons: Int, val ringed: Boolean, val tag: String, val id: Long) {
    MERCURY(3.3e23, 0, false, "m", 1L shl 40),
    EARTH(5.97e24, 1, false, "e", 2L shl 40),
    SATURN(5.68e26, 146, true, "s", 3L shl 40);

    fun describe(): String = "$name#$ordinal moons=$moons ringed=$ringed tag=$tag id=$id"
}

object Config {
    var enabled: Boolean = true
    var limit: Long = 5_000_000_000L
    var name: String = "cfg"
    var level: Int = 3
}

interface Shape { fun area(): Double }

class Rect(val w: Int, val flag: Boolean, val h: Long, val name: String) : Shape {
    override fun area(): Double = (w * h).toDouble()
}

fun main() {
    val s = Sample(true, 5, 6_000_000_000L, "lbl", 7)
    println(Sample::flag.name + " " + Sample::count.name + " " + Sample::big.name + " " + Sample::label.name + " " + Sample::small.name + " " + Sample::tail.name)
    println(Sample::flag.get(s))
    println(Sample::count.get(s))
    println(Sample::big.get(s))
    println(Sample::label.get(s))
    println(Sample::small.get(s))
    println(Sample::tail.get(s))
    val c: KMutableProperty1<Sample, Int> = Sample::count
    c.set(s, 55)
    println(s.count)
    val bound = s::label
    println(bound.name + "=" + bound.get())
    println(Sample::class.simpleName)
    val ps = listOf(s, Sample(false, 1, 2L, "two", 3))
    println(ps.map(Sample::big))
    println(ps.map(Sample::flag))
    println(ps.sumOf(Sample::count))
    for (p in Planet.values()) println(p.describe())
    println(Planet.valueOf("EARTH").ordinal)
    println(Planet.entries.map { it.massKg > 1e24 })
    println(Planet.SATURN.compareTo(Planet.MERCURY))
    println("${Config.enabled} ${Config.limit} ${Config.name} ${Config.level}")
    Config.level += 1
    Config.limit /= 5
    Config.enabled = false
    println("${Config.enabled} ${Config.limit} ${Config.name} ${Config.level}")
    val (a, b, d) = Triple(1L shl 36, true, "t")
    println("$a $b $d")
    val (k, v) = "key" to 99L
    println("$k $v")
    val shape: Shape = Rect(3, true, 4_000_000_000L, "r")
    println(shape.area())
    println((shape as Rect).name + " " + shape.flag + " " + shape.w + " " + shape.h)
    println(shape::class.simpleName)
}
