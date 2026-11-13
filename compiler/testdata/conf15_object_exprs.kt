// object expressions and declarations: capturing locals, implementing multiple interfaces, init order, property access, equality
interface Shape { fun area(): Int }
interface Named { val name: String }
abstract class Base(val x: Int) { abstract fun f(): Int; override fun toString() = "Base($x)" }
object Registry : Named { override val name = "registry"; private val items = mutableListOf<String>(); fun add(s: String): Registry { items.add(s); return this }; override fun toString() = items.toString() }
private fun counterObj(start: Int) = object { var n = start; fun inc() = ++n }
fun main() {
    var captured = 10
    val sq = object : Shape, Named { override fun area() = captured * captured; override val name = "sq" }; captured = 3; println(sq.area()); println(sq.name); val s: Shape = sq; println(s is Named)
    val b = object : Base(7) { override fun f() = x * 2; override fun toString() = "anon" + super.toString() }; println(b.f()); println(b); println(b.x)
    val c = counterObj(5); c.inc(); c.inc(); println(c.n); println(Registry.add("a").add("b")); println(Registry.name); println(Registry === Registry)
    val o1 = object { val v = 1 }; val o2 = object { val v = 1 }; println(o1.v == o2.v); println(o1 == o2); println(o1.equals(o1))
    val comparator = object : Comparator<Int> { override fun compare(a: Int, b: Int) = b - a }; println(listOf(1, 3, 2).sortedWith(comparator))
    val it = object : Iterator<Int> { var i = 0; override fun hasNext() = i < 3; override fun next() = i++ * 10 }; while (it.hasNext()) print(it.next().toString() + " "); println()
    val r = object : Runnable { override fun run() { println("run " + captured) } }; r.run(); val items = (1..3).map { i -> object { val sq = i * i } }; println(items.map { it.sq })
    abstract class Tmpl { fun run() = step1() + step2(); abstract fun step1(): String; open fun step2() = "-default" }; println(object : Tmpl() { override fun step1() = "custom" }.run()); println(object : Tmpl() { override fun step1() = "c2"; override fun step2() = "-s2" }.run())
    val lst = object : AbstractList<Int>() { override val size = 3; override fun get(index: Int) = index * index }; println(lst); println(lst.sum()); println(lst.contains(4)); println(lst.indexOf(4))
}
