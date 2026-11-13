// custom getters/setters, backing fields, private set, const/val/var, computed properties, field default
class Temp {
    var celsius: Double = 0.0
        set(v) { field = if (v < -273.15) -273.15 else v }
    val fahrenheit: Double get() = celsius * 9 / 5 + 32
    var label: String = "t"
        get() = field.uppercase()
        set(value) { field = value.trim() }
    var counter = 0
        private set
    fun bump() { counter++ }
    val isFreezing get() = celsius <= 0
    var lazyish: Int = 5
        get() { println("get lazyish"); return field }
}
class Rect(var w: Int, var h: Int) { var area: Int
    get() = w * h
    set(v) { h = v / w } }
object Registry { private val items = ArrayList<String>(); val count get() = items.size; fun add(s: String) = items.add(s); var last: String? = null; get() = items.lastOrNull() }
class Square(side: Int) { val side = side.coerceAtLeast(0); val perimeter = this.side * 4 }
fun main() {
    val t = Temp(); t.celsius = 100.0; println(t.fahrenheit); t.celsius = -500.0; println(t.celsius); println(t.isFreezing)
    t.label = "  abc "; println(t.label); t.bump(); t.bump(); println(t.counter); println(t.lazyish)
    val r = Rect(3, 4); println(r.area); r.area = 30; println(r.h); r.w = 6; println(r.area)
    Registry.add("a"); Registry.add("b"); println(Registry.count); println(Registry.last)
    println(Square(-3).side); println(Square(5).perimeter)
}
