import kotlin.properties.Delegates

interface Printer { fun print(s: String): String; val prefix: String }
class Basic(override val prefix: String) : Printer { override fun print(s: String) = prefix + s }
class Shouting(p: Printer) : Printer by p {
    override fun print(s: String) = p2(s)
    private val inner = p
    private fun p2(s: String) = inner.print(s).uppercase()
}
class Plain(p: Printer) : Printer by p

class Model {
    var name: String by Delegates.observable("init") { prop, old, new -> println("${prop.name}: $old -> $new") }
    var age: Int by Delegates.vetoable(0) { _, _, new -> new >= 0 }
    var late: String by Delegates.notNull()
    val computed: Int by lazy { println("computing"); 42 }
    var map: Int by Holder()
}

class Holder {
    private var v = 0
    operator fun getValue(t: Any?, p: kotlin.reflect.KProperty<*>) = v
    operator fun setValue(t: Any?, p: kotlin.reflect.KProperty<*>, x: Int) { v = x * 2 }
}

fun main() {
    println(Shouting(Basic("> ")).print("hi"))
    println(Plain(Basic("# ")).print("yo") + Plain(Basic("# ")).prefix)
    val m = Model()
    m.name = "a"
    m.name = "b"
    m.age = 5
    m.age = -1
    println(m.age)
    m.late = "set"
    println(m.late)
    println(m.computed + m.computed)
    m.map = 4
    println(m.map)
    val local by lazy { "lazy local" }
    println(local)
    val lz = lazy { 7 }
    println(lz.isInitialized())
    println(lz.value)
    println(lz.isInitialized())
}
