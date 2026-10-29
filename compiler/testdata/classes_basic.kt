class Counter(val name: String, var count: Int) {
    var step: Int = 1
    val label: String = name + "#" + count

    init {
        println("init " + name)
    }

    constructor(name: String) : this(name, 0) {
        println("secondary " + name)
    }

    fun inc(): Int {
        count += step
        return count
    }

    fun describe(): String = "$name=$count (step $step)"
}

class Temp {
    var celsius: Double = 0.0
    val fahrenheit: Double
        get() = celsius * 9.0 / 5.0 + 32.0
    var doubled: Int = 0
        set(value) {
            field = value * 2
        }
    private var secret = 7
    fun reveal() = secret
}

fun main() {
    val c = Counter("a", 5)
    println(c.inc())
    c.step = 10
    println(c.inc())
    println(c.describe())
    println(c.label)
    val d = Counter("b")
    d.inc()
    println(d.describe())
    println(c.count + d.count)

    val t = Temp()
    t.celsius = 100.0
    println(t.fahrenheit)
    t.doubled = 21
    println(t.doubled)
    println(t.reveal())
}
