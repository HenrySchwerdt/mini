interface Named {
    val name: String
    fun greet(): String = "Hello, " + name
}

interface Walker {
    fun walk(): String
    fun rest(): String = "resting"
}

interface Runner : Walker {
    fun run(): String
    override fun rest(): String = "runner " + super.rest()
}

class Person(override val name: String) : Named

class Athlete(override val name: String) : Named, Runner {
    override fun walk() = name + " walks"
    override fun run() = name + " runs"
    override fun greet(): String = "Yo, " + name
}

class Cat : Walker {
    override fun walk() = "cat walks"
}

open class Base : Walker {
    override fun walk() = "base walks"
}

class Derived : Base(), Named {
    override val name = "derived"
}

fun useWalker(w: Walker) {
    println(w.walk() + " / " + w.rest())
}

fun useRunner(r: Runner) {
    println(r.run() + " / " + r.walk() + " / " + r.rest())
}

fun useNamed(n: Named) = println(n.greet())

fun main() {
    val p = Person("Ann")
    val a = Athlete("Bob")
    useNamed(p)
    useNamed(a)
    useWalker(a)
    useWalker(Cat())
    useWalker(Derived())
    useRunner(a)
    useNamed(Derived())
    val w: Walker = a
    println(w is Runner)
    println(w is Named)
    val c: Walker = Cat()
    println(c is Runner)
}
