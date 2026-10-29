enum class Color {
    RED, GREEN, BLUE
}

enum class Planet(val mass: Int, val label: String) {
    MERCURY(3, "small"),
    EARTH(6, "home"),
    JUPITER(1900, "giant");

    fun heavy(): Boolean = mass > 100
    fun describe(): String = name + "/" + label + "/" + ordinal
}

fun colorName(c: Color): String = when (c) {
    Color.RED -> "red!"
    Color.GREEN -> "green!"
    Color.BLUE -> "blue!"
}

fun warm(c: Color): Boolean = c == Color.RED

fun main() {
    val c = Color.GREEN
    println(c)
    println(c.name)
    println(c.ordinal)
    println(colorName(Color.RED))
    println(colorName(c))
    println(colorName(Color.BLUE))
    println(warm(c))
    println(warm(Color.RED))
    println(Color.BLUE == Color.BLUE)
    println(Color.BLUE === Color.BLUE)
    val p = Planet.JUPITER
    println(p)
    println(p.heavy())
    println(Planet.EARTH.heavy())
    println(p.describe())
    println(Planet.MERCURY.mass + Planet.EARTH.mass)
    println("planet: " + Planet.EARTH + " " + Planet.EARTH.label)
    val any: Any = Color.RED
    println(any is Color)
    println(any)
    val n: Color? = null
    println(n)
    println(n ?: Color.BLUE)
}
