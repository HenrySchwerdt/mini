// data classes, enums, sealed hierarchies and `when` without `else`.

data class Point(val x: Int, val y: Int)

enum class Direction(val dx: Int, val dy: Int) {
    NORTH(0, 1), EAST(1, 0), SOUTH(0, -1), WEST(-1, 0);

    fun turnRight(): Direction = values()[(ordinal + 1) % values().size]
}

sealed class Expr
class Num(val value: Int) : Expr()
class Add(val left: Expr, val right: Expr) : Expr()
class Mul(val left: Expr, val right: Expr) : Expr()
class Neg(val inner: Expr) : Expr()

fun eval(e: Expr): Int = when (e) {        // exhaustive: no `else` needed
    is Num -> e.value
    is Add -> eval(e.left) + eval(e.right)
    is Mul -> eval(e.left) * eval(e.right)
    is Neg -> -eval(e.inner)
}

fun main() {
    val p = Point(1, 2)
    val (x, y) = p
    println("$p x=$x y=$y")
    println(p == Point(1, 2))
    println(p.copy(y = 5))

    var d = Direction.NORTH
    repeat(5) { d = d.turnRight() }
    println("$d ${d.dx},${d.dy} ${d.ordinal}")
    println(Direction.valueOf("SOUTH"))

    println(eval(Add(Num(2), Mul(Num(3), Neg(Num(4))))))
}
