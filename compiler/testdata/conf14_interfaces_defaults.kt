interface Greeter {
    val name: String
    fun greet() = "Hello, $name"
    fun shout() = greet().uppercase() + "!"
}

interface Polite : Greeter {
    override fun greet() = "Good day, $name"
}

interface Loud {
    fun shout() = "LOUD"
    fun id() = 1
}

class Both(override val name: String) : Polite, Loud {
    override fun shout() = super<Polite>.shout() + "/" + super<Loud>.shout()
}

class Plain(override val name: String) : Greeter

abstract class Animal(val name: String) {
    abstract fun sound(): String
    open fun intro() = "$name says ${sound()}"
}

open class Dog(name: String) : Animal(name) {
    override fun sound() = "woof"
}

class Puppy(name: String) : Dog(name) {
    override fun sound() = "yip"
    override fun intro() = super.intro() + "!"
}

class Robot : Animal("rbt") {
    override fun sound() = "beep"
    final override fun intro() = "[" + super.intro() + "]"
}

fun main() {
    println(Plain("a").greet())
    println(Plain("a").shout())
    println(Both("b").greet())
    println(Both("b").shout())
    println(Both("b").id())
    val zoo: List<Animal> = listOf(Dog("d"), Puppy("p"), Robot())
    zoo.forEach { println(it.intro()) }
    val g: Greeter = object : Greeter { override val name = "anon" }
    println(g.greet())
    println((zoo[1] is Dog) to (zoo[0] is Puppy))
    val d: Dog = Puppy("q")
    println(d.sound())
}
