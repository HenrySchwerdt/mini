// open/override, super calls chain, covariant return types, protected, abstract property overrides, final
open class Animal { open fun sound() = "..."; open val legs: Int get() = 4; protected open fun secret() = "animal secret"; fun reveal() = secret(); open fun self(): Animal = this; override fun toString() = "Animal" }
open class Dog : Animal() { override fun sound() = "woof"; override val legs get() = super.legs; override fun secret() = "dog>" + super.secret(); override fun self(): Dog = this; override fun toString() = "Dog(" + super.toString() + ")" }
class Puppy : Dog() { override fun sound() = super.sound() + "!"; override val legs = 4; final override fun secret() = "pup>" + super.secret() }
abstract class Shape { abstract val sides: Int; open fun describe() = "shape with $sides sides"; abstract fun area(): Double }
class Tri(val b: Double, val h: Double) : Shape() { override val sides = 3; override fun area() = b * h / 2; override fun describe() = "triangle: " + super.describe() }
open class Cnt { open var n = 0; open fun add() { n++ } }
class Cnt2 : Cnt() { override var n: Int = 10; override fun add() { super.add(); n += 100 } }
open class Gen<T> { open fun make(): T? = null; open fun take(t: T) = "base" }
class GenS : Gen<String>() { override fun make() = "made"; override fun take(t: String) = "str" + t.length }
fun main() {
    val d: Animal = Puppy(); println(d.sound()); println(d.legs); println(d.reveal()); println(d.self()); println(Dog().self().sound()); println(Animal().reveal())
    println(Tri(3.0, 4.0).describe()); println(Tri(3.0, 4.0).area()); val c = Cnt2(); c.add(); println(c.n); val c0 = Cnt(); c0.add(); println(c0.n)
    val g: Gen<String> = GenS(); println(g.make()); println(g.take("abc")); println(Gen<Int>().make()); println(Gen<Int>().take(1))
    println(listOf(Animal(), Dog(), Puppy()).map { it.sound() }); println(listOf(Animal(), Dog(), Puppy()).map { it is Dog })
}
