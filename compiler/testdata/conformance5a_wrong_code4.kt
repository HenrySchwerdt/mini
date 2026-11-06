// Regressions found by the Kotlin box tests (docs/conformance.md, fifth round, a): interface diamonds, captured lateinit locals,
// the evaluation order of named arguments, safe calls of inner constructors, suspend references, member references in lambdas with receiver.
import kotlin.coroutines.*

// ---- an interface diamond: the override wins over what the other branch inherits ----
interface A {
    fun foo() = "Fail"
    var bar: Boolean
        get() = false
        set(value) { throw AssertionError("Fail set") }
}

interface B : A

interface C : A {
    override fun foo() = "OK"
    override var bar: Boolean
        get() = true
        set(value) {}
}

interface D : B, C

class Impl : D

// ---- a captured lateinit local that was never assigned throws ----
fun lateinitCaptured(): String {
    lateinit var s: String
    fun get() = s
    try {
        return get()
    } catch (e: RuntimeException) {
        return "uninitialized"
    }
}

// ---- named arguments of a super constructor call are evaluated in source order ----
var log = ""

open class Base(val o: String, val k: String)
class Derived : Base(k = { log += "k"; "K" }(), o = { log += "o"; "O" }())

// ---- the bound receiver of a callable reference is evaluated in argument order ----
fun String.id() = this

val receiver: String
    get() {
        log += "R"
        return "R"
    }

fun three(a: String, b: String, c: () -> String): String = a + b + c()

// ---- `outer?.Inner()` ----
class Outer(val name: String) {
    inner class Inner {
        val thing: String get() = name
    }
}

fun innerOf(o: Outer?): String = o?.Inner()?.thing ?: "none"

// ---- references to suspend functions and to members of a lambda receiver ----
fun builder(c: suspend () -> Unit) {
    c.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
}

suspend fun id(s: String) = s

suspend fun runS(fn: suspend (String) -> String) = fn("O")

val plain: (String) -> String = { it + "K" }

class Writer {
    fun write(): String = "written"
}

fun withWriter(a: suspend Writer.() -> String): String {
    var res = ""
    builder { res = Writer().a() }
    return res
}

fun interface Runnable2 {
    fun run()
}

fun runTwice(r: Runnable2) {
    r.run()
    r.run()
}

class Counter {
    var n = 0
    fun inc() { n++ }
}

fun main() {
    println(Impl().foo())
    val impl = Impl()
    impl.bar = false
    println(impl.bar)
    println(lateinitCaptured())

    val d = Derived()
    println(log + " " + d.o + d.k)

    log = ""
    val r = three(b = { log += "b"; "b" }(), c = receiver::id, a = { log += "a"; "a" }())
    println(log + " " + r)

    println(innerOf(null))
    println(innerOf(Outer("outer")))

    var result = ""
    builder {
        result = runS(::id) + runS(plain)
    }
    println(result)
    println(withWriter { (::write).let { it() } })

    val counter = Counter()
    runTwice(counter::inc)
    println(counter.n)
}
