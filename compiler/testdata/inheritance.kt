open class Animal(val name: String) {
    open fun sound(): String = "..."
    fun speak(): String = name + " says " + sound()
    open val legs: Int
        get() = 4
    override fun toString(): String = "Animal($name)"
}

open class Dog(name: String) : Animal(name) {
    override fun sound(): String = "Woof"
    fun fetch() = "$name fetches"
}

class Puppy(name: String) : Dog(name) {
    override fun sound(): String = super.sound() + "!"
}

class Bird(name: String) : Animal(name) {
    override fun sound() = "Tweet"
    override val legs: Int
        get() = 2
}

abstract class Shape {
    abstract fun area(): Double
    abstract val corners: Int
    fun summary(): String = "area=" + area() + " corners=" + corners
}

class Rect(val w: Double, val h: Double) : Shape() {
    override fun area() = w * h
    override val corners = 4
}

class Tri(val b: Double, val h: Double) : Shape() {
    override fun area() = b * h / 2.0
    override val corners get() = 3
}

fun describe(a: Animal): String = a.speak() + " on " + a.legs + " legs"

fun main() {
    val animals = arrayOfNothing()
    println(describe(Animal("Generic")))
    println(describe(Dog("Rex")))
    println(describe(Puppy("Bit")))
    println(describe(Bird("Tweety")))
    val a: Animal = Puppy("Up")
    println(a)
    println("$a and ${Dog("D")}")
    val shapes1: Shape = Rect(2.0, 3.5)
    val shapes2: Shape = Tri(3.0, 4.0)
    println(shapes1.summary())
    println(shapes2.summary())
    println(Dog("F").fetch())
}

fun arrayOfNothing(): Int = 0
