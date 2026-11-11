// Builder inference: type variables of a lambda's receiver / parameter types stay open while the body is checked
// (conformance round 10a).

class Box<T : Any>(val block: (Box<T>.() -> Unit)? = null) {
    var handler: ((T) -> Unit)? = null
    init { block?.invoke(this) }
}

fun handleInt(arg: Int) = println("handleInt $arg")

open class TargetBase
class Target : TargetBase()
class Different

class Bounded<B : TargetBase> {
    var stored: B? = null
    fun set(value: B) { stored = value }
}

fun Bounded<Target>.set(arg: Different) { println("extension set") }

fun <P : TargetBase> bounded(instructions: Bounded<P>.() -> Unit): Bounded<P> = Bounded<P>().apply(instructions)

interface Consumer<in T>

fun <T> buildConsumer(b: Boolean, block: (Consumer<T>) -> Unit): T? = if (b) ("O" as T) else null
fun <T> materialize(): T = "K" as T
fun expectConsumerString(x: Consumer<String>) {}

fun elvis(b: Boolean) = buildConsumer(b) { expectConsumerString(it) } ?: materialize()

fun <R> select(vararg x: R) = x[0]

class Buildee<TV> {
    var item: TV? = null
    fun replace(value: TV): TV? { val t = item; item = value; return t }
}

fun <PTV> build(instructions: Buildee<PTV>.() -> Unit): Buildee<PTV> = Buildee<PTV>().apply(instructions)

fun main() {
    val b = Box {
        handler = { handleInt(it) }
    }
    b.handler?.invoke(7)

    bounded<Target> {
        set(Target())
        set(Different())
    }
    bounded {
        set(Target())
        set(Different())
    }

    println(elvis(true) + elvis(false))

    val inv = select({ 1 }, { 1f })
    println(inv())
    val flag = true
    val f2 = if (flag) { { 1 } } else { { 1f } }
    println(f2())

    val bd = build {
        if (true) replace(Target()) else Different()
    }
    println(bd.item is Target)

    val list = buildList { add(1); add(2) }
    println(list.sum())
    val m = buildMap { put("k", 3) }
    println(m["k"]!! + 1)
}
