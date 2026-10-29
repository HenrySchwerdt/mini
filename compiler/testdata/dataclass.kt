data class Point(val x: Int, val y: Int)

data class User(val name: String, val age: Int, var active: Boolean = true)

data class Line(val from: Point, val to: Point) {
    fun length2(): Int {
        val dx = to.x - from.x
        val dy = to.y - from.y
        return dx * dx + dy * dy
    }
}

data class Mixed(val d: Double, val l: Long, val c: Char, val b: Boolean)

fun main() {
    val p = Point(1, 2)
    val q = Point(1, 2)
    val r = p.copy(y = 5)
    println(p)
    println(r)
    println(p == q)
    println(p == r)
    println(p === q)
    println(p.hashCode() == q.hashCode())
    println(p.hashCode())
    val (x, y) = r
    println(x + y)
    println(r.component1())
    val u = User("ann", 30)
    println(u)
    println(u.copy(age = 31, active = false))
    println(User("bob", 5, false).hashCode())
    val l = Line(Point(0, 0), Point(3, 4))
    println(l)
    println(l.length2())
    println(l == Line(Point(0, 0), Point(3, 4)))
    println(l.copy(to = Point(6, 8)).length2())
    val m = Mixed(1.5, 99999999999L, 'z', true)
    println(m)
    println(m.hashCode())
    println(m == Mixed(1.5, 99999999999L, 'z', true))
    println(m.equals(Mixed(1.5, 99999999999L, 'z', false)))
}
