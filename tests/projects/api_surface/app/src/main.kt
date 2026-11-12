import surface.*

class Label(title: String) : Widget(title) {
    override val weight = 4
    override fun render(width: Int, fill: Char) = "[" + super.render(width, fill) + "]"
}

class Triangle : Shape {
    override val sides = 3
    override fun area(scale: Int) = 6 * scale
}

@Marker("app")
class Item(var name: String = "", var qty: Int = 0)

fun Counter.report() = "n=" + n

fun firstEven(xs: List<Int>): Int {
    val v = findFirst(xs) { it % 2 == 0 }
    if (v < 0) return -100
    return v
}

fun search(xs: List<Int>): String {
    xs.forEach { x ->
        withLabel("x") {
            if (x > 2) return "found " + x
        }
    }
    return "none"
}

fun main() {
    val id = Id<String>(4)
    println(id.toString() + " " + id.next + " " + id.next.next.raw)
    val names = listOf("a", "b", "c", "d", "e", "f")
    println(names[id])
    println(Shape.UNIT)
    val sq = Shape.square(3)
    println(sq.describe() + " " + sq.area(2))
    println(Triangle().describe())
    println(Label("hi").render())
    println(Label("hi").render(8))
    println(Label("hi").render(fill = '*'))
    val b: Box<Any> = Box<Nothing?>(null).map { 5 }
    println(b)
    println(Box(3).map { it * 2 }.map { "v" + it })
    println(emptyBox)
    println(Config.NAME + Config.limit + Config.twice(21))
    Config.level = Level.HIGH
    println(levelName(Config.level))
    println(when (Config.level) { Level.LOW -> 1; Level.MID -> 2; Level.HIGH -> 3 })
    val item = build<Item>({ name = "pen"; qty = 3 }) { Item() }
    println(item.name + item.qty)
    println(firstEven(listOf(1, 3, 6, 8)))
    println(firstEven(listOf(1, 3)))
    println(search(listOf(1, 2, 3, 4)))
    println(search(listOf(1)))
    println(retryTimes(5) { if (it == 3) "ok" + it else null })
    println(retryTimes(2) { null as String? })
    println(10.applyIf(true) { it + 1 }.applyIf(false) { it * 100 }.applyIf(true))
    println(Counter().bump().bump(5).report())
    println(2 pow 10)
    println(Tagged().m())
}
