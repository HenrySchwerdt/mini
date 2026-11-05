// object expressions, companion objects with interfaces, inner classes and this@Outer, nested classes inside local classes.
interface Greeter { fun greet(): String }
interface Named { val name: String }
abstract class Base(val n: Int) {
    abstract fun f(): Int
    open fun g() = f() + 1
}

class Outer(val x: Int) {
    val tag = "outer$x"

    inner class In(val y: Int) {
        val tag = "in$y"
        fun both() = this@Outer.tag + "/" + this.tag + "/" + (x + y)
        inner class Deep { fun path() = this@In.tag + ">" + this@Outer.tag }
    }

    class Nested(val z: Int) { fun twice() = z * 2 }

    companion object : Greeter {
        const val K = 42
        override fun greet() = "companion"
        fun make(v: Int) = Outer(v)
    }

    fun inner(y: Int) = In(y)
}

interface Factory<T> { fun create(): T }

class Widget(val id: Int) {
    companion object : Factory<Widget> {
        var created = 0
        override fun create(): Widget { created++; return Widget(created) }
    }
}

object Registry : Greeter {
    private val items = mutableListOf<String>()
    fun add(s: String) { items.add(s) }
    override fun greet() = "registry:" + items.joinToString(",")
}

fun counterFrom(start: Int): () -> Int {
    var c = start
    return { c++ }
}

fun main() {
    val k = 5
    var calls = 0
    val g = object : Greeter {
        override fun greet(): String { calls++; return "anon$k" }
    }
    println(g.greet() + g.greet() + calls)

    val b = object : Base(2) {
        override fun f() = n * k
    }
    println(b.f())
    println(b.g())

    val both = object : Greeter, Named {
        override val name = "both"
        override fun greet() = "hi $name"
    }
    println(both.greet())
    println(both.name)

    val o = Outer(3)
    val i = o.In(4)
    println(i.both())
    println(i.Deep().path())
    println(o.inner(9).tag)
    println(Outer.Nested(6).twice())
    println(Outer.greet())
    println(Outer.K)
    println(Outer.make(8).tag)
    val gr: Greeter = Outer
    println(gr.greet())

    val w1 = Widget.create()
    val w2 = Widget.create()
    println(w1.id + w2.id)
    val f: Factory<Widget> = Widget
    println(f.create().id)
    println(Widget.created)

    Registry.add("a")
    Registry.add("b")
    println(Registry.greet())
    val reg: Greeter = Registry
    println(reg.greet())

    class Local(val v: Int) {
        class Inner(val w: Int) { fun sum(l: Local) = w + l.v }
        fun make() = Inner(v * 10)
    }
    val lc = Local(2)
    println(lc.make().sum(lc))
    println(Local.Inner(1).sum(Local(1)))

    val c = counterFrom(10)
    println(c() + c() + c())

    val list = listOf(1, 2, 3).map { n ->
        object {
            val doubled = n * 2
            fun show() = "n=$n d=$doubled"
        }
    }
    println(list.map { it.show() })

    val cmp = object : Comparator<String> {
        override fun compare(a: String, b: String) = a.length - b.length
    }
    println(listOf("ccc", "a", "bb").sortedWith(cmp))
}
