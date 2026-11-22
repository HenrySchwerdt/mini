interface Factory<T> { fun make(): T }

class Widget private constructor(val id: Int) {
    companion object : Factory<Widget> {
        private var counter = 0
        const val KIND = "widget"
        val registry = mutableListOf<Int>()
        override fun make(): Widget = Widget(++counter).also { registry.add(it.id) }
        @JvmStatic fun reset() { counter = 0 }
    }
    override fun toString() = "$KIND#$id"
}

object Logger {
    private val lines = mutableListOf<String>()
    var level = 1
    fun log(s: String) { lines.add("[$level] $s") }
    fun dump() = lines.joinToString(";")
    init { lines.add("init") }
}

object Registry : Comparator<String> {
    override fun compare(a: String, b: String) = b.compareTo(a)
}

class Counter {
    companion object Named {
        fun create() = Counter()
        val zero = 0
    }
    var n = zero
}

fun main() {
    println(Widget.make())
    println(Widget.make())
    println(Widget.registry)
    Widget.reset()
    println(Widget.Companion.make())
    println(Widget.KIND)
    Logger.log("a")
    Logger.level = 2
    Logger.log("b")
    println(Logger.dump())
    println(listOf("a", "c", "b").sortedWith(Registry))
    println(Counter.create().n)
    println(Counter.Named.zero)
    val f: Factory<Widget> = Widget
    println(f.make())
    val anon = object {
        val x = 5
        fun twice() = x * 2
    }
    println(anon.twice())
    val cmp = object : Comparator<Int> { override fun compare(a: Int, b: Int) = a - b }
    println(listOf(3, 1, 2).sortedWith(cmp))
}
