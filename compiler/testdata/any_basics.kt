class Plain(val id: Int)

class Pt(val x: Int, val y: Int) {
    override fun toString(): String = "Pt(" + x + ", " + y + ")"
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Pt) return false
        return x == other.x && y == other.y
    }
    override fun hashCode(): Int = 31 * x + y
}

fun show(a: Any?): String = "<" + a + ">"

fun main() {
    val p1 = Plain(1)
    val p2 = Plain(1)
    println(p1 == p1)
    println(p1 == p2)
    println(p1 != p2)
    println(p1 === p2)
    println(p1.equals(p1))
    println(p1.hashCode() == p1.hashCode())
    val text = p1.toString()
    println(text.length > 6)

    val a = Pt(1, 2)
    val b = Pt(1, 2)
    val c = Pt(2, 1)
    println(a)
    println(a == b)
    println(a === b)
    println(a == c)
    println(a.equals(c))
    println(a.hashCode())
    println(c.hashCode())
    println(a.hashCode() == b.hashCode())
    println("value: $a")
    println("sum " + a + " and " + c)
    println(show(a))
    println(show(null))
    println(show("str"))
    println(show(42))
    println(show(true))
    println(show(2.5))
    println(show('c'))
    val any: Any = a
    println(any)
    println(any == b)
    println(any.toString())
    println(any.hashCode())
    val n: Any = 5
    println(n == 5)
    println(n.equals(6))
    println(n.hashCode())
    val s: Any = "abc"
    println(s == "abc")
    println(s.hashCode() == "abc".hashCode())
}
