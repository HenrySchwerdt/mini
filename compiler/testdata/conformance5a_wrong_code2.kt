// More regressions found by the Kotlin box tests (docs/conformance.md, fifth round, a).

// ---- the setter of a fake override reaches a field of a generic superclass (an Int stored in a T field) ----
open class G<T> {
    var size: T = 56 as T
}

interface HasSize {
    var size: Int
}

class GB : HasSize, G<Int>()

// ---- the arguments of a super constructor call do not see the members of the class being constructed ----
class B1

open class A1(val b: B1) {
    fun a(): A1 = object : A1(b) {}
}

// ---- a private member of a superclass is not a member of the subclass ----
open class X(private val n: String) {
    fun foo(): String {
        return object : X("inner") {
            fun print(): String = n
        }.print()
    }
}

// ---- the companion `init` blocks run when the class is first instantiated ----
var global = "A"

class C {
    init {
        global += "D"
    }

    companion object {
        init {
            global += "B"
        }
    }
}

// ---- private function of an interface ----
interface PI {
    private fun foo() = "private"
    fun bar() = foo()
}

class PB : PI {
    private fun foo() = "fail"
}

// ---- `is T?` ----
fun nullableChecks(n: Any?, s: Any?): String {
    val r = StringBuilder()
    r.append(n is Int?).append(' ')
    r.append(n is String?).append(' ')
    r.append(n is Int).append(' ')
    r.append(s is Int?).append(' ')
    r.append(s is String?).append(' ')
    r.append(s is Any?).append(' ')
    r.append("" is Nothing).append(' ')
    r.append(n is Nothing?)
    return r.toString()
}

// ---- a Unit value used as Any, a Unit property ----
class WithUnit(val c: Unit, val d: Unit?)

fun unitCast(): String = (println("side") as Any).toString()

// ---- `break` / `continue` as an operand ----
fun breakOperand(): Int {
    var sum = 0
    for (i in 0..3) {
        for (j in continue downTo 1) {
            sum += 100
        }
        sum += 1
    }
    return sum
}

fun main() {
    val gb = GB()
    println(gb.size)
    gb.size = 55
    println(gb.size)
    val hs: HasSize = gb
    hs.size = 57
    println(hs.size)

    val b = B1()
    println(A1(b).a().b === b)
    println(X("OK").foo())

    println(global)
    C()
    println(global)
    C()
    println(global)

    println(PB().bar())
    println(nullableChecks(null, 5))
    println(unitCast())
    println(WithUnit(Unit, null).d)
    println(breakOperand())
}
