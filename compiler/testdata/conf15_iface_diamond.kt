// interface default methods, diamond resolution with super<X>, abstract class + interface, properties in interfaces
interface A { fun hi() = "A.hi"; val id: String get() = "A" }
interface B { fun hi() = "B.hi"; val id: String get() = "B" }
class C : A, B { override fun hi() = super<A>.hi() + "+" + super<B>.hi(); override val id: String get() = super<A>.id + super<B>.id }
interface Named { val name: String; fun greet() = "Hello, $name" }
abstract class Base(override val name: String) : Named { abstract fun extra(): String; override fun greet() = super.greet() + extra() }
class Impl(n: String) : Base(n) { override fun extra() = "!" }
interface Counter { var count: Int; fun inc() { count++ } }
class CI : Counter { override var count = 0 }
interface Stack<T> { fun push(t: T); fun pop(): T; fun peek(): T; val size: Int; fun isEmpty() = size == 0 }
class AS<T> : Stack<T> { private val l = ArrayList<T>(); override fun push(t: T) { l.add(t) }; override fun pop() = l.removeAt(l.size - 1); override fun peek() = l[l.size - 1]; override val size get() = l.size }
interface Visitor<R> { fun num(n: Int): R; fun add(a: Int, b: Int): R }
object Printer : Visitor<String> { override fun num(n: Int) = "n$n"; override fun add(a: Int, b: Int) = "$a+$b" }
fun main() {
    val c = C(); println(c.hi()); println(c.id); println(Impl("bob").greet()); val ci = CI(); ci.inc(); ci.inc(); println(ci.count)
    val s = AS<String>(); println(s.isEmpty()); s.push("a"); s.push("b"); println(s.peek()); println(s.pop()); println(s.size); println(s.isEmpty())
    println(Printer.num(3) + Printer.add(1, 2)); val n: Named = Impl("x"); println(n.name); println(n is Base); println((n as Base).extra())
    val anon = object : A { override fun hi() = "anon " + super.hi() }; println(anon.hi()); println(anon.id)
    fun f(a: A) = a.hi()
    println(f(c)); println(f(object : A {}))
}
