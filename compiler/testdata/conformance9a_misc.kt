// Conformance round 9 (a): a local `transform` that cannot take a lambda falls through to the extension `transform`, an alias chain of
// more than 50 copies (a constructor with 60 arguments that all need statements), `a?.f()` for a Unit `f` is the Unit object, a generic
// call with T = Unit, `x is SuspendFunction0<*>` tells suspend lambdas and references from plain function objects, a fun interface whose
// abstract method is `toString`, a companion's extension referenced through an implicit receiver, the same inline function expanded
// inside its own lambda argument, unsigned array `sumOf`, `.name` of a function reference held in a value, context overloads.
import kotlin.coroutines.*

class Box<T>(val v: T)

inline fun <T, R> Box<T>.transform(crossinline transform: Box<R>.(value: T) -> Unit): String = "extension"

fun <T, R : Any> Box<T>.mapNotNull(transform: (value: T) -> R?): String = transform { value ->
    val x = value
    return@transform
}

class Wide(
    val p0: Double?, val p1: Double?, val p2: Double?, val p3: Double?, val p4: Double?, val p5: Double?, val p6: Double?, val p7: Double?,
    val p8: Double?, val p9: Double?, val p10: Double?, val p11: Double?, val p12: Double?, val p13: Double?, val p14: Double?, val p15: Double?,
    val p16: Double?, val p17: Double?, val p18: Double?, val p19: Double?, val p20: Double?, val p21: Double?, val p22: Double?, val p23: Double?,
    val p24: Double?, val p25: Double?, val p26: Double?, val p27: Double?, val p28: Double?, val p29: Double?, val p30: Double?, val p31: Double?,
    val p32: Double?, val p33: Double?, val p34: Double?, val p35: Double?, val p36: Double?, val p37: Double?, val p38: Double?, val p39: Double?,
    val p40: Double?, val p41: Double?, val p42: Double?, val p43: Double?, val p44: Double?, val p45: Double?, val p46: Double?, val p47: Double?,
    val p48: Double?, val p49: Double?, val p50: Double?, val p51: Double?, val p52: Double?, val p53: Double?, val p54: Double?, val p55: Double?,
    val p56: Double?, val p57: Double?, val p58: Double?, val p59: Double?,
)

fun wide(agg: (List<Double>) -> Double, l: List<Wide>): Wide = Wide(
    agg(l.mapNotNull { it.p0 }), agg(l.mapNotNull { it.p1 }), agg(l.mapNotNull { it.p2 }), agg(l.mapNotNull { it.p3 }),
    agg(l.mapNotNull { it.p4 }), agg(l.mapNotNull { it.p5 }), agg(l.mapNotNull { it.p6 }), agg(l.mapNotNull { it.p7 }),
    agg(l.mapNotNull { it.p8 }), agg(l.mapNotNull { it.p9 }), agg(l.mapNotNull { it.p10 }), agg(l.mapNotNull { it.p11 }),
    agg(l.mapNotNull { it.p12 }), agg(l.mapNotNull { it.p13 }), agg(l.mapNotNull { it.p14 }), agg(l.mapNotNull { it.p15 }),
    agg(l.mapNotNull { it.p16 }), agg(l.mapNotNull { it.p17 }), agg(l.mapNotNull { it.p18 }), agg(l.mapNotNull { it.p19 }),
    agg(l.mapNotNull { it.p20 }), agg(l.mapNotNull { it.p21 }), agg(l.mapNotNull { it.p22 }), agg(l.mapNotNull { it.p23 }),
    agg(l.mapNotNull { it.p24 }), agg(l.mapNotNull { it.p25 }), agg(l.mapNotNull { it.p26 }), agg(l.mapNotNull { it.p27 }),
    agg(l.mapNotNull { it.p28 }), agg(l.mapNotNull { it.p29 }), agg(l.mapNotNull { it.p30 }), agg(l.mapNotNull { it.p31 }),
    agg(l.mapNotNull { it.p32 }), agg(l.mapNotNull { it.p33 }), agg(l.mapNotNull { it.p34 }), agg(l.mapNotNull { it.p35 }),
    agg(l.mapNotNull { it.p36 }), agg(l.mapNotNull { it.p37 }), agg(l.mapNotNull { it.p38 }), agg(l.mapNotNull { it.p39 }),
    agg(l.mapNotNull { it.p40 }), agg(l.mapNotNull { it.p41 }), agg(l.mapNotNull { it.p42 }), agg(l.mapNotNull { it.p43 }),
    agg(l.mapNotNull { it.p44 }), agg(l.mapNotNull { it.p45 }), agg(l.mapNotNull { it.p46 }), agg(l.mapNotNull { it.p47 }),
    agg(l.mapNotNull { it.p48 }), agg(l.mapNotNull { it.p49 }), agg(l.mapNotNull { it.p50 }), agg(l.mapNotNull { it.p51 }),
    agg(l.mapNotNull { it.p52 }), agg(l.mapNotNull { it.p53 }), agg(l.mapNotNull { it.p54 }), agg(l.mapNotNull { it.p55 }),
    agg(l.mapNotNull { it.p56 }), agg(l.mapNotNull { it.p57 }), agg(l.mapNotNull { it.p58 }), agg(l.mapNotNull { it.p59 }),
)

class A(val x: String) {
    fun bar(): Unit {}
}

fun <T> something(): T = Any() as T

fun fn1(x: Any) {}
val lambda1 = { x: Any -> } as (Any) -> Unit
val suspendLambda0: suspend () -> Unit = {}
suspend fun suspendFn0() {}

fun interface SomeFun {
    override fun toString(): String
}

open class P {
    fun instance() = true
    companion object : P()
}

fun P.ext() = true

inline fun call(f: () -> Boolean) = f()

fun <T> supplier(f: () -> T) = f

inline fun consumer1(c: (Unit) -> Unit) = c(Unit)

class Outer {
    fun f(): String {
        var n = 0
        consumer1 {
            supplier {
                consumer1(consumer2())
                n++
            }::apply
        }
        return "n=$n"
    }
    fun consumer2(): (Unit) -> Unit = {}
}

fun refName(): String {
    val f = ::fn1
    val g = A::bar
    val h = A("x")::bar
    return f.name + "," + g.name + "," + h.name
}

class Scope(val name: String)

// (a second implementation of Continuation: with BaseContinuation alone, `completion.context` devirtualizes to itself)
class Plain : Continuation<Unit> {
    override val context: CoroutineContext = EmptyCoroutineContext
    override fun resumeWith(result: Result<Unit>) {}
}

class Ctx {
    fun foo(): String = "plain"

    context(Scope)
    fun foo(): String = "ctx"
}

fun main() {
    println(Box(1).mapNotNull<Int, String> { it.toString() })
    val l = listOf(Wide(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0,
        21.0, 22.0, 23.0, 24.0, 25.0, 26.0, 27.0, 28.0, 29.0, 30.0, 31.0, 32.0, 33.0, 34.0, 35.0, 36.0, 37.0, 38.0, 39.0, 40.0,
        41.0, 42.0, 43.0, 44.0, 45.0, 46.0, 47.0, 48.0, 49.0, 50.0, 51.0, 52.0, 53.0, 54.0, 55.0, 56.0, 57.0, 58.0, 59.0, 60.0))
    val w = wide({ it.sum() }, l)
    println(w.p0.toString() + " " + w.p59)
    val a1: A? = A("x")
    val a2: A? = null
    println(a1?.bar())
    println(a2?.bar())
    println(a1?.bar() == null)
    val u: Any = something<Unit>()
    println(u === Unit)
    val f1 = ::fn1 as Any
    val sf0 = ::suspendFn0 as Any
    println((f1 is SuspendFunction0<*>).toString() + " " + (sf0 is SuspendFunction0<*>) + " " + (suspendLambda0 is SuspendFunction0<*>) + " " +
        (lambda1 is SuspendFunction0<*>) + " " + (sf0 is Function1<*, *>))
    println(SomeFun { "OK" }.toString())
    with(P) {
        println(call(::instance).toString() + " " + call(::ext))
    }
    println(Outer().f())
    println(refName())
    println(uintArrayOf(1u, 2u, 3u).sumOf { it.toInt() })
    println(ushortArrayOf(4u, 5u).sumOf { it.toLong() })
    val scope = Scope("s")
    val c = Ctx()
    println(c.foo() + " " + with(scope) { c.foo() })
    println(Plain().context === EmptyCoroutineContext)
}
