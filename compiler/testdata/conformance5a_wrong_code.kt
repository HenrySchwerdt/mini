// Wrong-code regressions found by the Kotlin box tests (docs/conformance.md, fifth round, a).

// ---- `super@Outer` / unqualified `super` inside an inner class ----
interface BK {
    fun foo(): String
    fun bar(): String
}

interface K : BK {
    override fun foo() = bar()
}

class A : K {
    override fun foo() = "A.foo"
    override fun bar() = "A.bar"

    inner class B : K {
        override fun foo() = "B.foo"
        override fun bar() = "B.bar"

        fun test1() = super<K>@A.foo()
        fun test2() = super<K>@B.foo()
        fun test3() = super<K>.foo()
        fun test4() = super@A.foo()
        fun test5() = super@B.foo()
        fun test6() = super.foo()
    }
}

// ---- `var (a, b) = ...` captured and assigned by a lambda ----
class P {
    operator fun component1() = 1
    operator fun component2() = 2
}

fun destructuredCapture(): String {
    var (a, b) = P()
    val local = { a = 3 }
    local()
    return if (a == 3 && b == 2) "ok" else "bad"
}

// ---- an integer literal adapts to the other argument (T = Long) ----
fun <T> same(x: T, y: T): Boolean = x == y

// ---- `label@{ ... }` as a call argument: `return@label` ----
inline fun <R> doCall(block: () -> R): R = block()

fun labelled(b: Boolean): String {
    val r = doCall local@{
        if (b) {
            return@local "local"
        } else {
            return "nonLocal"
        }
    }
    return "r=" + r
}

// ---- a `val` assigned later by a lambda ----
fun runOnce(action: () -> Unit) = action()

fun deferred(): String {
    val res: String
    runOnce { res = "deferred" }
    return res
}

// ---- `let { throw }` (a generic result of type Nothing) ----
fun nothingLet(s: String?): String {
    s?.let { throw IllegalStateException("boom " + it) }
    return "none"
}

// ---- the iterator of a CharSequence calls length once per hasNext ----
class Counting(private val s: String) : CharSequence {
    var lengthCtr = 0
    override val length: Int
        get() = s.length.also { lengthCtr++ }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = TODO()
    override fun get(index: Int): Char = s[index]
}

fun main() {
    val b = A().B()
    println(b.test1() + " " + b.test2() + " " + b.test3() + " " + b.test4() + " " + b.test5() + " " + b.test6())
    println(destructuredCapture())
    val l = 1234L
    println(same(1234, l))
    println(labelled(true))
    println(labelled(false))
    println(deferred())
    try {
        nothingLet("x")
    } catch (e: IllegalStateException) {
        println(e.message)
    }
    println(nothingLet(null))
    val c = Counting("abcd")
    var n = 0
    for ((i, ch) in c.withIndex()) n += i
    println(n.toString() + " " + c.lengthCtr)
}
