// sealed hierarchies and exhaustive when (expression and statement), nested sealed, objects and data
sealed interface Expr
data class Num(val v: Int) : Expr
data class Add(val l: Expr, val r: Expr) : Expr
data class Mul(val l: Expr, val r: Expr) : Expr
object Zero : Expr { override fun toString() = "Zero" }
sealed class Result { data class Ok(val v: Int) : Result(); data class Err(val m: String) : Result(); object Pending : Result() }
fun eval(e: Expr): Int = when (e) { is Num -> e.v; is Add -> eval(e.l) + eval(e.r); is Mul -> eval(e.l) * eval(e.r); Zero -> 0 }
fun show(r: Result): String = when (r) { is Result.Ok -> "ok " + r.v; is Result.Err -> "err " + r.m; Result.Pending -> "pending" }
fun simplify(e: Expr): Expr = when {
    e is Add && e.l == Zero -> simplify(e.r)
    e is Add && e.r == Zero -> simplify(e.l)
    e is Mul && (e.l == Zero || e.r == Zero) -> Zero
    e is Add -> Add(simplify(e.l), simplify(e.r))
    else -> e
}
enum class Color { R, G, B }
fun name(c: Color) = when (c) { Color.R -> "red"; Color.G -> "green"; Color.B -> "blue" }
fun main() {
    println(eval(Add(Num(2), Mul(Num(3), Num(4)))))
    println(show(Result.Ok(1)) + "," + show(Result.Err("x")) + "," + show(Result.Pending))
    println(simplify(Add(Zero, Add(Num(1), Zero))))
    println(simplify(Mul(Num(5), Zero)))
    println(Color.values().map { name(it) })
    val r: Result = Result.Ok(3)
    when (r) { is Result.Ok -> println("v=" + r.v); is Result.Err -> println("e"); Result.Pending -> println("p") }
    println(Add(Num(1), Zero))
}
