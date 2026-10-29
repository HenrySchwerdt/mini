// Classes, constructors, properties, inheritance, interfaces, companion objects, singletons.

interface Shape {
    val name: String
    fun area(): Double
    fun describe(): String = "$name with area ${area()}"   // default method
}

class Rect(val w: Double, val h: Double) : Shape {
    override val name = "rect"
    override fun area() = w * h
}

open class Animal(val name: String) {
    open fun sound(): String = "..."
    override fun toString(): String = "$name says ${sound()}"
}

class Dog(name: String) : Animal(name) {
    override fun sound() = "woof"
}

class Counter {
    var count = 0
        private set

    fun inc(): Counter {
        count++
        return this
    }

    companion object {
        fun startingAt(n: Int): Counter {
            val c = Counter()
            repeat(n) { c.inc() }
            return c
        }
    }
}

class Temperature(celsius: Double) {
    var celsius = celsius
    val fahrenheit: Double get() = celsius * 9 / 5 + 32

    init {
        println("created at $celsius C")
    }
}

object Registry {
    private var next = 100
    fun nextId(): Int = next++
}

fun main() {
    println(Rect(2.0, 3.5).describe())
    val animals: List<Animal> = listOf(Animal("generic"), Dog("rex"))
    for (a in animals) println(a)
    println(Counter.startingAt(3).inc().count)
    val t = Temperature(100.0)
    println(t.fahrenheit)
    t.celsius = 0.0
    println(t.fahrenheit)
    println(Registry.nextId() + Registry.nextId())
    println(animals[1] is Dog)
}
