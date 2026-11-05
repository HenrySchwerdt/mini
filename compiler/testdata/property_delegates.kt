// Property delegates written against the standard signature (thisRef, property: KProperty<*>), kotlin.properties
// (ReadOnlyProperty, ReadWriteProperty, Delegates) and `by ::otherProperty`.
import kotlin.properties.*
import kotlin.reflect.KProperty

const val K = "K"
val top: String by ::K

class Box {
    var n: Int by Delegates.observable(1) { p, o, nw -> println("${p.name}: $o -> $nw") }
    var small: Int by Delegates.vetoable(0) { _, _, nw -> nw < 10 }
}

class Up : ReadOnlyProperty<Any?, String> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): String = property.name.uppercase()
}

val abc: String by Up()

class Named(val prefix: String) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String = prefix + property.name
}

class Owner(val id: Int) {
    val label: String by Named("owner-")
}

fun main() {
    println(top)
    val b = Box()
    b.n = 5
    b.n = 7
    println(b.n)
    b.small = 3
    b.small = 50
    println(b.small)
    println(abc)
    println(Owner(1).label)
    var nn: Int by Delegates.notNull()
    nn = 3
    println(nn)
    val local: String by Named("local-")
    println(local)
}
